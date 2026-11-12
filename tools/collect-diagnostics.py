#!/usr/bin/env python3
"""Collect opt-in RedrainCast diagnostics; LAN mode requires explicit consent."""
import argparse
import asyncio
import getpass
import hashlib
import hmac
import ipaddress
import json
import math
from pathlib import Path
import re
import secrets
import ssl
import socket
import sys
import time


MAX_LINE = 16384
MAX_INT64 = (1 << 63) - 1
FETCH_WINDOW_SECONDS = 1.01
FETCH_RESERVED_RECORDS = 32
FETCH_RESERVED_BYTES = 15360
MAX_FROZEN_RECORDS = 4096 + 1024
MAX_FROZEN_BYTES = 2 * 1024 * 1024 + 1024 * 2048
FROZEN_RETENTION_SECONDS = 120
PENDING_RETRY_SECONDS = 0.25
ENDPOINT_QUERY_PORT = 27184
ENDPOINT_QUERY_SIZE = 256
ENDPOINT_QUERY_PROTOCOL = "redraincast-diagnostics"
FLAGS = {
    "sender": ["session", "recovery", "audio.capture", "audio.transport",
               "audio.queue", "video.progress", "transport.health"],
    "receiver": ["session", "recovery", "audio.transport", "audio.queue",
                 "audio.output", "video.progress", "transport.health"],
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


def fingerprint(value):
    normalized = value.replace(":", "").lower()
    if not re.fullmatch(r"[a-f0-9]{64}", normalized):
        raise DiagnosticError("Fingerprint must contain exactly 32 SHA-256 bytes")
    return normalized


def endpoint(value):
    if value.startswith("["):
        match = re.fullmatch(r"\[([^\]]+)\]:([0-9]+)", value)
    else:
        match = re.fullmatch(r"([^:\s]+):([0-9]+)", value)
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
    """Defaults negotiate; explicit requests fail rather than silently losing evidence."""
    if not isinstance(supported, list) or any(not isinstance(flag, str) for flag in supported):
        raise DiagnosticError("Missing role capabilities")
    if requested is None:
        selected = [flag for flag in FLAGS[role] if flag in supported]
        if not selected:
            raise DiagnosticError("No supported default flags")
        return selected
    unsupported = [flag for flag in requested if flag not in supported]
    if unsupported:
        raise DiagnosticError("Unsupported requested flags: " + ",".join(unsupported))
    return requested


def strict_json(data):
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
        value = json.loads(data.decode("utf-8"), object_pairs_hook=pairs,
                           parse_constant=invalid_constant)
    except (UnicodeError, ValueError, RecursionError) as error:
        raise DiagnosticError("Malformed diagnostic JSON") from error

    def check_depth(item, depth):
        if depth > 8:
            raise DiagnosticError("Diagnostic nesting exceeds limit")
        if isinstance(item, dict):
            for child in item.values():
                check_depth(child, depth + 1)
        elif isinstance(item, list):
            for child in item:
                check_depth(child, depth + 1)
        elif isinstance(item, float) and not math.isfinite(item):
            raise DiagnosticError("Nonfinite diagnostic number")

    if not isinstance(value, dict):
        raise DiagnosticError("Diagnostic JSON root is not an object")
    check_depth(value, 0)
    return value


def validate_page(page, capture_id, cursor):
    if not isinstance(page, dict) or not isinstance(page.get("records"), list):
        raise DiagnosticError("Malformed FETCH result")
    records = page["records"]
    if len(records) > 32 or type(page.get("hasMore")) is not bool:
        raise DiagnosticError("Malformed FETCH page bounds")
    pending = page.get("pendingRecords")
    if type(pending) is not int or not 0 <= pending <= 1024:
        raise DiagnosticError("Malformed FETCH pending publication count")
    for key in ("oldestSeq", "latestSeq", "nextAfterSeq",
                "droppedBeforeRetention", "evictedAfterRetention"):
        exact_long(page.get(key))
    validate_loss_watermarks(page.get("lossWatermarks"), page)
    previous = cursor
    for record in records:
        if not isinstance(record, dict) or record.get("captureId") != capture_id:
            raise DiagnosticError("FETCH capture identity mismatch")
        sequence = exact_long(record.get("seq"))
        exact_long(record.get("monoNs"))
        if sequence <= previous:
            raise DiagnosticError("FETCH cursor did not advance")
        if sequence > exact_long(page["latestSeq"]):
            raise DiagnosticError("FETCH record exceeds published watermark")
        if not isinstance(record.get("kind"), str) or not isinstance(record.get("data"), dict):
            raise DiagnosticError("Malformed diagnostic event")
        previous = sequence
    next_cursor = exact_long(page["nextAfterSeq"])
    if next_cursor != previous:
        raise DiagnosticError("FETCH cursor does not match records")
    if page["hasMore"] and not records:
        raise DiagnosticError("FETCH claims backlog without progress")
    return records, next_cursor


def validate_loss_watermarks(loss, page):
    if not isinstance(loss, dict) or loss.get("schema") != "loss_watermarks":
        raise DiagnosticError("Missing or malformed loss watermarks")
    watermarks = {key: exact_long(loss.get(key))
                  for key in ("publishedSeq", "oldestRetainedSeq", "latestRetainedSeq")}
    if (watermarks["publishedSeq"] != exact_long(page["latestSeq"]) or
            watermarks["oldestRetainedSeq"] != exact_long(page["oldestSeq"]) or
            watermarks["latestRetainedSeq"] > watermarks["publishedSeq"] or
            (watermarks["oldestRetainedSeq"] > 0 and
             watermarks["latestRetainedSeq"] < watermarks["oldestRetainedSeq"])):
        raise DiagnosticError("Loss watermarks disagree with FETCH cursors")
    admission = loss.get("admission")
    reasons = ("routine_capacity", "critical_capacity", "closed_capture", "expired", "oversized_record")
    if not isinstance(admission, dict) or exact_long(admission.get("total")) != exact_long(
            page["droppedBeforeRetention"]):
        raise DiagnosticError("Malformed admission loss accounting")
    total = 0
    for reason in reasons:
        item = admission.get(reason)
        if not isinstance(item, dict):
            raise DiagnosticError("Missing admission loss reason")
        count = exact_long(item.get("count"))
        first = item.get("firstMonoNs")
        last = item.get("lastMonoNs")
        if count:
            first = exact_long(first)
            last = exact_long(last)
            if first > last:
                raise DiagnosticError("Reversed admission loss time range")
        elif first is not None or last is not None:
            raise DiagnosticError("Empty admission loss has a time range")
        total += count
    if total != exact_long(admission["total"]):
        raise DiagnosticError("Admission loss reason totals disagree")
    retention = loss.get("retention")
    if not isinstance(retention, dict):
        raise DiagnosticError("Missing retention loss accounting")
    evicted = 0
    for reason in ("routine", "critical", "oversized"):
        item = retention.get(reason)
        if not isinstance(item, dict):
            raise DiagnosticError("Missing retention loss reason")
        count = exact_long(item.get("count"))
        first = item.get("firstSeq")
        last = item.get("lastSeq")
        if type(item.get("exactRange")) is not bool:
            raise DiagnosticError("Malformed retention sequence range")
        if count:
            first = exact_long(first)
            last = exact_long(last)
            if first > last or (item["exactRange"] and last - first + 1 != count):
                raise DiagnosticError("Inconsistent retention sequence range")
        elif first is not None or last is not None or item["exactRange"]:
            raise DiagnosticError("Empty retention loss has a sequence range")
        if reason != "oversized":
            evicted += count
    if evicted != exact_long(page["evictedAfterRetention"]):
        raise DiagnosticError("Retention eviction totals disagree")


class FetchBudget:
    """Reserve worst-case FETCH output against the service's strict rolling caps."""

    def __init__(self):
        self.deliveries = []

    async def reserve(self, deadline=None):
        loop = asyncio.get_running_loop()
        while True:
            now = loop.time()
            if deadline is not None and now >= deadline:
                return None
            self.deliveries = [item for item in self.deliveries
                               if now - item[0] < FETCH_WINDOW_SECONDS]
            records = sum(item[1] for item in self.deliveries)
            size = sum(item[2] for item in self.deliveries)
            if (records + FETCH_RESERVED_RECORDS <= 256 and
                    size + FETCH_RESERVED_BYTES <= 131072):
                token = (now, FETCH_RESERVED_RECORDS, FETCH_RESERVED_BYTES)
                self.deliveries.append(token)
                return token
            wait = max(0.001, self.deliveries[0][0] + FETCH_WINDOW_SECONDS - now)
            if deadline is not None:
                remaining = deadline - loop.time()
                if remaining <= 0:
                    return None
                wait = min(wait, remaining)
            await asyncio.sleep(wait)

    def release(self, token):
        try:
            self.deliveries.remove(token)
        except ValueError:
            pass

    def commit(self, token):
        self.release(token)
        loop = asyncio.get_running_loop()
        self.deliveries.append((loop.time(), token[1], token[2]))


async def fetch_page(client, arguments, budget, deadline=None):
    for attempt in range(2):
        reservation = await budget.reserve(deadline)
        if reservation is None:
            return None
        try:
            page = await client.request("FETCH", arguments)
            budget.commit(reservation)
            return page
        except DiagnosticError as error:
            budget.release(reservation)
            if str(error) != "FETCH rejected: RATE_LIMITED" or attempt:
                raise
            # FETCH is read-only and cursor-pinned; retry only this explicit budget response.
            await asyncio.sleep(FETCH_WINDOW_SECONDS)


async def drain_frozen(client, capture_id, cursor, budget, deadline, output):
    """Bound both retained evidence and the once-only staging tail after END."""
    record_count = 0
    record_bytes = 0
    # Every nonempty page advances; empty pending pages are paced until lease expiry.
    max_pages = MAX_FROZEN_RECORDS + math.ceil(
        FROZEN_RETENTION_SECONDS / PENDING_RETRY_SECONDS) + 1
    for _ in range(max_pages):
        page = await fetch_page(client, {
            "captureId": capture_id, "afterSeq": str(cursor), "limit": 32},
            budget, deadline)
        if page is None or asyncio.get_running_loop().time() >= deadline:
            raise DiagnosticError("Frozen capture drain exceeded its deadline")
        records, cursor = validate_page(page, capture_id, cursor)
        record_count += len(records)
        record_bytes += sum(len(json.dumps(record, ensure_ascii=False,
                                          separators=(",", ":")).encode("utf-8"))
                            for record in records)
        if record_count > MAX_FROZEN_RECORDS or record_bytes > MAX_FROZEN_BYTES:
            raise DiagnosticError("Frozen capture exceeds bounded retention")
        for record in records:
            emit(output, "event", record)
        emit(output, "retention", {key: value for key, value in page.items()
                                   if key != "records"})
        if not page["hasMore"] and not page["pendingRecords"]:
            return
        if not page["hasMore"]:
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise DiagnosticError("Frozen capture drain exceeded its deadline")
            await asyncio.sleep(min(PENDING_RETRY_SECONDS, remaining))
    raise DiagnosticError("Frozen capture exceeds bounded drain progress")


class Client:
    def __init__(self, reader, writer):
        self.reader = reader
        self.writer = writer
        self.serial = 0

    @classmethod
    async def connect_lan(cls, address):
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(*address, limit=MAX_LINE + 2), 8)
        try:
            client = cls(reader, writer)
            capabilities = await client.request("HELLO")
            return client, capabilities
        except BaseException:
            writer.close()
            raise

    @classmethod
    async def connect(cls, address, expected_fingerprint, secret):
        # A self-signed peer is authenticated by an exact out-of-band pin,
        # not by a public CA or hostname. No app bytes precede pin validation.
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(*address, ssl=context, server_hostname=address[0],
                                    ssl_handshake_timeout=5, limit=MAX_LINE + 2), 8)
        try:
            tls = writer.get_extra_info("ssl_object")
            certificate = tls.getpeercert(binary_form=True) if tls else None
            actual = hashlib.sha256(certificate).hexdigest() if certificate else ""
            if not hmac.compare_digest(actual, fingerprint(expected_fingerprint)):
                raise DiagnosticError("TLS certificate fingerprint mismatch; AUTH was not sent")
            client = cls(reader, writer)
            auth = await client.request("AUTH", {"secret": secret})
            return client, auth
        except BaseException:
            writer.close()
            raise

    async def request(self, operation, arguments=None):
        self.serial += 1
        request_id = "r" + str(self.serial)
        data = json.dumps({"v": 1, "id": request_id, "op": operation,
                           "args": arguments or {}}, separators=(",", ":")).encode("utf-8")
        if len(data) > MAX_LINE:
            raise DiagnosticError("Command exceeds frame limit")

        async def exchange():
            self.writer.write(data + b"\n")
            await self.writer.drain()
            while True:
                try:
                    line = await self.reader.readline()
                except (ValueError, asyncio.LimitOverrunError) as error:
                    raise DiagnosticError("Peer exceeded diagnostic frame limit") from error
                if not line:
                    raise DiagnosticError("Diagnostic connection closed")
                if not line.endswith(b"\n") or len(line) - 1 > MAX_LINE:
                    raise DiagnosticError("Invalid diagnostic frame length")
                reply = strict_json(line[:-1])
                if type(reply.get("v")) is not int or reply["v"] != 1:
                    raise DiagnosticError("Unsupported diagnostic response version")
                if "event" in reply and "id" not in reply:
                    continue
                if reply.get("id") != request_id or reply.get("op") != operation:
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


