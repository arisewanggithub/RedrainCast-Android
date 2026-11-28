# Automatic session telemetry

Status: implemented in the current Android source. This document describes
the wire and retention contract, not a deployment or performance measurement.
For symptom-led collection and interpretation, see the
[troubleshooting runbook](../TROUBLESHOOTING.md). The filename remains for existing links.

Automatic telemetry is bounded metadata from one cast, independent of the
playback statistics panel and the separately consented
[live LAN diagnostic service](REMOTE_DIAGNOSTICS.md). A compatible receiver
publishes on the already admitted cast control connection. The sender retains
both endpoints' recent evidence in memory and owns the only automatic report
store and report UI. The receiver neither persists automatic reports nor
offers report retrieval after the cast.

## Negotiation and publication

The cast control handshake advertises the capability
`"sessionTelemetry":{"version":2,"intervalMs":1000,"maxBatchBytes":8192}`.
The publication interval is one second. An older or
incompatible peer continues casting normally with receiver telemetry marked
unsupported and sender-local diagnostics available. There is no automatic
legacy incident protocol, retry, listener reconnect or retrieval fallback.
Version 2 adds a distinct causal row vocabulary. Version 1 peers do not negotiate
version 2: casting continues without receiver telemetry, not with mislabeled
new rows. Stored version 1 batches remain readable; version 1 batches cannot
contain causal rows. The enclosing stored report remains version 2.

One owner per endpoint lifecycle maintains its state and publication sequence.
Each publication is independently interpretable: it includes current state,
bounded events, cumulative first-pressure and first-failure anchors, and
accounting for omitted evidence. Sequence numbers order publications from
that endpoint; their gaps describe missing telemetry publications, **not lost
media**. Timestamps describe local timing only. Sender and receiver clocks
are not synchronized and must not be used as a common causal clock.

Metadata uses a fixed numeric vocabulary rather than arbitrary strings, logs
or media data. It covers the existing diagnostic concepts: counters and
configuration, recovery and readiness, first output and deadlines, terminal
cause and outcome, audio and video progress and queues, and operations.
State and event producers only update bounded caches or attempt bounded event
admission; they do not encode JSON, perform socket or storage I/O, or inspect
live media resources on a producer hot path. Admission does not wait for a
consumer. Repeated observations may be coalesced, with explicit counts.
Queue caches are refreshed on every queue change; publication remains periodic.
Encoded queue rows continue to describe encoded packets only. During a future
audio recovery start, decoded source slices can be retained while that queue
drains; admission-pressure byte totals include both retained decoded input and
encoded packets under the same 512 KiB cap. A future audio-start wait with a
draining encoded queue is therefore not evidence of a blocked decoder. Recovery
checks keep the original wait start/deadline across bounded worker steps.
In-flight operation rows contain the begin time and operation ID until the
matching generation and operation completes. Receiver recovery retirement and
construction share one sequential operation slot per track. The sender's SESSION SETTLED
state is its retained outcome and may differ from the first observed failure.

Each periodic STATUS payload is at most 8192 encoded UTF-8 bytes. The SCPY v3
header is 40 bytes, so a maximum-size STATUS frame is 8,232 bytes before TCP
framing. There is at most one batch per interval and no catch-up burst. The
same payload limit applies to the final batch. Per endpoint:

| Resource | Bound |
| --- | --- |
| Individual event | 512 encoded bytes and at most 20 numeric details |
| Event ingress | 128 events and 64 KiB |
| Current state | 64 role-relevant rows, including up to 16 operations |
| Receiver build identity | one additional state slot, within the 64-row publication limit |
| Pending events | 64 events and 32 KiB |
| Additional causal checkpoint cache | 8 rows plus first gate decision, at most 4.5 KiB |
| Frozen first-fault context | 8 rows plus first gate decision, at most 4.5 KiB |
| Later causal transitions | first/latest mapping, first detach/prepare, first/latest start; at most 6 rows/3 KiB |

