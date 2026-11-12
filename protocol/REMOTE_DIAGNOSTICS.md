# Remote diagnostics control contract v1

Status: implemented in the Android source and available starting in v0.5.0.
One 180-second paired LAN capture with the latest debug build completed without
disconnect, recovery or a sustained freeze. This single run is not broad
LAN/lifetime acceptance or a physical sender/projector overhead/display
measurement; those remain required before a nonperturbing-performance claim.
This is a separate diagnostic service, not an extension of SCPY media/control framing.
It must not change casting behavior or require both casting peers to support it.

**Intentional revised, unreleased v1 draft:** local opt-in now authorizes
unauthenticated, unencrypted LAN TCP diagnostics without manual pairing. This
replaces the earlier TLS/AUTH draft, not an automatic downgrade or fallback.
Anyone on the LAN can read controlled metadata, occupy the single collector
slot, and start/end/clear diagnostic captures while enabled. There is no proof
of device identity, confidentiality, peer authentication, or protection from
on-path alteration/denial of diagnostics. Use only a trusted LAN and disable
after use. The sender's local consent also delegates receiver access for its
normally admitted cast, without a receiver settings change. Receiver-local
explicit access remains available independently. No media or casting control is exposed.

## Local consent and lifetime

- The local playback-statistics setting exposes an additional **Allow unpaired LAN
  diagnostics** switch beside an explicit risk warning, off by default. Both must be enabled to open the
  local diagnostic listener. Delegated receiver access is described below.
  LAN permission remains required where applicable.
- Enabling access starts only the listener and HELLO machinery. It does
  not enable additional media instrumentation, event history or disk logging.
  Existing ordinary application logging/statistics are unaffected.
- The UI displays the endpoint, unauthenticated LAN access warning and whether
  a collector/capture is active. No certificate, pairing secret or fingerprint exists.
- Local access is process-local, not persisted across app restarts. Turning either
  local setting off stops local collection, closes its sockets and clears its history.
  On a sender it also sends delegated revocation to the admitted receiver; that
  receiver freezes same-cast evidence for up to 120 seconds rather than continuing sampling.
  UI recreation must not accidentally enable access or duplicate the listener.
- A sender may be backgrounded while casting. Backgrounding or hiding the
  overlay alone does not revoke consent; disabling the underlying statistics
  setting does. A receiver must remain diagnosable after casting fails.
- A compatible receiver honors the admitted sender's negotiated diagnostic flag
  for that single cast without requiring local receiver statistics/remote opt-in.
  Accepting a cast with this flag intentionally opens bounded LAN metadata
  access. It never modifies saved receiver settings or bypasses OS permission.
- Capture is a bounded lease: at most 180 seconds per START. There is no automatic
  renewal, automatic capture on connection or silent capture of the next cast.
  The collector must explicitly START again after expiry.

## Transport and access

- Use a separate plain TCP listener. There is intentionally no encryption,
  certificate, credential or authentication. The collector requires explicit
  `--lan` acknowledgment and sends HELLO; no implicit downgrade/fallback is allowed.
- Bind only the intended local-network interfaces, never automatically expose
  through port forwarding, and display the actual bound endpoint/port.
  Optional receiver discovery advertises only the live port as described below;
  QR presentation is not implemented. Discovery does not grant consent.
- Allow one HELLO-completed collector and at most two pending HELLO connections.
  HELLO has a 5-second deadline. Reject further connections. There is no
  authentication-attempt limit because no authentication is performed.
- The listener exposes only the commands below. No shell, file access, system
  log streaming, media payload retrieval, settings changes or playback commands.
- Losing the collector connection immediately ends collection. Previously
  collected records remain frozen for retrieval for up to 120 seconds while
  local consent remains enabled. No collection continues during disconnection.
  Disconnecting a local collector (including after HELLO alone) releases only
  collector ownership, not local consent or the listener. Explicit local disable
  still closes sockets immediately and clears retained capture data.
- A slow client must never stall media. Disconnect it after a diagnostic write
  remains incomplete for 2 seconds. Apply independent diagnostic I/O deadlines,
  never SCPY media/Stop deadlines.

## Framing and validation

Each message is one UTF-8 JSON object terminated by LF. Limit every original
line to 16,384 bytes, excluding LF, before JSON parsing. Reject malformed UTF-8,
duplicate keys, non-object roots, excessive nesting (more than 8 levels), and
noncanonical typed values; do not coerce strings, Booleans or fractional numbers.
Maximum command rate is 20/second, with a burst allowance of 20.

Requests have `v:1`, `id` (ASCII identifier, 1..64 characters from
`[A-Za-z0-9_-]`), `op` and `args` (object). Responses echo `id` and `op`, have
`ok` (Boolean), and contain either `result` or `error`, never both.
Errors have a stable `code` and a controlled `message`, not arbitrary exception
text. Unsupported versions/operations/flags fail explicitly; no silent downgrade
or partial START. Unknown request/argument fields are rejected in v1.
Operation names are 1..64 characters. Invalid/oversized identifiers or operation
names receive `INVALID_ARGUMENT` with the invalid echo field set to null, rather
than reflecting an unbounded attacker-controlled value into a response.
IDs must be unique for the connection; duplicate IDs return `DUPLICATE_ID`
without repeating side effects. Keep at most 4,096 IDs, then require reconnect.

Session IDs, monotonic nanoseconds, source PTS microseconds, capture sequence
numbers and other int64 values use canonical nonnegative decimal strings
(`0` or a nonzero digit followed by digits), bounded to signed int64. IDs must
not pass through floating-point JSON conversion. Small bounded counts and
durations use exact JSON integers.