def emit(output, kind, value):
    output.write(json.dumps({"type": kind, "value": value}, separators=(",", ":")) + "\n")
    output.flush()


async def record_clocks(client, output, phase):
    for _ in range(5):
        sent = time.monotonic_ns()
        sample = await client.request("CLOCK", {"clientSendNs": str(sent)})
        received = time.monotonic_ns()
        if sample.get("clientSendNs") != str(sent):
            raise DiagnosticError("CLOCK echo mismatch")
        device_received = exact_long(sample.get("receiveNs"))
        device_sent = exact_long(sample.get("sendNs"))
        if device_sent < device_received:
            raise DiagnosticError("CLOCK timestamps reversed")
        lower = device_sent - received
        upper = device_received - sent
        if upper < lower:
            raise DiagnosticError("CLOCK interval is inconsistent")
        emit(output, "clock_sample", {
            "phase": phase, "clientSendNs": str(sent), "clientReceiveNs": str(received),
            "deviceReceiveNs": str(device_received), "deviceSendNs": str(device_sent),
            "offsetLowerNs": str(lower), "offsetUpperNs": str(upper),
            "uncertaintyNs": str(upper - lower)})


def capture_target(role, session, scope):
    if scope is not None:
        if not isinstance(scope, dict) or scope.get("role") != role:
            raise DiagnosticError("Diagnostic role does not match delegated scope")
        scoped_session = scope.get("session")
        exact_long(scoped_session)
        if session is not None and session != scoped_session:
            raise DiagnosticError("Requested session does not match delegated scope")
        session = scoped_session
    return {"role": role, "session": session} if session else {
        "role": role, "nextSession": True}


