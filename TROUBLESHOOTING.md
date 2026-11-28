# Troubleshooting and diagnostics

Use this guide for the current Android implementation. Start with the visible
error and a consenting reproduction on idle devices; note each app/build version,
roles, settings, network, symptom and approximate time. Change one setting at a
time and compare like-for-like runs. Do not promise a fix from a single trace.
The [cast protocol](protocol/README.md), [automatic telemetry contract](protocol/PRESSURE_INCIDENTS.md)
and [live v2 contract](protocol/REMOTE_DIAGNOSTICS.md) are the implementation
references; they are not evidence that a particular device behaved as specified.

## Temporary virtual audio discard experiment

From the repository root, build a **debug-only** experiment with
`.\gradlew.bat :app:assembleDebug -PvirtualAudioExperiment=true`.
The APK version contains `virtual-audio`. Omit the property (or set it to
`false`) for normal debug audio; release builds always use normal audio.
Install the experimental APK on the receiver as well as the sender if both
devices need the same build. The receiver decodes and drops PCM without creating
an AudioTrack. Audio is intentionally inaudible. A bounded 100 ms virtual FIFO
advances at nominal sample rate and drives internal A/V timing; it is not a
hardware playback measurement. Its completion and video-recovery proof use this
simulated timing, not native audio presentation. Receiver logcat marks startup,
first discard and recovery proof with `VIRTUAL_AUDIO_*` and
`physical_playback=false`.

To prevent simulated counts being reported as native playback, receiver session
telemetry, playback statistics and paired/receiver diagnostics are unavailable
in this experiment. Do not arm a paired diagnostic capture: an armed proxy is
explicitly rejected, not silently downgraded. Sender audio capture and audio
transport remain enabled for an unarmed cast. Success would not establish an
AudioTrack hardware cause: pacing and clock behavior also changed. Reinstall a
normal build on each device to end the experiment.

## Temporary hybrid audio/video clock experiment

Mode 1 is the default. Mode 2 is the silent discard build above. Mode 3 is
`.\gradlew.bat :app:assembleDebug -PhybridAudioExperiment=true`; the version
contains `hybrid-audio`. Properties are mutually exclusive and release builds
always use mode 1. The receiver log identifies mode 1, 2 or 3. Mode 3 still
creates and writes the real AudioTrack: native head/timestamps control writable
capacity, output metrics and discard safety; recovery continues to use real
accepted writes and native observations, not the virtual clock. An accepted
write is not proof of audible playback. Only video
scheduling uses the independent `VIRTUAL_SYNC` clock. It advances at nominal
source rate, capped by accepted source submissions, and bounds its simulated
FIFO to 100 ms by skipping excess virtual backlog. It does not model device
latency, silence or source timestamp corrections, and is not playback evidence.
Before audio attaches and after it detaches, video uses the ordinary recovery
clock rather than a virtual audio clock.
Select `video.progress,video.timing` to retain `VIRTUAL_SYNC` scheduling labels
in a compatible diagnostic capture; otherwise use the receiver's mode log.
Video scheduling differences can themselves affect a comparison; no outcome
alone proves a hardware or scheduler cause.

## Connection or startup fails

* Keep the receiver awake with RedrainCast in the foreground and check that it
  shows READY. On Android 17/API 37+, grant the app local-network access if
  prompted; capture and microphone consent are separate. Check the trusted LAN,
  then try the receiver's currently displayed IP and port via **Connect by IP :
  Port**. Ports can change when listening resumes. The receiver listener pauses
  while that device is sending; backgrounding the receiver retires it.
* Read the actual error. An unsupported codec, raster/rate combination,
  rotation capability, uncapped FPS, or HDR path is not a network diagnosis.
  Update compatible peers or explicitly choose supported settings before a
  new cast; a capped FPS or 1920x1080@60 retry is an experiment, not a guarantee.
  HDR requires a supported native HDR path; retry SDR if unavailable. Protected
  content can be black by Android policy. Do not bypass capture protections.
* A diagnostic-armed cast needs a compatible receiver proxy and successful
  bootstrap before media. If it fails, save the collector's final `status`
  and `setupFailure` code/provenance if available. An ordinary unarmed cast
  does not wait for diagnostics. Do not interpret setup failure as receiver
  coverage or silently fall back to an old diagnostic protocol.

