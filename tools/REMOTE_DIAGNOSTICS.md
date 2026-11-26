# Paired Android remote diagnostics (v2)

The [v2 contract](../protocol/REMOTE_DIAGNOSTICS.md) uses one collector
connection to the sender and no standalone receiver diagnostic endpoint.
Both peers must support the proxy capability. It does not install an app or
remotely enable consent.

1. Enable **Allow unpaired LAN diagnostics** on the sender before casting.
   The traffic is plaintext and unauthenticated; use only a trusted LAN.
2. Obtain the sender endpoint from its settings or query its known private
   IPv4 address (one unicast UDP query, not a scan):

   ```powershell
   python -B .\tools\collect-diagnostics.py --discover-sender 192.168.1.135
   ```

3. Before starting the cast, arm a paired capture:

   ```powershell
   python -B .\tools\collect-diagnostics.py --lan `
       --sender PHONE_HOST:PHONE_PORT --duration 180 --output .\diagnostic-capture-001
   ```

   Start the cast when prompted. The lease starts at START, including waiting
   and setup. The receiver bootstrap must be retained before media begins.
   Optional strict flag lists use `--sender-flags` and `--receiver-flags`;
   receiver `video.timing` requires `video.progress`.
4. The collector writes `paired.jsonl` once and never overwrites it. It reports
   sender and receiver records with independent cursors, source identity,
   loss, and terminal state. Full bootstrap, selected flags and detailed terminal
   outcomes are saved in `status` lines as soon as available and after freezing.
   Setup failures include a sanitized code and sender-local or receiver-control
   provenance in the final `status`; receiver coverage is not implied.
   Partial output remains after errors or Ctrl+C.
   A missing or truncated receiver terminal is an explicit collection failure,
   not a complete paired capture. Retained frozen pages are drained with bounded
   pacing and a 120-second deadline.
   STATUS may describe an admitted but unconfirmed receiver outcome using
   `confirmed:false` and null receiver time and reason; it is reconstructed by
   the sender, not receiver evidence or confirmation of cleanup.

There is no `--receiver`, `--follow-receiver`, receiver discovery, existing
session attach, TLS, or v1 fallback. See the contract for source identity and
depth limits. Unit tests do not establish device overhead or display timing.

```powershell
python -B .\tools\diagnostics-test.py
python -B .\tools\diagnostics-v2-test.py
```

The archived guide below contains event interpretation details but its v1
transport, commands, examples, validation claims, and limits are obsolete.

<details>
<summary>Archived v1 guide (not current collection instructions)</summary>

# Paired Android remote diagnostics (archived v1)

The [v1 control contract](../protocol/REMOTE_DIAGNOSTICS.md) defines a separate
opt-in diagnostic channel. This collector does not install an application,
enable diagnostics remotely or change casting configuration. It requires an
Android build implementing the service; earlier v0.4.19 releases do not
implement it.

The historical two-listener capture procedure has been removed. The current
collector has one sender connection and must be armed before the cast; use the
v2 instructions above.

The default flags negotiate the advertised audio investigation surfaces,
`video.progress`, the optional receiver `video.timing` extension, and application
transport health separately for each peer.
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

For a focused paired video capture, use the v2 command above with
`--sender-flags session,recovery,video.progress,transport.health` and
`--receiver-flags session,recovery,video.progress,video.timing,transport.health`.
Unsupported explicit flags fail rather than silently narrowing coverage.

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

Receiver `video_frame_timing` requires both `video.progress` and `video.timing`;
the latter explicitly negotiates deeper responses and larger records. Legacy
collectors do not select it and receive only legacy-sized, depth-8 records.
Receiver `video_frame_timing` adds bounded signed timing ranges for decoded
availability, unclamped intended deadline versus codec release timestamp,
and safely matched callback timestamps and cadence. Check its missing,
ambiguous, evicted, mapping-change and future-callback counters before drawing
conclusions. Compare callback cadence to the actual PTS cadence rather than
assuming a fixed frame rate; callback evidence still does not establish scanout.
For deadline variation, inspect `deadlineCadenceMinusPts` and its bounded
minimum/maximum paired scheduling examples. Each includes the clock position
actually used, mapping revision, cached audio observation sequence/monotonic
age, output timestamp/head classification when known, source slew offset at
observation, latest packet-map correction, and delay-read-to-schedule times.
Unchanged observation identity with increasing age exposes reuse. Video deadlines
now project qualified native presentation only to already-consumed frames and
the existing 100 ms DAC timestamp validity boundary, using the real resampler/
silence mapping and source phase slew without advancing actual audio observations.
`sourcePositionUs` is that unguarded scheduling input, not necessarily cached PTS.
`scheduleAtNs` and clock `readAtNs` are the same deadline-calculation time whether
recording is on or off. Packet corrections are not instantaneous audible-clock changes,
and hardware timestamp age is not cached observation age. Null is unknown.
Scheduling pairs do not require callbacks and never span mapping revisions;
these are correlations, not proof of scheduling jitter or native clock drift.
Correlation contributions remain provisional while their PTS groups occupy the
128-group duplicate-detection window. Later duplicates within that window retract
matches, affected cadence ranges and exemplars. Retirement commits eligible
per-frame history; adjacency pairs commit only after both endpoints retire,
so callback-order pairs survive out-of-order callbacks and PTS-order retirement.
At most 512 pending edges bridge the bounded window; there are no endpoint chains.
`outsideWindowCallbacks` exposes unmatched callbacks too old to revise
that history. No lifetime uniqueness beyond the window is claimed, and snapshots
must not be treated as monotonic deltas.
The collector keeps the 16,384-byte line limit but accepts response depth 10,
including fully populated clock corrections inside paired FETCH exemplars.
Frozen draining is capped at 5,120 records / 14,385,152 record bytes, covering
2 MiB initial retention plus 1,024 delayed publications of up to 12,000 bytes.
The CI collector integration runs after the JVM fixture generator with
`DIAGNOSTICS_REQUIRE_FIXTURE=1`; standalone Python tests may skip absent fixtures.

The v2 collector writes a single paired JSONL file with selected flags, process
clock IDs, clock samples, capture identities, events and retention/drop counters.
Secrets, endpoints and certificate material are not written into the evidence.
Keep the files private: timing and session metadata can still be sensitive.
There is no automatic upload or sharing.

`--lan` is an explicit client opt-in, not an automatic downgrade after failed
authentication. It sends HELLO, then the bounded diagnostic commands. The v2
collector has no TLS fallback; LAN output is not authenticated device identity.

The sender proxies receiver evidence; a missing lane or incomplete receiver
tail fails the paired command explicitly. Already written partial evidence is
retained, not presented as a complete paired capture.
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
Current `DiagnosticListenerTest` runs this Python collector against the sender's
v2 listener; receiver evidence arrives through the sender-owned proxy lane.
</details>