async def collect(role, address, pin, secret, selected_flags, duration, session, output_path,
                  lan=False, connection=None):
    client = None
    capture_id = None
    cursor = 0
    ended = False
    with output_path.open("x", encoding="utf-8", newline="\n") as output:
        try:
            client, auth = connection or (await Client.connect_lan(address) if lan else
                                         await Client.connect(address, pin, secret))
            capabilities = auth.get("capabilities")
            supported = capabilities.get(role) if isinstance(capabilities, dict) else None
            if not isinstance(supported, list) or not isinstance(auth.get("clockId"), str):
                raise DiagnosticError("AUTH lacks role capabilities or clock identity")
            selected_flags = select_flags(role, selected_flags, supported)
            emit(output, "capture_metadata", {"protocol": 1, "role": role,
                                           "clockId": auth["clockId"], "flags": selected_flags,
                                           "access": "unauthenticated_lan" if lan else "pinned_tls"})
            await record_clocks(client, output, "start")
            target = capture_target(role, session, auth.get("scope"))
            result = await client.request("START", {
                "target": target, "flags": selected_flags,
                "durationMs": duration * 1000, "samplePeriodMs": 1000})
            capture_id = result.get("captureId")
            if not isinstance(capture_id, str) or not capture_id:
                raise DiagnosticError("START lacks capture identity")
            exact_long(result.get("startedNs"))
            exact_long(result.get("expiresNs"))
            emit(output, "started", result)
            print(role + ": capture active; reproduce the cast now.", flush=True)
            deadline = asyncio.get_running_loop().time() + duration
            last_status = 0
            fetch_budget = FetchBudget()
            while True:
                page = await fetch_page(client, {
                    "captureId": capture_id, "afterSeq": str(cursor), "limit": 32},
                    fetch_budget, deadline)
                if page is None:
                    break
                records, cursor = validate_page(page, capture_id, cursor)
                for record in records:
                    emit(output, "event", record)
                emit(output, "retention", {key: value for key, value in page.items()
                                           if key != "records"})
                now = asyncio.get_running_loop().time()
                if now - last_status >= 1:
                    status = await client.request("STATUS")
                    emit(output, "status", {key: value for key, value in status.items()
                                            if key != "connections"})
                    last_status = now
                    if status.get("state") == "frozen":
                        break
                if now >= deadline:
                    break
                if not page["hasMore"]:
                    await asyncio.sleep(min(0.25, deadline - now))
            frozen_deadline = asyncio.get_running_loop().time() + FROZEN_RETENTION_SECONDS
            result = await client.request("END", {"captureId": capture_id})
            ended = True
            emit(output, "ended", result)
            await record_clocks(client, output, "end")
            await drain_frozen(client, capture_id, cursor, fetch_budget, frozen_deadline, output)
            print(role + ": capture saved to " + str(output_path), flush=True)
        finally:
            if client:
                try:
                    if capture_id and not ended and sys.exc_info()[0] is None:
                        await client.request("END", {"captureId": capture_id})
                finally:
                    client.close()


