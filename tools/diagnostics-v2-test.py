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

    def test_audio_start_retention_and_observation_age_survive_collection(self):
        page = copy.deepcopy(self.page)
        record = page["records"][0]["record"]
        record["kind"] = "AUDIO_OUTPUT"
        metrics = dict(schema="audio_start_decision", decision=1, mode=1,
                       positionUs="9007199254740993", delayUs="250000",
                       firstVideoUs=None, releasedVideoUs="9007199254740990",
                       waitedUs="1000", revision="10", flags="15",
                       observationAgeUs=None, retainedDecodedBytes=7680,
                       retainedDecodedPackets=2, encodedQueueBytes=3840,
                       encodedQueuePackets=1)
        record["data"] = dict(stage="sample", reason="none", point="audio_start_wait",
                              ptsUs="9007199254990993", operationId="42", metrics=metrics)
        records, _ = collector.validate_page(page, page["pairId"], self.cursors, flags=self.flags)
        self.assertEqual(metrics, records[0]["record"]["data"]["metrics"])

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


class SequenceCompletenessTests(unittest.TestCase):
    def test_tail_details_is_explicitly_negotiated_without_changing_legacy_default(self):
        supported = ["session", "tail.details"]
        self.assertEqual(["session"], collector.select_flags("receiver", None, supported))
        self.assertEqual(supported, collector.select_flags("receiver", supported, supported))
        with self.assertRaises(collector.DiagnosticError):
            collector.select_flags("receiver", supported, ["session"])

    def test_unconfirmed_receiver_uses_last_source_watermark_without_forgetting_fetched_records(self):
        tracker = collector.SequenceCompleteness()
        tracker.observe([{"role": "receiver", "record": {"seq": "1"}}])
        result = tracker.finish({"sender": None, "receiver": {
            "latestSeq": "1", "sourceLoss": {"publishedSeq": "3"}, "endConfirmed": False}})["receiver"]
        self.assertEqual([["2", "3"]], result["missingRanges"])
        self.assertFalse(result["sequenceComplete"])

    def test_priority_tail_survives_proxy_fetch_and_reports_source_gaps(self):
        fixture = Path(__file__).resolve().parent.parent / "app" / "build" / "diagnostics-contract" / \
            "receiver-priority-tail-fetch.json"
        self.assertTrue(fixture.is_file(), "Run boundedTailProtectsFailureAfterRoutineBudgetExhaustion first")
        page = json.loads(fixture.read_text(encoding="utf-8"))
        records, _ = collector.validate_page(page, page["pairId"], {"sender": 0, "receiver": 0},
            flags={"sender": ["session"], "receiver": ["session", "audio.output", "tail.details"]})
        receiver = [item["record"] for item in records if item["role"] == "receiver"]
        failure = next(record for record in receiver if record["data"].get("stage") == "failed")
        sequences = [int(record["seq"]) for record in receiver]
        self.assertEqual(sorted(sequences), sequences)
        details = page["receiver"]["terminal"]["tailDetails"]
        self.assertEqual("budget", details["cause"])
        self.assertLess(int(details["unsentFirstSeq"]), int(failure["seq"]))
        tracker = collector.SequenceCompleteness()
        tracker.observe(records)
        observed = tracker.finish(page)["receiver"]
        missing = {n for first, last in observed["missingRanges"]
                   for n in range(int(first), int(last) + 1)}
        published = int(page["receiver"]["sourceLoss"][1])
        self.assertGreater(published, int(page["receiver"]["latestSeq"]))
        self.assertEqual(set(range(1, published + 1)) - set(sequences), missing)
        self.assertIn(int(details["unsentFirstSeq"]), missing)
        self.assertIn(published, missing)
        self.assertNotIn(int(failure["seq"]), missing)

    def test_tail_details_are_typed_and_legacy_absence_remains_valid(self):
        complete = dict(cause=None, stage=None, unsentFirstSeq=None, unsentLastSeq=None,
                        observedUnsentCount="0")
        collector.validate_tail_details(complete, False)
        truncated = dict(cause="budget", stage="delivery", unsentFirstSeq="10",
                         unsentLastSeq="12", observedUnsentCount="2")
        collector.validate_tail_details(truncated, True)
        collector.validate_tail_details(dict(truncated, cause="deadline"), True)
        collector.validate_tail_details(dict(truncated, cause="source_pending", stage="source"), True)
        for invalid in (dict(truncated, stage="source"), dict(truncated, unsentFirstSeq="13"),
                        dict(truncated, observedUnsentCount="not-a-number")):
            with self.assertRaises(collector.DiagnosticError):
                collector.validate_tail_details(invalid, True)

    def test_tracks_initial_internal_and_tail_gaps_per_role(self):
        tracker = collector.SequenceCompleteness()
        tracker.observe([{"role": "sender", "record": {"seq": "2"}},
                         {"role": "receiver", "record": {"seq": "1"}},
                         {"role": "sender", "record": {"seq": "4"}}])
        result = tracker.finish({"sender": {"latestSeq": "5"}, "receiver": {"latestSeq": "1"}})
        self.assertEqual([["1", "1"], ["3", "3"], ["5", "5"]], result["sender"]["missingRanges"])
        self.assertEqual("3", result["sender"]["missingCount"])
        self.assertTrue(result["receiver"]["sequenceComplete"])

    def test_retention_eviction_after_fetch_does_not_create_gap(self):
        tracker = collector.SequenceCompleteness()
        tracker.observe([{"role": "receiver", "record": {"seq": str(n)}} for n in range(1, 5)])
        result = tracker.finish({"sender": None, "receiver": {"latestSeq": "4",
                                "retentionLoss": {"count": "4"}}})
        self.assertTrue(result["receiver"]["sequenceComplete"])

    def test_gap_ranges_are_bounded(self):
        tracker = collector.SequenceCompleteness()
        tracker.observe([{"role": "sender", "record": {"seq": str(n)}} for n in range(2, 82, 2)])
        result = tracker.finish({"sender": {"latestSeq": "80"}, "receiver": None})["sender"]
        self.assertEqual(32, len(result["missingRanges"]))
        self.assertEqual(8, result["omittedRanges"])
        self.assertEqual("40", result["missingCount"])