Every peer must complete HELLO before other commands; otherwise return
`HELLO_REQUIRED` and close without state disclosure. HELLO is not authentication.
Old AUTH always returns `UNKNOWN_OPERATION`; there is no compatibility fallback.
Invalid framing closes the connection; a valid command
with invalid arguments receives an explicit error and makes no state change.
Control responses have priority over telemetry, which may be dropped as described
below. Serialize all writes on one diagnostic I/O lane.

## Commands

| Operation | Arguments | Result / behavior |
|---|---|---|
| `HELLO` | none | Acquires the single collector slot; returns `access:"lan"`, capabilities, roles, connection ID, process clock ID and limits. A competing collector receives `BUSY` and closes. No credential checks occur. |
| `STATUS` | none | Returns listener/capture state, selected target, flags, expiry, retained range, loss watermarks and currently available cast roles/sessions. Does not enable sampling. |
| `CONNECTIONS` | none | Returns current process-memory connection hints, also included as `connections` in HELLO/STATUS. Does not enable sampling or discover/probe peers. |
| `CLOCK` | `clientSendNs` decimal string | Echoes the client value and returns device `receiveNs`/`sendNs`, sampled around handling. Available without an active capture, after HELLO. |
| `START` | `target`, `flags`, `durationMs`, `samplePeriodMs` | Starts a new capture atomically after full validation; returns `captureId`, effective limits, `startedNs` and `expiresNs`. Reject if a capture is active or frozen data remains. |
| `END` | `captureId` | Stops instrumentation and periodic sampling, freezes retained data and emits `CAPTURE_ENDED`. Repeating END for the same retained capture succeeds with its original end state. Does not disconnect. |
| `FETCH` | `captureId`, `afterSeq`, `limit` | Returns up to 32 retained records, with a smaller page if required by the line limit, plus cursor and explicit admission/retention loss watermarks. Valid during capture and while frozen. Never enables collection. |
| `CLEAR` | `captureId` | Clears a frozen capture and its buffers. Reject while collecting. Does not start another capture. |
| `PING` | none | Liveness response only; does not extend the capture lease or data-retention lifetime. |

`durationMs` is 1,000..180,000; `samplePeriodMs` is 100..5,000. No implicit
defaults for START arguments. `target` is exactly one of:

```json
{"role":"sender","session":"797516823435589197"}
{"role":"receiver","session":"797516823435589197"}
{"role":"sender","nextSession":true}
```

`nextSession` binds once to the first newly started local cast of that role after
START; the lease starts immediately, including time waiting for that cast.
It never attaches to an existing session or a later replacement session.
An explicit session must currently exist in that role or START fails
`SESSION_NOT_FOUND`. When the bound session ends, freeze capture automatically.
Do not silently rebind across reconnects or size-switch sessions.

Capabilities enumerate supported flags separately for each role. `flags` is a
nonempty array of unique names, not an ambiguous numeric bitmask:

| Flag | Requested observations |
|---|---|
| `session` | Binding, track configuration, terminal outcome and controlled failure category. |
| `recovery` | Trigger, pressure limits, generation/state transitions, cutoff, available source window, READY, proofs and explicit window-rejection reason. |
| `audio.capture` | Sender read start/end, sample counts, AudioTimestamp observations and source PTS; no PCM or encoded bytes. |
| `audio.transport` | Sender write begin/end/failure; receiver read begin/end and admission outcome; bytes/counts and generation/PTS only. |
| `audio.queue` | Queue occupancy, oldest age, PTS range, eviction/hold/release and writer-in-flight age. |
| `audio.output` | Receiver decode/resample/refill/AudioTrack call begin/end, frame counts, playback progress and underrun observations where available. |
| `video.progress` | Low-rate received/decoded/presented/dropped counters and recovery progress, not per-frame video traces. |
| `transport.health` | Per-lane application progress and supported TCP statistics, with metric provenance and availability. |

No automatic enabling of omitted groups. A minimal capture lifecycle, sequence
and drop accounting always accompanies selected flags. START acceptance must
guarantee the returned groups are supported; unavailable individual OS metrics
are reported explicitly, never as zero. Adding more groups requires END, CLEAR
and another START in v1, not a mid-capture unbounded expansion.

Example:

```json
{"v":1,"id":"capture1","op":"START","args":{"target":{"role":"sender","nextSession":true},"flags":["session","recovery","audio.capture","audio.transport","audio.queue"],"durationMs":180000,"samplePeriodMs":1000}}
{"v":1,"id":"capture1","op":"START","ok":true,"result":{"captureId":"c1","startedNs":"613130000000000","expiresNs":"613310000000000","maxRecords":4096,"maxBytes":2097152,"maxRecordBytes":2048}}
{"v":1,"id":"end1","op":"END","args":{"captureId":"c1"}}
```

Stable error codes include `HELLO_REQUIRED`, `BUSY`, `UNSUPPORTED_VERSION`,
`UNKNOWN_OPERATION`, `INVALID_ARGUMENT`, `UNSUPPORTED_FLAG`, `DUPLICATE_ID`,
`RATE_LIMITED`, `SESSION_NOT_FOUND`, `CAPTURE_ACTIVE`, `CAPTURE_NOT_FOUND`,
`RETAINED_CAPTURE_EXISTS`, `PERMISSION_REQUIRED`, `SCOPE_MISMATCH`,
`SESSION_ENDED` and `INTERNAL`.
Unexpected internal errors also use the existing local error-reporting path;
never report success or silently skip requested instrumentation.

## Telemetry and on-demand instrumentation

