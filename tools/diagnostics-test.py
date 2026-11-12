#!/usr/bin/env python3
"""Dependency-free remote diagnostic collector contract tests."""
import asyncio
import hashlib
import importlib.util
import json
import io
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
            legacy = [flag for flag in supported if flag != "video.progress"]
            self.assertEqual(legacy, collector.select_flags(role, None, legacy))

    def test_explicit_video_is_strict_and_parser_accepts_it(self):
        self.assertEqual(["video.progress"], collector.parse_flags("video.progress"))
        with self.assertRaisesRegex(collector.DiagnosticError, "Unsupported requested flags"):
            collector.select_flags("receiver", ["video.progress"], ["session"])
        self.assertEqual(["video.progress"], collector.select_flags(
            "receiver", ["video.progress"], ["session", "video.progress"]))

    def test_default_does_not_invent_flags_or_accept_empty_capabilities(self):
        self.assertEqual(["session"], collector.select_flags("sender", None, ["session", "future.flag"]))
        with self.assertRaises(collector.DiagnosticError):
            collector.select_flags("sender", None, [])


class SenderDiscoveryTests(unittest.TestCase):
    nonce = "0123456789abcdef0123456789abcdef"

    def response(self, **changes):
        value = dict(protocol=collector.ENDPOINT_QUERY_PROTOCOL, version=1, nonce=self.nonce,
                     diagnostic=dict(version=1, port=41234, access="lan"))
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
                    version=1, port=port, access="lan")), self.nonce, "192.168.1.135")
        for changes in (dict(version=True), dict(version=2), dict(access="tls")):
            hint = dict(version=1, port=41234, access="lan")
            hint.update(changes)
            with self.assertRaises(collector.DiagnosticError):
                collector.sender_discovery_endpoint(self.response(diagnostic=hint),
                                                   self.nonce, "192.168.1.135")
        self.assertEqual("192.168.1.135:41234", collector.sender_discovery_endpoint(
            self.response(diagnostic=dict(version=1, port=41234, access="lan", host="evil")),
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
                                diagnostic=dict(version=1, port=41234, access="lan"))
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
    return dict(v=1, id="r1", op=op, ok=True,
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


class ParsingTests(unittest.TestCase):
    def test_sender_connections_validate_types_owner_state_and_exact_endpoint(self):
        receiver = dict(available=True, host="192.0.2.10", port=34567, version=1,
                        access="lan", session=str(2**63-1), state="enabled")
        value = dict(version=1, receiver=receiver)
        self.assertEqual((("192.0.2.10", 34567), str(2**63-1), "enabled"),
                         collector.receiver_connection(value))
        for field, invalid in (("port", True), ("port", 0), ("port", 65536),
                               ("port", "34567"), ("version", True), ("version", 2),
                               ("session", 123), ("host", "untrusted.example"), ("host", 123),
                               ("state", "frozen"), ("access", "tls")):
            with self.subTest(field=field, value=invalid), self.assertRaises(collector.DiagnosticError):
                collector.receiver_connection(dict(value, receiver=dict(receiver, **{field: invalid})))
        with self.assertRaises(collector.DiagnosticError):
            collector.receiver_connection(value, "78")
        for unavailable in ({}, {"version": 1}, {"version": True, "receiver": receiver},
                            {"version": 1, "receiver": {"available": False, "reason": "grace_expired"}}):
            with self.assertRaises(collector.DiagnosticError):
                collector.receiver_connection(unavailable)
    def test_delegated_target_binds_only_hello_scope(self):
        scope = {"role": "receiver", "session": "797516823435589197", "active": True}
        self.assertEqual({"role": "receiver", "session": scope["session"]},
                         collector.capture_target("receiver", None, scope))
        for role, session in (("sender", None), ("receiver", "78")):
            with self.assertRaises(collector.DiagnosticError):
                collector.capture_target(role, session, scope)
        self.assertEqual({"role": "sender", "nextSession": True},
                         collector.capture_target("sender", None, None))

    def test_discovery_uses_udp_source_not_advertised_host(self):
        value = dict(protocol="screencopy", version=3, name="Receiver", port=1234,
                     state="busy", diagnostic={"version": 1, "port": 2345,
                                               "host": "untrusted"})
        self.assertEqual("192.0.2.1:2345", collector.discovery_endpoint(
            json.dumps(value).encode(), "192.0.2.1"))

    def test_legacy_and_future_diagnostic_versions_are_not_endpoints(self):
        base = dict(protocol="screencopy", version=3, name="Receiver", port=1234,
                    state="available")
        self.assertIsNone(collector.discovery_endpoint(json.dumps(base).encode(), "192.0.2.1"))
        base["diagnostic"] = {"version": 2}
        self.assertIsNone(collector.discovery_endpoint(json.dumps(base).encode(), "192.0.2.1"))

    def test_invalid_diagnostic_advertisements_are_rejected(self):
        base = dict(protocol="screencopy", version=3, name="Receiver", port=1234,
                    state="available")
        for diagnostic in (None, {}, {"version": True, "port": 1234},
                           {"version": 1, "port": True}, {"version": 1, "port": 0},
                           {"version": 1, "port": 65536}, {"version": 1, "port": "1234"}):
            with self.subTest(diagnostic=diagnostic), self.assertRaises(collector.DiagnosticError):
                collector.discovery_endpoint(json.dumps(dict(base, diagnostic=diagnostic)).encode(),
                                             "192.0.2.1")
        with self.assertRaises(collector.DiagnosticError):
            collector.discovery_endpoint(b" " * 1025, "192.0.2.1")

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
        for _ in range(10):
            value = {"nested": value}
        with self.assertRaises(collector.DiagnosticError):
            collector.strict_json(json.dumps(value).encode())

    def test_fingerprint_normalization(self):
        self.assertEqual("ab" * 32, collector.fingerprint(":".join(["AB"] * 32)))
        for value in ("", "ab" * 31, "gg" * 32, "ab" * 33):
            with self.assertRaises(collector.DiagnosticError):
                collector.fingerprint(value)

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
        records, cursor = collector.validate_page(page([event(sequence)], sequence), "c1", 0)
        self.assertEqual(int(sequence), cursor)
        self.assertEqual(sequence, records[0]["seq"])

    def test_fetch_validates_exact_admission_and_retention_loss_ranges(self):
        value = page([event("3")], "3")
        loss = value["lossWatermarks"]
        loss["admission"]["routine_capacity"] = dict(
            count="2", firstMonoNs="1000", lastMonoNs="2000")
        loss["admission"]["total"] = value["droppedBeforeRetention"] = "2"
        loss["retention"]["routine"] = dict(
            count="2", firstSeq="1", lastSeq="4", exactRange=False)
        value["evictedAfterRetention"] = "2"
        collector.validate_page(value, "c1", 0)
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
                collector.validate_page(invalid, "c1", 0)

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
                collector.validate_page(value, "c1", 0)


class ProtocolTests(unittest.IsolatedAsyncioTestCase):
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
            pages.append(page(batch, batch[-1]["seq"], index < len(backlog)))
        self.assertGreater(len(pages), 129)
        output = io.StringIO()
        fetching = AsyncMock(side_effect=pages)
        with patch.object(collector, "fetch_page", fetching):
            await collector.drain_frozen(None, "c1", 0, None,
                                         asyncio.get_running_loop().time() + 120, output)
        events = [json.loads(line)["value"] for line in output.getvalue().splitlines()
                  if json.loads(line)["type"] == "event"]
        self.assertEqual(backlog, events)
        self.assertEqual(len(pages), fetching.await_count)
        self.assertEqual([str(sum(len(p["records"]) for p in pages[:index]))
                          for index in range(len(pages))],
                         [call.args[1]["afterSeq"] for call in fetching.await_args_list])

    async def test_pending_frozen_publication_waits_for_terminal_tail(self):
        tail = event("1")
        tail["kind"] = "CAPTURE_ENDED"
        output = io.StringIO()
        with patch.object(collector, "fetch_page", AsyncMock(side_effect=[
                page(pending=1), page([tail], "1")])), \
                patch.object(collector.asyncio, "sleep", AsyncMock()) as sleeping:
            await collector.drain_frozen(None, "c1", 0, None,
                                         asyncio.get_running_loop().time() + 120, output)
        sleeping.assert_awaited_once()
        self.assertIn('"kind":"CAPTURE_ENDED"', output.getvalue())

    async def test_stalled_frozen_publication_fails_explicitly_at_deadline(self):
        with patch.object(collector, "fetch_page", AsyncMock(side_effect=[
                page(pending=1), None])) as fetching, \
                patch.object(collector.asyncio, "sleep", AsyncMock()) as sleeping:
            with self.assertRaisesRegex(collector.DiagnosticError, "exceeded its deadline"):
                await collector.drain_frozen(None, "c1", 0, None,
                                             asyncio.get_running_loop().time() + 120, io.StringIO())
        self.assertEqual(2, fetching.await_count)
        sleeping.assert_awaited_once()

    async def test_fetch_reservations_stay_within_rolling_record_and_byte_caps(self):
        budget = collector.FetchBudget()
        for _ in range(8):
            self.assertIsNotNone(await budget.reserve())
        self.assertEqual(256, sum(item[1] for item in budget.deliveries))
        self.assertEqual(122880, sum(item[2] for item in budget.deliveries))
        loop = asyncio.get_running_loop()
        self.assertIsNone(await budget.reserve(deadline=loop.time() + 0.01))
        self.assertEqual(8, len(budget.deliveries))

    async def test_lan_hello_requires_no_secret_or_tls(self):
        writer = Writer()
        reader = stream(reply("HELLO", {"clockId": "c", "capabilities": {}}))
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

    async def test_pinned_connection_authenticates_only_after_match(self):
        writer = Writer()
        reader = stream(reply("AUTH"))
        digest = hashlib.sha256(writer.certificate).hexdigest()
        with patch.object(collector.asyncio, "open_connection", AsyncMock(return_value=(reader, writer))):
            client, _ = await collector.Client.connect(("host", 1234), digest, "private-secret")
        self.assertEqual("AUTH", json.loads(writer.data[0])["op"])
        client.close()
        self.assertTrue(writer.closed)

    async def test_wrong_pin_sends_no_auth_or_other_bytes(self):
        writer = Writer()
        with patch.object(collector.asyncio, "open_connection",
                          AsyncMock(return_value=(stream(), writer))):
            with self.assertRaisesRegex(collector.DiagnosticError, "fingerprint mismatch"):
                await collector.Client.connect(("host", 1234), "00" * 32, "private-secret")
        self.assertEqual([], writer.data)
        self.assertTrue(writer.closed)

    async def test_missing_certificate_fails_closed(self):
        writer = Writer(None)
        with patch.object(collector.asyncio, "open_connection",
                          AsyncMock(return_value=(stream(), writer))):
            with self.assertRaises(collector.DiagnosticError):
                await collector.Client.connect(("host", 1234), "00" * 32, "secret")
        self.assertEqual([], writer.data)

    async def test_live_events_do_not_confuse_command_responses(self):
        reader = stream({"v": 1, "event": event()}, reply(result={"state": "idle"}))
        client = collector.Client(reader, Writer())
        self.assertEqual({"state": "idle"}, await client.request("STATUS"))

    async def test_mismatched_response_identity_fails(self):
        value = reply()
        value["id"] = "other"
        client = collector.Client(stream(value), Writer())
        with self.assertRaises(collector.DiagnosticError):
            await client.request("STATUS")

    async def test_remote_errors_do_not_echo_arbitrary_text(self):
        value = dict(v=1, id="r1", op="START", ok=False,
                     error={"code": "CAPTURE_ACTIVE", "message": "secret-sensitive-text"})
        client = collector.Client(stream(value), Writer())
        with self.assertRaisesRegex(collector.DiagnosticError, "^START rejected: CAPTURE_ACTIVE$"):
            await client.request("START")

    async def test_invalid_success_and_version_are_rejected(self):
        for changes in ({"v": True}, {"v": 2}, {"ok": 1},
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
        class Fake:
            def __init__(self):
                self.ops = []
                self.closed = False

            async def request(self, op, args=None):
                self.ops.append(op)
                if op == "CLOCK":
                    now = str(collector.time.monotonic_ns())
                    return dict(clientSendNs=args["clientSendNs"], receiveNs=now, sendNs=now)
                if op == "START":
                    return dict(captureId="c1", startedNs="100", expiresNs="200")
                if op == "FETCH":
                    return page()
                if op == "STATUS":
                    return {"state": "frozen", "connections": {"self": {"host": "private-host"}}}
                if op == "END":
                    return {"captureId": "c1"}
                raise AssertionError(op)

            def close(self):
                self.closed = True

        fake = Fake()
        with test_workspace("collect-complete") as directory:
            output = Path(directory) / "sender.jsonl"
            with patch.object(collector.Client, "connect",
                              AsyncMock(return_value=(fake, {
                                  "clockId": "clock1", "capabilities": {"sender": ["recovery"]}}))):
                await collector.collect("sender", ("host", 1234), "00" * 32,
                                        "private-secret", ["recovery"], 1, None, output)
            text = output.read_text()
            self.assertNotIn("private-secret", text)
            self.assertNotIn("host", text)
            self.assertNotIn("connections", text)
            self.assertEqual(1, fake.ops.count("END"))
            self.assertEqual(10, fake.ops.count("CLOCK"))
            self.assertTrue(fake.closed)
            self.assertIn('"type":"ended"', text)

    async def test_backlogged_fetches_run_without_fixed_poll_sleep(self):
        class Fake:
            def __init__(self):
                self.fetches = 0

            async def request(self, op, args=None):
                if op == "CLOCK":
                    now = str(collector.time.monotonic_ns())
                    return dict(clientSendNs=args["clientSendNs"], receiveNs=now, sendNs=now)
                if op == "START":
                    return dict(captureId="c1", startedNs="100", expiresNs="200")
                if op == "FETCH":
                    self.fetches += 1
                    if args["afterSeq"] == "0":
                        return page([event("1")], "1", True)
                    if args["afterSeq"] == "1":
                        return page([event("2")], "2")
                if op == "STATUS":
                    return {"state": "frozen"}
                if op == "END":
                    return {"captureId": "c1"}
                raise AssertionError(op)

            def close(self):
                pass

        fake = Fake()
        with test_workspace("collect-backlog") as directory, \
                patch.object(collector.Client, "connect",
                             AsyncMock(return_value=(fake, {
                                 "clockId": "clock1", "capabilities": {"sender": ["recovery"]}}))), \
                patch.object(collector.asyncio, "sleep", AsyncMock()) as sleeping:
            await collector.collect("sender", ("host", 1234), "00" * 32, "unused",
                                    ["recovery"], 1, None, Path(directory) / "sender.jsonl")
            sleeping.assert_not_awaited()
            self.assertEqual(2, fake.fetches)

    async def test_unsupported_requested_flags_never_start_capture(self):
        fake = AsyncMock()
        fake.close = lambda: None
        with test_workspace("collect-unsupported") as directory:
            with patch.object(collector.Client, "connect", AsyncMock(return_value=(
                    fake, {"clockId": "clock1", "capabilities": {"sender": ["session"]}}))):
                with self.assertRaisesRegex(collector.DiagnosticError, "Unsupported requested flags"):
                    await collector.collect("sender", ("host", 1), "00" * 32,
                                            "secret", ["audio.capture"], 1, None,
                                            Path(directory) / "sender.jsonl")
        fake.request.assert_not_called()

    async def test_paired_failure_cancels_other_collector(self):
        cleaned = asyncio.Event()

        async def fake_collect(**device):
            if device["role"] == "receiver":
                raise collector.DiagnosticError("receiver failed")
            try:
                await asyncio.Event().wait()
            finally:
                cleaned.set()

        with patch.object(collector, "collect", fake_collect):
            with self.assertRaises(collector.DiagnosticError):
                await collector.run([{"role": "sender"}, {"role": "receiver"}])
        self.assertTrue(cleaned.is_set())

    async def test_follow_receiver_rejects_unavailable_or_stale_scope_before_any_start(self):
        sender = AsyncMock()
        sender.close = lambda: None
        receiver = AsyncMock()
        receiver.close = lambda: None
        sender.request.return_value = {"version": 1, "receiver": dict(
            available=True, host="192.0.2.10", port=34567, version=1,
            access="lan", session="77", state="enabled")}
        with test_workspace("follow-scope") as directory:
            device = dict(role="sender", address=("192.0.2.1", 1234), session=None,
                          duration=1, output_path=Path(directory) / "sender.jsonl")
            for scope in ({"role": "receiver", "session": "78", "active": True},
                          {"role": "receiver", "session": "77", "active": False}, None):
                with patch.object(collector.Client, "connect_lan", AsyncMock(side_effect=[
                        (sender, {}), (receiver, {"scope": scope})])), \
                        patch.object(collector, "collect", AsyncMock()) as capture:
                    with self.assertRaises(collector.DiagnosticError):
                        await collector.run([dict(device)], follow_receiver=True)
                    capture.assert_not_called()
            for reason in ("unsupported", "endpoint_unavailable"):
                sender.request.return_value = {"version": 1, "receiver": {
                    "available": False, "reason": reason}}
                with patch.object(collector.Client, "connect_lan",
                                  AsyncMock(return_value=(sender, {}))) as connect, \
                        patch.object(collector, "collect", AsyncMock()) as capture:
                    with self.assertRaisesRegex(collector.DiagnosticError, reason):
                        await collector.run([dict(device)], follow_receiver=True)
                    self.assertEqual(1, connect.await_count)
                    capture.assert_not_called()


class TlsIntegrationTests(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        executable = shutil.which("openssl")
        git_openssl = Path(r"C:\Program Files\Git\usr\bin\openssl.exe")
        if not executable and git_openssl.is_file():
            executable = str(git_openssl)
        if not executable:
            raise unittest.SkipTest("TLS loopback tests require an existing OpenSSL executable")
        directory = TEST_ROOT / "tls"
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)
        cls.addClassCleanup(shutil.rmtree, directory, True)
        cls.key = directory / "ephemeral-key.pem"
        cls.cert = directory / "ephemeral-cert.pem"
        subprocess.run(
            [executable, "req", "-x509", "-newkey", "ec",
             "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes", "-days", "1",
             "-subj", "/CN=diagnostic-test", "-keyout", str(cls.key),
             "-out", str(cls.cert)], check=True, capture_output=True)
        der = ssl.PEM_cert_to_DER_cert(cls.cert.read_text())
        cls.pin = hashlib.sha256(der).hexdigest()

    def tls_context(self):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(self.cert, self.key)
        return context

    async def test_real_tls_pair_collects_and_closes(self):
        operations = []
        failures = []

        async def handler(reader, writer):
            try:
                while line := await reader.readline():
                    request = collector.strict_json(line)
                    op, args = request["op"], request["args"]
                    operations.append(op)
                    result = {}
                    if op == "AUTH":
                        self.assertEqual("ephemeral-pairing-secret", args["secret"])
                        result = {"clockId": "test-process-clock",
                                  "capabilities": {"sender": ["recovery"],
                                                   "receiver": ["recovery"]}}
                    elif op == "CLOCK":
                        now = str(collector.time.monotonic_ns())
                        result = {"clientSendNs": args["clientSendNs"],
                                  "receiveNs": now, "sendNs": now}
                    elif op == "START":
                        result = {"captureId": "c1", "startedNs": "100", "expiresNs": "200"}
                    elif op == "FETCH":
                        result = page([event()], "1") if args["afterSeq"] == "0" else page(cursor="1")
                    elif op == "STATUS":
                        result = {"state": "frozen", "captureId": "c1"}
                    elif op == "END":
                        result = {"captureId": "c1"}
                    else:
                        self.fail("Unexpected operation " + op)
                    writer.write(json.dumps(dict(v=1, id=request["id"], op=op,
                                                 ok=True, result=result)).encode() + b"\n")
                    await writer.drain()
            except Exception as error:
                failures.append(error)
            finally:
                writer.close()

        server = await asyncio.start_server(handler, "127.0.0.1", 0, ssl=self.tls_context())
        try:
            address = ("127.0.0.1", server.sockets[0].getsockname()[1])
            with test_workspace("tls-pair") as directory:
                devices = [dict(role=role, address=address, pin=self.pin,
                                secret="ephemeral-pairing-secret", selected_flags=["recovery"],
                                duration=1, session=None,
                                output_path=Path(directory) / (role + ".jsonl"))
                           for role in ("sender", "receiver")]
                await collector.run(devices)
                for device in devices:
                    records = [json.loads(line) for line in device["output_path"].read_text().splitlines()]
                    self.assertEqual(1, sum(row["type"] == "event" for row in records))
                    self.assertNotIn("ephemeral-pairing-secret", device["output_path"].read_text())
            self.assertEqual([], failures)
            self.assertEqual(2, operations.count("AUTH"))
            self.assertEqual(2, operations.count("END"))
        finally:
            server.close()
            await server.wait_closed()

    async def test_real_tls_mismatch_discloses_no_auth(self):
        observed = []
        finished = asyncio.Event()

        async def handler(reader, writer):
            try:
                observed.append(await reader.readline())
            finally:
                writer.close()
                finished.set()

        server = await asyncio.start_server(handler, "127.0.0.1", 0, ssl=self.tls_context())
        try:
            address = ("127.0.0.1", server.sockets[0].getsockname()[1])
            with self.assertRaisesRegex(collector.DiagnosticError, "fingerprint mismatch"):
                await collector.Client.connect(address, "00" * 32, "must-never-send")
            await asyncio.wait_for(finished.wait(), 2)
            self.assertEqual([b""], observed)
        finally:
            server.close()
            await server.wait_closed()


if __name__ == "__main__":
    unittest.main()
