# Paired Android remote diagnostics

The [v1 control contract](../protocol/REMOTE_DIAGNOSTICS.md) defines a separate
opt-in diagnostic channel. This collector does not install an application,
enable diagnostics remotely or change casting configuration. It requires an
Android build implementing the service; earlier v0.4.19 releases do not
implement it.

## Capture

To find receivers with diagnostics enabled, listen to existing LAN discovery:

```powershell
python -B .\tools\collect-diagnostics.py --discover --discover-seconds 5
```

This prints the diagnostic endpoint advertised by each receiver, including busy
receivers. It does not probe ports, start collection or discover sender services.
Advertisements are untrusted. Disabled services are omitted, and
old builds without the diagnostic advertisement cannot be found this way.
Discovery listens on UDP 27183; failure to bind is reported explicitly and no
other discovery service is stopped. Local firewall/LAN policy may block packets.

For an already-casting sender whose startup log is no longer available, query
its known private LAN IPv4 address (updated builds only):

```powershell
python -B .\tools\collect-diagnostics.py --discover-sender 192.168.1.135 --discover-seconds 5
```

This sends **one** 256-byte unicast query to UDP **27184**, not a port scan or
broadcast. It prints only the current consenting listener's TCP endpoint. It
does not open settings, toggle consent, restart the app/cast, connect TCP or
start collection. Use that endpoint as `--sender` below. The responder is owned
by the diagnostic listener, not the Activity or casting UI, and remains usable
while casting/backgrounded. Process exit, consent/permission/network revocation
and listener closure remove it. A new process requires fresh consent; no endpoint
or consent file is persisted. Receiver session delegation retains only its
existing bounded frozen grace and is revoked on owner replacement.

The nonce/source-checked response is still **unauthenticated**: use a trusted
LAN. A timeout can mean an old build, disabled consent, a changed network or
firewall policy; it is not permission to probe other ports. A busy diagnostic
TCP collector does not prevent this read-only query. UDP 27184 deliberately
does not share the Activity-owned receiver-advertisement socket on UDP 27183.
There is no debug/run-as dependency; the same path is compiled into optimized
release builds and tested by the existing collector/JVM/assembly CI workflow.

1. Enable playback statistics and remote diagnostics on the sender before
   starting the cast. Accept its LAN-access warning. On a compatible receiver,
   the admitted cast automatically opens session-scoped diagnostics without
   changing projector settings. Both devices need the updated build.
   The receiver endpoint may become discoverable after cast admission when
   the advertisement's source address matches its diagnostic bind address.
   Alternatively, standalone receiver-local diagnostics can still be enabled.
2. Run the collector with Python 3.10 or newer, following the session selection
   instructions in step 4:

   ```powershell
   python -B .\tools\collect-diagnostics.py --lan `
       --sender PHONE_HOST:PHONE_PORT `
       --follow-receiver `
       --duration 180 --output .\diagnostic-capture-001
   ```

3. LAN mode requires no pairing code or fingerprint. It is intentionally
   plaintext and unauthenticated: anyone on the reachable LAN can access the
   permitted diagnostic metadata, occupy the one collector slot, or impersonate
   a diagnostic service. Use only a trusted LAN and disable access when finished.
4. For sender-delegated access, start the cast first and use `--follow-receiver`.
   It asks the sender's diagnostic TCP `CONNECTIONS` command for its current
   in-memory receiver endpoint/session, connects both listeners, checks receiver
   HELLO scope and explicitly STARTs both exact-session captures. No projector
   endpoint, receiver UDP packets or settings UI is needed. The receiver's HELLO scope automatically
   selects that exact admitted session; it rejects other/next-session requests.
   If the cast uses a different receiver interface or IPv6, the receiver hint is
   unavailable; casting continues, but follow-receiver cannot attach.
   For standalone local diagnostics, wait for both captures to become active
   before starting a new cast, or pass explicit existing session IDs.
5. Captures end on session end or after the requested duration, whichever comes
   first. Ctrl+C closes connections and ends collection; partial local evidence
   is retained. The collector does not automatically retry, reconnect or START
   another capture after failure.

Use `--sender-session DECIMAL_SESSION_ID` / `--receiver-session ...` to attach to
an existing session. Session IDs are protocol IDs, not Android process IDs.
Either device can be collected alone by omitting the other role.
Manual `--receiver PROJECTOR_HOST:PROJECTOR_PORT` remains available, but cannot
be combined with `--follow-receiver`. The latter requires `--lan --sender`;
`--sender-session` can optionally pin the expected cast. Missing/legacy,
unsupported, malformed, frozen, expired or changed-session information fails
explicitly without silently scanning, retrying or capturing the next session.

Prefer `--discover-sender` to bootstrap the sender during a cast. Settings are
intentionally unavailable while a casting role is active. The local display
before casting, or an ordinary startup event still present in already authorized
ADB logs, remain optional alternatives:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s PHONE_SERIAL logcat -d -s RemoteDiagnostics:I |
    Select-String 'DIAGNOSTIC_LISTENER_STARTED.*role=local'
```

Use only an event from the current enabled process; it may already have rolled
out of logcat, so logs are not durable discovery. Disabling/re-enabling changes
the ephemeral TCP port. Neither the new query nor a new APK can repair discovery
in an already-running old process that lacks the responder. If its endpoint is
unknown and its startup log is gone, there is no supported non-disruptive
bootstrap for that old process. Do not stop a live cast to obtain it. Receiver
broadcast discovery alone still does not discover senders. Connection hints
remain process-only and no tokens/secrets are logged.