Telemetry uses `{"v":1,"event":{...}}`, separate from command responses.
Each retained event has `captureId`, `seq`, `monoNs`, `kind`, and typed `data`.
Bound events also include `role` and exact cast `session`; track-specific events
include integer `track` and `generation`. The schema must be enumerated alongside
the implementation, not accept arbitrary string maps or free-form log messages.

- Lifecycle events: `CAPTURE_STARTED`, `SESSION_BOUND`, `CAPTURE_ENDED`.
  END reasons: `requested`, `lease_expired`, `session_ended`,
  `client_disconnected`, `slow_client`, `local_disabled`, `internal_error`.
  Closing/revocation paths record the reason locally when remote delivery is
  impossible. END acknowledges only after instrumentation is disabled; it must
  not wait for media/native calls or diagnostic network writes.
- Fast operation starts/completions are aggregated per selected kind/point and
  sample window. The typed `operation_summary` metrics include started,
  completed, zero-operation call, payload-byte and supplied-unit counts; four duration histogram
  buckets (`under1Ms`, `1To5Ms`, `5To20Ms`, `over20Ms`); slow-call count and
  maximum duration. A slow summary contains at most one correlated exemplar
  (`slowExampleOperationId`, `slowExampleDurationNs`, `slowExampleTrack`,
  `slowExampleGeneration`, and optional source PTS/count/bytes) per
  kind/point/window. No event or allocation is retained per successful fast
  operation. Failures remain individual records.
  `RECORDER_SAMPLE` and `CAPTURE_ENDED` report a bounded set of currently
  in-flight operation IDs, ages, points, track/generation and any supplied
  source PTS/count/bytes; a missing completion is not proof of a crash.
- Recovery records include cutoff and first/last queued source intervals,
  codec/preroll, available coverage, evictions, READY delay and one controlled
  rejection reason. Record this before discarding the evidence on reset.
- For cross-device media correlation use the existing cast session, track,
  generation and source PTS/interval, plus configuration and payload length.
  Diagnostic sequence/operation IDs are local, not wire packet identities.
  Do not assume PTS is unique or infer packet loss from sequence numbers on
  different devices. No media protocol header change is required for v1.
- Keep at most 4,096 records AND 2 MiB of serialized retained data per device;
  individual records are at most 2,048 bytes. Retention reserves 512 records /
  512 KiB for lifecycle, session, recovery and failure evidence; routine records
  use the remaining 3,584 records / 1.5 MiB. Use fixed, bounded typed counters
  and serialize off media threads. Event hooks do not do socket I/O, disk I/O,
  JSON formatting or wait for a diagnostic lock/queue.
- Offer records nonblockingly through a fixed 1,024-slot MPSC ring. Routine
  records use at most 896 in-flight slots; critical records have 128 reserved
  slots, and the final slot is reserved for terminal lifecycle state. Assign a
  sequence only when a record obtains its publication ticket; reject full-queue
  events without inventing sequence numbers. A terminal admission atomically
  closes the queue, so no late producer can publish after `CAPTURE_ENDED`.
  The control lane drains in ticket order and never lets a later record pass an
  earlier producer that has reserved but not yet published its slot.
- Routine retention cannot evict critical retention. Counters distinguish
  routine/critical queue saturation, expired observations, closed-capture
  races, oversized records and routine/critical retention eviction. Admission
  counters include first/last monotonic timestamps. Retention reports first and
  last lost sequence, count, and whether that envelope is an exact contiguous
  range; an inexact envelope is not a claim that intermediate records were
  dropped. `lossWatermarks` reports published, oldest-retained and
  latest-retained sequence watermarks in STATUS and every FETCH response.
- Send at most 256 records/second AND 128 KiB/second, including framing.
  Enforce both budgets; summarize or drop excess with explicit accounting.
  A slow client cannot cause unbounded outgoing queues. FETCH and live delivery
  share these budgets and cursors tolerate repeated records.
- FETCH returns `oldestSeq`, `latestSeq`, `nextAfterSeq`, `hasMore`,
  integer `pendingRecords` (0..1024),
  cumulative `droppedBeforeRetention` / `evictedAfterRetention` counts and
  `lossWatermarks`. Sequence/counter values are decimal strings except integer
  `pendingRecords`, Boolean `hasMore` and range `exactRange`. On an empty page `nextAfterSeq` equals the
  requested cursor. `hasMore` describes retained records after this page and
  must never be true on an empty page. `pendingRecords` counts reserved staging
  tickets not yet drained in order, including a paused producer and any terminal
  records behind it. `latestSeq` / `publishedSeq` are the contiguous drained
  watermark, not the reservation tail. All these fields share one response
  snapshot. A frozen drain completes only when both `hasMore` is false and
  `pendingRecords` is zero; an empty pending page is not completion. Retry such
  pages with pacing under the original frozen deadline, never spin or advance
  the cursor without records. Sequence gaps identify unretained records; never fabricate
  continuity. STATUS exposes the same accounting if a notification is lost.
- Allocate capture buffers and start sampling only after START validation.
  Hot paths check an inactive/flag gate before reading diagnostic clocks,
  allocating records or querying platform state. END disables gates and cancels
  sampling; discard late observations from that capture using a capture epoch.
  Publishing the epoch must not require pausing a media worker.
- The 120-second frozen retention starts at capture end and is not extended by
  FETCH/reconnect. On expiry clear data without re-enabling instrumentation.
  Idle listeners allocate no per-media-event history. No pre-START replay exists.

Record only controlled categories, numeric timings/counters, codec enums and
protocol identities. Exclude pixels, audio samples, packet contents, content
hashes, CSD, application titles, URLs, peer addresses, filesystem paths, hardware
serials, account names, pairing secrets and arbitrary exception strings.
Diagnostics are sensitive metadata even without media; do not automatically
upload them or register raw captures as shareable artifacts.