Dropped and coalesced events, serialization omissions, unsent publications
and observed sequence gaps are separately accounted for. Saturation or a
missing detailed event must not erase the cumulative first-pressure or
first-failure anchors or make the minimum essential cause/outcome invalid.
Peers validate original UTF-8 byte lengths and JSON types and reject
duplicate keys, malformed UTF-8 and excessive nesting using strict JSON
validation; they do not coerce malformed values.

The receiver publishes its full APK version name as a prioritized current
`SESSION/SAMPLE` row (track/generation 0, value `-20261126`): details are
version code, UTF-8 byte length, then big-endian integers containing up to
seven bytes apiece. Names are limited to 112 bytes. Older readers accept the
numeric row; newer summaries decode it. The existing causal BUILD row and
its unavailable source revision remain unchanged. As with all current state,
missing or omitted publications are not evidence of a particular build.

## Control priority and terminal transaction

Telemetry uses a separate bounded, replaceable control-writer slot: at most one
pending and one in flight, outside the ordinary 64-message/256-KiB control
queue. A newer pending periodic publication replaces the older one. Heartbeat,
READY, recovery, fatal error and terminal control take priority over telemetry.
No telemetry producer waits on media locks or congests the ordinary control
queue. A TCP frame already being written cannot be preempted; byte and write
deadlines bound that work.

For negotiated telemetry, terminal handling quiesces producers and cleans up,
then seals the final batch and sends it before the receiver's STOP,
SESSION_END or fatal ERROR. The terminal references the final publication sequence or
explicitly gives a reason the final was skipped. Final preparation is bounded
by 250 ms and transmission by 750 ms, both **within** the existing teardown
deadline, not additional allowances. If the final has not started, it can be
skipped so terminal control can proceed. If a started frame times out partway
through, close TCP rather than writing a terminal after a partial frame.
Fatal errors retain their own priority and cause without an indefinite
diagnostic wait.

An admitted fatal-error path retains the original error code, message and reply
owner. Playback and recovery retirement precede sealing. Fatal cleanup has a
six-second control-delivery bound; if retirement is still blocked, the writable
control lane sends ERROR with `cleanupSucceeded:false`, `finalSeq:null` and
`finalSkipReason:"UNAVAILABLE"` (no final is sealed before cleanup). Retirement
may still be pending, including an audio pause before track locks are acquired.
The control connection can finish without waiting indefinitely, but the active
session and its role lease remain owned until the fatal retirement worker and
control cleanup both finish. Ownership release is claimed once under the
retirement lock; fatal resource-retirement proof is tracked separately from
cleanup health, so a later control-writer failure suppresses healthy availability
without undoing completed resource retirement. Receiver destruction retains its
existing forced-release policy and is not proof of native retirement. A sender's control write-half close does not close
the receiver's reply lane or seal telemetry while retirement is pending. A
detached recovery player's failed retirement is also treated as a cleanup failure,
even if construction never began. A failed resource retirement keeps the receiver busy
until it is destroyed rather than admitting a successor over an unretired player.
This fence also applies when either peer initiates an ordinary Stop while recovery
is retiring a player: negotiated terminal control reports `cleanupSucceeded:false`,
and control cleanup does not release the session or role lease. A failed installed
player retirement is treated the same way. Legacy Stop retains its existing wire
behavior, while failed resource retirement still fences ownership.
Final preparation uses at most 250 ms and the reserved terminal write uses at
most 750 ms. A blocked or partially written frame fails closed, so delivery of
ERROR itself is not guaranteed. No diagnostic retry or linger is introduced.
The cleanup and deadline paths serialize fatal terminal admission before either
closes the writer. A receiver recovery watchdog expiry follows the same fatal
path for an admitted telemetry session, retaining its original TIMEOUT; legacy
sessions retain their existing immediate-close behavior.

For an accepted, negotiated fatal session, the sender allows 7500 ms after its
terminal ERROR finishes writing: 6000 ms receiver cleanup, 250 ms final
preparation, 750 ms terminal transaction, and 500 ms margin. Terminal queuing
and writing have a separate ten-second bound; an individual frame retains its
original ten-second deadline. Capture cleanup or a queued ERROR cannot consume
the receiver-response allowance before ERROR is written. Retirement and its
watchdog use the same anchored deadline. A recovery-deadline failure keeps a
healthy negotiated control lane available for this transaction instead of
closing it before retirement. Legacy/non-negotiated fatal teardown remains
two seconds; ordinary and crossed Stop keep their existing 22-second delivery
and two-second acknowledgement deadlines.

