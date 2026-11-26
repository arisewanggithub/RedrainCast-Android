# Automatic session telemetry

Status: implemented in the current Android source. This document describes
the wire and retention contract, not a deployment or performance measurement.
The filename remains for existing links.

Automatic telemetry is bounded metadata from one cast, independent of the
playback statistics panel and the separately consented
[live LAN diagnostic service](REMOTE_DIAGNOSTICS.md). A compatible receiver
publishes on the already admitted cast control connection. The sender retains
both endpoints' recent evidence in memory and owns the only automatic report
store and report UI. The receiver neither persists automatic reports nor
offers report retrieval after the cast.

## Negotiation and publication

The cast control handshake advertises the capability
`"sessionTelemetry":{"version":1,"intervalMs":1000,"maxBatchBytes":8192}`.
The publication interval is one second. An older or
incompatible peer continues casting normally with receiver telemetry marked
unsupported and sender-local diagnostics available. There is no automatic
legacy incident protocol, retry, listener reconnect or retrieval fallback.

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
| Pending events | 64 events and 32 KiB |

Dropped and coalesced events, serialization omissions, unsent publications
and observed sequence gaps are separately accounted for. Saturation or a
missing detailed event must not erase the cumulative first-pressure or
first-failure anchors or make the minimum essential cause/outcome invalid.
Peers validate original UTF-8 byte lengths and JSON types and reject
duplicate keys, malformed UTF-8 and excessive nesting using strict JSON
validation; they do not coerce malformed values.

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
kind, stage, enum code, required key or skip-reason vocabulary is introduced.

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
2 retry because preroll is not ready, 3 retry because recovery audio is stale,
4 ready, 5 cancelled, 6 failed. Clock modes are 0 unavailable, 1 monotonic,
2 initial audio without a source observation, 3 callback audio, 4 cached audio.
Flags are bits 1 audio expected, 2 audio started, 4 resuming audio, 8 recovering
audio. Output clock source uses the existing `audioClockSources` dictionary.
Unknown values are -1 except signed `delayUs`, whose unknown marker is signed
int64 minimum. Unknown delay is not an enormous real clock difference.
`releasedVideoPtsUs` is the latest decoder render-enabled release known to the
clock, not a frame-rendered callback or physical-display observation.

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