## Paired collector and clock interpretation

The PC connects and completes HELLO on each locally consenting device independently and STARTs captures
before reproduction, using `nextSession:true` where necessary. Different device
capabilities may require different flag sets. One failed connection must not
silently disable the other or claim a paired capture.

Sample CLOCK several times and retain client send/receive plus both device
timestamps. Use round-trip bounds and lowest-delay samples to estimate offsets;
report uncertainty, clock ID and drift. CLOCK samples from different process
clock IDs must not be merged. Source PTS is a media clock, not a substitute for
cross-device clock synchronization.

First investigation profile: session/recovery and all available audio groups,
plus low-rate video progress and transport health. Diagnose in this order:

1. Compare sender capture progress with queue admission and write completion.
2. Compare writes to receiver read/admission and worker/output call progress.
3. Inspect receiver backlog/blocked calls and explicit recovery-window reasons.
4. Distinguish application stalls from TCP evidence where supported.

A successful sender write is not proof of receiver arrival. A delayed receiver
read is not proof of Wi-Fi latency. Android may not expose TCP retransmission/
send-queue metrics through supported APIs; report unavailable evidence and
remaining ambiguity rather than invent a network diagnosis.

## Implementation acceptance requirements

- This document alone does not enable a listener. Implement consent UI/warning,
  HELLO, strict parser, bounded recorder, role-specific hooks and PC collector
  together before advertising support.
- Test HELLO-before-state, competing/idle clients, malformed/oversized frames,
  duplicate keys/IDs, unsupported flags, rate limits and connection limits.
- Test inactive hooks produce no diagnostic records or periodic sampling; test
  selected groups only, explicit START, expiry, END, disconnect, consent revoke,
  process restart, background sender and frozen retention expiry.
- Test bounded memory/output, slow/nonreading clients, full/contended buffers,
  late callbacks, dropped-record visibility and ordered control responses.
- Test exact int64 round trips, clock uncertainty, independent device clocks,
  session binding, repeated PTS and cross-generation correlation.
- Reproduce blocked audio write/output and repeated recovery-window rejection
  in deterministic tests. Verify diagnostics cannot delay media admission,
  native output servicing, recovery deadlines or orderly Stop.
- Measure disabled and enabled overhead on a real sender/projector pair before
  claiming the instrumentation is nonperturbing. Capture success is not a fix
  for the underlying audio failure.

## Android implementation profile and schemas

### Sender-delegated receiver access

This unreleased draft intentionally supports sender-controlled receiver
diagnostics: the sender warning explicitly states that enabling local
statistics plus remote diagnostics also opens compatible receiver diagnostics
for an admitted cast. Projector settings need not be accessed. Local
receiver statistics/diagnostic preferences are not prerequisites and are never
mutated by delegation. Any reachable LAN client can use the bounded endpoint;
neither cast ownership nor the metadata service provides cryptographic identity.

Negotiation is additive to existing SCPY version 3 framing:

1. Sender HELLO offers `diagnostics:{"version":1,"sessionDelegation":true}`.
   Receiver echoes this capability only when it implements delegation.
   Missing fields mean legacy/unsupported; future well-typed positive capability
   versions are unsupported. Integer version and Boolean delegation are exact
   types, duplicate keys remain forbidden by the normal wire parser.
2. With both sender local consent and negotiated support, SESSION_REQUEST
   includes `diagnostic:{"version":1,"enabled":true}`. This object has exactly
   those two fields, exact integer version 1 and Boolean enabled; malformed
   requests reject before diagnostic activation. Unsupported peers are reported
   to the sender as `unsupported`; no speculative request/false success occurs.
3. Only after normal active-owner CAS, cast-ownership lease acquisition, request
   validation, and SESSION_ACCEPT may the injected receiver process owner open
   the listener. Discovery, diagnostic HELLO, pre-admission STATUS, rejected/busy
   peers and unowned media connections cannot delegate or replace access.
   SESSION_ACCEPT adds `diagnostic:{"version":1,"state":"starting","session":"77"}` for a
   supported request (`unsupported` if not negotiated). A later session-bound
   SCPY STATUS with state `"diagnostics"`, controlled message, and
   `diagnostic:{"version":1,"state":"enabled","session":"77","port":43210,"access":"lan"}`
   confirms the actual bound listener. `local` and `frozen` may likewise carry
   the live endpoint only when the cast control socket's local address matches
   the diagnostic bind address. Otherwise the result is `unavailable` without a
   port; casting over other interfaces or IPv6 remains supported.
   Starting/failed/unsupported results never carry a port.
   The owner is a canonical int64 string, matching the SCPY frame's session.
   Other result states: `unavailable`, `frozen`, `local`, `disabled`, `unsupported`.
   Binding/permission failures report `unavailable`, not a casting error.
4. The same admitted owner may revoke using session-bound SCPY STATUS
   `{"state":"diagnostics","message":"disabled","diagnostic":{"version":1,"enabled":false}}`.
   The sender queues this on local flag-off. Late enable is forbidden; re-enabling
   the switch does not activate/rebind an already admitted cast. Ownership and
   exact session are rechecked before accepting revocation.

Delegated diagnostic HELLO and STATUS include
`scope:{"role":"receiver","session":"<canonical int64>","active":true}`.
HELLO advertises empty sender capabilities. STATUS filters available sessions
to the delegated receiver session, and no unrelated retained capture/history
survives a scope transition. START requires role receiver plus that **explicit
matching session**. `nextSession`, sender targets or other sessions return
`SCOPE_MISMATCH`. Once the owner/flag ends, START returns `SESSION_ENDED` even
after CLEAR; no rearming/rebinding is allowed. PC START is still mandatory:
admission/listener startup never enables recording.

