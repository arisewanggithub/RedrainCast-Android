# Remote diagnostics control contract v2

The live collector connects **only to the sender**, over a locally opted-in,
plaintext and unauthenticated listener on eligible LAN and VPN IPv4 addresses.
Any reachable LAN or VPN peer can control captures and read diagnostic metadata;
the TCP listener does not authenticate or filter peers, including routed or
forwarded connections. Use only trusted networks and do not forward the port.
START arms the
next cast; it cannot attach to an existing cast. The receiver supplies evidence
through a token-bound diagnostic lane on its existing cast listener, not a
separate diagnostic listener or advertised endpoint. Peers must implement the
proxy capability; there is no v1 fallback. A failed bootstrap stops the armed
attempt before media. Ordinary, unarmed casts do not wait for diagnostics.
For historical captures only, see the [archived v1 transport and delegation
contract](REMOTE_DIAGNOSTICS_V1_ARCHIVE.md); it is not a current collection guide.

## Sender endpoint discovery

With the sender listener enabled, TCP binds each eligible private Wi-Fi/Ethernet
IPv4 and, when the active network is a VPN, each eligible VPN IPv4. The optional
responder binds those addresses on UDP 27184. Send one unicast query to a known
sender LAN or VPN IPv4 address,
not a scan. The request is exactly 256 bytes: UTF-8 JSON padded with spaces,
with exactly `{"protocol":"redraincast-diagnostics","version":1,"nonce":"<32 lowercase hex digits>"}`.
The response is at most 256 bytes and contains the same protocol, version and
nonce plus `"diagnostic":{"version":2,"port":<TCP port>,"access":"lan"}`.
The port is an integer in 1–65535. The discovery envelope version 1 is separate
from the diagnostic control version 2. The responder accepts site-local IPv4
sources on LAN bindings and unicast IPv4 sources on VPN bindings (and loopback
in loopback tests), and replies no more than once per 250 ms per binding.
It sends nothing for malformed queries or without a live endpoint;
failure to bind discovery does not close the TCP listener. The hint is
unauthenticated, does not enable consent, sampling or identify a device, and
has no receiver endpoint or receiver-discovery fallback.

The app verifies each address belongs to the reported live interface and fails
closed on duplicate or unverifiable bindings. Changes to the active network,
selected addresses, or interfaces, when observed by the listener's 100 ms
checks or command/discovery gates, revoke consent and close all sockets rather
than silently rebinding. A transient change entirely between checks may not
be observed. Re-enable explicitly after revocation. VPN
reachability depends on the VPN's routing and policy; an endpoint is not proof
that a remote collector can reach it. No listener is bound to the wildcard address.

## Control framing and commands

The collector uses UTF-8 JSON lines, `v:2`, a 16,384-byte line limit and
canonical nonnegative decimal strings for request int64 values. Signed metric
values such as delays and offsets use signed decimal strings. Requests are limited to
depth 8; paired FETCH responses to depth 11. A source-record subtree is limited
to depth 7, and receiver lane frames to depth 8. Normal source records are at
most 2,048 bytes; selected `video.timing` records at most 12,000 bytes.
All encoded responses, including errors and their newline, share the rolling
256-record/128-KiB-per-second delivery budget. Duplicate IDs also consume the
20-command-per-second request budget. Capacity is reserved for an explicit
sanitized error; if no error fits, the listener closes the connection.
Unknown operation names are not reflected in the response `op` field (null).

Each LF-terminated line is one JSON object. The listener rejects malformed
UTF-8/JSON, duplicate keys, non-object roots, excess depth and oversized or
truncated lines. A request has exactly `v`, `id`, `op`, `args`: `v` is integer
2, `id` matches `[A-Za-z0-9_-]{1,64}`, `op` is a string of 1–64 characters,
and `args` is an object containing exactly the fields required by the command.
Numbers required to be integers must be JSON integer tokens in signed 32-bit
range, not strings, Booleans or fractional/exponent forms. Request int64
strings are `0` or a nonzero digit followed by digits, within signed int64
range. HELLO precedes all other commands; duplicate IDs cannot repeat effects
and at most 4,096 IDs are retained per connection. The request budget is 20
commands per second with a burst of 20.

Responses have `v:2`, `id`, `op`, Boolean `ok`, and exactly one of `result`
or `error`; errors contain controlled `code` and `message`. Invalid identifiers
and unknown operations can be echoed as null. Unsupported versions, invalid
framing and HELLO-before-state violations close the connection as applicable;
invalid arguments do not authorize a partial START. HELLO is not authentication.
Responses, including the newline, must fit the 16,384-byte frame limit.

The sender supports `session`, `recovery`, `audio.capture`, `audio.transport`,
`audio.queue`, `video.progress`, and `transport.health`. The receiver supports
`session`, `recovery`, `audio.transport`, `audio.queue`, `audio.output`,
`video.progress`, `video.timing`, `transport.health`, `tail.details`, `audio.timing`,
and `audio.timing.work`.
`video.timing` requires `video.progress`; `tail.details` is optional and is
not selected by the collector by default. `audio.timing` is independently
selected and also excluded by default. `audio.timing.work` requires `audio.timing`
and is separately opt-in, excluded by default. Explicit flag requests are strict.

| Operation | Arguments | Purpose |
|---|---|---|
| `HELLO` | none | Acquire the single collector slot and return role flag capabilities. |
| `START` | `target:{"nextSession":true}`, `senderFlags`, `receiverFlags`, `durationMs`, `samplePeriodMs` | Arm one new cast, with one lease of at most 180 seconds. |
| `STATUS` | optional `pairId` | Return pair state, identity and coverage. |
| `CONNECTIONS` | none | Return bounded local connection information. |
| `CLOCK` | `role`, `clientSendNs`, and for receiver `pairId` | Sample the corresponding clock; receiver samples require the proxy lane. |
| `FETCH` | `pairId`, `afterSenderSeq`, `afterReceiverSeq`, `limit` | Fetch up to 32 fairly interleaved source envelopes and per-role cursors/loss. |
| `END` / `CLEAR` | `pairId` | Freeze / clear the pair. |
| `PING` | none | Check liveness without extending any lease. |

START requires exact integer `durationMs` in 1,000–180,000 and
`samplePeriodMs` in 100–5,000. Each flag array contains 1–16 distinct,
nonempty strings of at most 64 characters, supported for that role.
`target` contains only Boolean `nextSession:true`. FETCH's `limit` is an
integer in 1–32; its cursors are canonical nonnegative decimal int64 strings.
The lease starts at START, including waiting for the cast.

### FETCH pagination and drain

The result has a shared `records` array and separate `sender` and `receiver`
page objects. `receiver` is null until receiver intake exists; it is not an
empty, complete receiver capture. For each present role:

| Field | Type and meaning |
|---|---|
| `oldestSeq` | Decimal string: oldest retained source sequence, or `"0"` if none. |
| `latestSeq` | Decimal string: sender's contiguous drained publication watermark, or receiver intake's committed source-sequence watermark. It is not necessarily the latest retained record; receiver source `publishedSeq` can be greater. |
| `nextAfterSeq` | Decimal string: last record from that role actually included in this response, or that role's requested cursor when none was included. |
| `hasMore` | Boolean: retained records for that role remain after its returned cursor, including records omitted by the shared count or byte limit. |
| `pendingRecords` | JSON integer: sender publication tickets not yet drained in order, or receiver intake records still staged. It is separate from retained backlog and terminal confirmation. |