def receiver_connection(value, expected_session=None):
    if not isinstance(value, dict) or type(value.get("version")) is not int or value["version"] != 1:
        raise DiagnosticError("Sender does not support connection information")
    receiver = value.get("receiver")
    if not isinstance(receiver, dict) or type(receiver.get("available")) is not bool:
        raise DiagnosticError("Malformed sender receiver connection information")
    if not receiver["available"]:
        reason = receiver.get("reason")
        allowed = {"no_session", "not_requested", "unsupported", "awaiting_endpoint",
                   "endpoint_unavailable", "malformed", "cast_ended", "grace_expired", "revoked"}
        raise DiagnosticError("Receiver diagnostics unavailable: " +
                              (reason if isinstance(reason, str) and reason in allowed else "unknown"))
    session = receiver.get("session")
    exact_long(session)
    if expected_session is not None and session != expected_session:
        raise DiagnosticError("Sender connection belongs to another cast session")
    if receiver.get("state") not in ("enabled", "local"):
        raise DiagnosticError("Receiver connection is not active; frozen retrieval cannot start a capture")
    if (type(receiver.get("version")) is not int or receiver["version"] != 1 or
            receiver.get("access") != "lan" or type(receiver.get("port")) is not int or
            not 1 <= receiver["port"] <= 65535):
        raise DiagnosticError("Invalid receiver diagnostic endpoint")
    try:
        if not isinstance(receiver.get("host"), str):
            raise ValueError("Host must be a string")
        host = str(ipaddress.ip_address(receiver["host"]))
    except ValueError as error:
        raise DiagnosticError("Receiver diagnostic host is not a connected IP address") from error
    return (host, receiver["port"]), session, receiver["state"]