The optional terminal `finalSkipReason` is one of `PREPARATION_TIMEOUT`,
`PREPARATION_FAILED`, `UNAVAILABLE`, or `WRITE_SKIPPED`. It is absent when a
final sequence is sent; older peers may omit it. It is not arbitrary text.
The sender retains the typed reason in the report without treating a missing
reason from an older peer as a specific failure.

On a preparation timeout the receiver also emits one local
`TELEMETRY_FINAL_PREPARATION_TIMEOUT` diagnostic with the best-effort phase
(`NOT_STARTED`, `QUEUED`, `QUIESCING`, `PUBLISHING`, `VALIDATING`, or `COMPLETE`),
elapsed nanoseconds and active producer count. A concurrent phase transition may
race the observation; a phase is not proof of a blocked particular call. This
local diagnostic is not a new wire field and requires receiver logs to retain it.
Batch size accounting encodes each candidate row once rather than repeatedly
serializing the whole growing batch; the existing size and time limits are unchanged.

The receiver audio-start retention state also carries the decision and queue
sample times, observation sequence/time/PTS, output clock source, native
timestamp time/frame, applied source offset and last correction sequence. It
shares the existing slot and publication bounds. The samples are not atomic
across the clock and queue; the output observation may reuse an unchanged
native timestamp even when its receiver observation sequence advances. The
live paired record exposes the same fields. Neither telemetry path guarantees
that a terminal batch or enough observations will be delivered. See the
[live diagnostic contract](REMOTE_DIAGNOSTICS.md) for correlation and loss.

The sender continues reading telemetry while stopping. Sender-initiated,
receiver-initiated and crossed stops preserve the existing media-drain and
terminal arbitration semantics. Receiver completeness requires receipt of
the final batch, a terminal referencing its matching sequence, and successful
receiver cleanup. Control loss, abrupt EOF, a skipped final, a gap or cleanup
failure remains visible as incomplete evidence; no reconnect is attempted to
repair it.

ERROR accepts the same optional final/cleanup fields as STOP and SESSION_END.
Fatal cast outcome and receiver cleanup outcome are independent: a failed cast
may have a complete final report when cleanup succeeded. Legacy fatal ERROR
without these fields remains a cast failure with incomplete receiver evidence.
These are optional existing numeric-row and terminal extensions under telemetry
v1: strict framing/JSON validation is unchanged, old readers accept arbitrary
numeric current-row details and ignore unknown ERROR object members. No new
kind, stage, enum code, required key or skip-reason vocabulary was introduced
by those terminal extensions. The separate causal vocabulary below requires
telemetry version 2.

## Automatic first-fault causal context (telemetry v2)

Causal rows have kind `DiagnosticKind.size + 3` (11), track 2, and their own
stage dictionary: BUILD, WORKER, SERVICE, OUTPUT, INPUT, MAPPING, START, TRIM,
CAPTURE (0 through 8). Existing diagnostic enums and row meanings are unchanged.
`value` identifies provenance: 0 current checkpoint, 1 frozen first fault,
2 transition published after the latch, 3 capture accounting, 4 first gate decision of the current
checkpoint generation (also frozen with the first bundle). Each row keeps its original local
observation timestamp and generation. An independently timestamped cached copy
is not an atomic or fresh inspection of all media resources.

At first audio pressure, the receiver freezes cached primitive checkpoints
**before** beginning clock recovery or retiring the player. Pressure/failure
publication and externally requested audio recovery also freeze any
as-yet-unfrozen context. Freezing never calls
the player, acquires the clock/queue monitor, waits for a worker, or queries
AudioTrack. A stalled producer therefore need not complete. The original bundle
is repeated in every subsequent publication, including the final batch, ahead
of current state and ordinary events; replacement STATUS and ordinary event
saturation cannot erase it. Before a fault, current causal checkpoints are
published instead. Later transitions have their own fixed retention: first
and latest mapping, first detach and first preparation mapping, and first and
latest start, with omission accounting. Pinning detach/preparation preserves
the first recovery's old/new bases even after subsequent recoveries.
Their timestamps identify the original mutation/decision, which can precede
the latch if publication was delayed after releasing the clock monitor.
Obsolete out-of-order transitions rejected from the checkpoint cache are
also counted as omitted. Mapping generation is captured with the primitive
transition under the clock monitor, not read again during publication.