Advance `afterSenderSeq` and `afterReceiverSeq` independently to their
respective `nextAfterSeq`; do not advance a role without its returned record.
Records are fairly interleaved but ordered by source sequence within each role;
the shared `limit` and response byte bound can yield fewer than requested.
An empty role on a page may still have `hasMore` when the other role used the
page; an entirely empty response claiming `hasMore` is rejected by the
collector. Cursors and sequence numbers are source-local, not media packet
identities.

After freezing, retrieval is drained only when **every present role** reports
both `hasMore:false` and `pendingRecords:0`. An empty page with pending work
is not completion: retain both cursors and retry with pacing, delivery-budget
limits and the original frozen deadline. Exhaustion, stalled producers or an
unavailable receiver cannot be called a complete paired capture. Draining
alone does not establish receiver terminal confirmation or lossless evidence.

The sender page has decimal-string `droppedBeforeRetention` and
`evictedAfterRetention` plus compact `lossWatermarks`. The receiver page has
nullable compact `sourceLoss` and three separate loss objects:
`intakeLoss` counts source tickets rejected at sender proxy staging admission;
`forcedLoss` counts staged tickets discarded at a forced intake cutoff;
`retentionLoss` counts tickets evicted after sender intake retention. Each
object has decimal `count`, nullable decimal `firstSeq`/`lastSeq`, and Boolean
`exactRange`: zero count has null endpoints and false; an inexact nonempty
envelope is not a contiguous loss assertion. Do not combine these with source
admission/retention loss or infer lossless capture from their absence.

The receiver page also carries `state` (`active`, `finishing`, `finished`),
nullable decimal `cutoffOrdinal`, Boolean `endConfirmed`, and nullable
`terminal` containing `reason`, `tailTruncated`, and optional `tailDetails`.
These describe intake cutoff and terminal evidence, not the separate detailed
receiver outcome in STATUS. A missing, unconfirmed or truncated terminal is not
proof of cleanup or successful completion.

Each FETCH record wraps the unchanged source record with `role`, source
`session` (null when unbound), `captureId` and `clockId`. Pair state and
bootstrap are separate from source records. FETCH has only bootstrap and
terminal summaries; complete bootstrap, selected flags and detailed receiver
outcomes are in STATUS. For a failed setup, STATUS may retain `setupFailure`
with bounded `code`, `provenance` (`receiver_control` or `sender_local`) and
`reason` (`receiver_reported`, `timeout`, `eof`, `connect`, or `setup`).
These fields never contain peer messages or addresses. FETCH reports only
`setupFailureAvailable`; the collector saves full STATUS at final freeze,
before any CLEAR, and also when bootstrap appears. Source loss is
`["compact1",publishedSeq,oldestRetainedSeq,latestRetainedSeq,admissionTotal,admissionReasons,retentionReasons]`.
It retains the same values: each admission reason is
`[count,firstMonoNs,lastMonoNs]`, each retention reason is
`[count,firstSeq,lastSeq,exactRange]`. Admission `reasons` is ordered
`routine_capacity`, `critical_capacity`, `closed_capture`, `expired`,
`oversized_record`; retention is ordered `routine`, `critical`, `oversized`.
Preserve source, intake and
retention loss and terminal/truncation information; missing evidence is not
proof that no loss occurred. Frozen pair data is retained for up to 120 seconds
from freeze while consent remains enabled; reconnect, FETCH and PING do not
extend the deadline. The current collector cannot reattach. A separate v2
client may HELLO on a new connection, query STATUS without a pair ID and FETCH
a frozen pair by its returned ID, preserving both independent cursors and
existing output. Active pairs remain owner-restricted; reconnect is not an
active-session attach. Consent revocation clears retained data.

### Audio timing

`audio.timing` selects only the original v1 trace. To include worker boundaries,
explicitly select both `audio.timing` and `audio.timing.work` through the existing
receiver flag offer. Collector HELLO advertises the sender's accepted receiver
flags; an older sender lacking `audio.timing.work` is rejected by explicit
collector selection before START. The sender offers the exact selected set and
requires the receiver's HELLO capabilities to contain it; an older receiver
lacking work support fails setup with `UNSUPPORTED_CONFIG`, before lane/media
startup. Work without base timing is invalid at START and receiver offer
validation. No proxy revision change or new listener is used.
The additional explicit `audio.timing.detail` flag requires both flags above.
Older peers reject its selection during negotiation rather than silently
accepting an incompatible record. It is not in the default selection.
The additional `audio.timing.owner` flag requires detail and is supported only
for an explicitly admitted paired receiver capture. It is opt-in and both
peers must advertise it; an older peer rejects the selection.
The receiver has a separate preallocated 1,024-slot numeric buffer (about
56 KiB of primitive arrays), in addition to the existing recorder staging
budget. Producers make bounded atomic attempts and do not perform diagnostic
I/O. Capture closure fences new producers; pending producers and unknown tail
must not be interpreted as complete coverage.

The `AUDIO_TIMING` source record has `data.schema:"audio_timing_v1"` (types
1–21 only), `"audio_timing_v2"` (types 1–24), or
`"audio_timing_v3"` (selected detail only, types 1–29), or
`"audio_timing_v4"` (selected owner only, types 1–31), with
1–8 event arrays `[eventSeq,monoNs,generation,packetId,type,value1,value2]`.
Int64 values are canonical decimal strings; generation and type are integers.
Event sequences are capture-local, may arrive out of timestamp order, and
can have gaps. `attempted`, `dropped`, `droppedBeforeSequence`, and
`batchDropped` are cumulative counts, not source RECORD sequences. Counter
snapshots are not atomic: loss is read before `attempted` so `dropped` and
`batchDropped` cannot exceed it, including while admitted producers retire.
`droppedBeforeSequence` is independent and can exceed `attempted`. Each
encoded source record is at most 2,048 bytes. These batches share the existing
proxy and collector traffic budgets.

Types are: 1 receipt (`ptsUs`, bytes); 2 admission check (queue packets,
retained input bytes); 3 admitted (same measurements); 4 admission pressure
(same measurements); 5 dequeue (`ptsUs`, bytes); 6/7 RAW decode
begin/end (`ptsUs`, input bytes/output samples); 8 AAC input (`ptsUs`, bytes);
9 AAC output (`ptsUs`, bytes); 10 trim (input frames, remaining frames);
11 preroll replacement (old/new PTS); 12/13 output write request/result and
14/15 silence write request/result (requested samples and buffered
frames/accepted samples); 16 stale or trimmed drop (`ptsUs`, bytes or zero);
17 output progress (submitted, consumed frames); 18 output presentation
(presented frames, buffered microseconds); 19/20 Opus decode begin/end
(`ptsUs`, input bytes/output samples); 21 decoded FIFO drop (discarded,
remaining frames). Types 22/23/24 are worker work begin/end/failed
(`workStage`, cumulative current-thread CPU nanoseconds, or -1 if unavailable).
Work stages are 1 pending preparation (fresh recovery trim, clock preparation,
and associated trim diagnostics), 2 start gate, 3 resampling/source FIFO mapping
(including compensation and callbacks), 4 playback `play()` call,
5 output refill, 6 output timing query, 7 start-decision diagnostics callback,
8 other callbacks/diagnostic reporting, 9 underrun observation/query, 10 source
clock anchoring, 11 resampler compensation, 12 startup submission (pending
cleanup through source anchoring, resampling, playback start and initial
underrun observation). Preparation ends even when no video is available or
all frames are trimmed; that does not imply gate entry. Gate end does not
imply permission to start: retries/cancellation can return normally.
Failed boundaries indicate an escaping exception, not successful completion.
The first-submission callback's existing outer stage 8 contains two adjacent
nested stage-8 intervals, in fixed order: playback-lock acquisition, then
critical section. The first ends immediately after acquisition; the second
ends inside the monitor before release. CPU snapshots belong to the calling
audio epoch, including retired callbacks. Neither interval identifies a lock
owner or distinguishes descheduling from monitor contention by itself. Other
stage-8 callbacks do not have this fixed pair; identify first submission using
the surrounding first source write and intact nesting, not stage 8 alone.