Owner-liveness checks in the typed recorder gates are atomic/volatile reads,
not media-thread locks/socket/JSON work. Loss of owner/flag prevents new
observations immediately. The diagnostic lane freezes retained metadata and
leaves the same-scope listener available for **at most 120 seconds** from
revocation/teardown, not 120 seconds from each fetch/ping/reconnect. It then
closes sockets and clears history. Permission loss has no grace. A new accepted
cast revokes the old delegated scope, clients and retained data even if the new
cast does not request diagnostics. Old async startups/callbacks cannot grant a
replacement session. The regular 180-second capture lease and all byte/record/
rate/deadline limits remain unchanged.

Explicit local receiver mode takes precedence, has its original process-local
statistics gate and unrestricted local profile, and is not closed by delegated
cast teardown. Enabling local mode revokes a delegated listener/history first;
turning local mode off does not silently rearm an earlier delegation. No receiver
preference is changed. Each new cast needs a fresh negotiated request.

Receiver UDP advertisements publish the existing informational
`diagnostic:{"version":1,"port":43210,"access":"lan"}` while the delegated
listener is live, including frozen grace. The admitted-owner SCPY result also
supplies its actual diagnostic port; UDP is not needed to attach from the sender.
The sender's diagnostics status displays the receiver session,
and diagnostic HELLO `scope` supplies the exact target identity. Receiver
endpoint reachability remains unauthenticated and is not a casting capability.

### Sender in-memory connection information

The sender retains the admitted receiver result only in process memory, keyed
by its owning cast session. The host is the connected SCPY control socket's
numeric peer address, **never** an advertised host. Version/state/session/port/
access are strictly typed and validated; malformed or legacy state-only results
never become endpoints. Late results from another owner cannot replace current
information. New cast startup clears the old hint even before negotiation.

After diagnostic HELLO, `CONNECTIONS` (no arguments) returns, for example:

```json
{"version":1,"self":{"host":"192.0.2.1","port":41001,"version":1,"access":"lan"},"receiver":{"available":true,"host":"192.0.2.2","port":43210,"version":1,"access":"lan","session":"77","state":"enabled","reason":"available"}}
```

`self` uses that diagnostic socket's actual local address and bound port, or null
if no longer available. Receiver `available:false` omits host/port/version/access
and supplies `state`, nullable `session` and a controlled `reason`: `no_session`,
`not_requested`, `unsupported`, `awaiting_endpoint`, `endpoint_unavailable`,
`malformed`, `cast_ended`, `grace_expired`, or `revoked`. The same object is included
in HELLO and STATUS. It contains no token, media token, certificate, or secret.
These are unauthenticated endpoint hints, not proof of identity or reachability.

Cast teardown freezes an enabled delegated hint for at most 120 seconds without
renewal; expired hints omit the endpoint. Revocation clears sender memory
synchronously (and closes local access); the receiver independently applies its
existing frozen grace. New ownership replaces all old hints. Local receiver mode
is not assumed to survive cast teardown. A stale/new receiver scope is checked
again at receiver HELLO/START; a hint never authorizes another cast or enables
collection. `--follow-receiver` refuses frozen hints for new captures.

The first sender endpoint can be obtained with the bounded unicast query below,
without settings access or retained startup logs. Successful Android listener startup also logs the controlled local event
`event=DIAGNOSTIC_LISTENER_STARTED endpoint=<bound-ip>:<bound-port> version=1 access=lan role=local`
(delegated receiver listeners use `role=receiver`). Existing authorized ADB/log
access can read it without opening settings during a cast, **if still retained**.
Logcat is not durable endpoint discovery. There is no port scanning, persistent
endpoint/consent file or permission bypass.

### Active sender endpoint bootstrap

The diagnostic listener also owns a unicast-only UDP **27184** responder bound
to its **same private IPv4 address**. It is independent of Activity/settings
lifetime and the sender's receiver-advertisement reader on UDP 27183. Sharing
that Activity-owned socket would lose discovery while backgrounded/destroyed
and could divert existing receiver advertisements. No wildcard/broadcast bind,
multicast announcement, extra permission, ADB/debug interface or media/control
executor is added. Both local and session-delegated listeners use this path in
debug and optimized release builds.

An explicit collector query to one known private IPv4 host is exactly **256
bytes**: this JSON object, padded with ASCII spaces:

```json
{"protocol":"redraincast-diagnostics","version":1,"nonce":"0123456789abcdef0123456789abcdef"}
```

Only these three keys, an exact integer version 1 and 32 lowercase hexadecimal
nonce characters are accepted. Duplicate keys, malformed UTF-8/JSON, excessive
nesting, oversized/undersized datagrams, unknown fields and non-private IPv4
sources are ignored. Production never binds loopback; loopback is injectable
only for deterministic local tests.

A live, permitted listener replies to the request's source address/port:

```json
{"protocol":"redraincast-diagnostics","version":1,"nonce":"0123456789abcdef0123456789abcdef","diagnostic":{"version":1,"port":43210,"access":"lan"}}
```

The existing typed diagnostic discovery envelope supplies the actual bound TCP
port. Responses contain no host, process/cast identity, capture state, token,
secret or media, never exceed 256 bytes (no UDP amplification), and are globally
limited to four per second per listener. Malformed queries do not consume the
reply budget. Parsing, gating and replies run only on a dedicated diagnostic
thread; no diagnostic TCP slot is acquired and no capture begins.