The first audio admission rejection is also pinned as a current `AUDIO_QUEUE`
`REJECTED` row: its timestamp is detection time, value is incoming PTS, and
details are encoded bytes, retained-decoded bytes, queued packets, attempted
bytes, queue span and limit code. These queue values are captured together under
the admission monitor; separately timestamped causal checkpoints are not a
globally atomic snapshot. Retained-decoded bytes count actual retained PCM,
not the unused portion of an in-flight decode reservation. Attempted bytes
include encoded bytes, retained PCM, any unused reservation, and the incoming
packet; queued bytes in pressure reports likewise include the reservation.
Thus attempted bytes need not equal encoded plus retained-decoded plus incoming.

The export's `telemetryCausalLayouts` dictionary gives the exact ordered numeric
fields; `causalWorkerPhases`, `causalMappingReasons` and `causalOutputBoundaries`
decode their codes. Detail counts for stages 0 through 8 are respectively
3, 17, 18, 20, 15, 20, 13, 7, 5. The layouts are:

* BUILD: versionCode, sourceRevision (-1 unavailable), layoutRevision (1).
  There is no runtime Git lookup or fabricated revision.
* WORKER: phase/phaseBegin, step begin/end, last dequeue time/PTS, first and
  maximum >=5 ms measured interval begin/end/wall/CPU/kind, slow-interval count.
  Interval kinds are STEP, INPUT, DECODE, REFILL (0 through 3).
  CPU is -1 when no existing worker CPU observation is available.
* SERVICE: window begin, step/slow-step counts, maximum refill gap with preceding
  padding/PCM/arrival-age context, minimum padding/PCM, zero writes, silence
  frames/episodes, empty/queued-empty observations, total/window underruns,
  first >=5 ms refill-gap time, counter resets. These are numeric copies of
  existing worker-owned service aggregates, not logcat text.
* OUTPUT: boundary, operation/point, call begin, last completion/duration,
  maximum duration, zero/partial-write counts and first times, cached output
  observation time/frame counters/timestamp age/source/underruns/last progress,
  last completed operation point.
  TIMING boundaries distinguish position-lock entry/acquisition and the existing
  timestamp/head/underrun queries. No additional device queries are made.
* INPUT: latest arrival time/PTS and consecutive deltas, expected duration from
  configured format and codec headers (-1 when unavailable), first PTS discontinuity time/step,
  maximum PTS step/arrival gap, arrivals less than half a packet duration apart,
  arrival count, and last admission/dequeue time/PTS. No packet history is stored.
  Duration observation also covers non-recovery sessions; it does not impose
  recovery packet-duration limits or change legacy packet admission.
* MAPPING: reason, old/new revisions, old/new base PTS/local time, old/new clock
  mode and monotonic position floor, new audio observation PTS/time, flags,
  audio observation sequence, old audio observation PTS/time, last packet
  correction before/after offset and correction sequence (0 unavailable).
  Signed offsets, including -1, are valid only when that sequence is positive.
  Primitive copies are made under the existing
  clock monitor and published only after releasing it.
* START: operation, decision/mode, target/position/delay, revision/flags,
  waited time, last released-video PTS, original decoded PTS, effective trim
  cutoff and trim reason (-1 none; otherwise the TRIM reason vocabulary).
  The raw target/cutoff remain attached to the retained decision even when
  a later generation overwrites the ordinary trim checkpoint.
  Only decision/mode/target/revision/
  flag changes are retained as transitions, not every periodic wait observation.
  Signed delay uses int64-min for unavailable; -1 is a valid delay.