Types 30/31 are playback-monitor owner begin/end. `packetId` is zero;
`value1` is the numeric Java thread ID and `value2` is a positive,
capture-local span ID. Match begin/end by capture, generation, thread ID and
span, and compare their monotonic timestamps with the callback acquisition
interval. Each complete pair records an interval actually inside the existing
`playbackLock` monitor; it is not the full hold time and overlap with a callback
wait does not prove that the callback was blocked rather than descheduled.
Nested/reentrant intervals can overlap and must not be summed. Java thread IDs
are not necessarily OS TIDs used by Perfetto; correlation requires independent
evidence. No thread names or payloads are added.

Coverage is best effort, not a complete owner history: a hold already in
progress when capture starts, an acquisition before an audio generation exists,
capture revocation/closure, a lost boundary, and undrained or saturated buffers
all leave ownership unknown. Never infer an owner from an unmatched endpoint,
a gap, or the absence of an interval, and do not extrapolate beyond a matched
pair. Inspect all loss counters and incomplete tails. Instrumentation may
perturb timing; owner events contend for the same bounded trace capacity and
can cause other evidence to be dropped. With the opt-in disabled, monitor acquisitions
perform a gate check but allocate no ownership events. Existing sampled trace
overhead does not isolate the cost of owner instrumentation.

With both `audio.output` and work selected, recovery begin/end and detach also
emit ordinary `AUDIO_OUTPUT` records at point `recovery`, stages
`config`/`settled`/`reset` respectively. Existing `ptsUs`, `cutoffUs`, and `count`
carry the base PTS, base monotonic ns (not a trim cutoff), and mapping revision.
The existing `audio_clock_observation` metrics retain the cached observation
and its native timestamp/progress. Detach retains the final observation sampled
by the existing retirement path before clearing the source; begin/end use the
latest cached observation, not an additional native query. Absent metrics mean
no coherent observation. Record monoNs is publication time; observation atNs
and the causal mapping transition identify the separately sampled state.
Compare observation age, progress counters and base mapping across epochs to
locate accumulated lag. Missing/stale records are not proof of a clock fault.
Without detail, strict older work-v2 validators accept the same envelope and
stage range.

Detail v3 adds work stage 13 (PCM/Opus decoder or AAC pump) and 14 (actual
AudioTrack write call); stage 6 times the overall output timing query and stage
2 the startup gate. Event 25 is queue PTS span in microseconds and oldest
admission age in nanoseconds at admission check; -1 means unavailable or empty.
Event 26 is the active output buffer size in frames and last sampled underrun
count (-1 if unavailable); event 28 is native AudioTrack maximum capacity in
frames (-1 for another output), accompanying progress/presentation events.
Event 29 contains requested and accepted write frames. Older request and result
events use samples; divide by channel count to obtain frames.
Event 27 samples approximately one in 128 work starts: wall and thread CPU
nanoseconds spent sampling the first boundary and recording it. It excludes
the second sample, this event, aggregation, transport, queue scans and the rest
of tracing; it is not total overhead. Compare runs with and without tracing.
All records remain numeric, bounded and share the existing pressure window,
loss counters and explicit unknown-tail semantics.

Work boundaries are nested and synchronous on the audio worker; closing-caller
teardown timing/reporting does not emit worker CPU boundaries. Pair by
generation, packet ID, stage and nesting in event-sequence order, only when
coverage is intact; loss can remove either endpoint. Wall duration is the
receiver-local monotonic difference; CPU duration is the difference of two
nonnegative CPU snapshots on that same worker. Wall minus CPU includes waits,
scheduling and instrumentation overhead, not proof of any one cause. Nested
durations overlap and must not be added. Begin/end sampling itself adds small
overhead; snapshots are not simultaneous. Start-decision callback boundaries
separate diagnostic work inside the gate from deliberate gate waits.

Batches containing work boundaries use v2 only when `audio.timing.work` was
selected; batches with only original types remain v1. Receiver producers skip
work tracing (including CPU snapshots) when that flag is absent; the bounded
buffer also ignores unselected work before consuming sequence/loss budget.
Updated sender and collector validators accept v2 only for the explicit work
selection and reject unsolicited v2 schemas and unknown event/stage IDs.
An older sender selecting only `audio.timing` from a new receiver gets v1,
never automatic work events. Updated peers can still select legacy v1 timing;
there is no silent downgrade of an explicit work request. Unarmed casts,
non-opt-in captures and original event meanings are unchanged. Packet ID zero is
unknown: AAC output and device writes do not establish source-packet
attribution. Resampling and output work also use zero when the caller has no
retained packet identity; adjacency to a packet is not write attribution.
Existing output progress remains separate; no event proves sound
was audible.

The first failed admission or decoded-input reservation claims one
capture-scoped marker separately from the ordered recorder queue. Negotiated
`AUDIO_PRESSURE` lane metadata carries receiver `monoNs`, session, generation,
and numeric reason: 0 packet limit, 1 bytes, 2 PTS span, 3 decoded-byte
reservation, 4 retained-packet reservation. It has no source sequence.
Transmission is best effort, checked before routine batches and during the
existing retirement grace, including an accepted peer's fatal ERROR without
session telemetry when `audio.timing` was selected. Until sent, selected
captures reserve one existing delivery slot and 512 bytes for the marker; the 256-record/128-KiB rolling
limits are unchanged. The receiver page's `audioTiming.marker` is null
if it was not received. Repeated identical markers are deduplicated.

Sender intake holds at most 768 KiB of timing source envelopes, separately
from its existing staging budget. Before a marker it keeps rolling history;
after one it retains batches intersecting the receiver-local
`[marker-5s, marker+10s]` interval, including late-arriving batches, subject to
that finite cap. Whole batches can extend beyond the interval. `audioTiming`
reports retained bytes, actual oldest/latest event timestamps, requested window,
eviction count and sequence envelope, and `tailUnknown`. Missing markers,
sequence gaps, evictions, incomplete terminal information and disconnects
prevent a guarantee of the requested window. FETCH source cursors remain
ordered and never replay earlier records when a marker arrives.

### Recorder bounds and reserves

Each source recorder uses a 1,024-slot staging ring. Routine submissions are
limited to 896 outstanding slots, leaving 128 for priority work. Nonterminal
priority submissions are further limited to `1024 - reservedFinalSummaryRecords
- 1`; final summaries may use all but the last slot, reserved for the terminal.
`reservedFinalSummaryRecords` is computed from diagnostic kind and point counts,
video stage count, and one additional summary; HELLO limits report its value.
Retention holds at most 4,096 records / 2 MiB: routine records have 3,584
records / 1.5 MiB and critical records 512 records / 512 KiB. Routine retention
cannot evict critical records. These are bounded reserves, not guarantees of
lossless delivery; staging admission and retention evictions are reported.