The collector sends one request with a fresh random nonce, accepts only replies
from the queried host/port, validates the nonce/version/access/port and derives
the TCP host from that known address. The receive window is bounded to 1..30
seconds; there are no retries, alternative-port probes or broadcast queries.
This rejects stale process/query replies but is **not authentication** or proof
of current reachability. Normal TCP HELLO/START and follow-receiver scope checks
remain required; discovery never enables consent or selects a cast.

Queries recheck the same listener/network/permission/owner gates before replying.
Explicit local disable, process death, failed binding, identity change or
listener closure cannot leave persisted hints/responder state. Delegated mode
uses only its existing 120-second frozen grace; expiry/new ownership/reset closes
both sockets. Local consent intentionally survives individual cast teardown
until explicitly revoked or the process/network/permission changes. If UDP
27184 is occupied, the listener logs discovery unavailability but preserves the
already-working TCP path/casting; it never evicts another socket or changes port.

CLI: `--discover-sender PRIVATE_IPV4 --discover-seconds 5`, separate from
capture/receiver discovery. It only prints the untrusted endpoint. Then use
`--lan --sender HOST:PORT --follow-receiver ...` for explicit collection.
Old running builds without this responder cannot be recovered by source changes:
with settings gated, no known port and no retained log, there is no supported
non-disruptive bootstrap. Do not restart/toggle consent or scan during a cast.

### Optional receiver endpoint discovery

The existing receiver discovery advertisement on UDP **27183** may include the
optional root field `"diagnostic":{"version":1,"port":43210,"access":"lan"}`. `port` is an exact
JSON integer in 1..65535, taken directly from the bound diagnostic server socket,
not the casting TCP port or a parsed display string. It is included only when
local statistics/remote consent or admitted session delegation remains valid and the diagnostic listener
is successfully bound/live with LAN permission. Disabled, revoked, failed,
starting and closed listeners omit the entire field. Closing the receiver also
prevents the field from appearing in its advertisement. Existing advertisement
state/capabilities and the 1024-original-byte budget remain unchanged.
The UDP socket selects its source for each destination before constructing the
packet; the field is omitted unless that exact source is the diagnostic bind
address. If route selection fails, a legacy advertisement without the field is
sent instead.

The process owner supplies a safe immutable nullable snapshot through
MainActivity's provider to ReceiverServer. Delegation invokes an injected
process-owned session-access interface only after cast admission; ReceiverServer
does not itself create diagnostic sockets or retrieve media data. Listener snapshots recheck closed
state, socket binding and permission; local revocation gates the snapshot before
asynchronous listener cleanup. There is no pre-consent diagnostic advertisement.

Android sender discovery retains the optional typed metadata independently of
the normal casting endpoint. Missing fields are legacy-compatible. A present
envelope must be an object with positive integer `version` and valid integer
`port`; version 1 additionally requires exact `access:"lan"`. Strings in numeric
fields, Booleans, fractional values, nulls, missing required fields,
duplicate keys and ports outside the TCP range reject the advertisement.
Unknown positive revisions with a well-typed valid envelope are ignored, never
treated as version 1. Additional envelope fields are additive; the complete
datagram must still fit 1024 bytes before parsing.

The PC derives an endpoint host from the UDP source address and combines it
with this port. Advertisements are unauthenticated, spoofable/stale hints.
Neither discovery nor the port authenticates a device or grants collection.
**Never advertise media, credentials or capture state.**
Discovery reports endpoint/access only; it performs no automatic probing or
connection. The collector requires explicit `--lan` to acknowledge plaintext,
unauthenticated access. Network policy, NAT and stale packets can still prevent
reachability; the exact locally displayed bound endpoint remains authoritative.

Local access: **Settings → Advanced → Show playback statistics → Allow unpaired LAN
diagnostics (this process only)**, read the warning, then **Save**. Reopen **LAN diagnostics
endpoint / status** after listener startup, before casting, to read the actual TCP endpoint and
unauthenticated-access status/warning. Settings cannot be opened during an active
casting role; use the bounded unicast bootstrap above instead. This dialog remains screenshot-protected.
Nothing is persisted about remote consent or capture buffers. A process restart
requires new local consent. There is no pairing step.
Disabling either local switch revokes local explicit access immediately, even before saving the draft;
cancelling a settings dialog never grants access. Backgrounding and overlay
visibility do not revoke it. Receiver access survives the failed cast.
Delegated receiver mode instead follows admitted cast ownership/flag and its
bounded frozen grace, irrespective of the receiver's local checkbox values.
The listener also rechecks LAN permission independently of Activity foreground
state and rejects/revokes access if that permission is lost.

The listener binds exactly one private IPv4 address on the active Wi-Fi/Ethernet
network, not a wildcard, cellular interface, VPN, loopback or forwarded port.
IPv6-only networks are currently unsupported and startup fails visibly.
If the active network, its eligible transport, or the bound address changes,
the listener is revoked rather than silently rebinding under old consent.
An address on another casting interface is reported unavailable for diagnostics;
this does not restrict casting.
No AndroidKeyStore keys, certificates, pairing secrets or fingerprints are generated.

HELLO returns `access:"lan"`, `clockId`, `connectionId`, `roles`, `limits`, `delivery:"fetch"`,
`capabilities` with separate `sender`/`receiver` arrays, and
`metricAvailability:{"tcpKernel":false,"transportProvenance":"application_socket_calls","videoPresentation":"codec_release_and_optional_render_callback","physicalDisplay":false}`.
The supported flags are:

| Role | Supported flags |
|---|---|
| sender | `session`, `recovery`, `audio.capture`, `audio.transport`, `audio.queue`, `video.progress`, `transport.health` |
| receiver | `session`, `recovery`, `audio.transport`, `audio.queue`, `audio.output`, `video.progress`, `transport.health` |