async def run(devices, follow_receiver=False, receiver_flags=None):
    tasks = []
    opened = []
    try:
        if follow_receiver:
            sender = next(device for device in devices if device["role"] == "sender")
            connection = await Client.connect_lan(sender["address"])
            opened.append(connection[0])
            info = await connection[0].request("CONNECTIONS")
            address, session, state = receiver_connection(info, sender["session"])
            sender["session"] = session
            sender["connection"] = connection
            receiver = await Client.connect_lan(address)
            opened.append(receiver[0])
            scope = receiver[1].get("scope")
            if state == "enabled" and (not isinstance(scope, dict) or scope.get("active") is not True):
                raise DiagnosticError("Receiver delegation is no longer active")
            capture_target("receiver", session, scope)
            devices.append(dict(role="receiver", address=address, pin=None, secret=None,
                                selected_flags=receiver_flags, duration=sender["duration"],
                                session=session, lan=True, connection=receiver,
                                output_path=sender["output_path"].with_name("receiver.jsonl")))
            if devices[-1]["output_path"].exists():
                raise DiagnosticError("Receiver output already exists; choose a fresh evidence directory")
        tasks = [asyncio.create_task(collect(**device)) for device in devices]
        await asyncio.gather(*tasks)
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        for client in opened:
            client.close()