Receiver proxy intake reserves eight of its existing 64 staging slots and
32 KiB of its existing 256 KiB staging byte budget for lifecycle, session, recovery and
failure/pressure records. The terminal reservation is unchanged. This is
bounded protection, not a guarantee under critical saturation; intake and
later retention losses remain reported separately.
Worker CPU intervals begin at startup step entry when diagnostics are active.
The existing bounded worker checkpoint records phase and in-flight step times.
Wall time minus thread CPU includes blocking, locks, GC and scheduling; it
does not distinguish runnable from blocked time. Resolve that distinction
with OS scheduler tracing if needed.

When an admitted receiver ends without a validated lane ENDED, STATUS retains
a sender-reconstructed receiver outcome with `confirmed:false`, null `reason`
and `endedNs`, unknown retirement and ownership, and a truncated, incomplete
`captureEnd` with null time and reason. This is not a receiver-sourced event.
An unadmitted receiver remains unavailable with no outcome. FETCH retains only
its existing compact terminal confirmation; unconfirmed or truncated coverage
is a collection failure.
If receiver coverage is lost after bootstrap, the sender continues collecting
until its own retirement or the existing lease/terminal deadline; the final
pair reason remains `coverage_lost` when sender retirement completes first.

The collector emits `sequence_completeness` separately from terminal
confirmation. Per role it reports the count and up to 32 ranges of missing
published sequence tickets, including a final known tail; additional ranges
are counted. Previously fetched tickets remain observed even if subsequently
evicted. The receiver's source-loss `publishedSeq` can exceed the last ticket
relayed by the proxy; the collector uses that known watermark to account for
missing trailing tickets, including when a later critical record was delivered.
This does not establish lossless capture: admission
drops have no ticket, and an unconfirmed or truncated receiver tail may have
an unknown extent. Inspect the separate loss and terminal evidence as well.

The optional receiver flag `tail.details` negotiates the extended ENDED
schema; it is opt-in so old peers continue to use the exact legacy frame.
The canonical collector does not select it by default: for a receiver known
to support it, include `tail.details` in `--receiver-flags` alongside the
other desired receiver flags. Requesting it from an older receiver can make
the diagnostic setup fail rather than silently downgrade.
It reserves one record, one delivery slot and 4096 bytes within the existing
delivery window for a failed SESSION record, as well as the unchanged ENDED
reservation. Other failed records, including operation failures, cannot consume
that reservation. When earlier records cannot fit, the bounded scan may skip
them to deliver a later failed SESSION record in source-sequence order.
`tailDetails` reports `cause` (`budget`,
`deadline`, or `source_pending`), `stage` (`delivery` or `source`), the
nullable envelope `unsentFirstSeq`/`unsentLastSeq`, and
`observedUnsentCount`. The envelope can include delivered records and is not
an exact contiguous loss range; pending or deadline-expired records may not
have been counted. A deadline reached before a terminal can be written still
leaves coverage unconfirmed rather than inventing a cause.

An audio queue rejection captures detection time, incoming PTS and the
encoded/retained-decoded byte split while holding the admission monitor. Its
first-fault causal cache is frozen at detection, with each checkpoint's own
identity and timestamp. The independently produced worker, clock and output
checkpoints are not a globally atomic snapshot.

Audio startup decisions emit the first sample of each capture and each
reason/mode/flag, target, mapping-revision or generation transition,
including READY and FAILED. Repeated unchanged samples
are limited to one per 250 ms per player; `omittedRepeats` counts repeats
since the prior emitted sample. Normal recorder admission and retention loss
still apply.
Under audio-led initial startup, `firstVideoUs` and `releasedVideoUs` can be
null even when audio is READY or playing. A missing first-video timestamp is
not itself an audio-start gate; compare decision, source-clock observations
and the audio work intervals rather than inferring a video wait. Existing
captures from older builds may reflect the former first-video gate.

`audio_clock_observation` samples active output at most every 250 ms and
includes one clock observation sequence, source sample and mapped PTS,
submitted/consumed/presented frames, native timestamp/frame and applied
offset. Video scheduling examples carry that same coherent observation inline
and its sequence, including when a later sampled event is not retained. The
snapshot describes the timing read, not independent samples from later
checkpoints; it does not prove scheduler behavior.

For `PREPARATION_TIMEOUT`, newer control terminals optionally carry the typed
`preparationTimeout` phase, elapsed nanoseconds and active producer count;
the sender validates and preserves these in `terminal_coverage`. Older peers
without the optional detail remain accepted.

After successful normal STOP retirement, the receiver opportunistically flushes
ENDED asynchronously within the existing maximum 100 ms retirement window;
control acknowledgement and ownership cleanup do not wait for this flush.
If the lane cannot deliver in that window, it closes and the outcome remains
unconfirmed and truncated.

Fatal retirement now uses the same bounded asynchronous window once media
retirement has been attempted, including a failed retirement. Previously its
cleanup closed the proxy lane immediately after requesting the flush; that race
could discard the terminal and tail. Routine receiver-lane egress reserves one
slot and 16,388 bytes of the existing rolling budget for ENDED; it does not
raise the 128-KiB/s limit. If the tail cannot fit, ENDED may still report
truncation. A blocked writer, expiry, unretired media or lost lane can still
prevent confirmation. The observed Galaxy capture had receiver source sequence
3900 but intake only through 3837 and no validated ENDED; these data do not
prove which of the close race, delivery budget, or another interruption occurred.

The rate-limited `audio_start_decision` includes clock sample and later queue
sample times, observation sequence/time/source PTS, output source, native
timestamp time/frame and applied source offset/correction sequence. Null means
unavailable. The sequence identifies receiver clock observations, not native
timestamp updates: repeated native time/frame values can accompany new clock
observations. Compare successive observation sequences to distinguish reuse;
compare native time/frame separately, and inspect timestamp age/source for
staleness or fallback. Clock fields are captured together under the playback
clock lock; queue and retained counts are collected afterwards and are not an
atomic snapshot with it or necessarily with each other. These are session-local
timing and frame counts, not device identifiers or audio content.
`queueSampledAtNs` is read after the queue snapshot, not an exact timestamp
of each constituent count; preemption can enlarge that uncertainty.

For symptom-led collection, clock correlation and interpretation, see the
[troubleshooting runbook](../TROUBLESHOOTING.md). Missing observations,
coalescing, rate limits and retention loss are not evidence of absence.