`video.progress` supplies bounded cumulative stage snapshots, described below.
Collector defaults intersect their known flags with each peer's advertised
capabilities, including when following the receiver. Explicit flag lists stay
strict: unsupported requests fail before START; `capture_metadata.flags` always
records the effective set.
No TCP send-queue/retransmission/RTT measurement is synthesized from socket call
durations. These deliberate unavailable surfaces narrow this implementation
profile without silently accepting omitted groups.

This implementation uses **retained FETCH only**, not unsolicited telemetry.
Timeout/revocation closes the plain TCP transport directly, independently of
the diagnostic writer, enforcing a nonreading-client deadline.
Lifecycle notifications are retained event objects returned in `records`.
This avoids a second outgoing queue and keeps command ordering/deadlines simple.
FETCH pages obey both strict rolling one-second budgets (256 records/128 KiB,
including response framing); a budget-exhausted request returns `RATE_LIMITED`
without advancing the client cursor or discarding retained evidence.
`START` returns `captureId`, `startedNs`, `expiresNs`, `maxRecords`, `maxBytes`,
`maxRecordBytes`, `maxDurationMs`. `STATUS` returns `state` (`idle`, `collecting`,
`frozen`), nullable `captureId`, `target`, `flags`, `expiresNs`, `endedNs`,
`endReason`, `sessions` and the retained range/drop counters. The frozen timer
never renews on STATUS, PING or FETCH. END is idempotent while its capture remains.
STATUS also returns integer `pendingRecords` (zero when idle). Frozen retrieval
is bounded by the initial 4,096-record/2 MiB retention plus at most 1,024 staging
records of at most 2,048 bytes each; pages may be byte-limited, not just
32-record-limited. The collector enforces both evidence bounds, cursor progress
and the original 120-second deadline rather than a 129-page assumption.

When local consent precedes asynchronous listener binding, admitted receiver
sessions retain a consent-generation/owner-scoped reporter. They publish `local`
only after the endpoint is bound, outside listener/session state locks.
Disable, bind failure, retirement, replacement or consent-generation change
invalidates the pending report. Collector disconnect alone does not revoke
local consent; explicit access revocation still closes access and clears history.

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

- `CAPTURE_STARTED`, `SESSION_BOUND`, `CAPTURE_ENDED`: lifecycle; end data has
  the controlled reason listed above. These do not depend on selected groups.
- `RECORDER_SAMPLE`: periodic recorder-health observation at `samplePeriodMs`;
  `queueDepth`, `inFlightCount`, decimal-string drop counters, and `inFlight`
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
  blocked.
- `SESSION`, `RECOVERY`, `AUDIO_CAPTURE`, `AUDIO_TRANSPORT`, `AUDIO_QUEUE`,
  `AUDIO_OUTPUT`, `VIDEO_PROGRESS`, `TRANSPORT_HEALTH`: selected typed observations below.

Selected observation `data` contains controlled `stage`, `reason`, `point` and
optional decimal-string `operationId`, `ptsUs`, `endPtsUs`, `count`, `bytes`,
`cutoffUs`. Unavailable numeric observations are omitted, never replaced with
zero. `stage` is one of `started`, `ended`, `config`, `begin`, `complete`,
`failed`, `admitted`, `rejected`, `pressure`, `ready`, `reset`, `window`,
`sample`, `proof`, `hold`, `evicted`. `point` is one of `unspecified`, `read`,
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
| `capture_timestamp` | Boolean `available`; decimal `framePosition`, `monoNs` when available, otherwise both null |
| `video_configuration` | controlled `codec`; integer `width`, `height`, `frameRate` (0 means unspecified); nullable Boolean `renderedCallback`; constant `presentationEvidence:"codec_release_or_render_callback_not_physical_display"` |
| `video_progress` | controlled `videoStage`; decimal cumulative `count`, `firstAtNs`, `lastAtNs`, `maxPtsGapUs`, `maxObservationGapNs`; nullable decimal `bytes`, `keyFrames`, `firstPtsUs`, `lastPtsUs`, `sourceAtNs`; controlled `lastReason`; Boolean `retired`; `reasons` with decimal `stale`, `capacity`, `pressure`, `sourcePending`, `stopped`, `replaced` |
| `video_failure` | controlled lowercase decoder `VideoStage` in `videoStage` and `VideoFault` in `fault`; enclosing `failed` event supplies track/generation, waiting/last input PTS and reason `failure`, without exception messages |
| `operation_summary` | lowercase `point`; decimal `windowStartNs`, `windowEndNs`, `started`, `completed`, `zeroOperationEvents`, `bytes`, `units`, `slow`, `maxDurationNs`; `durationBuckets` with decimal `under1Ms`, `1To5Ms`, `5To20Ms`, `over20Ms`; nullable decimal `slowExampleOperationId`, `slowExampleDurationNs`, `slowExamplePtsUs`, `slowExampleEndPtsUs`, `slowExampleCount`, `slowExampleBytes`; nullable integer slow-exemplar track/generation |

### Video progress evidence

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
acquire a media lock or perform I/O/native timing reads. Contention exhaustion
uses the existing admission-loss counters. No per-frame staging event is
created; periodic snapshots and rare generation/configuration/failure records
use existing bounded staging and retention. Final per-stage samples have
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

