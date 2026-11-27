#!/usr/bin/env python3
"""Dependency-free remote diagnostic collector contract tests."""
import asyncio
import hashlib
import importlib.util
import json
import io
import os
from contextlib import contextmanager
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import unittest
from unittest.mock import AsyncMock, patch


spec = importlib.util.spec_from_file_location(
    "collector", Path(__file__).with_name("collect-diagnostics.py"))
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)
TEST_FILES = Path(__file__).resolve().parent.parent / "build"
TEST_FILES.mkdir(exist_ok=True)
TEST_ROOT = TEST_FILES / "diagnostics-contract"
TEST_ROOT.mkdir(exist_ok=True)


def fixture_path(name):
    fixture = TEST_FILES.parent / "app" / "build" / "diagnostics-contract" / name
    if not fixture.is_file():
        message = "Run DiagnosticVideoTimingTest to generate " + name
        if os.environ.get("DIAGNOSTICS_REQUIRE_FIXTURE") == "1":
            raise AssertionError(message)
        raise unittest.SkipTest(message)
    return fixture


def legacy_collector():
    path = os.environ.get("DIAGNOSTICS_LEGACY_COLLECTOR")
    if not path:
        return None
    legacy_spec = importlib.util.spec_from_file_location("legacy_collector", path)
    module = importlib.util.module_from_spec(legacy_spec)
    legacy_spec.loader.exec_module(module)
    return module


@contextmanager
def test_workspace(name):
    directory = TEST_ROOT / name
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True)
    try:
        yield directory
    finally:
        shutil.rmtree(directory, ignore_errors=True)


class VideoFlagTests(unittest.TestCase):
    def test_video_defaults_negotiate_current_and_legacy_capabilities(self):
        for role in collector.FLAGS:
            supported = collector.FLAGS[role]
            self.assertIn("video.progress", collector.select_flags(role, None, supported))
            legacy = [flag for flag in supported if flag not in ("video.progress", "video.timing")]
            self.assertEqual(legacy, collector.select_flags(role, None, legacy))

    def test_explicit_video_is_strict_and_parser_accepts_it(self):
        self.assertEqual(["video.progress"], collector.parse_flags("video.progress"))
        with self.assertRaisesRegex(collector.DiagnosticError, "Unsupported requested flags"):
            collector.select_flags("receiver", ["video.progress"], ["session"])
        self.assertEqual(["video.progress"], collector.select_flags(
            "receiver", ["video.progress"], ["session", "video.progress"]))
        with self.assertRaisesRegex(collector.DiagnosticError, "requires video.progress"):
            collector.select_flags("receiver", ["video.timing"], ["video.progress", "video.timing"])
        self.assertEqual(["video.progress", "video.timing"], collector.select_flags(
            "receiver", None, ["video.progress", "video.timing"]))
        self.assertEqual(["video.progress"], collector.select_flags(
            "receiver", None, ["video.progress"]))

    def test_default_does_not_invent_flags_or_accept_empty_capabilities(self):
        self.assertEqual(["session"], collector.select_flags("sender", None, ["session", "future.flag"]))
        with self.assertRaises(collector.DiagnosticError):
            collector.select_flags("sender", None, [])


class SenderDiscoveryTests(unittest.TestCase):
    nonce = "0123456789abcdef0123456789abcdef"

    def response(self, **changes):
        value = dict(protocol=collector.ENDPOINT_QUERY_PROTOCOL, version=1, nonce=self.nonce,
                     diagnostic=dict(version=2, port=41234, access="lan"))
        value.update(changes)
        return json.dumps(value).encode()

    def test_only_exact_nonce_version_and_bounded_integer_lan_hint_are_accepted(self):
        self.assertEqual("192.168.1.135:41234", collector.sender_discovery_endpoint(
            self.response(), self.nonce, "192.168.1.135"))
        for invalid in (
                self.response(nonce="0" * 32), self.response(version=True), self.response(version=2),
                self.response(protocol="screencopy"), self.response(diagnostic=None),
                b" " * 257, self.response().replace(b'"version": 1', b'"version": 1, "version": 1', 1)):
            with self.subTest(invalid=invalid), self.assertRaises(collector.DiagnosticError):
                collector.sender_discovery_endpoint(invalid, self.nonce, "192.168.1.135")

    def test_hint_never_overrides_queried_host_or_accepts_invalid_port(self):
        for port in (0, 65536, True, 1.5, "41234", None):
            with self.subTest(port=port), self.assertRaises(collector.DiagnosticError):
                collector.sender_discovery_endpoint(self.response(diagnostic=dict(
                    version=2, port=port, access="lan")), self.nonce, "192.168.1.135")
        for changes in (dict(version=True), dict(version=1), dict(access="tls")):
            hint = dict(version=2, port=41234, access="lan")
            hint.update(changes)
            with self.assertRaises(collector.DiagnosticError):
                collector.sender_discovery_endpoint(self.response(diagnostic=hint),
                                                   self.nonce, "192.168.1.135")
        self.assertEqual("192.168.1.135:41234", collector.sender_discovery_endpoint(
            self.response(diagnostic=dict(version=2, port=41234, access="lan", host="evil")),
            self.nonce, "192.168.1.135"))

    def test_only_explicit_private_ipv4_hosts_can_be_queried(self):
        for host in ("192.168.1.135", "10.0.0.1", "172.16.0.1"):
            self.assertEqual(host, collector.private_ipv4(host))
        for host in ("example.com", "127.0.0.1", "::1", "8.8.8.8", "169.254.1.1",
                     "192.0.2.1", "224.0.0.1", "255.255.255.255"):
            with self.subTest(host=host), self.assertRaises(collector.argparse.ArgumentTypeError):
                collector.private_ipv4(host)

    def test_discovery_cannot_be_mixed_with_capture_or_broadcast_discovery(self):
        for extra in (["--discover"], ["--sender", "192.168.1.135:41234"],
                      ["--output", "unused"], ["--follow-receiver", "--lan"]):
            with patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit) as result:
                collector.main(["--discover-sender", "192.168.1.135"] + extra)
            self.assertEqual(2, result.exception.code)


class SenderDiscoveryIntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def test_one_fixed_port_query_discovers_active_service_without_logs_or_tcp_capture(self):
        requests = []

        class Responder(asyncio.DatagramProtocol):
            def connection_made(self, transport):
                self.transport = transport

            def datagram_received(self, data, peer):
                requests.append(data)
                query = json.loads(data)
                response = dict(protocol=collector.ENDPOINT_QUERY_PROTOCOL, version=1,
                                nonce="stale-process-response",
                                diagnostic=dict(version=2, port=41234, access="lan"))
                self.transport.sendto(json.dumps(response).encode(), peer)
                response["nonce"] = query["nonce"]
                self.transport.sendto(json.dumps(response).encode(), peer)

        loop = asyncio.get_running_loop()
        transport, _ = await loop.create_datagram_endpoint(Responder, local_addr=("127.0.0.1", 0))
        try:
            with patch.object(collector, "private_ipv4", return_value="127.0.0.1"), \
                    patch.object(collector, "ENDPOINT_QUERY_PORT",
                                 transport.get_extra_info("sockname")[1]), \
                    patch("sys.stdout", new=io.StringIO()), \
                    patch.object(collector, "Client") as tcp:
                result = await collector.discover_sender("192.168.1.135", 1)
                self.assertEqual("127.0.0.1:41234", result)
                tcp.assert_not_called()
            self.assertEqual(1, len(requests))
            self.assertEqual(256, len(requests[0]))
            self.assertEqual({"protocol", "version", "nonce"}, set(json.loads(requests[0])))
        finally:
            transport.close()

    async def test_no_reply_fails_boundedly_without_retry_or_capture(self):
        transport = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        transport.bind(("127.0.0.1", 0))
        try:
            loop = asyncio.get_running_loop()
            with patch.object(collector, "private_ipv4", return_value="127.0.0.1"), \
                    patch.object(collector, "ENDPOINT_QUERY_PORT", transport.getsockname()[1]), \
                    patch.object(loop, "sock_recv", new=AsyncMock(side_effect=TimeoutError)), \
                    self.assertRaisesRegex(collector.DiagnosticError, "No enabled diagnostic endpoint"):
                await collector.discover_sender("192.168.1.135", 1)
        finally:
            transport.close()


class Writer:
    def __init__(self, certificate=b"certificate"):
        self.data = []
        self.closed = False
        self.certificate = certificate

    def write(self, data):
        self.data.append(data)

    async def drain(self):
        pass

    def close(self):
        self.closed = True

    def get_extra_info(self, name):
        return self if name == "ssl_object" else None

    def getpeercert(self, binary_form=False):
        return self.certificate


def stream(*messages):
    reader = asyncio.StreamReader(limit=collector.MAX_LINE + 2)
    for message in messages:
        reader.feed_data(json.dumps(message).encode() + b"\n")
    reader.feed_eof()
    return reader


def reply(op="STATUS", result=None, **extra):
    return dict(v=2, id="r1", op=op, ok=True,
                result={} if result is None else result, **extra)


def page(records=None, cursor="0", more=False, pending=0):
    records = records or []
    oldest = records[0]["seq"] if records else ("1" if cursor != "0" else "0")
    admission = {name: dict(count="0", firstMonoNs=None, lastMonoNs=None) for name in (
        "routine_capacity", "critical_capacity", "closed_capture", "expired", "oversized_record")}
    admission["total"] = "0"
    retention = {name: dict(count="0", firstSeq=None, lastSeq=None, exactRange=False)
                 for name in ("routine", "critical", "oversized")}
    loss = dict(schema="loss_watermarks", publishedSeq=cursor, oldestRetainedSeq=oldest,
                latestRetainedSeq=cursor, admission=admission, retention=retention)
    return dict(records=records, oldestSeq=oldest, latestSeq=cursor,
                nextAfterSeq=cursor, hasMore=more, pendingRecords=pending,
                droppedBeforeRetention="0", evictedAfterRetention="0", lossWatermarks=loss)


def event(seq="1", capture="c1"):
    return dict(captureId=capture, seq=seq, monoNs="1000", kind="SAMPLE", data={})


def sender_pair(source, capture="c1"):
    source = dict(source)
    records = source.pop("records")
    return dict(pairId="pair", armGeneration="1", state="covered", session=None,
                senderCaptureId=capture, senderClockId="clock", receiverCaptureId=None,
                receiverClockId=None, senderFlags=["session"], receiverFlags=["session"],
                sender=source, receiver=None,
                records=[dict(role="sender", session=None, captureId=capture, clockId="clock", record=r)
                         for r in records])


def frozen_sender_pair(source):
    value = sender_pair(source)
    value["state"] = "frozen"
    return value


def receiver_pair(source, capture="c1", session=str(collector.MAX_INT64)):
    source = dict(source)
    records = source.pop("records")
    loss = source.pop("lossWatermarks")
    source.pop("droppedBeforeRetention")
    source.pop("evictedAfterRetention")
    sequence_loss = dict(count="0", firstSeq=None, lastSeq=None, exactRange=False)
    source.update(sourceLoss=loss, intakeLoss=sequence_loss, forcedLoss=sequence_loss,
                  retentionLoss=sequence_loss, endConfirmed=False)
    sender = page()
    sender.pop("records")
    return dict(pairId="pair", armGeneration="1", state="covered", session=session,
                senderCaptureId="sender", senderClockId="sender-clock",
                receiverCaptureId=capture, receiverClockId="receiver-clock",
                senderFlags=["session"], receiverFlags=["video.progress", "video.timing"],
                sender=sender, receiver=source,
                records=[dict(role="receiver", session=r.get("session"), captureId=capture,
                              clockId="receiver-clock", record=r) for r in records])


def completed_pair(source):
    value = frozen_sender_pair(source)
    receiver = receiver_pair(page())["receiver"]
    receiver.update(endConfirmed=True, terminal={"reason": "receiver_retired_stop", "tailTruncated": False})
    value.update(session="77", receiverCaptureId="receiver", receiverClockId="receiver-clock",
                 receiver=receiver, bootstrap={"complete": True, "loss": False}, endReason="session_ended")
    return value


class FakePairClient:
    def __init__(self, pages):
        self.pages = iter(pages)
        self.ops = []
        self.closed = False
        self.last_bytes = 0
        self.last_page = None
        self.setup_failure = None
        self.receiver_outcome = None

    async def request(self, op, args=None):
        self.ops.append((op, args))
        if op == "CLOCK":
            now = str(collector.time.monotonic_ns())
            return dict(clientSendNs=args["clientSendNs"], receiveNs=now, sendNs=now, clockId="clock")
        if op == "START":
            return dict(pairId="pair", armGeneration="1", state="armed", startedNs="100",
                        expiresNs="200", senderCaptureId="c1", senderClockId="clock")
        if op == "FETCH":
            value = next(self.pages)
            if isinstance(value, Exception):
                raise value
            self.last_bytes = len(json.dumps(value).encode()) + 256
            self.last_page = value
            return value
        if op == "STATUS":
            value = dict(self.last_page)
            if self.setup_failure is not None:
                value["setupFailure"] = self.setup_failure
            if value.get("receiverCaptureId"):
                stages = ("socket_accepted", "hello_received", "hello_validated", "hello_reply_queued",
                          "request_received", "owner_admitted", "capture_started", "lane_admitted")
                value["bootstrap"] = dict(session=value["session"], pairId=value["pairId"],
                    captureId=value["receiverCaptureId"], clockId=value["receiverClockId"],
                    complete=True, loss=False, stages=[dict(stage=s, monoNs=str(i)) for i, s in enumerate(stages)])
                value["receiverRole"] = dict(state="finished", outcome=self.receiver_outcome or dict(
                    confirmed=True, reason="receiver_retired_stop", endedNs="1", mediaRetirement="succeeded",
                    ownershipCleanup="unknown", tailTruncated=False,
                    captureEnd=dict(atNs="1", reason="receiver_retired_stop", complete=True, truncated=False)))
            return value
        raise AssertionError(op)

    def close(self):
        self.closed = True