def discovery_endpoint(data, source):
    if len(data) > 1024:
        raise DiagnosticError("Oversized discovery advertisement")
    value = strict_json(data)
    if value.get("protocol") != "screencopy" or type(value.get("version")) is not int or value["version"] != 3:
        raise DiagnosticError("Not a supported casting advertisement")
    cast_port = value.get("port")
    if type(cast_port) is not int or not 1 <= cast_port <= 65535:
        raise DiagnosticError("Invalid casting discovery port")
    if value.get("state") not in ("available", "busy") or not isinstance(value.get("name"), str):
        raise DiagnosticError("Invalid casting discovery metadata")
    diagnostic = value.get("diagnostic")
    if diagnostic is None and "diagnostic" not in value:
        return None
    if not isinstance(diagnostic, dict):
        raise DiagnosticError("Invalid diagnostic advertisement")
    version = diagnostic.get("version")
    if type(version) is not int or version < 1:
        raise DiagnosticError("Invalid diagnostic discovery version")
    if version != 1:
        return None
    port = diagnostic.get("port")
    if type(port) is not int or not 1 <= port <= 65535:
        raise DiagnosticError("Invalid diagnostic discovery port")
    access = diagnostic.get("access")
    if access is not None and access != "lan":
        raise DiagnosticError("Unsupported diagnostic discovery access mode")
    # Always derive the host from the UDP source, never an advertised address.
    host = "[" + source + "]" if ":" in source else source
    return host + ":" + str(port)


async def discover(seconds):
    found = set()
    rejected = 0
    transport = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        transport.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        transport.bind(("", 27183))
        transport.setblocking(False)
        loop = asyncio.get_running_loop()
        deadline = loop.time() + seconds
        while loop.time() < deadline:
            try:
                data, peer = await asyncio.wait_for(
                    loop.sock_recvfrom(transport, 1025), deadline - loop.time())
            except TimeoutError:
                break
            try:
                address = discovery_endpoint(data, peer[0])
            except DiagnosticError:
                rejected += 1
                continue
            if address is not None and address not in found:
                found.add(address)
                print("Receiver diagnostics: " + address +
                      " (untrusted advertisement; --lan explicitly accepts unauthenticated access)",
                      flush=True)
    finally:
        transport.close()
    if rejected:
        print("Ignored " + str(rejected) + " invalid advertisements.", file=sys.stderr)
    if not found:
        raise DiagnosticError("No enabled receiver diagnostic service discovered")
    return found


def private_ipv4(value):
    try:
        address = ipaddress.IPv4Address(value)
    except ipaddress.AddressValueError as error:
        raise argparse.ArgumentTypeError("Use the device's private LAN IPv4 address") from error
    if not any(address in network for network in (
            ipaddress.IPv4Network("10.0.0.0/8"), ipaddress.IPv4Network("172.16.0.0/12"),
            ipaddress.IPv4Network("192.168.0.0/16"))):
        raise argparse.ArgumentTypeError("Use the device's private LAN IPv4 address")
    return str(address)


def sender_discovery_endpoint(data, nonce, host):
    if len(data) > ENDPOINT_QUERY_SIZE:
        raise DiagnosticError("Oversized endpoint discovery response")
    value = strict_json(data)
    if (value.get("protocol") != ENDPOINT_QUERY_PROTOCOL or
            type(value.get("version")) is not int or value["version"] != 1 or
            value.get("nonce") != nonce):
        raise DiagnosticError("Unmatched endpoint discovery response")
    hint = value.get("diagnostic")
    if (not isinstance(hint, dict) or type(hint.get("version")) is not int or
            hint["version"] != 1 or hint.get("access") != "lan" or
            type(hint.get("port")) is not int or not 1 <= hint["port"] <= 65535):
        raise DiagnosticError("Invalid endpoint discovery hint")
    return host + ":" + str(hint["port"])


