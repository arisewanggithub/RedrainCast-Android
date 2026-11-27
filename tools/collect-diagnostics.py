#!/usr/bin/env python3
"""Collect paired sender/receiver diagnostics from one explicitly enabled sender."""
import argparse
import asyncio
from collections import deque
import ipaddress
import json
import math
from pathlib import Path
import re
import secrets
import socket
import sys
import time

MAX_LINE = 16384
MAX_JSON_DEPTH = 11
MAX_INT64 = (1 << 63) - 1
FETCH_WINDOW_SECONDS = 1.01
MAX_FROZEN_RECORDS = 2 * 4096 + 1024 + 64
MAX_FROZEN_BYTES = 2 * 2 * 1024 * 1024 + 1024 * 2048 + 64 * 12000 + MAX_FROZEN_RECORDS * 512
ENDPOINT_QUERY_PORT = 27184
ENDPOINT_QUERY_SIZE = 256
ENDPOINT_QUERY_PROTOCOL = "redraincast-diagnostics"
FLAGS = {
    "sender": ["session", "recovery", "audio.capture", "audio.transport",
               "audio.queue", "video.progress", "transport.health"],
    "receiver": ["session", "recovery", "audio.transport", "audio.queue",
                 "audio.output", "video.progress", "video.timing", "transport.health"],
}


class DiagnosticError(Exception):
    pass


def exact_long(value):
    if not isinstance(value, str) or not re.fullmatch(r"0|[1-9][0-9]{0,18}", value):
        raise DiagnosticError("Invalid diagnostic int64")
    number = int(value)
    if number > MAX_INT64:
        raise DiagnosticError("Diagnostic int64 overflow")
    return number


def endpoint(value):
    match = re.fullmatch(r"\[([^\]]+)\]:([0-9]+)", value) if value.startswith("[") else \
        re.fullmatch(r"([^:\s]+):([0-9]+)", value)
    if not match or not 1 <= int(match[2]) <= 65535:
        raise argparse.ArgumentTypeError("Use host:port or [IPv6]:port")
    return match[1], int(match[2])


def parse_flags(value):
    flags = value.split(",")
    if not flags or len(set(flags)) != len(flags) or any(
            flag not in set(FLAGS["sender"] + FLAGS["receiver"]) for flag in flags):
        raise argparse.ArgumentTypeError("Use unique comma-separated diagnostic flag names")
    return flags


def select_flags(role, requested, supported):
    if not isinstance(supported, list) or any(not isinstance(flag, str) for flag in supported):
        raise DiagnosticError("Missing role capabilities")
    selected = [flag for flag in FLAGS[role] if flag in supported] if requested is None else requested
    if requested is None and "video.timing" in selected and "video.progress" not in selected:
        selected.remove("video.timing")
    if not selected or any(flag not in supported for flag in selected):
        raise DiagnosticError("Unsupported requested flags")
    if "video.timing" in selected and "video.progress" not in selected:
        raise DiagnosticError("video.timing requires video.progress")
    return selected


def strict_json(data, max_depth=MAX_JSON_DEPTH):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise DiagnosticError("Duplicate diagnostic JSON key")
            result[key] = value
        return result

    def invalid_constant(_):
        raise DiagnosticError("Nonfinite diagnostic number")

    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=pairs, parse_constant=invalid_constant)
    except (UnicodeError, ValueError, RecursionError) as error:
        raise DiagnosticError("Malformed diagnostic JSON") from error
    if not isinstance(value, dict):
        raise DiagnosticError("Diagnostic JSON root is not an object")
    check_depth(value, max_depth)
    return value


def check_depth(value, max_depth):
    def check(item, depth):
        if depth > max_depth:
            raise DiagnosticError("Diagnostic nesting exceeds limit")
        if isinstance(item, dict):
            for child in item.values():
                check(child, depth + 1)
        elif isinstance(item, list):
            for child in item:
                check(child, depth + 1)
        elif isinstance(item, float) and not math.isfinite(item):
            raise DiagnosticError("Nonfinite diagnostic number")
    check(value, 0)


def validate_sequence_loss(value):
    if not isinstance(value, dict) or type(value.get("exactRange")) is not bool:
        raise DiagnosticError("Malformed sequence loss")
    count = exact_long(value.get("count"))
    first, last = value.get("firstSeq"), value.get("lastSeq")
    if not count:
        if first is not None or last is not None or value["exactRange"]:
            raise DiagnosticError("Empty loss has a sequence range")
        return count
    first, last = exact_long(first), exact_long(last)
    if first > last or (value["exactRange"] and last - first + 1 != count):
        raise DiagnosticError("Invalid loss sequence range")
    return count