* TRIM: reason (0 startup, 1 recovery, 2 recovery refresh, 3 clock preparation,
  4 actually played audio),
  raw/trimmed PTS, cutoff/observed clock position, raw/remaining frames.
* CAPTURE: layout revision, available-stage mask, omitted later transitions,
  later-transition sequence, trigger track (-1 before freezing). The CAPTURE
  row's generation belongs to that trigger track, not necessarily audio.
  Track failure captures retain the originating track generation, while the
  legacy SESSION FAILED event continues to use generation zero.
  Missing bits and -1 fields explicitly mean unavailable.

All rows still satisfy 512 bytes/20 numeric details. The first bundle, later
transitions and accounting share the existing 64-event/8192-byte publication
budget; current state is omitted with existing accounting when necessary.
No new periodic timer, unbounded history, per-packet retained trace or live
capture consent is involved. Boundary entry proves only an uncompleted boundary,
not a native hang; old progress is not proof of continuous blockage. Wall minus
CPU includes locks, waits and GC, not proof of scheduler starvation. Device/OS
internals and unsynchronized endpoint clocks remain limits on attribution.

## Audio-start causal current row

Receiver current slot 60 (sender slot 60 still means settled outcome) stores
`AUDIO_OUTPUT` / `SAMPLE`, track 2, the installed wire generation. `value` is the
target audio source PTS in microseconds. The 20 numeric details are:

`point, operationId, decision, clockMode, clockPositionUs, delayUs,
firstVideoPtsUs, releasedVideoPtsUs, waitedUs, mappingRevision, flags,
outputObservedAtNs, submittedFrames, consumedFrames, presentedFrames,
timestampAgeUs, outputClockSource, underruns, lastOutputProgressNs, queuedPackets`.

`point` is the existing `AUDIO_START_WAIT` operation-point ordinal;
`operationId` joins the in-flight BEGIN row (zero means unavailable).
Decisions are 0 not waiting/new generation, 1 waiting for target time,
2 retry because the initial audio clock is not prepared, 3 retry because recovery audio is stale,
4 ready, 5 cancelled, 6 failed. Clock modes are 0 unavailable, 1 monotonic,
2 initial audio without a source observation, 3 callback audio, 4 cached audio.
Flags are bits 1 audio expected, 2 audio started, 4 resuming audio, 8 recovering
audio. Output clock source uses the existing `audioClockSources` dictionary.
Unknown values are -1 except signed `delayUs`, whose unknown marker is signed
int64 minimum. Unknown delay is not an enormous real clock difference.
`releasedVideoPtsUs` is the latest decoder render-enabled release known to the
clock, not a frame-rendered callback or physical-display observation.
For audio-led initial startup, either video timestamp may be unavailable when
audio is ready or playing; it is not evidence that audio is waiting for video.
Older captures can reflect the former first-video gate.

The clock snapshots while the wait is blocked, at most once per second unless
decision, clock mode or mapping revision changes, and emits an exit decision.
The observer runs outside the playback-clock monitor. The row is reset when a
new audio player generation is installed. It appears in periodic and final
publications, rather than only when the wait completes. Output timing and
underrun fields are the latest values already queried by that same player;
their local observation timestamp and progress timestamp expose staleness.
A fresh player that has never serviced output reports unknowns, not another
generation's measurements. No extra AudioTrack queries or sampling thread are
added. Queue rows provide the existing count/bytes/PTS context. A pinned clock,
old output progress, or recovery flags are causal evidence, not proof of
acoustic output or network failure.

Receiver current slot 63 also carries `AUDIO_OUTPUT` / `SAMPLE` track 2, with
target source PTS in microseconds in `value` (-1 before the first wait). Match
role, kind, stage and detail count using the export vocabulary:

- Current `telemetryAudioStartRetentionLayout` has 17 details, in order:
  `point, operationId, audioObservationAgeUs, retainedDecodedBytes,
  retainedDecodedPackets, encodedQueueBytes, encodedQueuePackets,
  decisionSampledNs, queueSampledNs, audioObservationSequence,
  audioObservationNs, audioObservationPtsUs, audioOutputSource,
  nativeTimestampNs, nativeTimestampFrame, appliedOffsetUs, correctionSequence`.
