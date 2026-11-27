#!/usr/bin/env python3
"""Tests for the paired v2 collector."""
import copy
import asyncio
import importlib.util
import io
import json
from pathlib import Path
import unittest
import uuid

spec = importlib.util.spec_from_file_location("collector", Path(__file__).with_name("collect-diagnostics.py"))
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class PairedFetchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixture = Path(__file__).resolve().parent.parent / "app" / "build" / "diagnostics-contract" / "receiver-timing-fetch.json"
        if not fixture.is_file():
            raise unittest.SkipTest("Run DiagnosticVideoTimingTest to generate the v2 fixture")
        cls.page = json.loads(fixture.read_text(encoding="utf-8"))["result"]
        cls.cursors = {"sender": collector.MAX_INT64, "receiver": 0}
        cls.flags = {"sender": [], "receiver": ["video.timing"]}

    def test_real_timing_page_preserves_receiver_records(self):
        records, cursors = collector.validate_page(self.page, self.page["pairId"], self.cursors, flags=self.flags)
        self.assertEqual(4, len(records))
        self.assertEqual(4, cursors["receiver"])
        self.assertEqual("receiver", records[0]["role"])
        self.assertEqual(self.page["session"], records[0]["record"]["session"])

    def test_rejects_mismatched_or_partial_source_identity_and_cursor(self):
        for mutation in ("session", "role", "capture", "cursor"):
            page = copy.deepcopy(self.page)
            item = page["records"][0]
            if mutation == "session":
                del item["record"]["session"]
            elif mutation == "role":
                item["role"] = "sender"
            elif mutation == "capture":
                item["captureId"] = "other"
            else:
                page["receiver"]["nextAfterSeq"] = "0"
            with self.subTest(mutation=mutation), self.assertRaises(collector.DiagnosticError):
                collector.validate_page(page, page["pairId"], self.cursors, flags=self.flags)

    def test_unbound_record_stays_unbound_after_pair_binding(self):
        page = copy.deepcopy(self.page)
        item = page["records"][0]
        item["session"] = None
        item["record"].pop("session")
        item["record"].pop("role")
        item["record"]["kind"] = "CAPTURE_STARTED"
        records, _ = collector.validate_page(page, page["pairId"], self.cursors, flags=self.flags)
        self.assertIsNone(records[0]["session"])
        self.assertNotIn("session", records[0]["record"])
        self.assertNotIn("role", records[0]["record"])

    def test_rejects_noncanonical_large_integer(self):
        page = copy.deepcopy(self.page)
        page["records"][0]["record"]["seq"] = 9007199254740993
        with self.assertRaises(collector.DiagnosticError):
            collector.validate_page(page, page["pairId"], self.cursors, flags=self.flags)

    def test_rejects_inconsistent_loss_accounting(self):
        page = copy.deepcopy(self.page)
        page["sender"]["lossWatermarks"][4] = "1"
        with self.assertRaises(collector.DiagnosticError):
            collector.validate_page(page, page["pairId"], self.cursors, flags=self.flags)

    def test_rejects_changed_pair_identity(self):
        identity = dict(armGeneration=self.page["armGeneration"],
                        senderCaptureId="unexpected", senderClockId=self.page["senderClockId"])
        with self.assertRaises(collector.DiagnosticError):
            collector.validate_page(self.page, self.page["pairId"], self.cursors, identity, self.flags)

    def test_fetch_accepts_finishing_and_rejects_unknown_receiver_role(self):
        page = copy.deepcopy(self.page)
        page["receiverRole"] = dict(state="finishing", ownershipCleanup="unknown")
        collector.validate_page(page, page["pairId"], self.cursors, flags=self.flags)
        page["receiverRole"]["state"] = "unknown"
        with self.assertRaisesRegex(collector.DiagnosticError, "Invalid receiver role state"):
            collector.validate_page(page, page["pairId"], self.cursors, flags=self.flags)

    def test_strict_json_rejects_duplicate_and_excess_depth(self):
        with self.assertRaises(collector.DiagnosticError):
            collector.strict_json(b'{"v":2,"v":2}')
        value = {"v": {}}
        node = value["v"]
        for _ in range(11):
            node["v"] = {}
            node = node["v"]
        with self.assertRaises(collector.DiagnosticError):
            collector.strict_json(json.dumps(value).encode())