def validate_source_loss(loss, source=None):
    if isinstance(loss, list):
        if len(loss) != 7 or loss[0] != "compact1" or not isinstance(loss[5], list) or len(loss[5]) != 5 or \
                not isinstance(loss[6], list) or len(loss[6]) != 3:
            raise DiagnosticError("Invalid source loss encoding")
        admission = {"total": loss[4]}
        retention = {}
        for reason, values in zip(("routine_capacity", "critical_capacity", "closed_capture",
                                   "expired", "oversized_record"), loss[5]):
            if not isinstance(values, list) or len(values) != 3:
                raise DiagnosticError("Malformed compact admission loss")
            admission[reason] = dict(zip(("count", "firstMonoNs", "lastMonoNs"), values))
        for reason, values in zip(("routine", "critical", "oversized"), loss[6]):
            if not isinstance(values, list) or len(values) != 4:
                raise DiagnosticError("Malformed compact retention loss")
            retention[reason] = dict(zip(("count", "firstSeq", "lastSeq", "exactRange"), values))
        loss = dict(schema="loss_watermarks", publishedSeq=loss[1], oldestRetainedSeq=loss[2],
                    latestRetainedSeq=loss[3], admission=admission, retention=retention)
    if not isinstance(loss, dict) or loss.get("schema") != "loss_watermarks" or "encoding" in loss:
        raise DiagnosticError("Malformed source loss")
    published = exact_long(loss.get("publishedSeq"))
    oldest = exact_long(loss.get("oldestRetainedSeq"))
    latest = exact_long(loss.get("latestRetainedSeq"))
    if latest > published or (oldest and oldest > latest):
        raise DiagnosticError("Invalid source loss watermarks")
    if source is not None and (published != exact_long(source["latestSeq"]) or
                               oldest != exact_long(source["oldestSeq"])):
        raise DiagnosticError("Loss watermarks disagree with source page")
    admission = loss.get("admission")
    retention = loss.get("retention")
    if not isinstance(admission, dict) or not isinstance(retention, dict):
        raise DiagnosticError("Missing source loss details")
    total = 0
    for reason in ("routine_capacity", "critical_capacity", "closed_capture", "expired", "oversized_record"):
        detail = admission.get(reason)
        if not isinstance(detail, dict):
            raise DiagnosticError("Missing admission loss reason")
        count = exact_long(detail.get("count"))
        first, last = detail.get("firstMonoNs"), detail.get("lastMonoNs")
        if count:
            if exact_long(first) > exact_long(last):
                raise DiagnosticError("Invalid admission loss range")
        elif first is not None or last is not None:
            raise DiagnosticError("Empty admission loss has a time range")
        total += count
    if total != exact_long(admission.get("total")):
        raise DiagnosticError("Admission loss totals disagree")
    evicted = sum(validate_sequence_loss(retention.get(reason)) for reason in ("routine", "critical"))
    validate_sequence_loss(retention.get("oversized"))
    if source is not None and (total != exact_long(source.get("droppedBeforeRetention")) or
                               evicted != exact_long(source.get("evictedAfterRetention"))):
        raise DiagnosticError("Loss accounting disagrees with source page")


def validate_receiver_role_state(role):
    if role is not None and (not isinstance(role, dict) or
                             role.get("state") not in ("unavailable", "active", "finishing", "finished")):
        raise DiagnosticError("Invalid receiver role state")