- `telemetryAudioStartRetentionLegacyLayout` has seven details, in order:
  `point, operationId, audioObservationAgeUs, retainedDecodedBytes,
  retainedDecodedPackets,
  encodedQueueBytes, encodedQueuePackets`.

`point` is the `AUDIO_START_WAIT` ordinal; `operationId` is 0 when unavailable.
Age is in microseconds and is -1 without a source observation. The byte and
packet fields are counts. Retained decoded counts cover only recovery PCM
arrays charged to the shared input byte cap, excluding unused decode reservations.
The `Ns` times are local monotonic nanoseconds, PTS and signed applied offset
are microseconds, and native timestamp frame is a frame position.
The decision and queue timestamps mark their respective samples; observation
sequence, time and PTS identify the cached audio observation. Native timestamp
time/frame describe its output timing; `correctionSequence` identifies the last
packet correction. `audioOutputSource` indexes `audioClockSources`.
Numeric telemetry uses -1
for unavailable values except `appliedOffsetUs` (signed int64 minimum) and
`correctionSequence` (0). Unlike these telemetry sentinels, the corresponding
nullable fields in the live diagnostic record use JSON null.

Queue values are sampled after the clock decision, not atomically with it; join
by generation and operation ID. The sender's slot 63 remains cleanup-failure
evidence. The snapshot resets on generation installation. Unchanged observations
are limited to one every 250 ms per player; changes in generation, decision,
clock mode, flags, mapping revision, target or capture can publish immediately.

During audio recovery, a detached clock may be anchored to a future scheduled
video release. Earlier audio still waits for its source presentation time; it
must not move that video anchor backward as initial preroll can. Repeated
backward anchor changes while `baseNs` is in the future can pin the clock,
fill the audio queue, and cause successive recoveries to miss their deadline.

## Sender history and reports

The sender retains a rolling 60 seconds of compact immutable payload blocks,
at most 480 KiB per endpoint. Rolling and frozen retained payload together is
limited to 3 MiB. This is a payload limit, not a total-heap guarantee: roughly
3 MiB of retained payload can coexist with temporary serialization buffers
of up to 2 MiB each, object/array and VM overhead, and decoding/export
allocations. Actual heap overhead and peak usage have not been measured.

The first pressure or retained failure creates at most one report per cast.
It freezes bounded prehistory, collects 10 seconds of post-trigger context,
and includes the latest 10-second terminal tail at teardown. Repeated pressure
is counted, not represented as separate episodes. Final receiver pressure or a
cleanup failure may create a report even if no earlier trigger was observed.
A successful cast without pressure or failure writes no report.

The initial, window-closed and final views require at most three revisions;
early teardown collapses unnecessary revisions. A bounded serial storage
worker keeps the newest pending revision rather than building an unbounded
write backlog. `final` describes the report revision, not proof of complete
receiver evidence.

Reports are at most 2 MiB each. The sender retains at most eight reports,
for at most seven days, with at most 16 MiB committed. Expiry is enforced on
later access or save. The sender's Settings → Advanced report UI provides
readable summary, export through Android's document picker, and clear.
There is no receiver automatic-report management UI. A report identifies
paired source sequence/time ranges, drops, omissions, sequence gaps,
truncation, cutoff, outcome and per-endpoint completeness. Older saved
reports may be decoded read-only with bounded input; their presence does not
enable the old runtime protocol.

## Privacy and interpretation

Automatic reports contain controlled metadata, not screen/audio payloads,
arbitrary logs, credentials or secrets, and are not uploaded. The admitted
cast connection is unencrypted and not cryptographically paired; use a trusted
LAN. A report is bounded best-effort evidence, not proof of physical
display/scanout or an exhaustive record. Missing or omitted observations do
not prove that a corresponding media event did or did not occur.

Separately, [Remote Diagnostics](REMOTE_DIAGNOSTICS.md) is a default-off,
consented live service with its own listener, collection and bounded
120-second frozen retrieval. Its consent, transport, limits and retrieval
rules do not grant an automatic telemetry fallback or imply automatic report
availability on the receiver.