## Video stalls, judder, missing frames or black output

Enable **Show playback statistics on receiver (next cast)** in sender Settings →
Advanced and save before starting. During playback with statistics active,
long-press the receiver video for the scrollable **Full playback diagnostics**
view. Settings are unavailable during a cast. When idle, Settings → Advanced
also opens this view, but it reports no active receiver statistics. Android 11
reports the independent panel unavailable. N/A and stale readings are not zero.
Requested FPS/bitrate are not measured throughput; encoded size, app view,
display mode and the unavailable render target are different measurements.
Android display mode is not proof of optical resolution or scanout.

For paired evidence, use [v2 collection](#collect-live-paired-v2-diagnostics)
and include `session,recovery,video.progress` on both roles and receiver
`video.timing` when supported. Compare `VIDEO_PROGRESS` stage counts and source
PTS: encoder output, queue admission/removal, write start/return, receiver
receipt/admission, decoder input/output, skipped output, release and rendered
callback. Snapshots are cumulative per stage/generation and an unchanged
snapshot is not a new frame. Sender encoder generations and recovery wire
generations are not necessarily equal; PTS can repeat. Static or variable
cadence, encoder repeats and capture sampling can legitimately create gaps.
The capture path can acquire only the latest available image, so capture PTS
gaps alone do not establish loss.
Successful socket writes do not prove receipt. Decoded skips are not network
loss. A successful codec release is scheduling evidence; rendered means a
MediaCodec callback, not compositor latch or physical display.

Receiver `video_frame_timing` offers bounded deadline and callback correlations.
Compare callback/deadline cadence with source PTS cadence and inspect missing,
ambiguous, evicted, mapping-change and future-callback counters first. Timing
snapshots can revise earlier provisional matches and must not be summed or
subtracted as monotonic deltas. `deadlineCadenceMinusPts` and scheduling
examples can expose reused audio observations, clock mapping changes and
local timing, but do not establish jitter's cause or physical presentation.
Cached observation age differs from native timestamp age; null is unknown.

### Optional debug sender frame trace

On an identity-verified, authorized sender, before a new cast enable the debug
tag: `adb -s <verified-device> shell setprop log.tag.RedrainVideoTrace DEBUG`.
Release builds do not trace. The first capture callback starts a 30-second,
16,384-event window. For 600 seconds set `log.tag.RedrainVideoTraceLong DEBUG`
too; its 1,440,000-event bound can consume tens of MiB. Reset both tags to
`INFO` after measurement. Metadata only is recorded; recording has overhead.

`VIDEO_FRAME_TRACE_SAVED` gives the exact private filename. In Windows
PowerShell, replace the verified sender serial, the decimal session number
from that filename, and the destination in a private directory. The device's
`base64` command and a streaming decoder preserve the file bytes even if Windows
adb translates line endings. This requires the negotiated `shell_v2` feature
for remote exit status and separate stderr; unsupported or unreachable devices
are not retried with `exec-out` or a legacy shell:

```powershell
$sender = 'verified-sender-serial'
$trace = 'files/video-frame-123456789.jsonl'
$path = 'C:\private\sender.jsonl'
if ($sender -notmatch '^[A-Za-z0-9._:-]+$') { throw 'Invalid sender serial' }
if ($trace -notmatch '^files/video-frame-[0-9]+\.jsonl$') { throw 'Invalid trace filename' }
$adb = Get-Command adb.exe -CommandType Application -ErrorAction Stop |
    Select-Object -First 1 -ExpandProperty Source
$features = @(& $adb -s $sender features)
if ($LASTEXITCODE -ne 0 -or $features -notcontains 'shell_v2') {
    throw 'adb shell_v2 is unavailable; trace not retrieved'
}
$process = [System.Diagnostics.Process]::new()
$process.StartInfo.FileName = $adb
$process.StartInfo.Arguments = "-s $sender shell -n -T run-as dev.redrain.cast.debug base64 $trace"
$process.StartInfo.UseShellExecute = $false
$process.StartInfo.RedirectStandardOutput = $true
$file = $null
$decoder = $null
$started = $false
try {
    $file = [System.IO.File]::Open($path, [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    $transform = [System.Security.Cryptography.FromBase64Transform]::new(
        [System.Security.Cryptography.FromBase64TransformMode]::IgnoreWhiteSpaces)
    $decoder = [System.Security.Cryptography.CryptoStream]::new($file, $transform,
        [System.Security.Cryptography.CryptoStreamMode]::Write, $true)
    $started = $process.Start()
    if (-not $started) { throw 'Could not start adb' }
    $process.StandardOutput.BaseStream.CopyTo($decoder)
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "adb or remote command failed (exit $($process.ExitCode))" }
    $decoder.FlushFinalBlock()
    $file.Flush()
    if ($file.Length -eq 0) { throw 'Trace is empty' }
    Write-Host "Retrieved $($file.Length) bytes to $path; verify before deleting the device trace."
} finally {
    try {
        if ($started -and -not $process.HasExited) {
            $process.Kill()
            $process.WaitForExit()
        }
    } finally {
        try { if ($null -ne $decoder) { $decoder.Dispose() } }
        finally {
            try { if ($null -ne $file) { $file.Dispose() } }
            finally { $process.Dispose() }
        }
    }
}
```

`CreateNew` refuses an existing destination, including when adb would fail.
Remote stderr goes to the console, not the trace file. On failure, any newly
created empty or partial file remains for inspection
or manual deletion; choose a different path for a retry. The command never
deletes the device trace. Only consider removing that exact named trace after
adb exits successfully, the local file is nonempty, and its content has been
verified; these checks alone do not prove completeness. The synchronous byte
copy may not respond immediately to Ctrl+C while blocked. Orderly cleanup may
save a partial trace; write failures are logged. Successful submissions/writes
have end events, but sink return does not prove transmission. Boundary events,
overflow and incomplete stages cannot prove missing frames. Receiver recovery
can remap generations; correlate session and PTS, not just generation.

Run `.\tools\Analyze-VideoFrameTrace.ps1 -SenderTrace <sender.jsonl>
[-ReceiverTrace <receiver.jsonl>]`; receiver input uses the matching Windows
metadata schema. It compares stage cadence, PTS multiset discontinuities and
durations, rejects overflow/different sessions, and excludes a 500 ms margin
at overlapping stage boundaries. Minute bins at the edges may be partial;
empty/non-overlapping stages are unmeasured. Validate the analyzer with
`.\tools\Test-VideoFrameTrace.ps1`.

## Audio crackles, silence or A/V drift

To send video only, uncheck **Send audio**, press **Save**, then start a new
cast. That selection requests only video track 1 and skips audio recording,
encoding and receiver playback. It does not change the selected codecs, video
quality or jitter/buffering policy, and cannot change an already reserved or
running session. Cancel/stop that session before starting another.

The protocol still attaches an audio TCP connection for video-only casts; that
connection alone is not evidence of an audio track or audio packets. Verify the
new session's request has `audio.enabled=false` and its acceptance has `tracks:[1]`.
If sound is still audible, distinguish that session's receiver audio player from
old/background playback or another source/route. Video-only casting does not
mute the sender's local media playback.

Check whether audio was enabled, the chosen source is permitted by Android
capture policy, and whether symptoms persist with a comparable source/route.
Statistics' A/V skew is rendered video PTS minus played **source** audio PTS,
not end-to-end latency; unavailable or stale observations are N/A. Device
played frames can include silence. Peak skew is sampled at 1 Hz, not a per-frame
maximum. Neither skew nor underrun counts alone establish audible glitches.

On an identity-verified receiver, with participants' consent, begin a bounded
collection session before reproducing the issue. This uses the device's own
current epoch clock for the initial lower bound, rather than the host clock; it
does not clear the shared log buffer. First check
`adb -s 'verified-receiver-serial' logcat --help` for `-T TIME` with epoch
seconds. Do not fall back to an unbounded buffer dump if unsupported. Choose a
new file in a private directory with appropriate access permissions. In Windows
PowerShell, replace the serial and path, then run:

```powershell
$receiver = 'verified-receiver-serial'
$path = 'C:\private\audio-log.txt'
if ($receiver -notmatch '^[A-Za-z0-9._:-]+$') { throw 'Invalid receiver serial' }
$null = Get-Command adb -ErrorAction Stop
$state = @(& adb -s $receiver get-state)
if ($LASTEXITCODE -ne 0 -or $state.Count -ne 1 -or $state[0].Trim() -ne 'device') {
    throw 'Receiver is unavailable or unauthorized; verify the serial and connection'
}
$time = @(& adb -s $receiver shell date +%s)
if ($LASTEXITCODE -ne 0 -or $time.Count -ne 1 -or $time[0].Trim() -notmatch '^\d{10,}$') {
    throw 'Could not obtain device epoch time; capture not started'
}
$start = $time[0].Trim()
$marker = 'redrain-audio-' + [guid]::NewGuid().ToString('N')
$file = $null
$writer = $null
try {
    $file = [System.IO.File]::Open($path, [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read)
    $writer = [System.IO.StreamWriter]::new($file, [System.Text.UTF8Encoding]::new($false))
    $writer.AutoFlush = $true
    Write-Host "Capture starting. In another window send and check marker: $marker"
    & adb -s $receiver logcat -b main -T "$start.000" -v threadtime ScreenCopyReceiver:I '*:S' |
        ForEach-Object { $writer.WriteLine($_) }
    if ($LASTEXITCODE -ne 0) { throw "adb logcat failed (exit $LASTEXITCODE)" }
    Write-Warning 'logcat exited; capture is no longer running.'
} finally {
    try { if ($null -ne $writer) { $writer.Dispose() } }
    finally { if ($null -ne $file) { $file.Dispose() } }
}
```

In a second PowerShell window, use the same verified serial and private path,
and copy the unique marker printed by the first window:

```powershell
$receiver = 'verified-receiver-serial'
$path = 'C:\private\audio-log.txt'
$marker = 'redrain-audio-COPY-PRINTED-MARKER'
& adb -s $receiver shell log -p i -t ScreenCopyReceiver $marker
if ($LASTEXITCODE -ne 0) { throw 'Could not send readiness marker' }
Select-String -LiteralPath $path -SimpleMatch $marker
```

Only reproduce after the marker appears in the file and the first window is
still running. If it does not appear or adb fails, stop and diagnose rather
than reproduce. Stop capture with Ctrl+C immediately afterward. `CreateNew`
fails if the path already exists and never overwrites it; on failure or Ctrl+C
the handles are closed and any newly created empty or partial file is retained
for review or manual deletion. Do not reuse a path to retry.

The timestamp is rounded down to seconds, so the file may include matching
messages from earlier in that second and between timestamp acquisition and
readiness, as well as messages from other sessions during capture. The initial
`-T` is not a continuous privacy filter: Android log readers may clear the
start-time filter after the first flush, and device clock changes can further
affect the window. It is not a session filter. Review and sanitize the file
before sharing, store it privately, and delete it when no longer needed.

`AUDIO_SERVICE_WINDOW` appears every five seconds of active audio servicing and
on clean worker release, independent of the statistics panel. It reports
`window_us`, native underrun delta/total, inserted silence, discarded PCM,
zero writes and maximum worker-step/input/decode/refill wall durations. Each
reported thread-CPU duration belongs to that stage's maximum-wall-time call,
not an independent CPU maximum.
Without active receiver telemetry, measurements begun before audio starts are
skipped. With receiver telemetry active, a measured step or decode that spans
output start can be recorded in full, including its pre-start wait; the service
window itself begins at output start.
Frames convert to milliseconds as `frames * 1000 / sample_rate`; underrun
increments per second are `native_underruns * 1000000 / window_us`, not
perceptually distinct clicks. `max_refill_gap_us` is time between service calls;
`gap_previous_pre_padding_us` is measured *before* the previous refill.
`gap_last_arrival_age_us` refers to worker admission, not physical network
arrival. Empty/pending observations after service cannot establish what was
available throughout a preceding stall.

`AUDIO_UNDERRUN_HISTORY` and numbered `AUDIO_HISTORY` chunks provide a bounded
512-event history; tuples are `relative_us,kind,a,b` relative to the header's
local monotonic timestamp. Receipt means validated worker receipt, not physical
network arrival. `receive` holds PTS/bytes; `admit`/`dequeue` PTS/queue count;
`decode_begin`/`aac_input` PTS/bytes; `decode_end` PTS/current regulator
buffered PCM frames (not decoded packet count, and excluding retained recovery
slices); `refill`
pre-refill padding microseconds/PCM frames; `silence` accepted frames/PCM
frames; `observe` native counter/queue count. Refill sampling and overwritten
entries limit the history; rate-limited dumps retain throttled increments.
Large wall time with little thread CPU suggests off-CPU delay, including
blocking, GC or scheduling; it does not identify which. An external system
trace may help distinguish scheduling states.

For live evidence, correlate capture timestamps and packet PTS, queue
admission/dequeue, decode/refill and clock/output observations by session,
track and generation. An audio-start queue sample is later than its clock
sample, not an atomic snapshot. A new receiver observation sequence can reuse
the same native timestamp; compare native time/frame separately. A draining
encoded queue can coexist with retained decoded recovery slices; it does not
prove a blocked decoder. Mapping correction is not instantaneous audible-clock
change. Recovery completion proves source-sample submission, not acoustic output.

### External receiver system trace

Only collect a trace with device-owner consent during an authorized reproduction.
Use the already-running receiver and existing debugging/tracing access; do not
change device settings to enable it. If neither route below is available with
existing access, skip this capture.

* **Authorized ADB and Android Studio:** Connect to the already-authorized
  receiver, open **Profiler**, select its running app process and start a
  **System Trace** task attached to it. Start before the relevant interval, then
  stop after the symptom and wait for parsing. In **Past Recordings**, use
  **Export recording** if available; otherwise use the device route below or
  [Perfetto over ADB](https://developer.android.com/tools/perfetto). Profiler
  recordings otherwise last only for the current Studio session.
* **Authorized ADB without Studio:** With Python and the existing SDK's ADB,
  replace the serial and output name below with the receiver's authorized
  serial and a unique local filename. This 20-second, 16 MiB rolling trace
  requests scheduler switch/wakeup events and writes binary output directly to
  a new local file; it does not change device settings.

  ```powershell
  $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
  $receiver = 'RECEIVER_SERIAL'
  @'
  import subprocess, sys
  with open(sys.argv[3], "xb") as trace:
      subprocess.run([sys.argv[1], "-s", sys.argv[2], "exec-out", "perfetto",
                      "-o", "-", "-t", "20s", "-b", "16mb",
                      "sched/sched_switch", "sched/sched_wakeup"],
                     stdout=trace, check=True)
  '@ | python - $adb $receiver 'receiver-audio-UNIQUE.perfetto-trace'
  ```

  Start the reproduction within the recording window. A failed command can
  leave a partial file; do not treat it as a valid trace. Event availability and
  thread/process names depend on the device; check the resulting tracks.
* **Existing device System Tracing UI:** If the app or Quick Settings tile is
  already available, start **Record trace**, reproduce the symptom, then stop
  via the tile or notification. After **Trace saved**, use the notification or
  Files app to save/share the `.perfetto-trace` file. Do not enable developer
  options, add a tile, or change tracing categories or buffer settings. The
  default rolling buffer may retain only 10–30 seconds; stop promptly.
  See [Android's on-device instructions](https://developer.android.com/topic/performance/tracing/on-device)
  and [Studio profiling instructions](https://developer.android.com/studio/profile).

Preserve the original trace under a unique name alongside the matching capture
and build/mode notes; do not overwrite existing evidence. Open it in Studio or
the [Perfetto UI](https://ui.perfetto.dev/) and inspect scheduler/thread-state
tracks for the receiver process and `ScreenCopy-audio` (the displayed name may
be shortened). Match process and thread IDs and the relevant generation/time
window; recovery can create a new worker. Runnable-but-not-running differs from
sleeping or blocked, but unavailable events, buffer loss and trace overhead
limit conclusions. App timing and system trace clocks need a verified local
correlation; do not subtract unsynchronized device or host timestamps.

Neither the app log nor this trace procedure identifies the owner of the
`playbackLock` monitor. A blocked state alone does not establish a lock owner
or root cause. Separately selected `audio.timing.owner` capture spans can
identify observed holders, subject to the gaps and correlation limits below.
Traces can contain system-wide process names and device metadata;
restrict access and review/redact before sharing.

## Pressure, recovery or disconnect

First inspect the sender's automatic report, if one exists, then collect a
paired live capture for a repeatable issue. Check first pressure/failure,
affected track and generation, queue limit/reason, recovery request, READY,
source window/cutoff and completion proof, followed by terminal and cleanup
coverage. Recovery v2 is negotiated for the session; recovery transactions
are per-track. It does not
remove the underlying load or transport problem. Older/non-negotiating peers
use strict congestion behavior, not recovery v1 fallback.

Sender queue byte limits are per track (video 8 MiB, audio 512 KiB); its
500 ms PTS-span and admission-wait bounds are separate. `PTS_SPAN` pressure
need not mean memory exhaustion. Receiver encoded queue metrics exclude
dequeued codec work and decoded PCM; audio admission pressure can include
retained decoded bytes and decode reservation. `origin=PEER` identifies a
peer-reported error, not a local decoder failure. Per-lane in-flight age and
write duration can indicate backpressure but cannot distinguish Wi-Fi from
receiver admission/drain stalls without additional evidence. Kernel TCP
metrics are unavailable. Do not infer a network or scheduler root cause from
one blocked operation or a missing completion.

## Automatic sender report

When the sender is idle, Settings → Advanced → **View or clear session
telemetry reports** shows summaries, exports through the document picker and
deletes reports.
Automatic telemetry is independent of statistics and live diagnostics. The
sender retains bounded recent metadata and saves at most one report per cast
on first pressure or retained failure; a normal successful cast without either
writes none. It retains up to 60 seconds/480 KiB per endpoint, freezes bounded
prehistory and gathers post-trigger and terminal context. Up to eight reports
of 2 MiB each are retained for seven days, with expiry checked on later access
or save. The receiver publishes through the cast control connection but
does not store or offer reports. Older/incompatible peers cast normally without
receiver telemetry; there is no automatic retrieval fallback.

In an export, use its `vocabulary`, including `telemetryCausalLayouts` and
pressure layouts, to decode rows by role, kind, stage and detail count. The
pinned `AUDIO_QUEUE`/`REJECTED` row layout is not in those pressure layouts;
see [Automatic first-fault causal context](protocol/PRESSURE_INCIDENTS.md#automatic-first-fault-causal-context-telemetry-v2).
v2 causal rows include current checkpoints, first-fault context and bounded
transitions; their independently timestamped caches are not an atomic snapshot.
The sender's settled outcome can differ from the receiver's first failure.
Version 1 stored batches are readable, but have no v2 causal rows.
Publication sequence gaps mean missing telemetry, not missing media. A `final`
report revision does not mean complete receiver evidence: final batch and
matching terminal sequence plus successful cleanup are required. Check gaps,
omissions, dropped/coalesced records, skip reason and source completeness.
Absence of a report or observation does not prove a successful cast or cleanup.

## Collect live paired v2 diagnostics

This is separate from automatic telemetry and is **off by default**. Anyone on
the LAN or VPN who can reach the device can read bounded diagnostic metadata and control captures while enabled;
traffic is plaintext and unauthenticated, with no identity guarantee. Use a
trusted LAN and VPN and obtain participants' consent. Diagnostics do not contain media
payloads but timing and session metadata, logs and exports may still be sensitive.
Store privately, review before sharing, and delete when no longer needed; there
is no automatic upload. Do not port-forward or scan.

1. Before the cast, on the sender open Settings → Advanced, read the warning
   and select **Allow unpaired LAN / VPN diagnostics (this process only)**, then
   Save. Once applied, reopen Settings → Advanced → **LAN / VPN diagnostics endpoints
   / status** and note the appropriate displayed endpoint, or use the local
   `DIAGNOSTIC_LISTENER_STARTED` log event. The status dialog is read-only.
   Settings are unavailable during a cast. Consent is process-local;
   backgrounding alone does not revoke it. Disabling access clears retained data.
   The listener closes on a network or address change; re-enable it explicitly.
   VPN routes and policies must permit the collector to reach the displayed address.
2. Alternatively, with the sender's known LAN or VPN IPv4 address, query its live
   endpoint once (not a port scan):

   ```powershell
   python -B .\tools\collect-diagnostics.py --discover-sender 192.168.1.135
   ```

   This is an unauthenticated unicast hint; it requires an updated running
   listener. Older processes without the responder cannot be discovered this way.
3. Before starting the cast, run:

   ```powershell
   python -B .\tools\collect-diagnostics.py --lan `
       --sender PHONE_HOST:PHONE_PORT --duration 180 --output .\diagnostic-capture-001
   ```

   Start the cast when prompted. START arms only the next cast; the 1–180 second
   lease includes waiting/setup. Both peers need v2 proxy support. There is one
   sender connection, no receiver diagnostics listener/discovery or existing
   session attach. The receiver preference is not changed. Default flags select
   known advertised groups, except opt-in `tail.details`, `audio.timing`,
   `audio.timing.work`, `audio.timing.detail`, and `audio.timing.owner`; `capture_metadata`
   records the selection. Explicit comma-separated `--sender-flags` and
   `--receiver-flags` are strict; receiver `video.timing` requires
   `video.progress`, and unsupported flags can fail setup. Optional
   `tail.details` requires a receiver known to support it.
   To request receiver audio timing including startup wall/CPU work boundaries, add:

   ```powershell
   --receiver-flags session,recovery,audio.transport,audio.queue,audio.output,audio.timing,audio.timing.work,audio.timing.detail,video.progress,transport.health
   ```

   Both peers must advertise the requested flags; there is no silent fallback.
   `audio.timing.detail` requires `audio.timing.work`, which requires `audio.timing`.
   To request bounded playback-lock ownership intervals in a paired receiver
   capture, also append `audio.timing.owner`; it requires all three timing
   flags. Match complete begin/end spans as described in the protocol, and
   treat activation gaps, loss and unmatched endpoints as unknown. Java thread
   IDs do not necessarily equal Perfetto OS TIDs. An overlapping observed hold
   does not establish that the audio callback was blocked instead of descheduled.
   Detail includes write/decode CPU, queue and native capacity samples plus
   sampled boundary overhead; see the protocol for limitations. Omitting work preserves the legacy
   v1 trace, including when a legacy sender connects to an updated receiver.
   On audio-led builds, a missing `firstVideoUs` during audio startup is expected
   and does not mean the audio gate is waiting for video. Older captures may
   reflect the former first-video gate; inspect the decision and clock fields.
   Ordinary `PLAYED_AUDIO` trims discard only source samples behind actually
   presented audio, using qualified native frames mapped to original, unslewed
   source samples—not the sync clock's fallback floor or phase slew. Native
   timing/mapping loss disables trimming; source gaps, overlap/reordering or FIFO
   drops disable it for the output lifetime. Admission always counts the raw
   queued/incoming PTS span, including proved played packets: an earlier FIFO
   decode can invalidate proof before a later dependency is processed.
   Post-decode trimming still benefits safely proved samples, but stale encoded
   backlog can hit the audio queue's one-second PTS span, 256-packet limit, or
   shared 512 KiB encoded/retained-input byte cap first. These are independent
   bounds, not a guarantee of one second of audio for every packetization.
   Negotiated track recovery or legacy wait/congestion semantics handle that
   pressure; dependencies are not silently dropped. A large span or
   newer arrivals do not prove staleness: if output is stalled and all queued
   PTS are ahead of its fixed presented position, track-local recovery remains
   necessary. Do not infer that live trimming fixes that capture.
4. The collector creates `paired.jsonl` without overwriting it. During normal
   collection it drains frozen evidence with a bounded 120-second deadline.
   On error or Ctrl+C, already written output is preserved, but the collector
   closes without a further frozen-evidence drain. The file may be incomplete
   even if some or all pages were already drained. Frozen data remains for at
   most 120 seconds from freeze while access stays enabled; the deadline is not
   renewed by retrieval. The current collector cannot reattach. A separate
   v2 client can reconnect with HELLO, use STATUS to identify the frozen pair,
   and FETCH both role cursors as described in the
   [protocol](protocol/REMOTE_DIAGNOSTICS.md#fetch-pagination-and-drain);
   preserve existing output and reconcile overlapping pages and loss before
   judging completeness. This is a protocol option, not a collector command.
   A retained pair blocks START until cleared or expired. Do not CLEAR or
   disable access until evidence is preserved or its loss is explicitly
   accepted; disabling clears the in-memory pair. Loss of local-network
   permission or a change to the active network, its eligible transport, or
   the bound address also revokes access and clears the pair before its
   retention deadline. Disable access when finished.

### Interpret collection quality first

Inspect `capture_metadata`, `started`, `status`, `event`, `retention`,
`sequence_completeness`, `ended` and any `clock_unavailable` records. Source
envelopes identify role, session, capture and clock; compare only matching
identities and retain source, proxy-intake and retention loss. `status` contains
bootstrap, selected flags and detailed receiver outcome; `setupFailure` gives
sanitized code and provenance, not peer text. `confirmed:false` is a
sender-reconstructed outcome, not receiver confirmation of cleanup. Missing
bootstrap or terminal, an unconfirmed/truncated receiver tail, failed drain,
or partial output is not complete paired coverage. Even a confirmed terminal
does not imply lossless records: admission drops have no sequence ticket, and
loss envelopes need not be exact contiguous ranges. `sequence_completeness`
is separate from terminal confirmation and cannot establish losslessness.

When selected, `audio.timing` adds numeric receiver-local event batches and a
separate first-pressure marker in receiver retention metadata.
Work boundaries and worker CPU snapshots additionally require `audio.timing.work`.
For recovery-clock lag, the selected work trace now includes cached mapping/native
snapshots at recovery begin/end and detach (ordinary `AUDIO_OUTPUT` records
at point `recovery`; also select `audio.output`, see the protocol).
Compare successive epochs: stale observation timestamps with unchanged native
progress locate a stalled/unobserved output interval; fresh progress with an
incorrect base discontinuity instead points to mapping. A future video release
deadline alone proves neither: compare PTS delta with base-time delta first.
The first-submission callback's outer stage 8 contains two adjacent nested stage-8
intervals: lock acquisition, then critical section. A dominant first interval
localizes delay before acquisition; a dominant second interval localizes it
inside the callback. Low CPU still cannot identify a monitor
owner or distinguish contention from descheduling. Missing snapshot members,
loss across an interval, or stale observations require more evidence, not a
behavior change. Older strict work-v2 sender/collector validators remain compatible;
do not infer physical playback from head/presentation counters.
The sender keeps
at most 768 KiB of these raw batches, favoring receiver timestamps from five
seconds before to ten seconds after the marker. A missing marker, eviction,
producer or proxy loss, disconnect, or unknown tail means incomplete evidence;
the window is not guaranteed. Packet ID zero means unknown attribution, notably
AAC output and device writes. An accepted write is not proof of audible output.
See the [protocol](protocol/REMOTE_DIAGNOSTICS.md#audio-timing) for the event
schema and actual retained bounds.

Each endpoint has an independent monotonic clock. Never subtract sender and
receiver timestamps directly. The collector records sender CLOCK before START
and, if available, receiver CLOCK after coverage; each sample bounds the device
offset relative to the collector with round-trip uncertainty. Derive a
cross-device mapping only from appropriate samples and carry combined
uncertainty; these single samples do not establish drift over the cast.
Source PTS aids media correlation but is not synchronized device wall time.
Operation summaries aggregate calls, while failures and bounded slow examples
are separate; recorder samples may show in-flight operations without proving a
hang. Loss, sampling and coalescing mean missing observations are not proof of
non-execution, success or failure.

## Tests and limits

See [collector fixture generation and tests](tools/REMOTE_DIAGNOSTICS.md).
Host/JVM and loopback tests do not establish real-device overhead, hardware
playback, physical scanout or live interoperability. Reproduce only on idle,
authorized devices; preserve the evidence and report remaining uncertainty.