def timing_event(seq="1"):
    number = str(collector.MAX_INT64)
    correction = dict(sequence=number, beforeOffsetUs="0", afterOffsetUs="100",
                      deltaUs="100", appliedOffsetAtPacketUs="50")
    clock = dict(mode="CACHED_AUDIO", readAtNs=number, mappingRevision=number,
                 delayUs="100", positionUs=number, sourcePositionUs=number,
                 audioObservationSequence=number, audioObservationAtNs=number,
                 audioObservationAgeNs="0", audioOutputSource="TIMESTAMP",
                 audioTimestampAgeAtObservationUs="10", observedAppliedOffsetUs="50",
                 recoveringAudio=False, lastPacketCorrection=correction)
    scheduling = dict(mappingStable=True, ptsUs=number, deadlineNs=number,
                      releaseNs=number, scheduleAtNs=number, mappingRevision=number, clock=clock)
    pair = dict(deadlineCadenceMinusPtsNs="-10000", previous=scheduling, current=scheduling)
    metrics = dict(schema="video_frame_timing", capacity=128,
                   latestSchedulingExample=scheduling,
                   minimumDeadlineCadenceExample=pair, maximumDeadlineCadenceExample=pair,
                   worstCallbackErrorExample=dict(ptsUs=number, deadlineNs=number,
                       releaseNs=number, callbackNs=number, observedNs=number,
                       decodedMinusDeadlineNs="-1000", scheduling=scheduling))
    for name in ("matched", "missing", "pending", "unmatched", "ambiguous", "evicted",
                 "incomplete", "releaseFailed", "clockUnavailable", "mappingChanged",
                 "futureCallbacks", "outsideWindowCallbacks"):
        metrics[name] = number
    for name in ("decodedMinusDeadline", "suppliedMinusDeadline", "callbackMinusDeadline",
                 "observedMinusCallback", "ptsCadence", "deadlineCadence", "callbackCadence",
                 "callbackCadenceMinusPts", "callbackCadenceMinusDeadline",
                 "deadlineCadenceMinusPts", "audioObservationAge", "clockReadToSchedule"):
        metrics[name] = dict(count=number, minNs="-10000", maxNs=number)
    record = event(seq)
    record.update(kind="VIDEO_PROGRESS", role="receiver", session=number, track=1, generation=1)
    record["data"] = dict(stage="sample", reason="none", point="unspecified", metrics=metrics)
    return record