def validate_page(page, pair_id, cursors, identity=None, flags=None):
    if not isinstance(page, dict) or page.get("pairId") != pair_id:
        raise DiagnosticError("FETCH pair identity mismatch")
    if page.get("state") not in ("armed", "binding", "covered", "draining", "frozen"):
        raise DiagnosticError("Invalid pair state")
    validate_receiver_role_state(page.get("receiverRole"))
    if "setupFailureAvailable" in page and type(page["setupFailureAvailable"]) is not bool:
        raise DiagnosticError("Invalid setup failure summary")
    if page.get("setupFailureAvailable") and \
            (page["state"] != "frozen" or page.get("endReason") != "setup_failed"):
        raise DiagnosticError("Setup failure summary has invalid state")
    exact_long(page.get("armGeneration"))
    if identity is not None:
        for key in ("armGeneration", "senderCaptureId", "senderClockId",
                    "session", "receiverCaptureId", "receiverClockId"):
            value = page.get(key)
            if identity.get(key) is not None and value != identity[key]:
                raise DiagnosticError("FETCH pair identity changed")
            if value is not None:
                identity[key] = value
    session = page.get("session")
    if session is not None:
        exact_long(session)
    records = page.get("records")
    if not isinstance(records, list) or len(records) > 32:
        raise DiagnosticError("Malformed FETCH records")
    previous = dict(cursors)
    for role in FLAGS:
        capture = page.get(role + "CaptureId")
        clock = page.get(role + "ClockId")
        source = page.get(role)
        if role == "receiver" and capture is None and clock is None and source is None:
            continue
        if not isinstance(capture, str) or not capture or not isinstance(clock, str) or not clock:
            raise DiagnosticError("Missing source identity")
        if not isinstance(source, dict) or type(source.get("hasMore")) is not bool:
            raise DiagnosticError("Malformed source cursor")
        for key in ("oldestSeq", "latestSeq", "nextAfterSeq"):
            exact_long(source.get(key))
        if type(source.get("pendingRecords")) is not int or not 0 <= source["pendingRecords"] <= 1024:
            raise DiagnosticError("Malformed source staging count")
        if role == "sender":
            validate_source_loss(source.get("lossWatermarks"), source)
        else:
            for name in ("intakeLoss", "forcedLoss", "retentionLoss"):
                validate_sequence_loss(source.get(name))
            if source.get("sourceLoss") is not None:
                validate_source_loss(source["sourceLoss"])
            if type(source.get("endConfirmed")) is not bool:
                raise DiagnosticError("Malformed receiver terminal state")
    for item in records:
        if not isinstance(item, dict) or set(item) != {"role", "session", "captureId", "clockId", "record"}:
            raise DiagnosticError("Malformed FETCH envelope")
        role = item.get("role")
        if role not in FLAGS or item["captureId"] != page.get(role + "CaptureId") or \
                item["clockId"] != page.get(role + "ClockId"):
            raise DiagnosticError("FETCH source identity mismatch")
        record = item["record"]
        if not isinstance(record, dict):
            raise DiagnosticError("Malformed source record")
        check_depth(record, 7)
        required = {"captureId", "seq", "monoNs", "kind", "data"}
        allowed = required | {"role", "session", "track", "generation"}
        if not required <= set(record) or not set(record) <= allowed or record["captureId"] != item["captureId"]:
            raise DiagnosticError("Malformed source record fields")
        if not isinstance(record["kind"], str) or not record["kind"] or not isinstance(record["data"], dict):
            raise DiagnosticError("Malformed source event")
        encoded = json.dumps(record, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        metrics = record["data"].get("metrics")
        role_flags = flags.get(role, []) if flags is not None else page.get(role + "Flags", [])
        timing = (record["kind"] == "VIDEO_PROGRESS" and isinstance(metrics, dict) and
                  metrics.get("schema") == "video_frame_timing" and
                  "video.timing" in role_flags)
        if len(encoded) > (12000 if timing else 2048):
            raise DiagnosticError("Oversized source record")
        sequence = exact_long(record["seq"])
        exact_long(record["monoNs"])
        if sequence <= previous[role] or sequence > exact_long(page[role]["latestSeq"]):
            raise DiagnosticError("Source sequence did not advance")
        bound = "role" in record
        if bound != ("session" in record) or (bound and
                (record["role"] != role or record["session"] != session or item["session"] != session)) or \
                (not bound and item["session"] is not None) or (record["kind"] == "SESSION_BOUND" and not bound):
            raise DiagnosticError("Source session mismatch")
        if bound:
            exact_long(record["session"])
        if ("track" in record) != ("generation" in record) or (
                "track" in record and (type(record["track"]) is not int or type(record["generation"]) is not int)):
            raise DiagnosticError("Malformed source track")
        data = record["data"]
        if "operationId" in data:
            exact_long(data["operationId"])
        if "inFlight" in data:
            if not isinstance(data["inFlight"], list):
                raise DiagnosticError("Malformed in-flight operations")
            for operation in data["inFlight"]:
                if not isinstance(operation, dict):
                    raise DiagnosticError("Malformed in-flight operation")
                exact_long(operation.get("operationId"))
        if metrics is not None:
            if not isinstance(metrics, dict):
                raise DiagnosticError("Malformed metrics")
            if metrics.get("slowExampleOperationId") is not None:
                exact_long(metrics["slowExampleOperationId"])
        previous[role] = sequence
    for role in FLAGS:
        source = page.get(role)
        if source is not None and exact_long(source["nextAfterSeq"]) != previous[role]:
            raise DiagnosticError("Source cursor does not match records")
    if not records and any(page.get(role) and page[role]["hasMore"] for role in FLAGS):
        raise DiagnosticError("FETCH claims backlog without progress")
    return records, previous


class Client:
    def __init__(self, reader, writer):
        self.reader, self.writer, self.serial = reader, writer, 0
        self.last_bytes = 0

    @classmethod
    async def connect_lan(cls, address):
        reader, writer = await asyncio.wait_for(asyncio.open_connection(*address, limit=MAX_LINE + 2), 8)
        client = cls(reader, writer)
        try:
            hello = await client.request("HELLO")
            if hello.get("pairedNextCast") != 1 or not isinstance(hello.get("clockId"), str):
                raise DiagnosticError("Sender lacks paired diagnostics")
            return client, hello
        except BaseException:
            writer.close()
            raise

    async def request(self, operation, arguments=None):
        self.serial += 1
        request_id = "r" + str(self.serial)
        data = json.dumps({"v": 2, "id": request_id, "op": operation,
                           "args": arguments or {}}, separators=(",", ":")).encode("utf-8")
        if len(data) > MAX_LINE:
            raise DiagnosticError("Command exceeds frame limit")
        check_depth(json.loads(data), 8)

        async def exchange():
            self.writer.write(data + b"\n")
            await self.writer.drain()
            try:
                line = await self.reader.readline()
            except (ValueError, asyncio.LimitOverrunError) as error:
                raise DiagnosticError("Peer exceeded diagnostic frame limit") from error
            if not line or not line.endswith(b"\n") or len(line) - 1 > MAX_LINE:
                raise DiagnosticError("Invalid diagnostic frame length")
            self.last_bytes = len(line)
            reply = strict_json(line[:-1])
            if type(reply.get("v")) is not int or reply["v"] != 2 or \
                    reply.get("id") != request_id or reply.get("op") != operation:
                raise DiagnosticError("Diagnostic response identity mismatch")
            if type(reply.get("ok")) is not bool:
                raise DiagnosticError("Malformed diagnostic response status")
            if not reply["ok"]:
                error = reply.get("error")
                code = error.get("code") if isinstance(error, dict) else None
                if not isinstance(code, str) or not re.fullmatch(r"[A-Z_]{1,64}", code):
                    raise DiagnosticError("Malformed diagnostic error")
                raise DiagnosticError(operation + " rejected: " + code)
            if "error" in reply or not isinstance(reply.get("result"), dict):
                raise DiagnosticError("Malformed diagnostic success")
            return reply["result"]
        return await asyncio.wait_for(exchange(), 10)

    def close(self):
        self.writer.close()


class FetchBudget:
    def __init__(self):
        self.deliveries = deque()

    async def reserve(self, deadline):
        loop = asyncio.get_running_loop()
        while True:
            now = loop.time()
            while self.deliveries and now - self.deliveries[0][0] >= FETCH_WINDOW_SECONDS:
                self.deliveries.popleft()
            if now >= deadline:
                return False
            if (sum(item[1] for item in self.deliveries) + 32 <= 256 and
                    sum(item[2] for item in self.deliveries) + MAX_LINE + 1 <= 131072):
                return True
            wait = self.deliveries[0][0] + FETCH_WINDOW_SECONDS - now
            await asyncio.sleep(min(max(wait, 0.001), deadline - now))

    def commit(self, records, size):
        self.deliveries.append((asyncio.get_running_loop().time(), records, size))


async def fetch_page(client, arguments, budget, deadline):
    for attempt in range(2):
        if not await budget.reserve(deadline):
            return None
        try:
            page = await client.request("FETCH", arguments)
            budget.commit(len(page.get("records", [])), client.last_bytes)
            return page
        except DiagnosticError as error:
            if str(error) != "FETCH rejected: RATE_LIMITED" or attempt:
                raise
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                return None
            await asyncio.sleep(min(FETCH_WINDOW_SECONDS, remaining))


def emit(output, kind, value):
    output.write(json.dumps({"type": kind, "value": value}, separators=(",", ":")) + "\n")
    output.flush()


async def drain_frozen(client, pair_id, cursors, budget, deadline, output, identity=None, first_page=None,
                       flags=None):
    count = 0
    size = 0
    page = first_page
    while True:
        if page is None:
            page = await fetch_page(client, {"pairId": pair_id, "afterSenderSeq": str(cursors["sender"]),
                "afterReceiverSeq": str(cursors["receiver"]), "limit": 32}, budget, deadline)
        if page is None or asyncio.get_running_loop().time() >= deadline:
            raise DiagnosticError("Frozen capture drain exceeded its deadline")
        if page.get("state") != "frozen":
            raise DiagnosticError("Frozen pair changed state")
        records, cursors = validate_page(page, pair_id, cursors, identity, flags)
        count += len(records)
        size += sum(len(json.dumps(item, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
                    for item in records)
        if count > MAX_FROZEN_RECORDS or size > MAX_FROZEN_BYTES:
            raise DiagnosticError("Frozen capture exceeds bounded retention")
        for record in records:
            emit(output, "event", record)
        emit(output, "retention", {key: value for key, value in page.items() if key != "records"})
        pending = any(source and (source["hasMore"] or source["pendingRecords"])
                      for source in (page.get("sender"), page.get("receiver")))
        if not pending:
            return page, cursors
        if not any(source and source["hasMore"] for source in (page.get("sender"), page.get("receiver"))):
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise DiagnosticError("Frozen capture drain exceeded its deadline")
            await asyncio.sleep(min(0.25, remaining))
        page = None


async def record_clock(client, output, role, pair_id=None):
    sent = time.monotonic_ns()
    args = {"role": role, "clientSendNs": str(sent)}
    if role == "receiver":
        args["pairId"] = pair_id
    sample = await client.request("CLOCK", args)
    received = time.monotonic_ns()
    if sample.get("clientSendNs") != str(sent):
        raise DiagnosticError("CLOCK echo mismatch")
    device_received, device_sent = exact_long(sample.get("receiveNs")), exact_long(sample.get("sendNs"))
    lower, upper = device_sent - received, device_received - sent
    if device_sent < device_received or upper < lower:
        raise DiagnosticError("CLOCK interval is inconsistent")
    sample.update(role=role, clientReceiveNs=str(received), offsetLowerNs=str(lower),
                  offsetUpperNs=str(upper), uncertaintyNs=str(upper - lower))
    emit(output, "clock_sample", sample)


async def record_status(client, output, pair_id, identity, deadline, budget):
    if not await budget.reserve(deadline):
        raise DiagnosticError("Status retrieval exceeded its deadline")
    for attempt in range(2):
        remaining = deadline - asyncio.get_running_loop().time()
        if remaining <= 0:
            raise DiagnosticError("Status retrieval exceeded its deadline")
        try:
            status = await asyncio.wait_for(client.request("STATUS", {"pairId": pair_id}), remaining)
            budget.commit(0, client.last_bytes)
            break
        except asyncio.TimeoutError as error:
            raise DiagnosticError("Status retrieval exceeded its deadline") from error
        except DiagnosticError as error:
            if str(error) != "STATUS rejected: RATE_LIMITED" or attempt:
                raise
            await asyncio.sleep(min(FETCH_WINDOW_SECONDS, max(0, deadline - asyncio.get_running_loop().time())))
    if not isinstance(status, dict) or status.get("pairId") != pair_id or \
            status.get("state") not in ("armed", "binding", "covered", "draining", "frozen"):
        raise DiagnosticError("Invalid pair status")
    failure = status.get("setupFailure")
    if failure is not None:
        if not isinstance(failure, dict) or set(failure) != {"code", "provenance", "reason"} or \
                failure["code"] not in ("VERSION", "BUSY", "UNSUPPORTED_CONFIG", "PROTOCOL",
                    "CAPTURE_FAILED", "AUDIO_FAILED", "DECODER_FAILED", "TIMEOUT", "CONGESTION", "INTERNAL") or \
                (failure["provenance"], failure["reason"]) not in (
                    ("receiver_control", "receiver_reported"), ("sender_local", "timeout"),
                    ("sender_local", "eof"), ("sender_local", "connect"), ("sender_local", "setup")) or \
                status.get("state") != "frozen" or status.get("endReason") != "setup_failed":
            raise DiagnosticError("Invalid setup failure")
    for key in ("armGeneration", "senderCaptureId", "senderClockId",
                "session", "receiverCaptureId", "receiverClockId"):
        value = status.get(key)
        if identity.get(key) is not None and value != identity[key]:
            raise DiagnosticError("STATUS pair identity changed")
        if value is not None:
            identity[key] = value
    bootstrap = status.get("bootstrap")
    if bootstrap is not None:
        stages = ("socket_accepted", "hello_received", "hello_validated", "hello_reply_queued",
                  "request_received", "owner_admitted", "capture_started", "lane_admitted")
        if not isinstance(bootstrap, dict) or set(bootstrap) != {
                "session", "pairId", "captureId", "clockId", "complete", "loss", "stages"} or \
                bootstrap.get("pairId") != pair_id or bootstrap.get("session") != identity.get("session") or \
                bootstrap.get("captureId") != identity.get("receiverCaptureId") or \
                bootstrap.get("clockId") != identity.get("receiverClockId") or \
                bootstrap.get("complete") is not True or bootstrap.get("loss") is not False or \
                not isinstance(bootstrap.get("stages"), list) or len(bootstrap["stages"]) != len(stages):
            raise DiagnosticError("Invalid receiver bootstrap")
        previous = -1
        for expected, stage in zip(stages, bootstrap["stages"]):
            if not isinstance(stage, dict) or set(stage) != {"stage", "monoNs"} or stage["stage"] != expected:
                raise DiagnosticError("Invalid receiver bootstrap stage")
            timestamp = exact_long(stage["monoNs"])
            if timestamp < previous:
                raise DiagnosticError("Invalid receiver bootstrap stage order")
            previous = timestamp
    receiver_role = status.get("receiverRole")
    validate_receiver_role_state(receiver_role)
    if receiver_role is not None:
        outcome = receiver_role.get("outcome")
        if outcome is not None:
            if not isinstance(outcome, dict) or bootstrap is None or receiver_role.get("state") != "finished":
                raise DiagnosticError("Invalid receiver outcome")
            capture_end = outcome.get("captureEnd")
            if not isinstance(capture_end, dict) or set(capture_end) != {"atNs", "reason", "complete", "truncated"}:
                raise DiagnosticError("Invalid receiver outcome")
            if outcome.get("confirmed") is False:
                if set(outcome) != {"confirmed", "reason", "endedNs", "mediaRetirement", "ownershipCleanup",
                                   "tailTruncated", "captureEnd"} or outcome["confirmed"] is not False or \
                        outcome["reason"] is not None or outcome["endedNs"] is not None or \
                        outcome["mediaRetirement"] != "unknown" or outcome["ownershipCleanup"] != "unknown" or \
                        outcome["tailTruncated"] is not True or capture_end["atNs"] is not None or \
                        capture_end["reason"] is not None or capture_end["complete"] is not False or \
                        capture_end["truncated"] is not True:
                    raise DiagnosticError("Invalid unconfirmed receiver outcome")
            else:
                confirmed_fields = {"reason", "endedNs", "mediaRetirement", "ownershipCleanup",
                                    "tailTruncated", "captureEnd"}
                if set(outcome) not in (confirmed_fields, confirmed_fields | {"confirmed"}) or \
                        outcome.get("confirmed", True) is not True or \
                        outcome["reason"] not in ("receiver_retired_stop", "receiver_retired_fatal") or \
                        outcome["mediaRetirement"] not in ("succeeded", "failed") or \
                        outcome["ownershipCleanup"] != "unknown" or type(outcome["tailTruncated"]) is not bool or \
                        capture_end["reason"] != outcome["reason"] or \
                        type(capture_end["complete"]) is not bool or type(capture_end["truncated"]) is not bool or \
                        capture_end["truncated"] != outcome["tailTruncated"] or \
                        capture_end["complete"] == capture_end["truncated"]:
                    raise DiagnosticError("Invalid confirmed receiver outcome")
                exact_long(outcome["endedNs"])
                exact_long(capture_end["atNs"])
    emit(output, "status", status)
    return status


async def collect_pair(address, sender_flags, receiver_flags, duration, output_path):
    client = None
    pair_id = None
    ended = False
    with output_path.open("x", encoding="utf-8", newline="\n") as output:
        try:
            client, hello = await Client.connect_lan(address)
            sender_flags = select_flags("sender", sender_flags, hello.get("senderFlags"))
            receiver_flags = select_flags("receiver", receiver_flags, hello.get("receiverFlags"))
            flags = {"sender": sender_flags, "receiver": receiver_flags}
            emit(output, "capture_metadata", {"protocol": 2, "access": "unauthenticated_lan",
                                              "senderFlags": sender_flags, "receiverFlags": receiver_flags})
            await record_clock(client, output, "sender")
            result = await client.request("START", {"target": {"nextSession": True},
                "senderFlags": sender_flags, "receiverFlags": receiver_flags,
                "durationMs": duration * 1000, "samplePeriodMs": 1000})
            pair_id = result.get("pairId")
            if not isinstance(pair_id, str) or not pair_id or result.get("state") != "armed":
                raise DiagnosticError("START lacks paired arm")
            exact_long(result.get("armGeneration"))
            exact_long(result.get("startedNs"))
            exact_long(result.get("expiresNs"))
            identity = {key: result.get(key) for key in
                        ("armGeneration", "senderCaptureId", "senderClockId",
                         "session", "receiverCaptureId", "receiverClockId")}
            if not all(isinstance(identity[key], str) and identity[key]
                       for key in ("senderCaptureId", "senderClockId")):
                raise DiagnosticError("START lacks sender identity")
            emit(output, "started", result)
            print("Capture armed; start the cast now.", flush=True)
            cursors = {"sender": 0, "receiver": 0}
            deadline = asyncio.get_running_loop().time() + duration
            receiver_clock = False
            bootstrap_saved = False
            frozen_deadline = None
            budget = FetchBudget()
            while True:
                now = asyncio.get_running_loop().time()
                if frozen_deadline is None and now >= deadline:
                    emit(output, "end_requested", await client.request("END", {"pairId": pair_id}))
                    frozen_deadline = now + 120
                page = await fetch_page(client, {"pairId": pair_id,
                    "afterSenderSeq": str(cursors["sender"]), "afterReceiverSeq": str(cursors["receiver"]),
                    "limit": 32}, budget, frozen_deadline or deadline)
                if page is None:
                    if frozen_deadline is not None:
                        raise DiagnosticError("Frozen capture drain exceeded its deadline")
                    continue
                if page.get("receiverCaptureId") and not bootstrap_saved:
                    status_deadline = frozen_deadline or deadline
                    if page.get("state") == "frozen" and frozen_deadline is None:
                        frozen_deadline = asyncio.get_running_loop().time() + 120
                        status_deadline = frozen_deadline
                    status = await record_status(client, output, pair_id, identity, status_deadline, budget)
                    if status.get("bootstrap") is None:
                        raise DiagnosticError("Receiver bootstrap unavailable")
                    bootstrap_saved = True
                if page.get("state") == "frozen":
                    if frozen_deadline is None:
                        frozen_deadline = asyncio.get_running_loop().time() + 120
                    page, cursors = await drain_frozen(client, pair_id, cursors, budget, frozen_deadline,
                                                       output, identity, page, flags)
                    final_status = await record_status(client, output, pair_id, identity, frozen_deadline, budget)
                    if final_status.get("state") != "frozen":
                        raise DiagnosticError("Final pair status is not frozen")
                    if "setupFailureAvailable" in page and \
                            page["setupFailureAvailable"] != (final_status.get("setupFailure") is not None):
                        raise DiagnosticError("Setup failure summary disagrees with final status")
                    emit(output, "ended", page)
                    ended = True
                    bootstrap = final_status.get("bootstrap")
                    terminal = page.get("receiver", {}).get("terminal") if isinstance(page.get("receiver"), dict) else None
                    receiver_role = final_status.get("receiverRole")
                    outcome = receiver_role.get("outcome") if isinstance(receiver_role, dict) else None
                    if (not page.get("receiverCaptureId") or not isinstance(bootstrap, dict) or
                            bootstrap.get("complete") is not True or bootstrap.get("loss") is not False or
                            not isinstance(page.get("receiver"), dict) or
                            page["receiver"].get("endConfirmed") is not True or
                            not isinstance(terminal, dict) or terminal.get("tailTruncated") is not False or
                            not isinstance(outcome, dict) or outcome.get("tailTruncated") is not False or
                            outcome.get("reason") != terminal.get("reason") or
                            page.get("endReason") not in ("requested", "session_ended", "lease_expired")):
                        raise DiagnosticError("Paired capture ended without confirmed receiver coverage: " +
                                              str(page.get("endReason")))
                    return
                records, cursors = validate_page(page, pair_id, cursors, identity, flags)
                for record in records:
                    emit(output, "event", record)
                emit(output, "retention", {key: value for key, value in page.items() if key != "records"})
                state = page.get("state")
                if state == "covered" and not receiver_clock:
                    try:
                        await record_clock(client, output, "receiver", pair_id)
                    except DiagnosticError as error:
                        if str(error) != "CLOCK rejected: CLOCK_UNAVAILABLE":
                            raise
                        emit(output, "clock_unavailable", {"role": "receiver"})
                    receiver_clock = True
                more = any(source and source["hasMore"] for source in (page.get("sender"), page.get("receiver")))
                if not more:
                    await asyncio.sleep(0.25)
        finally:
            if client:
                try:
                    if pair_id and not ended and sys.exc_info()[0] is None:
                        await client.request("END", {"pairId": pair_id})
                finally:
                    client.close()


def private_ipv4(value):
    try:
        address = ipaddress.IPv4Address(value)
    except ipaddress.AddressValueError as error:
        raise argparse.ArgumentTypeError("Use the device's private LAN IPv4 address") from error
    if not any(address in ipaddress.IPv4Network(network)
               for network in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")):
        raise argparse.ArgumentTypeError("Use the device's private LAN IPv4 address")
    return str(address)


def sender_discovery_endpoint(data, nonce, host):
    if len(data) > ENDPOINT_QUERY_SIZE:
        raise DiagnosticError("Oversized endpoint discovery response")
    value = strict_json(data)
    hint = value.get("diagnostic")
    if (value.get("protocol") != ENDPOINT_QUERY_PROTOCOL or type(value.get("version")) is not int or
            value["version"] != 1 or value.get("nonce") != nonce or not isinstance(hint, dict) or
            type(hint.get("version")) is not int or hint["version"] != 2 or hint.get("access") != "lan" or
            type(hint.get("port")) is not int or not 1 <= hint["port"] <= 65535):
        raise DiagnosticError("Invalid endpoint discovery response")
    return host + ":" + str(hint["port"])


async def discover_sender(host, seconds):
    host = private_ipv4(host)
    nonce = secrets.token_hex(16)
    request = json.dumps(dict(protocol=ENDPOINT_QUERY_PROTOCOL, version=1, nonce=nonce),
                         separators=(",", ":")).encode().ljust(ENDPOINT_QUERY_SIZE, b" ")
    transport = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        transport.connect((host, ENDPOINT_QUERY_PORT))
        transport.setblocking(False)
        loop = asyncio.get_running_loop()
        deadline = loop.time() + seconds
        await asyncio.wait_for(loop.sock_sendall(transport, request), seconds)
        while loop.time() < deadline:
            try:
                data = await asyncio.wait_for(loop.sock_recv(transport, ENDPOINT_QUERY_SIZE + 1),
                                              deadline - loop.time())
            except TimeoutError:
                break
            try:
                address = sender_discovery_endpoint(data, nonce, host)
            except DiagnosticError:
                continue
            print("Sender diagnostics: " + address + " (untrusted LAN hint)", flush=True)
            return address
    finally:
        transport.close()
    raise DiagnosticError("No enabled diagnostic endpoint replied")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sender", type=endpoint, help="sender host:port")
    parser.add_argument("--sender-flags", type=parse_flags)
    parser.add_argument("--receiver-flags", type=parse_flags)
    parser.add_argument("--duration", type=int, default=180, help="seconds, 1..180")
    parser.add_argument("--output", type=Path, help="local evidence directory")
    parser.add_argument("--discover-sender", type=private_ipv4, metavar="LAN_IPV4")
    parser.add_argument("--discover-seconds", type=int, default=5)
    parser.add_argument("--lan", action="store_true", help="accept unauthenticated plaintext on a trusted LAN")
    options = parser.parse_args(argv)
    if not 1 <= options.duration <= 180 or not 1 <= options.discover_seconds <= 30:
        parser.error("Invalid duration or discovery window")
    if options.discover_sender:
        if options.sender or options.output:
            parser.error("Discovery is separate from capture")
    elif not options.sender or not options.output or not options.lan:
        parser.error("Capture requires --sender, --output and explicit --lan")
    try:
        if options.discover_sender:
            asyncio.run(discover_sender(options.discover_sender, options.discover_seconds))
        else:
            print("WARNING: diagnostics is plaintext and unauthenticated; use a trusted LAN.", file=sys.stderr)
            options.output.mkdir(parents=True, exist_ok=True)
            asyncio.run(collect_pair(options.sender, options.sender_flags, options.receiver_flags,
                                     options.duration, options.output / "paired.jsonl"))
    except KeyboardInterrupt:
        print("Capture interrupted; partial evidence retained.", file=sys.stderr)
        return 130
    except (DiagnosticError, OSError, TimeoutError) as error:
        detail = str(error) if isinstance(error, DiagnosticError) else type(error).__name__
        print("Capture failed: " + detail + ". Partial evidence retained.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