Live `SESSION/FAILED` records carry `terminal_failure` metadata: `cause` is
the numeric automatic-telemetry terminal cause vocabulary and `origin` is the
receiver failure-origin ordinal (null for the sender's retained outcome).
Unknown causes map to zero. The receiver record is its first classified failure;
the sender record is its retained outcome at cleanup. Neither includes
peer-provided messages or other arbitrary text. Cause ordinals are UNKNOWN,
VERSION, BUSY, UNSUPPORTED_CONFIG, PROTOCOL, CAPTURE_FAILED, AUDIO_FAILED,
DECODER_FAILED, TIMEOUT, CONGESTION, INTERNAL (0 through 10). Receiver origins
are AUDIO, VIDEO, SESSION, PEER, CLEANUP, PRESENTATION (0 through 5).
On receipt of a negotiated control terminal the sender also emits
`SESSION/SAMPLE` with `terminal_coverage`: final sequence, cleanup outcome
and typed skip reason, each null if absent. Receipt of a sequence alone is
not proof that its matching final batch was received.

Overhead measurement: inserting representative new values into one retained
Galaxy `audio_start_decision` JSON source record increased its UTF-8 size from
633 to 956 bytes (+323 bytes; synthetic values, no framing). Values vary.
The JVM recorder fixture measured 607 to 865 bytes (+258 bytes).
Unchanged audio-start decisions are sampled no more often than once per
250 ms per player; changed decisions bypass that repeat gate, so there is no
four-records-per-second upper bound. Each record remains subject to the
2,048-byte source-record limit; the automatic state adds ten numeric
details to one existing slot and remains within the existing 8,192-byte batch
limit. This is a byte-size measurement, not an on-device CPU, allocation,
latency or playback-impact measurement; those remain unmeasured.

After a validated receiver ENDED or lane closure starts the ordered intake
cutoff, STATUS reports the receiver role as `finishing` until that cutoff is
accounted and the outcome is published. FETCH's intake state may advance
independently while that final publication is pending. The receiver-role state
in STATUS and FETCH is `unavailable`, `active`, `finishing`, or `finished`.

## Current v2 source event schemas

These are source records within the v2 FETCH envelopes above, not v1
transport. See [DiagnosticRecorder.kt](../app/src/main/java/dev/redrain/cast/diagnostics/DiagnosticRecorder.kt),
[DiagnosticMetadata.kt](../app/src/main/java/dev/redrain/cast/diagnostics/DiagnosticMetadata.kt),
[DiagnosticVideo.kt](../app/src/main/java/dev/redrain/cast/diagnostics/DiagnosticVideo.kt)
and [DiagnosticVideoTiming.kt](../app/src/main/java/dev/redrain/cast/diagnostics/DiagnosticVideoTiming.kt).
`Ns` and `Us` denote nanoseconds and microseconds; integer-valued long fields
are decimal strings, including signed delays, differences and offsets.
Small integers, Booleans, enum strings and null are JSON values as noted.

Event objects have `captureId`, decimal-string `seq`/`monoNs`, `kind`, `data`;
when bound, exact `role`/decimal-string `session`; and track-specific integer
`track`/`generation`. A zero generation on an in-flight capture/read operation
means no configuration/header has yet established a generation for it.
Successful operations are aggregated by kind/point/window, so a summary may
cover more than one track or generation; it does not claim per-call source
identity. A slow exemplar and an in-flight snapshot retain their own available
track/generation/PTS. Failures remain individual. Control-lane application
progress omits `track` rather than claiming media. Repeated PTS values and
generation-specific source intervals are not deduplicated.

Controlled `kind` values:

- `CAPTURE_STARTED`, `SESSION_BOUND`, `CAPTURE_ENDED`: lifecycle.
  Sender end reasons include `requested`, `lease_expired`, `session_ended`,
  `setup_failed`, `coverage_lost`, `terminal_deadline`, `client_disconnected`,
  `slow_client` and `internal_error`; local revocation uses `local_disabled`.
  Receiver retirement can use `receiver_retired_stop` or
  `receiver_retired_fatal`. A reason is not proof of receiver cleanup.
  `SESSION_BOUND.data` contains the local
  APK `versionName` and integer `versionCode`, including the full unique debug
  build identifier when applicable. These do not depend on selected groups.
- `RECORDER_SAMPLE`: periodic recorder-health observation at `samplePeriodMs`;
  integer `queueDepth` and `inFlightCount`; decimal-string
  `droppedBeforeRetention`, `evictedAfterRetention`, `inFlightNotTracked`,
  `staleVideoUpdates`, `videoTimingContention`; object `dropReasons`, and `inFlight`
  (up to four active operations with decimal-string `operationId`,
  `startedNs`, `ageNs`, and optional decimal-string `ptsUs`, `endPtsUs`,
  `count`, `bytes`;
  `kind` and lowercase `point` enums; integer `track`/`generation`). A separate
  bounded 256-slot atomic table tracks operation completion independently of
  event admission/drop. Slot collisions are exposed by decimal
  `inFlightNotTracked`; these samples are not an exhaustive native operation
  inventory. `CAPTURE_ENDED.data` also includes the bounded in-flight snapshot
  and total `inFlightCount`, so an unfinished call remains visible if END occurs
  before the next sample. A lost retained END is not evidence that a call remains
  blocked. `dropReasons.total` is a decimal string; each admission reason named
  above is an object containing decimal `count` and nullable decimal
  `firstMonoNs`/`lastMonoNs`. `CAPTURE_ENDED.data` contains string `reason`,
  integer `inFlightCount`, decimal `inFlightNotTracked` and
  `videoTimingContention`, and the same bounded `inFlight` array.
- `SESSION`, `RECOVERY`, `AUDIO_CAPTURE`, `AUDIO_TRANSPORT`, `AUDIO_QUEUE`,
  `AUDIO_OUTPUT`, `VIDEO_PROGRESS`, `TRANSPORT_HEALTH`: selected typed observations below.

Selected observation `data` contains controlled `stage`, `reason`, `point` and
optional decimal-string `operationId`, `ptsUs`, `endPtsUs`, `count`, `bytes`,
`cutoffUs`, `durationNs`. Unavailable numeric observations are omitted, never replaced with
zero. `stage` is one of `started`, `ended`, `config`, `begin`, `complete`,
`failed`, `admitted`, `rejected`, `pressure`, `ready`, `reset`, `window`,
`sample`, `proof`, `hold`, `evicted`, `settled`. `point` is one of `unspecified`, `read`,
`write`, `decode`, `resample`, `compensate`, `refill`, `timing`, `queue`,
`recovery`, `audio_start_wait`, `recovery_retire`, `recovery_construct`.
`reason` is one of `none`, `capture`, `receiver`, `pressure`,
`source_window_lost`, `preroll_missing`, `source_pending`, `proof_capacity`,
`stopped`, `failure`, `stale`, `capacity`, `window_gap`, `replaced`. No free-form strings or
exception/log messages are admitted by the producer API.

Optional `data.metrics` is exactly one enumerated typed schema:

| `schema` | Fields |
|---|---|
| `configuration` | controlled `codec` (`pcm`, `opus`, `aac`, `h264`, `h265`, `av1`, `unknown`); integer `sampleRate`, `channels`; nullable decimal `prerollUs` |
| `queue` | integer `packets`, `maxPackets`, `bytes`, `maxBytes`; nullable decimal `oldestAgeNs`; decimal `spanUs`, `maxSpanUs` |
| `window` | controlled `codec`; decimal `prerollUs`, `windowStartUs`; enclosing event supplies requested `cutoffUs` and queued `ptsUs`/`endPtsUs` |
| `output` | decimal `submittedFrames`, `consumedFrames`, `presentedFrames`; nullable decimal `timestampAgeUs`; controlled `clockSource`; nullable integer `underruns` |
| `audio_start_decision` | Receiver `audio.output`: integer `decision` (1 wait, 2 retry preroll, 3 retry stale recovery, 4 ready, 5 cancelled, 6 failed) and `mode`; decimal `waitedUs`, `revision`, `flags`, `omittedRepeats`; integer `retainedDecodedBytes`, `retainedDecodedPackets`, `encodedQueueBytes`, `encodedQueuePackets`. Nullable decimals: `positionUs`, signed `delayUs`, `firstVideoUs`, `releasedVideoUs`, `observationAgeUs`, `sampledAtNs`, `queueSampledAtNs`, `observationSequence`, `observationAtNs`, `observationPtsUs`, `nativeTimestampAtNs`, `nativeTimestampFrame`, signed `appliedOffsetUs`, `correctionSequence`; nullable lowercase enum `outputSource`. Event supplies target `ptsUs`, operation ID and generation. Observation age is cached-source age, not native timestamp age. Queue counts are a later, non-atomic snapshot; retained decoded counts exclude unused decode reservations. |
| `audio_clock_observation` | Decimal `sequence`, `atNs`, `sampleUs`, `sourcePtsUs`, signed `appliedOffsetUs`; nullable decimals `submittedFrames`, `consumedFrames`, `presentedFrames`, `nativeTimestampNs`, `nativeTimestampFrame`; nullable enum-name `clockSource`. |
| `terminal_failure` | JSON integer `cause` and nullable integer `origin`; vocabularies above. |
| `terminal_coverage` | Nullable decimal `finalSeq`, nullable Boolean `cleanupSucceeded`, nullable enum string `finalSkipReason`; optional `preparationTimeout` object with enum `phase`, decimal `elapsedNs` and integer `activeProducers`. |
| `capture_timestamp` | Boolean `available`; decimal `framePosition`, `monoNs` when available, otherwise both null |
| `video_configuration` | controlled `codec`; integer `width`, `height`, `frameRate` (0 means unspecified); nullable Boolean `renderedCallback`; constant `presentationEvidence:"codec_release_or_render_callback_not_physical_display"` |
| `video_progress` | Controlled `videoStage` and `lastReason`; Boolean `retired`; nullable decimal `bytes`, `keyFrames`, `firstPtsUs`, `lastPtsUs`, `sourceAtNs`, signed `minDelayUs`/`maxDelayUs`. Decimal counters/timings: `count`, `firstAtNs`, `lastAtNs`, `maxPtsGapUs`, `maxObservationGapNs`, `intervalCount`, `longIntervals`, `sourceIntervalCount`, `maxSourceIntervalNs`, `sourceRegressions`, `scheduledCount`, `lateSchedules`, `holdSamples`, `maxHoldNs`, `releaseCallSamples`, `maxReleaseCallNs`, `traceCandidates`, `traceSuppressed`. Integer `traceLimit`; `reasons` has decimal `stale`, `capacity`, `pressure`, `sourcePending`, `stopped`, `replaced`. |
| `video_frame_timing` | receiver-only bounded correlation counters and signed nanosecond ranges, described below |
| `video_anomaly` | controlled `videoStage`, `reason`; decimal `occurrenceAtNs`; nullable decimal `previousAtNs`, `sourceAtNs`, `previousSourceAtNs`, `delayUs`, `heldNs`, `releaseCallNs`; event supplies PTS and generation |
| `video_failure` | controlled lowercase decoder `VideoStage` in `videoStage` and `VideoFault` in `fault`; enclosing `failed` event supplies track/generation, waiting/last input PTS and reason `failure`, without exception messages |
| `operation_summary` | lowercase `point`; decimal `windowStartNs`, `windowEndNs`, `started`, `completed`, `zeroOperationEvents`, `bytes`, `units`, `slow`, `maxDurationNs`; `durationBuckets` with decimal `under1Ms`, `1To5Ms`, `5To20Ms`, `over20Ms`; nullable decimal `slowExampleOperationId`, `slowExampleDurationNs`, `slowExamplePtsUs`, `slowExampleEndPtsUs`, `slowExampleCount`, `slowExampleBytes`; nullable integer `slowExampleTrack`, `slowExampleGeneration` |

Unchanged audio-start decisions are sampled at most once per 250 ms per player;
the first sample and changes in capture, generation, reason, mode, flags,
revision or target pass immediately. `omittedRepeats` counts suppressed repeats.
Samples are ordinary bounded records,
so absence does not prove a decision did not occur. Flags use bits 1 audio
expected, 2 audio started, 4 resuming and 8 recovering; clock modes are 0
unavailable, 1 monotonic, 2 audio without observation, 3 callback audio and 4
observed audio. The decision describes scheduling, not physical presentation.

### Video progress evidence

Receiver `video.progress` also reports cumulative `intervalCount`, `longIntervals`
(observation gaps at least 50 ms), `sourceIntervalCount`, `maxSourceIntervalNs`,
`sourceRegressions`, `scheduledCount`, `lateSchedules`, and nullable signed
`minDelayUs`/`maxDelayUs`, plus `holdSamples`, `maxHoldNs`, `releaseCallSamples`
and `maxReleaseCallNs`. Hold time runs from holding a decoded output to codec
release invocation; release-call time is wall-clock time in that API call, not
time to presentation. Invalid/regressing clock samples are omitted. Source intervals are computed only between successive
render callbacks in one generation; regressions are counted, not converted to
positive intervals. Release delay is the playback clock's signed delay before
clamping to zero, not measured display lateness. A null delay means the clock
was unavailable. Release `sourceAtNs` is the requested codec deadline, whereas
render callback `sourceAtNs` is the codec-provided render timestamp. Observation
times use the recorder monotonic clock and release observation happens after the
codec call returns; callbacks can precede that observation.

`video_frame_timing` requires an active receiver capture selecting both
`video.progress` and `video.timing`, not the statistics panel. It summarizes a capture
and video generation, retaining provisional correlation contributions for the
most recent 128 PTS groups. Every range has decimal `count` and nullable
signed decimal `minNs`/`maxNs`; snapshots are not additive:

* `decodedMinusDeadline` is minus the signed playback-clock delay sampled at
  decoded output availability. Negative means available before that estimate.
  `suppliedMinusDeadline` compares the actual codec release timestamp with the
  **unclamped** deadline estimated from the existing schedule delay and
  schedule-time monotonic sample. Positive values include late-delay clamping.
  These are clock estimates, not measured display times; a null clock or
  overflow omits the corresponding sample.
* For safely correlated callbacks, `callbackMinusDeadline` compares the
  codec-supplied timestamp to the intended deadline. `observedMinusCallback`
  compares local callback observation to the supplied timestamp. Future
  supplied timestamps are excluded from matched correlations and cadence;
  `futureCallbacks` counts them, including unmatched callbacks.
  `worstCallbackErrorExample` is null until a match,
  then contains the matched `ptsUs`, `deadlineNs`, `releaseNs`, `callbackNs`,
  `observedNs`, and nullable `decodedMinusDeadlineNs` of the greatest absolute
  callback error; the decode value uses its earlier clock estimate and is
  omitted if its clock revision differs from the release.
* `ptsCadence`, `deadlineCadence`, and `callbackCadence` are intervals between
  consecutive safely matched callbacks in callback-observation order with the
  same clock mapping and increasing PTS. `callbackCadenceMinusPts` and
  `callbackCadenceMinusDeadline` are signed differences of those intervals.
  Legitimate variable source cadence is not itself a pacing fault.
* `deadlineCadenceMinusPts` independently compares consecutive successful
  unique-PTS scheduling decisions, after sealing by a later decoded PTS, with
  increasing PTS, adjacent release order and the same stable mapping. It does
  not require callbacks. Its minimum/maximum are accompanied by
  `minimumDeadlineCadenceExample`/`maximumDeadlineCadenceExample`, each containing
  the signed difference and both PTS-keyed scheduling snapshots. Duplicate outputs
  or releases, failures and mapping transitions break this continuity.
  `latestSchedulingExample` retains the latest eligible decision even without
  a deadline. The worst callback example also contains its `scheduling` snapshot.
* Scheduling snapshots contain `scheduleAtNs`, unclamped `deadlineNs`,
  `releaseNs`, `mappingRevision`, `mappingStable`, and a nullable `clock`.
  The clock snapshot is taken atomically at the **delay read actually used**,
  not from a later stats-panel query. `readAtNs` is the local monotonic time
  used for that read and the absolute-deadline calculation; `scheduleAtNs` is
  this same time, not the calculated deadline or a later timing read. `positionUs` includes the monotonic guard,
  while `sourcePositionUs` is its unguarded input (including bounded DAC/source-map
  prediction for qualified cached audio, not necessarily the cached observed PTS).
  `delayUs` is the original
  signed delay. Modes are `CACHED_AUDIO`, `CALLBACK_AUDIO` (legacy callback
  observation), `AUDIO_INITIAL` (attached audio without an observation),
  `MONOTONIC` (no attached audio), `UNAVAILABLE` (no anchor), and debug-only
  `VIRTUAL_SYNC` (video scheduling experiment, not physical audio evidence).
* `audioObservationSequence` identifies an accepted cached observation within
  the playback clock; unchanged values across decisions mean the same cached
  observation, not a repeated hardware sample read or an unchanged predicted
  scheduling position. For native audio `audioObservationAtNs` is the exact
  `AudioPosition` presentation-read time, not the later publication time. It and
  signed `audioObservationAgeNs` use receiver-local `System.nanoTime`, with age
  measured at `scheduleAtNs`. `audioObservationAge` and `clockReadToSchedule`
  summarize these signed nanosecond ages and read-to-schedule intervals over
  eligible decisions. Null means unknown, not zero.
* `audioOutputSource` and `audioTimestampAgeAtObservationUs` come from the
  exact `AudioOutputTiming` used to publish that cached observation. The
  source is AudioPosition's qualified timestamp/head fallback classification,
  not a claimed native effective clock source. Hardware timestamp age is at
  the audio read, **not** at video scheduling; it is distinct from cached
  observation age. Legacy callbacks have no output classification. Android
  AudioTrack supplies timestamps in the monotonic domain; no wall clock or
  sender timestamp is subtracted to obtain observation age.
* `observedAppliedOffsetUs` is the source-map slew offset at the cached audio
  observation. Nullable `lastPacketCorrection` retains the latest packet-map
  change since audio attachment, with sequence, before/after offsets, delta,
  and `appliedOffsetAtPacketUs`. A packet correction is not proof that its whole
  delta was applied to the audible clock: buffered anchors and bounded slew
  intervene. `recoveringAudio` is the actual recovery flag. A changed mapping
  between delay read and schedule invalidates the intended deadline, but the
  snapshot retains both revisions and `mappingStable=false`.

`worstCallbackErrorExample`, `latestSchedulingExample`, and the minimum/maximum
pair examples are nullable objects. Pair objects contain signed decimal
`deadlineCadenceMinusPtsNs` and `previous`/`current` scheduling objects.
Scheduling fields `ptsUs`, `deadlineNs`, `releaseNs`, `scheduleAtNs`, and
`mappingRevision` are nullable decimal strings; `mappingStable` is Boolean
and `clock` is nullable. Clock objects contain string `mode`, Boolean
`recoveringAudio`, nullable decimal strings for `readAtNs`, `mappingRevision`,
signed `delayUs`, `positionUs`, `sourcePositionUs`, `audioObservationSequence`,
`audioObservationAtNs`, signed `audioObservationAgeNs`,
`audioTimestampAgeAtObservationUs`, and signed `observedAppliedOffsetUs`;
`audioOutputSource` is a nullable enum name. The nullable `audioObservation`
object has the fields of `audio_clock_observation` without its `schema`.
The nullable `lastPacketCorrection` object contains decimal `sequence` and
signed decimal `beforeOffsetUs`, `afterOffsetUs`, `deltaUs` and
`appliedOffsetAtPacketUs`.

Only 128 PTS groups, constant-size cumulative ranges and four retained
scheduling exemplars (latest, minimum pair, maximum pair, worst callback)
are kept. At most two lightweight occurrence tokens per group and 512 pending
adjacency edges cover callback and release order across retirement. Each token
has at most one incoming and one outgoing edge in its stream; edges have no
chains or references to retired groups. Fully retired edges are accumulated
and discarded. Retired endpoint tokens survive only while referenced by these
bounded edges or the two stream tails, so they cannot form unbounded history. There is
no additional per-frame stream. Capture/generation resets
discard this context with the existing tracker. Audio publication retains
constant-size context without another output query; video snapshots are only
created under both active receiver video gates. These fields can
associate deadline variation with reused observations, source-map changes,
monotonic guarding or read-to-schedule time, but cannot establish causation,
thread wakeup latency, native PLL corrections, compositor latch or scanout.
The existing periodic aggregate remains one record, rather than introducing
a per-frame stream. Only receiver `video_frame_timing` records have a
12,000-byte UTF-8 ceiling when `video.timing` is selected (below the existing 15,000-byte read-page budget);
all other records retain the 2,048-byte ceiling. HELLO advertises the 12,000-byte
maximum and `maxDefaultRecordBytes` of 2,048. START returns the pair context
and selected flags rather than a per-capture maximum. Capture byte/record quotas,
retention reserves and oversized-record loss accounting are unchanged.

`matched`, `unmatched`, `ambiguous`, `evicted`, `missing`, `pending`,
`incomplete`, `releaseFailed`, `clockUnavailable`, `mappingChanged`, and
`outsideWindowCallbacks` are
decimal counters; `capacity` is 128 PTS groups. A callback group is eligible only
after a later decoded PTS seals it, and only with one observed output, one
successful release and one callback. The first observed PTS is deliberately
ambiguous because a pre-capture callback can arrive late. Eligible groups stay
in the bounded window after their first callback. A later duplicate output,
release or callback within that window retracts the corresponding provisional
match, cadence contributions and exemplars in subsequent snapshots. Callback
contributions follow callback-observation order, independently of PTS-order
retirement. Each adjacency is observed in callback/release order and remains
provisional until **both** endpoints retire. A pair crossing the window boundary
therefore survives PTS-order retirement without prematurely freezing a still-live
endpoint's eligibility. Scheduling contributions follow release order and do not depend on
callback uniqueness. Pre-release callbacks and recreated evicted PTS are never
guessed into matches. A callback during a release is held until success or failure.
`missing` counts successful releases without callbacks at eviction/end;
`pending` counts outstanding releases in live snapshots; `incomplete` counts
unsealed or in-flight groups at finalization. Ambiguity and eviction are
conservative group counts, not a reconstruction of every missing callback.
Eviction commits that group's eligible per-frame contributions and retires its duplicate
detection state; `evicted` now counts every such bounded-window retirement,
including successfully correlated groups. `outsideWindowCallbacks` counts
unmatched callbacks at/below the eviction watermark. Such callbacks cannot
retract that retired endpoint's eligibility: lifetime uniqueness beyond the 128-group window is
not asserted. Finalization includes eligible sealed retained groups, while
the last unsealed PTS remains incomplete. Correlation totals and ranges can
decrease or change when retained evidence becomes ambiguous; snapshots must
not be subtracted or summed as monotonic deltas. Earlier exported snapshots
remain historical observations of then-provisional evidence.
Generation replacement closes the prior tracker; capture end freezes it.
Clock source attachment/detachment, recovery and mapping corrections advance
a revision; callbacks crossing a revision are excluded, as are unstable
schedule samples. Regular audio-position observations can refine the clock
estimate without changing that revision. Late callbacks and unmatched PTS
break future cadence continuity. Bounds, recorder staging drops and retention loss
can all reduce evidence. No codec callback proves compositor latch or scanout.
Recorder lifecycle samples and the terminal record expose
`videoTimingContention` for failed bounded tracker-admission attempts.

At most 64 receiver `video_anomaly` examples per capture are attempted for
decoded/released/rendered observation gaps of at least 50 ms, callback timestamp
gaps of at least 50 ms or regressions, release delays at or below -20 ms, or
held output at least 50 ms, release calls at least 20 ms, or skipped pictures.
Examples include the occurrence timestamp,
previous same-stage observation if available, generation, PTS and the applicable
deadline/callback timestamp or signed delay. They are not a complete frame trace.
Each progress summary reports cumulative `traceCandidates`, `traceLimit` and
`traceSuppressed` (candidates beyond the cap); staging drops and retention evictions
remain separately reported by the normal capture loss watermarks. Repeated summaries
are snapshots, not additive. This instrumentation runs only for an explicitly
started receiver capture selecting `video.progress`; it never logs every frame.

Every video observation has `track:1`, session and generation. Fixed stage slots
hold immutable cumulative counters **since this capture first observed that
stage/generation**, not session totals and not per-window deltas:

- Sender `encoded`: validated non-configuration encoder output, before sink
  admission. Its generation is the **capture/encoder generation**, not the
  recovery-remapped wire generation. PTS is the encoder's source PTS.
- Sender `source_dropped`: recovery filtering before mux admission (waiting for
  readiness or a fresh random-access picture); `queued`, `queue_removed` and
  `queue_dropped`: successful mux admission, handoff to writer, and removal of
  unsent queued pictures for recovery/stop. A capacity rejection is `rejected`,
  separate from a recovery-filtered/discarded picture. Handoff is not send.
- Sender `write_started`, `written`, `write_failed`: application socket-send
  entry, successful return, and exception. Success does not prove receipt.
- Receiver `received`: parsed media-channel frame before track validation;
  `admitted`/`rejected`: worker queue admission outcome. Rejections distinguish
  stale/recovery/retired-target filtering from capacity (including admission
  timeout). Neither readiness nor admission proves decoding or presentation.
- Receiver `decoder_input`: successful codec queueInput return; `decoded`:
  nonempty, non-configuration codec output, including pictures later skipped;
  `skipped`: output released without rendering (decoded replacement, stale
  clock output or recovery cutoff); `released`: successful render-enabled
  codec release, with its requested monotonic release time in `sourceAtNs`.
- Receiver `rendered`: the **existing MediaCodec frame-rendered callback**,
  with callback-supplied timestamp in `sourceAtNs`. Counts are callback
  observations, not deduplicated physical display frames. Callback support
  comes from `video_configuration.renderedCallback`. No synthetic callback is
  produced for a decoder without this facility.

`firstAtNs`/`lastAtNs` are producer observation times on the recorder's
`clockId`; event `monoNs` is snapshot time. `maxPtsGapUs` is the greatest
positive consecutive source-PTS step at that stage; `maxObservationGapNs`
is the greatest observed local-time interval between updates. They do not
assume a fixed encoded frame rate: a static source can legitimately produce
no encoded frames. `bytes` and `keyFrames` are null where not observed.
`reasons` counts known filtering reasons; non-filter stages normally have
zero reason counts. Do not equate packet bytes with codec output bytes.

The independent diagnostic lane samples existing slots each `samplePeriodMs`,
even when unchanged. Compare snapshot `monoNs` with `lastAtNs` to see no newly
observed progress; correlate source PTS across stages and recorder clock
exchanges across devices. A missing stage means **no observation available**,
not zero frames or proof of a blocked native call. A frame-rendered callback,
render-enabled release, startup readiness and local UI presentation status
are all distinct. None proves compositor latch, scanout or physical display.

On stage generation replacement a `reset` snapshot preserves the previous
generation's final observed counters, subject to normal loss bounds. Old wire
generation slots are marked `retired` once a newer configuration/stage is
observed; stale updates cannot replace a newer stage and are counted in
`RECORDER_SAMPLE.staleVideoUpdates`. Encoder generation remains independent.
Player retirement emits `reset`/`stopped`; codec faults emit `failed` with
`video_failure`. Select `recovery`/`session` alongside video (the collector
defaults do) for existing readiness, key proofs, cutoffs, resets and termination.
The `video_failure.videoStage` vocabulary is `select`, `create`, `configure`,
`start`, `clock`, `input_keyframe`, `input_dequeue`, `input_buffer`, `input_queue`,
`output_dequeue`, `output_validate`, `output_release`, `close`.

The disabled hook does no clock read, JSON work or snapshot allocation.
Enabled hooks use bounded four-attempt atomic updates and never
acquire a media lock or perform I/O. Receiver release timing adds two monotonic
clock reads only while the video flag is active. Contention exhaustion uses the
existing admission-loss counters. Only capped anomaly examples, periodic snapshots
and generation/configuration/failure records use bounded staging and retention.
Final per-stage samples have
reserved staging capacity before the terminal record. Always inspect loss
watermarks: diagnostic loss and snapshots spanning concurrent stages prevent
proof of exact cross-stage conservation or absence of stalls.

`clockSource` is `timestamp`, `head_unavailable`, `head_warmup`, `head_stale` or
`head_invalid`, reflecting the existing AudioTrack clock selection, not a new
clock/recovery policy. Output frame counts and existing underrun diagnostics
come from the normal output worker's already-observed state. An absent/null
underrun observation is unavailable, not zero. Sender AudioTimestamp sampling
is enabled only for selected `audio.capture`, at the requested sample period.
An ordinary sender AAC configuration reports null preroll rather than reparsing
CSD solely for diagnostics; recovery-window records report the validated
codec-specific preroll. Receiver queue age is null if the existing playback
statistics path did not retain a matching oldest admission timestamp.

Successful READ/WRITE/decode/resample operation begin/complete events are
summarized rather than retained individually. In summaries, `completed` counts
finished calls, `bytes` sums supplied completion payload bytes, and `units` sums
nonnegative completion `count` values; the exact unit depends on the typed hook
(for example capture PCM frames or output scalar samples). Duration histograms
are per call. Slow calls retain one bounded correlatable exemplar per kind,
point and sample window. Failure records remain individual and include their
operation ID and known duration. Transport `bytes` is application payload
length, not TCP wire overhead. Sender queue `endPtsUs` in recovery/window
observations is the codec-derived end of the last retained interval; ordinary
queue observations may have only the last packet's start PTS. Receiver encoded
queue endpoints are packet-start PTS, not decoded/presented coverage.

Queue pressure, cutoff/window rejection (including absent preroll, an interval
gap, or raw source beginning over 250 ms beyond cutoff), eviction and RESET
are recorded while the existing queue monitor already protects the evidence,
before discarding it. No new acquisition of a media/READY/retirement lock is
introduced for diagnostics. Hooks do not change pressure limits or recovery
decisions. An incomplete native WRITE remains visible when END is acknowledged;
END does not wait for that call.