async def discover_sender(host, seconds):
    """One bounded unicast query to a known LAN host; never scans or STARTs."""
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
                data = await asyncio.wait_for(
                    loop.sock_recv(transport, ENDPOINT_QUERY_SIZE + 1), deadline - loop.time())
            except TimeoutError:
                break
            try:
                address = sender_discovery_endpoint(data, nonce, host)
            except DiagnosticError:
                continue
            print("Sender diagnostics: " + address +
                  " (untrusted hint; --lan explicitly accepts unauthenticated access)", flush=True)
            return address
    finally:
        transport.close()
    raise DiagnosticError("No enabled diagnostic endpoint replied; old build, disabled access or LAN policy")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for role in FLAGS:
        parser.add_argument("--" + role, type=endpoint, help="host:port (or [IPv6]:port)")
        parser.add_argument("--" + role + "-fingerprint",
                            help="SHA-256 certificate fingerprint shown by device")
        parser.add_argument("--" + role + "-session", help="existing session; otherwise next cast")
        parser.add_argument("--" + role + "-flags", type=parse_flags,
                            default=None, help="explicit comma-separated data groups; default negotiates advertised groups including video")
    parser.add_argument("--duration", type=int, default=180, help="seconds, 1..180")
    parser.add_argument("--output", type=Path, help="local evidence directory (required for capture)")
    parser.add_argument("--discover", action="store_true",
                        help="list enabled receiver diagnostic endpoints; no authentication or capture")
    parser.add_argument("--discover-sender", type=private_ipv4, metavar="LAN_IPV4",
                        help="query one known sender on UDP 27184; no capture, settings access or port scan")
    parser.add_argument("--discover-seconds", type=int, default=5, help="discovery window, 1..30 seconds")
    parser.add_argument("--lan", action="store_true",
                        help="accept plaintext, unauthenticated diagnostics on a trusted LAN")
    parser.add_argument("--follow-receiver", action="store_true",
                        help="read the current receiver endpoint from --sender TCP and capture both")
    options = parser.parse_args(argv)
    if options.follow_receiver and (not options.lan or not options.sender or options.receiver or
                                   options.discover or options.discover_sender):
        parser.error("--follow-receiver requires --lan --sender and no --receiver or discovery options")
    if options.follow_receiver and options.receiver_session:
        parser.error("Use --sender-session to pin the owning cast with --follow-receiver")
    if not 1 <= options.duration <= 180:
        parser.error("--duration must be 1..180")
    if not 1 <= options.discover_seconds <= 30:
        parser.error("--discover-seconds must be 1..30")
    if options.discover or options.discover_sender:
        if (options.sender or options.receiver or options.output or
                options.discover and options.discover_sender):
            parser.error("Choose one discovery mode, separate from capture options")
    elif options.output is None:
        parser.error("--output is required for capture")
    devices = []
    try:
        if options.discover:
            asyncio.run(discover(options.discover_seconds))
            return 0
        if options.discover_sender:
            asyncio.run(discover_sender(options.discover_sender, options.discover_seconds))
            return 0
        for role in FLAGS:
            address = getattr(options, role)
            if address is None:
                continue
            pin = None if options.lan else fingerprint(
                getattr(options, role + "_fingerprint") or
                input(role + " certificate SHA-256 fingerprint: ").strip())
            session = getattr(options, role + "_session")
            if session:
                exact_long(session)
            secret = None if options.lan else getpass.getpass(role + " pairing secret (not saved): ")
            if not options.lan and not secret:
                raise DiagnosticError("Pairing secret is required")
            devices.append(dict(role=role, address=address, pin=pin, secret=secret,
                                selected_flags=getattr(options, role + "_flags"),
                                duration=options.duration, session=session,
                                lan=options.lan,
                                output_path=options.output / (role + ".jsonl")))
        if not devices:
            parser.error("Supply --sender and/or --receiver")
        if options.lan:
            print("WARNING: LAN diagnostics is plaintext and unauthenticated; "
                  "use only a trusted network. Each device must opt in locally.", file=sys.stderr)
        options.output.mkdir(parents=True, exist_ok=True)
        for device in devices:
            if device["output_path"].exists():
                raise DiagnosticError("Output already exists; choose a fresh evidence directory")
        asyncio.run(run(devices, options.follow_receiver, options.receiver_flags))
    except KeyboardInterrupt:
        print("Capture interrupted; partial evidence retained and connections closed.", file=sys.stderr)
        return 130
    except (DiagnosticError, OSError, TimeoutError, ssl.SSLError) as error:
        # Do not print arbitrary remote text, endpoints, secrets or TLS dumps.
        detail = str(error) if isinstance(error, DiagnosticError) else type(error).__name__
        print("Capture failed: " + detail + ". Partial evidence retained.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