Only START allocates the capture's fixed 1,024-slot lock-free MPSC staging ring,
typed aggregation counters and retained buffers. Media hooks check an
inactive/selected-group gate before diagnostic clocks, allocation or platform
queries. Fast operation hooks update bounded atomics and do not allocate one
record per call. They never format JSON or use sockets, disks or a diagnostic
mutex. Routine records can occupy 896 ring slots; 128 are reserved for critical
recovery/session/failure/lifecycle records, and a final slot is reserved for
terminal state. A record receives a sequence only after a publication ticket
reserves its slot, so admission drops do not create false sequence gaps.
The diagnostic lane drains tickets in order and cannot pass an earlier
unpublished reservation. Closing the queue atomically with terminal admission
rejects late publishers. The diagnostic lane drains at most 1,024 observations
per pass, formats bounded schemas, then enforces the 4,096-record/2 MiB split
retention budgets and publishes exact/inexact loss watermarks.

JVM tests exercise real plain TCP loopback against the app listener,
HELLO/connection/rate limits, strict framing,
lease/retention, concurrent producers, blocked native output vs END, UI gating,
and repeated raw recovery-window rejection with retained cutoff/interval
evidence. Synthetic fixtures use the app build directory and are removed after
testing. These are not Wi-Fi, acoustic
output or real-device overhead acceptance results.

### Reproducible branch validation

The sender-memory connection follow-up passed **192 targeted JVM tests with
zero failures/errors/skips**, including
`DiagnosticListenerTest.pythonCollectorCapturesBothActualJvmLanListenersWithoutPairing`.
That integration launches the Python collector against two instances of
the actual app `DiagnosticListener`/`DiagnosticProtocol`/`DiagnosticRecorder`,
using CLI `--lan --sender <actual endpoint> --follow-receiver` with no
receiver endpoint/session, certificate or secret inputs. It verifies
paired unauthenticated LAN capture, exact signed-int64 maximum session identities,
with a locally consented sender listener and a scoped delegated receiver listener.
The sender TCP CONNECTIONS response supplies the exact current receiver endpoint
and session; the collector validates the receiver HELLO scope before START.
It also verifies
distinct process clock IDs, ten CLOCK observations per device, retained
START/binding/END events and frozen state. It uses no phone/ADB/install and writes
only self-cleaning synthetic fixtures under `app/build`. Set
`DIAGNOSTICS_TEST_PYTHON` to an installed Python executable if needed; Windows
also discovers the existing shared Python 3.13.9 ARM64 toolchain, and other hosts
default to `python3`. Nothing is downloaded or installed.

Exact Windows commands, from the isolated worktree:

```powershell
Set-Location 'E:\gitpersonal\RedrainCast-Android-remote-diagnostics'
$env:JAVA_HOME = "$env:LOCALAPPDATA\Copilot\Toolchains\jdk-21.0.13-x64"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat :app:testDebugUnitTest `
  --tests '*Diagnostic*' `
  --tests '*SharingSettingsTest' `
  --tests '*ReceiverOptions*' `
  --tests '*Configuration*' `
  --tests '*SenderSession*' `
  --tests '*StreamRecoverySenderTest' `
  --tests '*AudioWriteProgressPipelineTest' `
  --console=plain

$env:JAVA_HOME = "$env:USERPROFILE\.gradle\jdks\jetbrains_s_r_o_-21-aarch64-windows.2"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:assembleDebug :app:lintDebug --console=plain --quiet
git --no-pager diff --check
```

For only cross-language integration, replace the `--tests` selectors with
`--tests 'dev.redrain.cast.diagnostics.DiagnosticListenerTest.pythonCollectorCapturesBothActualJvmLanListenersWithoutPairing'`.
Reports are overwritten by the next run of the same Gradle task:

- `app/build/reports/tests/testDebugUnitTest/index.html`: consolidated JVM report.
- `app/build/test-results/testDebugUnitTest/TEST-*.xml`: machine-readable counts.
- `app/build/test-results/testDebugUnitTest/TEST-dev.redrain.cast.diagnostics.DiagnosticListenerTest.xml`:
  named Python/JVM integration case.
- `app/build/reports/lint-results-debug.html` and `lint-results-debug.txt`:
  native ARM64 lint, zero errors and 17 existing warnings; none in diagnostics code.
- `app/build/outputs/apk/debug/app-debug.apk`: successfully built debug APK,
  not installed or released. Follow-up SHA-256:
  `D4C2A26CC157607FF16475D6CA3B07A3B4C840C1529D8D5A9E11778092817338`.

App changes comprise diagnostics implementation/lifecycle and deterministic
test files, statistics consent/warning/status UI (`SharingSettings`, `MainActivity`),
sender capture/session/recovery/queue hooks (`ProjectionCapture`, `SenderSession`,
`StreamRecoverySender`, `MediaBudget`), receiver admission/queue/output hooks
(`ReceiverServer`, `TrackWorker`, `AudioPlayer`), additive delegation negotiation
(`DiagnosticDelegation`, `Request`, `ReceiverLink`, `SenderSession`), optional independent StrictJson
depth/size tracking controls, and focused UI/recovery/blocked-output regressions.
Related README/protocol docs and the existing Android workflow are updated;
the workflow runs the Python collector suite. Its **31 tests passed**, including
delegated HELLO scope selection, strict request/response validation and paired
loopback collection. The collector guide documents sender-only activation and
the different session selection needed for delegated versus standalone access.

Remaining device acceptance: sender-delegated activation without projector settings,
same-session freeze/grace/new-owner revocation; local consent/LAN
permission loss, UI recreation and background sender lifetime; actual Wi-Fi
endpoint reachability/discovery; paired live audio-failure reproduction and controlled
metadata inspection; disabled/enabled CPU, memory, audio timing and frame/recovery
overhead on a real sender/projector pair. No physical root-cause or nonperturbing
performance conclusion is established by loopback success.