class CollectorConnectionTests(unittest.IsolatedAsyncioTestCase):
    async def test_status_accepts_finishing_receiver_before_terminal_outcome(self):
        stages = ("socket_accepted", "hello_received", "hello_validated", "hello_reply_queued",
                  "request_received", "owner_admitted", "capture_started", "lane_admitted")
        status = dict(pairId="pair", armGeneration="1", state="covered", session="77",
                      senderCaptureId="sender", senderClockId="sender-clock",
                      receiverCaptureId="receiver", receiverClockId="receiver-clock",
                      bootstrap=dict(session="77", pairId="pair", captureId="receiver",
                                     clockId="receiver-clock", complete=True, loss=False,
                                     stages=[dict(stage=s, monoNs=str(i)) for i, s in enumerate(stages)]),
                      receiverRole=dict(state="finishing", outcome=None, ownershipCleanup="unknown"))

        class Client:
            last_bytes = 100

            async def request(self, operation, args):
                if operation != "STATUS" or args != {"pairId": "pair"}:
                    raise AssertionError("Unexpected status request")
                return status

        class Budget:
            async def reserve(self, deadline):
                return True

            def commit(self, records, size):
                if records != 0 or size != 100:
                    raise AssertionError("Incorrect status delivery accounting")

        output = io.StringIO()
        deadline = asyncio.get_running_loop().time() + 1
        received = await collector.record_status(Client(), output, "pair", {}, deadline, Budget())
        self.assertEqual("finishing", received["receiverRole"]["state"])
        self.assertIsNone(received["receiverRole"]["outcome"])
        self.assertEqual("finishing", json.loads(output.getvalue())["value"]["receiverRole"]["state"])
        for invalid in ("unknown", None, 1, []):
            status["receiverRole"]["state"] = invalid
            with self.subTest(state=invalid), self.assertRaisesRegex(
                    collector.DiagnosticError, "Invalid receiver role state"):
                await collector.record_status(Client(), io.StringIO(), "pair", {}, deadline, Budget())

    async def test_one_v2_connection_arms_and_retrieves_the_pair(self):
        requests = []
        cursor = dict(oldestSeq="0", latestSeq="0", nextAfterSeq="0", hasMore=False, pendingRecords=0)
        sequence_loss = dict(count="0", firstSeq=None, lastSeq=None, exactRange=False)
        drop = dict(count="0", firstMonoNs=None, lastMonoNs=None)
        loss = dict(schema="loss_watermarks", publishedSeq="0", oldestRetainedSeq="0",
                    latestRetainedSeq="0", admission=dict(total="0", **{
                        key: drop for key in ("routine_capacity", "critical_capacity", "closed_capture",
                                              "expired", "oversized_record")}),
                    retention={key: sequence_loss for key in ("routine", "critical", "oversized")})
        sender = dict(**cursor, lossWatermarks=loss, droppedBeforeRetention="0", evictedAfterRetention="0")
        receiver = dict(**cursor, endConfirmed=True, sourceLoss=loss,
                        intakeLoss=sequence_loss, forcedLoss=sequence_loss, retentionLoss=sequence_loss,
                        terminal={"reason": "receiver_retired_stop", "tailTruncated": False})

        async def serve(reader, writer):
            try:
                while line := await reader.readline():
                    request = json.loads(line)
                    requests.append(request)
                    op = request["op"]
                    if op == "HELLO":
                        result = dict(pairedNextCast=1, clockId="sender-clock",
                                      senderFlags=["session"], receiverFlags=["session"])
                    elif op == "CLOCK":
                        result = dict(clientSendNs=request["args"]["clientSendNs"],
                                      receiveNs="1", sendNs="1", clockId="sender-clock")
                    elif op == "START":
                        result = dict(pairId="pair", armGeneration="1", state="armed",
                                      startedNs="1", expiresNs="1000000000",
                                      senderCaptureId="sender", senderClockId="sender-clock")
                    elif op == "FETCH":
                        result = dict(pairId="pair", armGeneration="1", state="frozen", session="77",
                                      senderCaptureId="sender", senderClockId="sender-clock",
                                      receiverCaptureId="receiver", receiverClockId="receiver-clock",
                                      senderFlags=["session"], receiverFlags=["session"],
                                      sender=sender, receiver=receiver, records=[],
                                      bootstrap=dict(complete=True, loss=False), endReason="session_ended")
                    elif op == "STATUS":
                        stages = ("socket_accepted", "hello_received", "hello_validated", "hello_reply_queued",
                                  "request_received", "owner_admitted", "capture_started", "lane_admitted")
                        result = dict(pairId="pair", armGeneration="1", state="frozen", session="77",
                                      senderCaptureId="sender", senderClockId="sender-clock",
                                      receiverCaptureId="receiver", receiverClockId="receiver-clock",
                                      bootstrap=dict(session="77", pairId="pair", captureId="receiver",
                                          clockId="receiver-clock", complete=True, loss=False,
                                          stages=[dict(stage=s, monoNs=str(i)) for i, s in enumerate(stages)]),
                                      receiverRole=dict(state="finished", outcome=dict(
                                          reason="receiver_retired_stop", endedNs="1", mediaRetirement="succeeded",
                                          ownershipCleanup="unknown", tailTruncated=False,
                                          captureEnd=dict(atNs="1", reason="receiver_retired_stop",
                                                          complete=True, truncated=False))))
                    else:
                        self.fail("Unexpected operation " + op)
                    writer.write(json.dumps(dict(v=2, id=request["id"], op=op, ok=True,
                                                 result=result)).encode() + b"\n")
                    await writer.drain()
            finally:
                writer.close()

        server = await asyncio.start_server(serve, "127.0.0.1", 0)
        path = Path(__file__).resolve().parent.parent / "build" / ("paired-v2-" + uuid.uuid4().hex + ".jsonl")
        try:
            await collector.collect_pair(server.sockets[0].getsockname()[:2], None, None, 1, path)
            self.assertEqual(["HELLO", "CLOCK", "START", "FETCH", "STATUS", "STATUS"], [r["op"] for r in requests])
            self.assertTrue(all(r["v"] == 2 for r in requests))
            self.assertEqual({"nextSession": True}, requests[2]["args"]["target"])
            self.assertEqual("ended", json.loads(path.read_text().splitlines()[-1])["type"])
            self.assertIn('"socket_accepted"', path.read_text())
        finally:
            server.close()
            await server.wait_closed()
            path.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