class CollectorConnectionTests(unittest.IsolatedAsyncioTestCase):
    async def test_unconfirmed_receiver_drain_preserves_later_sender_terminal(self):
        fixture = Path(__file__).resolve().parent.parent / "app" / "build" / "diagnostics-contract" / "receiver-timing-fetch.json"
        if not fixture.is_file():
            self.skipTest("Run DiagnosticVideoTimingTest to generate the v2 fixture")
        page = json.loads(fixture.read_text(encoding="utf-8"))["result"]
        page["state"] = "frozen"
        page["endReason"] = "coverage_lost"
        page["sender"]["nextAfterSeq"] = "3"
        page["receiver"]["nextAfterSeq"] = "4"
        page["receiver"]["endConfirmed"] = False
        page["records"] = [dict(role="sender", session=page["session"],
            captureId=page["senderCaptureId"], clockId=page["senderClockId"],
            record=dict(role="sender", session=page["session"], captureId=page["senderCaptureId"],
                        seq="3", monoNs="3", kind="SESSION", data=dict(stage="failed")))]
        output = io.StringIO()
        completeness = collector.SequenceCompleteness()
        completeness.last = {"sender": 2, "receiver": 4}
        result, cursors = await collector.drain_frozen(None, page["pairId"],
            {"sender": 2, "receiver": 4}, None, asyncio.get_running_loop().time() + 1,
            output, first_page=page, flags={"sender": ["session"], "receiver": ["video.timing"]},
            completeness=completeness)
        event = json.loads(output.getvalue().splitlines()[0])
        self.assertEqual("failed", event["value"]["record"]["data"]["stage"])
        self.assertEqual(3, cursors["sender"])
        self.assertFalse(result["receiver"]["endConfirmed"])

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
                        terminal={"reason": "receiver_retired_stop", "tailTruncated": False,
                                  "tailDetails": dict(cause=None, stage=None, unsentFirstSeq=None,
                                                      unsentLastSeq=None, observedUnsentCount="0")})

        async def serve(reader, writer):
            try:
                while line := await reader.readline():
                    request = json.loads(line)
                    requests.append(request)
                    op = request["op"]
                    if op == "HELLO":
                        result = dict(pairedNextCast=1, clockId="sender-clock",
                                      senderFlags=["session"], receiverFlags=["session", "tail.details"])
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
                                      senderFlags=["session"], receiverFlags=["session", "tail.details"],
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
                                                          complete=True, truncated=False),
                                          tailDetails=dict(cause=None, stage=None, unsentFirstSeq=None,
                                                           unsentLastSeq=None, observedUnsentCount="0"))))
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
            await collector.collect_pair(server.sockets[0].getsockname()[:2], None,
                                         ["session", "tail.details"], 1, path)
            self.assertEqual(["HELLO", "CLOCK", "START", "FETCH", "STATUS", "STATUS"], [r["op"] for r in requests])
            self.assertTrue(all(r["v"] == 2 for r in requests))
            self.assertEqual({"nextSession": True}, requests[2]["args"]["target"])
            self.assertEqual(["session", "tail.details"], requests[2]["args"]["receiverFlags"])
            self.assertEqual("ended", json.loads(path.read_text().splitlines()[-1])["type"])
            self.assertIn('"socket_accepted"', path.read_text())
        finally:
            server.close()
            await server.wait_closed()
            path.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