The default flags negotiate the advertised audio investigation surfaces,
`video.progress` and application transport health separately for each peer.
Older builds without video support still collect their supported defaults; the
`capture_metadata.flags` record is the authoritative selected set. Explicit
`--sender-flags` / `--receiver-flags` remain strict: a build lacking a requested
flag rejects the capture. Kernel TCP metrics remain unavailable.
Select a smaller supported set rather than assuming an omitted group was captured:

```powershell
python -B .\tools\collect-diagnostics.py --lan `
    --sender PHONE_HOST:PHONE_PORT `
    --sender-flags session,recovery,audio.capture,audio.transport,audio.queue `
    --duration 180 --output .\diagnostic-capture-002
```

Changing groups requires a new capture. A retained frozen capture can prevent
START until it is explicitly cleared through the protocol or its 120-second
retention expires; the collector never silently deletes it. A fresh output
directory is required; existing evidence files are never overwritten.
After END, the collector drains byte-limited pages until retained backlog and
pending staging publication are both complete. Empty pending pages are paced,
not treated as completion. Record/byte progress bounds and the original
120-second frozen deadline prevent unlimited retries; expiry or a stalled
producer is an explicit collection error, not a successful truncated capture.

## Evidence and limits

For a focused paired video capture, explicitly request
`--sender-flags session,recovery,video.progress,transport.health` and
`--receiver-flags session,recovery,video.progress,transport.health` with the
existing `--follow-receiver` flow. This fails on older peers lacking video;
omit the explicit flag lists to negotiate backward-compatible defaults.

`VIDEO_PROGRESS` snapshots distinguish encoder output, source filtering,
sender queue admission/discard/handoff, send start/return/failure, receiver
receipt/admission/rejection, codec input/output, skipped output,
render-enabled release and the existing codec rendered callback. Counters
are cumulative per capture/stage/generation; compare PTS, `lastAtNs` and the
snapshot's `monoNs` for progress/stalls. An unchanged snapshot is not a new
frame. Encoder generations and recovery-remapped wire generations differ;
correlate source PTS, not generation equality alone. A static source can have
legitimate encoded gaps. Inspect recorder loss watermarks and recovery records.

`released` is successful codec release with the requested release timestamp;
`rendered` is callback evidence only (availability is explicit in the video
configuration). Neither is physical display/scanout proof. Missing stages
are unavailable observations, not fabricated zeroes. See
`protocol\REMOTE_DIAGNOSTICS.md` for exact schemas and generation semantics.

Each device writes its own JSONL file with selected flags, process clock ID,
start/end clock samples, capture identity, events and retention/drop counters.
Secrets, endpoints and certificate material are not written into the evidence.
Keep the files private: timing and session metadata can still be sensitive.
There is no automatic upload or sharing.

`--lan` is an explicit client opt-in, not an automatic downgrade after failed
authentication. It sends HELLO, then the bounded read-only diagnostic commands.
The collector retains pinned TLS support for the earlier prototype only when
`--lan` is omitted; the current LAN-mode Android service does not implement that
prototype handshake. Do not interpret LAN capture output as authenticated device
identity. No pairing secrets or certificates are needed for LAN mode.

Collection is independent on each device; a failed connection makes the paired
command fail explicitly and closes the other connection. Already written
partial evidence is retained, not presented as a complete paired capture.
The output contains clock-offset bounds, not an assumption of equal device
clocks. Compare beginning/end bounds to assess uncertainty and drift.

FETCH retrieves retained events using exact decimal int64 cursors. Sequence gaps
and `lossWatermarks` distinguish admission losses, routine/critical retention
evictions, and the published/retained sequence bounds; do not treat an inexact
loss envelope as a contiguous missing range. Routine fast operations appear as
typed per-window summaries with operation counts, bytes/units, duration buckets,
slow count/max and one bounded slow exemplar, not as one JSON record per call.
Failed calls remain individual records; current operations are sampled at
`RECORDER_SAMPLE` and included in `CAPTURE_ENDED` if still in flight. Neither
local sequence numbers nor successful socket writes prove cross-device packet delivery.
The collector does not diagnose Wi-Fi, decode stalls or recovery bugs by itself.
Application timestamps cannot replace unavailable kernel/TCP measurements.
While idle, live polling waits 250 ms before checking again. When a FETCH page
reports backlog, the collector drains immediately until caught up, waiting only
when its conservative rolling-window reservation would exceed the service's
256-record/second or 128-KiB/second cap. Frozen-tail retrieval uses the same
budget-aware drain. An explicit `RATE_LIMITED` FETCH may be retried once because
FETCH is read-only and cursor-pinned; other failures are not retried.

## Validation

```powershell
python -B .\tools\diagnostics-test.py
```

The focused suite covers strict framing, exact int64 values, delegated receiver
HELLO scope selection, LAN HELLO, certificate pinning
before AUTH, command identities, selected flags, strict admission/retention loss
watermarks, budget-aware backlog draining, paired failure cleanup, and a real
paired TLS loopback capture. TLS loopback tests use an existing OpenSSL
executable (including Git for Windows' bundled copy) to generate a test-only
certificate under the project's build directory and remove it after testing;
they skip explicitly if OpenSSL is absent. No packages are installed and no
devices are contacted by these tests.

Unit/loopback success does not establish Android integration or physical
projector performance. Device acceptance must also verify the app's consent
gates, matching service schema and that diagnostics do not perturb playback.
The app's `DiagnosticListenerTest` additionally runs this Python collector
against simultaneous actual JVM sender and delegated receiver listeners,
reading the receiver endpoint and exact session from sender TCP CONNECTIONS
using `--follow-receiver`, with no explicit receiver or session argument.