class ParsingTests(unittest.TestCase):
    def test_missing_generated_fixture_fails_in_required_ci_phase(self):
        with patch.dict(os.environ, {"DIAGNOSTICS_REQUIRE_FIXTURE": "1"}), \
                self.assertRaisesRegex(AssertionError, "Run DiagnosticVideoTimingTest"):
            fixture_path("missing-fixture.json")
        with patch.dict(os.environ, {"DIAGNOSTICS_REQUIRE_FIXTURE": "0"}), \
                self.assertRaises(unittest.SkipTest):
            fixture_path("missing-fixture.json")

    def test_pair_identity_rejects_replaced_sender_or_generation(self):
        value = sender_pair(page([event()], "1"))
        for field in ("senderCaptureId", "senderClockId", "armGeneration"):
            identity = {field: "different"}
            with self.subTest(field=field), self.assertRaises(collector.DiagnosticError):
                collector.validate_page(value, "pair", {"sender": 0, "receiver": 0}, identity)

    def test_direct_or_existing_session_capture_options_are_rejected(self):
        for options in (["--receiver", "host:1234"], ["--follow-receiver"],
                        ["--sender-session", "77"], ["--receiver-session", "77"]):
            with patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit) as result:
                collector.main(["--lan", "--sender", "host:1234", "--output", "unused"] + options)
            self.assertEqual(2, result.exception.code)

    def test_discovery_uses_queried_sender_not_advertised_host(self):
        value = dict(protocol=collector.ENDPOINT_QUERY_PROTOCOL, version=1, nonce="nonce",
                     diagnostic=dict(version=2, port=2345, access="lan", host="untrusted"))
        self.assertEqual("192.168.1.1:2345", collector.sender_discovery_endpoint(
            json.dumps(value).encode(), "nonce", "192.168.1.1"))

    def test_legacy_and_future_diagnostic_versions_are_not_endpoints(self):
        for version in (1, 3):
            value = dict(protocol=collector.ENDPOINT_QUERY_PROTOCOL, version=1, nonce="n",
                         diagnostic=dict(version=version, port=2345, access="lan"))
            with self.subTest(version=version), self.assertRaises(collector.DiagnosticError):
                collector.sender_discovery_endpoint(json.dumps(value).encode(), "n", "192.168.1.1")

    def test_invalid_diagnostic_advertisements_are_rejected(self):
        base = dict(protocol=collector.ENDPOINT_QUERY_PROTOCOL, version=1, nonce="n")
        for diagnostic in (None, {}, {"version": True, "port": 1234},
                           {"version": 2, "port": True}, {"version": 2, "port": 0},
                           {"version": 2, "port": 65536}, {"version": 2, "port": "1234"}):
            with self.subTest(diagnostic=diagnostic), self.assertRaises(collector.DiagnosticError):
                collector.sender_discovery_endpoint(json.dumps(dict(base, diagnostic=diagnostic)).encode(),
                                                    "n", "192.168.1.1")
        with self.assertRaises(collector.DiagnosticError):
            collector.sender_discovery_endpoint(b" " * 257, "n", "192.168.1.1")

    def test_exact_int64_preserves_large_session(self):
        self.assertEqual(797516823435589197, collector.exact_long("797516823435589197"))
        self.assertEqual((1 << 63) - 1, collector.exact_long(str((1 << 63) - 1)))

    def test_rejects_noncanonical_int64(self):
        for value in (1, True, 1.0, None, "", "01", "-1", "+1", "1e2",
                      str(1 << 63), "1" * 100):
            with self.subTest(value=value), self.assertRaises(collector.DiagnosticError):
                collector.exact_long(value)

    def test_json_rejects_duplicate_utf8_nonfinite_and_nonobject(self):
        for data in (b'{"x":1,"x":2}', b'{"x":"\xff"}', b'{"x":NaN}',
                     b'[]', b'{"x":Infinity}', b'{"x":1e999}', b'{"a":{"x":1,"x":2}}'):
            with self.subTest(data=data), self.assertRaises(collector.DiagnosticError):
                collector.strict_json(data)

    def test_json_depth_is_bounded(self):
        value = {}
        for _ in range(collector.MAX_JSON_DEPTH + 1):
            value = {"nested": value}
        with self.assertRaises(collector.DiagnosticError):
            collector.strict_json(json.dumps(value).encode())

    def test_tls_fingerprint_is_not_accepted_by_v2_lan_collector(self):
        with patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit) as result:
            collector.main(["--lan", "--sender", "host:1234", "--output", "unused",
                            "--sender-fingerprint", "ab" * 32])
        self.assertEqual(2, result.exception.code)

    def test_ipv4_hostname_and_bracketed_ipv6(self):
        self.assertEqual(("example.local", 1234), collector.endpoint("example.local:1234"))
        self.assertEqual(("fe80::1", 1234), collector.endpoint("[fe80::1]:1234"))
        for value in ("::1:1234", "host:0", "host:65536", "host:1:2"):
            with self.assertRaises(Exception):
                collector.endpoint(value)

    def test_flags_are_unique_and_known(self):
        self.assertEqual(["session", "recovery"], collector.parse_flags("session,recovery"))
        for value in ("", "session,session", "shell", "recovery,"):
            with self.assertRaises(Exception):
                collector.parse_flags(value)

    def test_fetch_retains_gaps_and_large_int64(self):
        sequence = "797516823435589197"
        records, cursor = collector.validate_page(sender_pair(page([event(sequence)], sequence)), "pair",
                                                   {"sender": 0, "receiver": 0})
        self.assertEqual(int(sequence), cursor["sender"])
        self.assertEqual(sequence, records[0]["record"]["seq"])

    def test_fetch_validates_exact_admission_and_retention_loss_ranges(self):
        value = page([event("3")], "3")
        loss = value["lossWatermarks"]
        loss["admission"]["routine_capacity"] = dict(
            count="2", firstMonoNs="1000", lastMonoNs="2000")
        loss["admission"]["total"] = value["droppedBeforeRetention"] = "2"
        loss["retention"]["routine"] = dict(
            count="2", firstSeq="1", lastSeq="4", exactRange=False)
        value["evictedAfterRetention"] = "2"
        collector.validate_page(sender_pair(value), "pair", {"sender": 0, "receiver": 0})
        for mutate in (
                lambda bad: bad["lossWatermarks"].update(publishedSeq="2"),
                lambda bad: bad["lossWatermarks"]["admission"]["routine_capacity"].update(
                    firstMonoNs="3000", lastMonoNs="2000"),
                lambda bad: bad["lossWatermarks"]["admission"].update(total="1"),
                lambda bad: bad["lossWatermarks"]["retention"]["routine"].update(
                    exactRange=True),
                lambda bad: bad["lossWatermarks"]["retention"].update(critical=dict(
                    count="1", firstSeq=None, lastSeq=None, exactRange=False)),
        ):
            invalid = json.loads(json.dumps(value))
            mutate(invalid)
            with self.assertRaises(collector.DiagnosticError):
                collector.validate_page(sender_pair(invalid), "pair", {"sender": 0, "receiver": 0})

    def test_compact_source_loss_preserves_ranges_and_rejects_malformed_arrays(self):
        source = page([event("3")], "3")
        source["lossWatermarks"] = ["compact1", "3", "3", "3", "2",
            [["2", "1000", "2000"]] + [["0", None, None] for _ in range(4)],
            [["2", "1", "4", False]] + [["0", None, None, False] for _ in range(2)]]
        source["droppedBeforeRetention"] = "2"
        source["evictedAfterRetention"] = "2"
        collector.validate_page(sender_pair(source), "pair", {"sender": 0, "receiver": 0})
        for invalid in (
                lambda bad: bad["lossWatermarks"][5].pop(),
                lambda bad: bad["lossWatermarks"][6][0].__setitem__(3, True),
                lambda bad: bad["lossWatermarks"].__setitem__(0, "unknown")):
            value = json.loads(json.dumps(source))
            invalid(value)
            with self.assertRaises(collector.DiagnosticError):
                collector.validate_page(sender_pair(value), "pair", {"sender": 0, "receiver": 0})

    def test_fetch_rejects_wrong_capture_rewind_and_false_progress(self):
        cases = [
            page([event(capture="other")], "1"),
            page([event("2"), event("1")], "1"),
            page([event()], "2"),
            page([], "0", True),
            dict(page(), hasMore=1),
            dict(page(), records=[event()] * 33),
            dict(page(), pendingRecords=True),
            dict(page(), pendingRecords=-1),
            dict(page(), pendingRecords=1025),
            dict(page(), records=[event("1")]),
        ]
        for value in cases:
            with self.subTest(value=value), self.assertRaises(collector.DiagnosticError):
                collector.validate_page(sender_pair(value), "pair", {"sender": 0, "receiver": 0})


class ProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def test_fully_populated_timing_fetch_passes_client_and_page_validation(self):
        record = timing_event()
        response = reply("FETCH", receiver_pair(page([record], "1")))
        encoded = json.dumps(response).encode()
        self.assertLessEqual(len(encoded), collector.MAX_LINE)
        self.assertEqual(response, collector.strict_json(encoded))
        old = legacy_collector()
        if old is not None:
            with self.assertRaisesRegex(old.DiagnosticError, "nesting exceeds"):
                old.strict_json(encoded)
        client = collector.Client(stream(response), Writer())
        result = await client.request("FETCH", {"pairId": "pair", "afterSenderSeq": "0",
                                                "afterReceiverSeq": "0", "limit": 32})
        records, cursor = collector.validate_page(result, "pair", {"sender": 0, "receiver": 0})
        self.assertEqual([record], [item["record"] for item in records])
        self.assertEqual(1, cursor["receiver"])
        correction = result["records"][0]["record"]["data"]["metrics"]["minimumDeadlineCadenceExample"][
            "previous"]["clock"]["lastPacketCorrection"]
        correction["sequence"] = {"tooDeep": "1"}
        response["result"] = result
        with self.assertRaisesRegex(collector.DiagnosticError, "nesting exceeds"):
            collector.strict_json(json.dumps(response).encode())

    async def test_generated_receiver_timing_fetch_passes_actual_collector_exchange(self):
        fixture = fixture_path("receiver-timing-fetch.json")
        response = collector.strict_json(fixture.read_bytes())
        response["id"] = "r1"
        client = collector.Client(stream(response), Writer())
        pair_id = response["result"]["pairId"]
        result = await client.request("FETCH", {"pairId": pair_id, "afterSenderSeq": str(collector.MAX_INT64),
                                                "afterReceiverSeq": "0", "limit": 32})
        cursors = {"sender": collector.MAX_INT64, "receiver": 0}
        with self.assertRaisesRegex(collector.DiagnosticError, "Oversized source record"):
            collector.validate_page(result, pair_id, cursors, flags={"sender": [], "receiver": []})
        records, _ = collector.validate_page(result, pair_id, cursors,
                                             flags={"sender": [], "receiver": ["video.timing"]})
        metrics = next(item["record"]["data"]["metrics"] for item in records
                       if item["record"]["data"].get("metrics", {}).get("schema") == "video_frame_timing")
        for pair in ("minimumDeadlineCadenceExample", "maximumDeadlineCadenceExample"):
            for side in ("previous", "current"):
                self.assertEqual("2", metrics[pair][side]["clock"]["lastPacketCorrection"]["sequence"])

    async def test_generated_legacy_receiver_capture_is_explicitly_rejected(self):
        fixture = json.loads(fixture_path("receiver-legacy-capture.json").read_bytes())
        hello = fixture["hello"]
        hello["id"] = "r1"
        records = fixture["fetch"]["result"]["records"]
        self.assertTrue(any(r["data"].get("metrics", {}).get("schema") == "video_progress" for r in records))
        writer = Writer()
        client = collector.Client(stream(hello), writer)
        with self.assertRaises(collector.DiagnosticError):
            await client.request("HELLO")
        self.assertEqual(["HELLO"], [json.loads(data)["op"] for data in writer.data])

    async def test_legacy_sized_delayed_tail_stays_within_old_frozen_limit(self):
        pages = []
        total = 0
        retained = 2 * 1024 * 1024 // 2048
        for begin, end in ((0, retained), (retained, retained + 1024)):
            for start in range(begin, end, 6):
                records = []
                stop = min(start + 6, end)
                for index in range(start, stop):
                    record = event(str(index + 1))
                    record["data"]["padding"] = ""
                    size = len(json.dumps(record, separators=(",", ":")).encode())
                    record["data"]["padding"] = "x" * (2048 - size)
                    total += 2048
                    records.append(record)
                pending = 1024 if stop == retained else 0
                pages.append(frozen_sender_pair(page(records, str(stop), stop < end, pending)))
        self.assertEqual(2 * 1024 * 1024 + 1024 * 2048, total)
        with patch.object(collector, "fetch_page", AsyncMock(side_effect=pages)), \
                patch.object(collector.asyncio, "sleep", AsyncMock()):
            output = io.StringIO()
            await collector.drain_frozen(None, "pair", {"sender": 0, "receiver": 0}, None,
                asyncio.get_running_loop().time() + 120, output)
            self.assertEqual(retained + 1024, sum('"type":"event"' in line
                for line in output.getvalue().splitlines()))

    async def test_delayed_full_size_timing_staging_tail_respects_new_frozen_bound(self):
        pages = []
        record_bytes = 0
        retained = (2 * 1024 * 1024) // 12000
        for index in range(retained + 64):
            record = timing_event(str(index + 1))
            record["data"]["padding"] = ""
            size = len(json.dumps(record, separators=(",", ":")).encode())
            record["data"]["padding"] = "x" * (12000 - size)
            self.assertEqual(12000, len(json.dumps(record, separators=(",", ":")).encode()))
            record_bytes += 12000
            item = receiver_pair(page([record], str(index + 1), index + 1 < retained + 64))
            item["state"] = "frozen"
            pages.append(item)
            if index + 1 == retained:
                pages[-1]["receiver"]["hasMore"] = False
                pages[-1]["receiver"]["pendingRecords"] = 64
                empty = receiver_pair(page(cursor=str(index + 1), pending=64))
                empty["state"] = "frozen"
                pages.append(empty)
        self.assertLessEqual(record_bytes, collector.MAX_FROZEN_BYTES)
        output = io.StringIO()
        with patch.object(collector, "fetch_page", AsyncMock(side_effect=pages)), \
                patch.object(collector.asyncio, "sleep", AsyncMock()) as sleeping:
            await collector.drain_frozen(None, "pair", {"sender": 0, "receiver": 0}, None,
                                         asyncio.get_running_loop().time() + 120, output)
        self.assertEqual(2, sleeping.await_count)
        self.assertEqual(retained + 64, sum('"type":"event"' in line for line in output.getvalue().splitlines()))
        with patch.object(collector, "MAX_FROZEN_BYTES", record_bytes - 1), \
                patch.object(collector, "fetch_page", AsyncMock(side_effect=pages)), \
                patch.object(collector.asyncio, "sleep", AsyncMock()):
            with self.assertRaisesRegex(collector.DiagnosticError, "exceeds bounded retention"):
                await collector.drain_frozen(None, "pair", {"sender": 0, "receiver": 0}, None,
                                             asyncio.get_running_loop().time() + 120, io.StringIO())

    async def test_byte_limited_frozen_backlog_exceeds_old_page_bound(self):
        backlog = []
        for kind, count, size in (("AUDIO_QUEUE", 3389, 464), ("RECORDER_SAMPLE", 179, 697)):
            for _ in range(count):
                record = event(str(len(backlog) + 1))
                record["kind"] = kind
                record["data"] = {"padding": ""}
                encoded = json.dumps(record, separators=(",", ":")).encode()
                record["data"]["padding"] = "x" * (size - len(encoded))
                self.assertEqual(size, len(json.dumps(record, separators=(",", ":")).encode()))
                backlog.append(record)
        self.assertLessEqual(3389 * 464, 2 * 1024 * 1024 - 512 * 1024)
        self.assertLessEqual(179 * 697, 512 * 1024)

        pages = []
        index = 0
        while index < len(backlog):
            batch = []
            size = 0
            while index < len(backlog) and len(batch) < 32:
                record = backlog[index]
                encoded_size = len(json.dumps(record, separators=(",", ":")).encode()) + 1
                if size + encoded_size > 12952:
                    break
                batch.append(record)
                size += encoded_size
                index += 1
            pages.append(frozen_sender_pair(page(batch, batch[-1]["seq"], index < len(backlog))))
        self.assertGreater(len(pages), 129)
        output = io.StringIO()
        fetching = AsyncMock(side_effect=pages)
        with patch.object(collector, "fetch_page", fetching):
            await collector.drain_frozen(None, "pair", {"sender": 0, "receiver": 0}, None,
                                         asyncio.get_running_loop().time() + 120, output)
        events = [json.loads(line)["value"]["record"] for line in output.getvalue().splitlines()
                  if json.loads(line)["type"] == "event"]
        self.assertEqual(backlog, events)
        self.assertEqual(len(pages), fetching.await_count)
        self.assertEqual([str(sum(len(p["records"]) for p in pages[:index]))
                          for index in range(len(pages))],
                         [call.args[1]["afterSenderSeq"] for call in fetching.await_args_list])

    async def test_pending_frozen_publication_waits_for_terminal_tail(self):
        tail = event("1")
        tail["kind"] = "CAPTURE_ENDED"
        output = io.StringIO()
        with patch.object(collector, "fetch_page", AsyncMock(side_effect=[
                frozen_sender_pair(page(pending=1)), frozen_sender_pair(page([tail], "1"))])), \
                patch.object(collector.asyncio, "sleep", AsyncMock()) as sleeping:
            await collector.drain_frozen(None, "pair", {"sender": 0, "receiver": 0}, None,
                                         asyncio.get_running_loop().time() + 120, output)
        sleeping.assert_awaited_once()
        self.assertIn('"kind":"CAPTURE_ENDED"', output.getvalue())

    async def test_stalled_frozen_publication_fails_explicitly_at_deadline(self):
        with patch.object(collector, "fetch_page", AsyncMock(side_effect=[
                frozen_sender_pair(page(pending=1)), None])) as fetching, \
                patch.object(collector.asyncio, "sleep", AsyncMock()) as sleeping:
            with self.assertRaisesRegex(collector.DiagnosticError, "exceeded its deadline"):
                await collector.drain_frozen(None, "pair", {"sender": 0, "receiver": 0}, None,
                                             asyncio.get_running_loop().time() + 120, io.StringIO())
        self.assertEqual(2, fetching.await_count)
        sleeping.assert_awaited_once()

    async def test_fetch_reservations_stay_within_rolling_record_and_byte_caps(self):
        budget = collector.FetchBudget()
        loop = asyncio.get_running_loop()
        for _ in range(8):
            self.assertTrue(await budget.reserve(loop.time() + 1))
            budget.commit(32, 15360)
        self.assertEqual(256, sum(item[1] for item in budget.deliveries))
        self.assertEqual(122880, sum(item[2] for item in budget.deliveries))
        self.assertFalse(await budget.reserve(deadline=loop.time() + 0.01))
        self.assertEqual(8, len(budget.deliveries))

    async def test_lan_hello_requires_no_secret_or_tls(self):
        writer = Writer()
        reader = stream(reply("HELLO", {"clockId": "c", "pairedNextCast": 1,
                                        "senderFlags": ["session"], "receiverFlags": ["session"]}))
        with patch.object(collector.asyncio, "open_connection",
                          AsyncMock(return_value=(reader, writer))) as opening:
            client, result = await collector.Client.connect_lan(("host", 1234))
        request = json.loads(writer.data[0])
        self.assertEqual("HELLO", request["op"])
        self.assertEqual({}, request["args"])
        self.assertNotIn("ssl", opening.call_args.kwargs)
        self.assertEqual("c", result["clockId"])
        client.close()

    async def test_lan_handshake_failure_closes_connection(self):
        writer = Writer()
        with patch.object(collector.asyncio, "open_connection",
                          AsyncMock(return_value=(stream(reply("AUTH")), writer))):
            with self.assertRaises(collector.DiagnosticError):
                await collector.Client.connect_lan(("host", 1234))
        self.assertTrue(writer.closed)

    async def test_legacy_version_response_is_rejected_without_auth(self):
        writer = Writer()
        reader = stream(dict(v=1, id="r1", op="HELLO", ok=True, result={}))
        with patch.object(collector.asyncio, "open_connection", AsyncMock(return_value=(reader, writer))), \
                self.assertRaises(collector.DiagnosticError):
            await collector.Client.connect_lan(("host", 1234))
        self.assertEqual(["HELLO"], [json.loads(data)["op"] for data in writer.data])
        self.assertTrue(writer.closed)

    async def test_missing_paired_capability_fails_without_capture(self):
        writer = Writer()
        with patch.object(collector.asyncio, "open_connection",
                          AsyncMock(return_value=(stream(reply("HELLO", {"clockId": "c"})), writer))):
            with self.assertRaises(collector.DiagnosticError):
                await collector.Client.connect_lan(("host", 1234))
        self.assertEqual(["HELLO"], [json.loads(data)["op"] for data in writer.data])
        self.assertTrue(writer.closed)

    async def test_missing_clock_identity_fails_closed(self):
        writer = Writer()
        with patch.object(collector.asyncio, "open_connection",
                          AsyncMock(return_value=(stream(reply("HELLO", {"pairedNextCast": 1})), writer))):
            with self.assertRaises(collector.DiagnosticError):
                await collector.Client.connect_lan(("host", 1234))
        self.assertEqual(["HELLO"], [json.loads(data)["op"] for data in writer.data])
        self.assertTrue(writer.closed)

    async def test_unsolicited_events_are_rejected_in_fetch_only_protocol(self):
        reader = stream({"v": 2, "event": event()}, reply(result={"state": "idle"}))
        client = collector.Client(reader, Writer())
        with self.assertRaises(collector.DiagnosticError):
            await client.request("STATUS")

    async def test_mismatched_response_identity_fails(self):
        value = reply()
        value["id"] = "other"
        client = collector.Client(stream(value), Writer())
        with self.assertRaises(collector.DiagnosticError):
            await client.request("STATUS")

    async def test_remote_errors_do_not_echo_arbitrary_text(self):
        value = dict(v=2, id="r1", op="START", ok=False,
                     error={"code": "CAPTURE_ACTIVE", "message": "secret-sensitive-text"})
        client = collector.Client(stream(value), Writer())
        with self.assertRaisesRegex(collector.DiagnosticError, "^START rejected: CAPTURE_ACTIVE$"):
            await client.request("START")

    async def test_invalid_success_and_version_are_rejected(self):
        for changes in ({"v": True}, {"v": 1}, {"ok": 1},
                        {"result": []}, {"error": {}}):
            value = reply()
            value.update(changes)
            client = collector.Client(stream(value), Writer())
            with self.subTest(changes=changes), self.assertRaises(collector.DiagnosticError):
                await client.request("STATUS")

    async def test_oversized_frame_fails(self):
        reader = asyncio.StreamReader(limit=collector.MAX_LINE + 2)
        reader.feed_data(b"x" * (collector.MAX_LINE + 3) + b"\n")
        client = collector.Client(reader, Writer())
        with self.assertRaises(collector.DiagnosticError):
            await client.request("STATUS")

    async def test_partial_frame_and_eof_fail(self):
        for data in (b"", b'{"v":1}'):
            reader = asyncio.StreamReader()
            reader.feed_data(data)
            reader.feed_eof()
            with self.assertRaises(collector.DiagnosticError):
                await collector.Client(reader, Writer()).request("STATUS")

    async def test_complete_capture_stops_and_drains_without_exporting_secret(self):
        fake = FakePairClient([completed_pair(page())])
        with test_workspace("collect-complete") as directory:
            output = Path(directory) / "sender.jsonl"
            with patch.object(collector.Client, "connect_lan", AsyncMock(return_value=(fake, {
                    "clockId": "clock", "senderFlags": ["session"], "receiverFlags": ["session"]}))):
                await collector.collect_pair(("private-host", 1234), ["session"], ["session"], 1, output)
            text = output.read_text()
            self.assertNotIn("private-host", text)
            self.assertNotIn("token", text)
            self.assertEqual(0, sum(op == "END" for op, _ in fake.ops))
            self.assertEqual(1, sum(op == "CLOCK" for op, _ in fake.ops))
            self.assertTrue(fake.closed)
            self.assertIn('"type":"ended"', text)
            self.assertIn('"stage":"socket_accepted"', text)
            self.assertIn('"outcome":', text)

    async def test_capture_uses_start_flags_for_timing_without_fetch_flags(self):
        response = collector.strict_json(fixture_path("receiver-timing-fetch.json").read_bytes())
        page = response["result"]
        page.update(pairId="pair", state="frozen", senderCaptureId="c1", senderClockId="clock",
                    endReason="session_ended")
        page["sender"]["nextAfterSeq"] = "0"
        page["receiver"].update(endConfirmed=True,
                                terminal={"reason": "receiver_retired_stop", "tailTruncated": False})
        page.pop("senderFlags", None)
        page.pop("receiverFlags", None)
        fake = FakePairClient([page])
        with test_workspace("collect-timing-flags") as directory, \
                patch.object(collector.Client, "connect_lan", AsyncMock(return_value=(fake, {
                    "clockId": "clock", "senderFlags": ["session"],
                    "receiverFlags": ["session", "video.progress", "video.timing"]}))):
            output = Path(directory) / "pair.jsonl"
            await collector.collect_pair(("host", 1234), ["session"],
                                         ["session", "video.progress", "video.timing"], 1, output)
            self.assertIn("video_frame_timing", output.read_text())

    async def test_backlogged_fetches_run_without_fixed_poll_sleep(self):
        fake = FakePairClient([completed_pair(page([event("1")], "1", True)),
                               completed_pair(page([event("2")], "2"))])
        with test_workspace("collect-backlog") as directory, \
                patch.object(collector.Client, "connect_lan",
                             AsyncMock(return_value=(fake, {"clockId": "clock",
                                 "senderFlags": ["session"], "receiverFlags": ["session"]}))), \
                patch.object(collector.asyncio, "sleep", AsyncMock()) as sleeping:
            await collector.collect_pair(("host", 1234), ["session"], ["session"], 1,
                                         Path(directory) / "pair.jsonl")
            sleeping.assert_not_awaited()
            self.assertEqual(["0", "1"], [args["afterSenderSeq"] for op, args in fake.ops if op == "FETCH"])

    async def test_unsupported_requested_flags_never_start_capture(self):
        fake = AsyncMock()
        fake.close = lambda: None
        with test_workspace("collect-unsupported") as directory:
            with patch.object(collector.Client, "connect_lan", AsyncMock(return_value=(
                    fake, {"clockId": "clock1", "senderFlags": ["session"], "receiverFlags": ["session"]}))):
                with self.assertRaisesRegex(collector.DiagnosticError, "Unsupported requested flags"):
                    await collector.collect_pair(("host", 1), ["audio.capture"], ["session"], 1,
                                                 Path(directory) / "pair.jsonl")
        fake.request.assert_not_called()

    async def test_paired_failure_closes_single_connection_and_preserves_partial_output(self):
        fake = FakePairClient([collector.DiagnosticError("receiver failed")])
        with test_workspace("paired-failure") as directory, \
                patch.object(collector.Client, "connect_lan", AsyncMock(return_value=(fake, {
                    "clockId": "clock", "senderFlags": ["session"], "receiverFlags": ["session"]}))) as connect:
            output = Path(directory) / "pair.jsonl"
            with self.assertRaises(collector.DiagnosticError):
                await collector.collect_pair(("host", 1), ["session"], ["session"], 1, output)
            self.assertTrue(fake.closed)
            self.assertEqual(1, connect.await_count)
            self.assertNotIn('"type":"ended"', output.read_text())

    async def test_missing_receiver_coverage_is_not_reported_as_success(self):
        incomplete = frozen_sender_pair(page())
        incomplete.update(endReason="setup_failed")
        fake = FakePairClient([incomplete])
        with test_workspace("missing-receiver") as directory, \
                patch.object(collector.Client, "connect_lan", AsyncMock(return_value=(fake, {
                    "clockId": "clock", "senderFlags": ["session"], "receiverFlags": ["session"]}))) as connect:
            with self.assertRaisesRegex(collector.DiagnosticError, "without confirmed receiver coverage"):
                await collector.collect_pair(("host", 1), ["session"], ["session"], 1,
                                             Path(directory) / "pair.jsonl")
            self.assertEqual(1, connect.await_count)
            self.assertTrue(fake.closed)

    async def test_unconfirmed_receiver_status_is_saved_but_never_complete(self):
        incomplete = completed_pair(page())
        incomplete["receiver"].update(endConfirmed=False, terminal=None)
        fake = FakePairClient([incomplete])
        fake.receiver_outcome = dict(confirmed=False, reason=None, endedNs=None, mediaRetirement="unknown",
            ownershipCleanup="unknown", tailTruncated=True,
            captureEnd=dict(atNs=None, reason=None, complete=False, truncated=True))
        with test_workspace("unconfirmed-receiver") as directory, \
                patch.object(collector.Client, "connect_lan", AsyncMock(return_value=(fake, {
                    "clockId": "clock", "senderFlags": ["session"], "receiverFlags": ["session"]}))):
            output = Path(directory) / "pair.jsonl"
            with self.assertRaisesRegex(collector.DiagnosticError, "without confirmed receiver coverage"):
                await collector.collect_pair(("host", 1), ["session"], ["session"], 1, output)
            lines = [json.loads(line) for line in output.read_text().splitlines()]
            statuses = [line["value"] for line in lines if line["type"] == "status"]
            self.assertEqual(fake.receiver_outcome, statuses[-1]["receiverRole"]["outcome"])
            self.assertTrue(fake.closed)

    async def test_confirmed_receiver_status_is_saved(self):
        fake = FakePairClient([])
        fake.last_page = completed_pair(page())
        fake.receiver_outcome = dict(confirmed=True, reason="receiver_retired_fatal", endedNs="2",
            mediaRetirement="failed", ownershipCleanup="unknown", tailTruncated=True,
            captureEnd=dict(atNs="3", reason="receiver_retired_fatal", complete=False, truncated=True))
        for outcome in (fake.receiver_outcome,
                        {key: value for key, value in fake.receiver_outcome.items() if key != "confirmed"}):
            with self.subTest(outcome=outcome):
                fake.receiver_outcome = outcome
                output = io.StringIO()
                await collector.record_status(fake, output, "pair", {},
                    asyncio.get_running_loop().time() + 2, collector.FetchBudget())
                self.assertEqual(outcome, json.loads(output.getvalue())["value"]["receiverRole"]["outcome"])

    async def test_confirmed_receiver_status_rejects_malformed_outcomes(self):
        base = dict(confirmed=True, reason="receiver_retired_stop", endedNs="1",
                    mediaRetirement="succeeded", ownershipCleanup="unknown", tailTruncated=False,
                    captureEnd=dict(atNs="1", reason="receiver_retired_stop", complete=True, truncated=False))
        variants = [
            dict(base, confirmed=False), dict(base, confirmed=1), dict(base, confirmed="true"),
            dict(base, confirmed=None),
            dict(base, extra=True), {key: value for key, value in base.items() if key != "endedNs"},
            dict(base, reason="other"), dict(base, endedNs=1), dict(base, endedNs="-1"),
            dict(base, mediaRetirement="unknown"), dict(base, ownershipCleanup="succeeded"),
            dict(base, tailTruncated=0),
            dict(base, captureEnd=dict(base["captureEnd"], atNs=1)),
            dict(base, captureEnd=dict(base["captureEnd"], reason="receiver_retired_fatal")),
            dict(base, captureEnd=dict(base["captureEnd"], complete=1)),
            dict(base, captureEnd=dict(base["captureEnd"], truncated=0)),
            dict(base, captureEnd=dict(base["captureEnd"], truncated=True)),
            dict(base, captureEnd=dict(base["captureEnd"], complete=False)),
            dict(base, captureEnd=dict(base["captureEnd"], extra=True)),
            dict(base, captureEnd={key: value for key, value in base["captureEnd"].items() if key != "atNs"}),
        ]
        for outcome in variants:
            fake = FakePairClient([])
            fake.last_page = completed_pair(page())
            fake.receiver_outcome = outcome
            with self.subTest(outcome=outcome), self.assertRaises(collector.DiagnosticError):
                await collector.record_status(fake, io.StringIO(), "pair", {},
                    asyncio.get_running_loop().time() + 2, collector.FetchBudget())

    async def test_unconfirmed_receiver_status_rejects_fabricated_or_untyped_fields(self):
        import io
        base = dict(confirmed=False, reason=None, endedNs=None, mediaRetirement="unknown",
                    ownershipCleanup="unknown", tailTruncated=True,
                    captureEnd=dict(atNs=None, reason=None, complete=False, truncated=True))
        for update in (dict(confirmed=0), dict(reason="receiver_retired_stop"), dict(endedNs="1"),
                       dict(mediaRetirement="succeeded"), dict(ownershipCleanup="succeeded"),
                       dict(tailTruncated=1), dict(tailTruncated=False), dict(extra=True),
                       dict(captureEnd=dict(atNs=None, reason=None, complete=0, truncated=True)),
                       dict(captureEnd=dict(atNs=None, reason=None, complete=False, truncated=1)),
                       dict(captureEnd=dict(atNs=None, reason=None, complete=False, truncated=True, extra=True))):
            fake = FakePairClient([])
            fake.last_page = completed_pair(page())
            fake.receiver_outcome = dict(base, **update)
            with self.subTest(update=update), self.assertRaises(collector.DiagnosticError):
                await collector.record_status(fake, io.StringIO(), "pair", {},
                    asyncio.get_running_loop().time() + 2, collector.FetchBudget())

    async def test_setup_failure_is_saved_from_status_without_inventing_coverage(self):
        incomplete = frozen_sender_pair(page())
        incomplete.update(endReason="setup_failed", setupFailureAvailable=True)
        fake = FakePairClient([incomplete])
        fake.setup_failure = dict(code="PROTOCOL", provenance="receiver_control", reason="receiver_reported")
        with test_workspace("setup-failure") as directory, \
                patch.object(collector.Client, "connect_lan", AsyncMock(return_value=(fake, {
                    "clockId": "clock", "senderFlags": ["session"], "receiverFlags": ["session"]}))):
            output = Path(directory) / "pair.jsonl"
            with self.assertRaisesRegex(collector.DiagnosticError, "without confirmed receiver coverage"):
                await collector.collect_pair(("host", 1), ["session"], ["session"], 1, output)
            lines = [json.loads(line) for line in output.read_text().splitlines()]
            statuses = [line["value"] for line in lines if line["type"] == "status"]
            self.assertEqual(fake.setup_failure, statuses[-1]["setupFailure"])
            self.assertNotIn("setupFailure", next(line["value"] for line in lines if line["type"] == "ended"))

    async def test_setup_failure_status_rejects_unsanitized_or_inconsistent_fields(self):
        import io
        for failure in (dict(code="PROTOCOL", provenance="receiver_control", reason="receiver_reported",
                             message="private"),
                        dict(code="PROTOCOL", provenance="receiver_control", reason="timeout"),
                        dict(code="private", provenance="sender_local", reason="setup")):
            fake = FakePairClient([])
            fake.last_page = dict(pairId="pair", state="frozen", endReason="setup_failed")
            fake.setup_failure = failure
            with self.subTest(failure=failure), self.assertRaises(collector.DiagnosticError):
                await collector.record_status(fake, io.StringIO(), "pair", {},
                    asyncio.get_running_loop().time() + 2, collector.FetchBudget())


class LanIntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def exchange(self, version):
        observed = []
        finished = asyncio.Event()

        async def handler(reader, writer):
            try:
                request = collector.strict_json(await reader.readline())
                observed.append(request)
                response = dict(v=version, id=request["id"], op="HELLO", ok=True,
                                result=dict(pairedNextCast=1, clockId="clock"))
                writer.write(json.dumps(response).encode() + b"\n")
                await writer.drain()
                observed.append(await reader.readline())
            finally:
                writer.close()
                finished.set()

        server = await asyncio.start_server(handler, "127.0.0.1", 0)
        try:
            address = ("127.0.0.1", server.sockets[0].getsockname()[1])
            if version == 2:
                client, hello = await collector.Client.connect_lan(address)
                self.assertEqual("clock", hello["clockId"])
                client.close()
            else:
                with self.assertRaises(collector.DiagnosticError):
                    await collector.Client.connect_lan(address)
            await asyncio.wait_for(finished.wait(), 2)
            self.assertEqual("HELLO", observed[0]["op"])
            self.assertEqual(2, observed[0]["v"])
            self.assertEqual(b"", observed[1])
        finally:
            server.close()
            await server.wait_closed()

    async def test_real_lan_v2_handshake_closes(self):
        await self.exchange(2)

    async def test_real_legacy_peer_is_rejected_without_capture(self):
        await self.exchange(1)


if __name__ == "__main__":
    unittest.main()
