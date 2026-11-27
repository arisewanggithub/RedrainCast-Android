# Archived remote diagnostics v1 transport and delegation

> Historical reference only, excerpted from `HEAD:protocol/REMOTE_DIAGNOSTICS.md`.
> This v1 service is unsupported for current collection; use the
> [v2 contract](REMOTE_DIAGNOSTICS.md) and [current runbook](../TROUBLESHOOTING.md#collect-live-paired-v2-diagnostics).
> Do not use v1 as a fallback. Statements below describe the historical contract.

## Transport and access

- Use a separate plain TCP listener. There is intentionally no encryption,
  certificate, credential or authentication. The collector requires explicit
  `--lan` acknowledgment and sends HELLO; no implicit downgrade/fallback is allowed.
- Bind only the intended local-network interfaces, never automatically expose
  through port forwarding, and display the actual bound endpoint/port.
  Optional [receiver discovery](#historical-receiver-endpoint-discovery-removed-in-v2)
  advertises only the live port;
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
duplicate keys, non-object roots, excessive nesting, and
noncanonical typed values; do not coerce strings, Booleans or fractional numbers.
Requests and legacy responses retain the 8-level bound. Only captures explicitly
selecting `video.timing` may return responses through depth 10 (root depth zero,
counting descent into values), for fully populated clock corrections. Depth 11
is rejected by the current collector.
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
Control responses have priority over telemetry, which may be dropped. Serialize all writes on one diagnostic I/O lane.

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
| `video.progress` | Low-rate video stage counters and bounded receiver anomaly examples. |
| `video.timing` | Receiver-only extended timing snapshots; requires `video.progress` and a collector supporting depth 10 and 12,000-byte records. |
| `transport.health` | Per-lane application progress and supported TCP statistics, with metric provenance and availability. |

No automatic enabling of omitted groups. A minimal capture lifecycle, sequence
and drop accounting always accompanies selected flags. START acceptance must
guarantee the returned groups are supported; unavailable individual OS metrics
are reported explicitly, never as zero. Adding more groups requires END, CLEAR
and another START in v1, not a mid-capture unbounded expansion.

Example:

```json
{"v":1,"id":"capture1","op":"START","args":{"target":{"role":"sender","nextSession":true},"flags":["session","recovery","audio.capture","audio.transport","audio.queue"],"durationMs":180000,"samplePeriodMs":1000}}
{"v":1,"id":"capture1","op":"START","ok":true,"result":{"captureId":"c1","startedNs":"613130000000000","expiresNs":"613310000000000","maxRecords":4096,"maxBytes":2097152,"maxRecordBytes":2048,"maxDefaultRecordBytes":2048}}
{"v":1,"id":"end1","op":"END","args":{"captureId":"c1"}}
```

Stable error codes include `HELLO_REQUIRED`, `BUSY`, `UNSUPPORTED_VERSION`,
`UNKNOWN_OPERATION`, `INVALID_ARGUMENT`, `UNSUPPORTED_FLAG`, `DUPLICATE_ID`,
`RATE_LIMITED`, `SESSION_NOT_FOUND`, `CAPTURE_ACTIVE`, `CAPTURE_NOT_FOUND`,
`RETAINED_CAPTURE_EXISTS`, `PERMISSION_REQUIRED`, `SCOPE_MISMATCH`,
`SESSION_ENDED` and `INTERNAL`.
Unexpected internal errors also use the existing local error-reporting path;
never report success or silently skip requested instrumentation.


### Sender-delegated receiver access

This unreleased draft intentionally supports sender-controlled receiver
diagnostics: the sender warning explicitly states that enabling local
remote diagnostics also opens compatible receiver diagnostics
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

Explicit local receiver mode takes precedence, has a process-local
consent gate and unrestricted local profile, and is not closed by delegated
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
collection. That historical hint-based attachment is no longer supported.

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

### Historical receiver endpoint discovery (removed in v2)

The existing receiver discovery advertisement on UDP **27183** may include the
optional root field `"diagnostic":{"version":1,"port":43210,"access":"lan"}`. `port` is an exact
JSON integer in 1..65535, taken directly from the bound diagnostic server socket,
not the casting TCP port or a parsed display string. It is included only when
local remote consent or admitted session delegation remains valid and the diagnostic listener
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
