# Unified SCPY v3 contract

Authority: current RedrainCast Windows `ScrcpySend.Core/SenderSession.cs`,
`ScrcpyReceive.Core/ReceiverServer.cs`, `Protocol.cs`, and `Discovery.cs`.
The sibling checkout is not modified by the Android build or protocol probe.

## Optional uncapped capture FPS

Updated Android control HELLO advertises Boolean `uncappedFps:true`. The
receiver advertises its support even to legacy senders; unknown HELLO members
remain additive. Offered/acknowledged values, when present, must be Boolean.
Senders may request `video.maxFps:0` only after the receiver explicitly
advertises support. Absence or false means an explicit positive cap is required;
do not silently replace Uncapped with a guessed cap. Current older Windows and
Android receivers reject zero and remain compatible with capped requests.

`maxFps` accepts exact integers 0..240 on updated Android: zero is uncapped,
positive values are capture limits, not measured throughput. Uncapped removes
both GPU pacing and the encoder maximum-FPS key; the independent encoder
frame-rate hint remains 60. Preflight still validates size/alignment but does
not test a fictitious zero-FPS rate. Receiver decoder configuration omits an
unknown frame-rate hint for zero, and statistics display Uncapped.
Existing positive-cap wire fixtures and behavior are unchanged.

## Optional native HDR10 video

This additive wire-v3 extension uses root `receiveCapabilities` in discovery
and in receiver HELLO only when sender HELLO offers integer `hdrVideoVersion:1`.
Capabilities have `version:1`, `videoModes`, `nativeHdrAvailable` (Boolean or null)
and Boolean `hdrToSdr`. The supported HDR tuple is exactly
`{"codec":"h265","profile":"main10","dynamicRange":"hdr10","colorPrimaries":"bt2020","transfer":"pq","matrix":"bt2020nc","range":"limited","bitDepth":10}`.
Legacy SDR modes are implicit. Unknown positive capability revisions negotiate
no HDR; malformed known fields, duplicate JSON keys and coerced integer/Boolean
values are rejected. Both `receiveCapabilities` and `videoColor` envelopes are
limited to 2048 original UTF-8 bytes, including whitespace and unknown members,
before revision handling. Discovery retains its 1024-byte total limit.

SESSION_REQUEST root `videoColor` contains `version:1`, that exact tuple and
optional `staticMetadata`. SESSION_ACCEPT echoes the selected color. Every
video STREAM_CONFIG generation repeats the same selected color and metadata;
missing, changed or unsolicited HDR color is an error. Old peers remain SDR,
never an optimistic HDR fallback. Selection is revalidated against current
output availability and exact decoder size/rate support before readiness.

Static metadata uses `masteringPrimaries:[Rx,Ry,Gx,Gy,Bx,By,Wx,Wy]` integers
in 0..50000 with each normalized xy pair satisfying x+y <=50000,
maximum mastering nits in 1..10000, minimum in ten-thousandths of
a nit in 0..min(65535,maximum*10000), and content/frame-average light levels in 0..10000
(zero means unknown). When content light is positive, frame-average light must
not exceed it; unknown content light permits a positive frame-average value.
Invalid light relationships are rejected before ACCEPT and descriptor output,
never clamped. The decoder receives the 25-byte little-endian CTA-861.3
descriptor with ID zero, not raw SEI. The shared wire schema rejects minimum
luminance above 65535 during metadata/color parsing, before ACCEPT. The
descriptor also defensively rejects unrepresentable typed values explicitly,
never clamps or wraps. A bounded HEVC SPS syntax walker verifies Main10 10-bit 4:2:0,
the declared raster and exact raw VUI BT2020/PQ/BT2020nc limited-range values
(9/16/9). Sub-layers, reference sets/pictures and scaling-list traversal are
bounded before walking untrusted values; no reference-picture arrays are
allocated from stream counts. Supplied static SEI is checked against selected metadata.
Only encoded mastering chromaticities may differ by at most one integer 1/50000
unit, accounting for hardware requantization of Media Foundation float primaries;
encoded xy pairs must still be normalized and in range. Mastering luminance and
content/frame-average light levels remain exact. Request/configuration/accept
metadata equality is unchanged; this allowance applies only to encoded SEI.
The genuine NVIDIA synthetic VPS/SPS/SEI regression uses a reconstructed metadata
fixture, not a retained phone capture, and is not proof of real-device HDR playback.
Unannounced VPS/SPS/PPS changes are rejected. Decoder output color is validated
before rendering. This verifies the input signaling, not physical HDR scanout.
The shared canonical schema/metadata fixture is `fixtures/hdr-video.json`.

Android uses a secure SurfaceView and guarded attached-display/current-mode HDR
queries (API30 baseline; per-mode HDR types API34). Display capability does not
prove active HDR scanout or expose the user's hidden disabled-HDR settings.
Hardware decoder capabilities are cached outside the broadcast loop. Android
uses `nativeHdrAvailable` with the advertised decoder tuple to describe the
reported eligible native presentation path, not proof that HDR is currently
being emitted. A missing public active-HDR getter does not disable an otherwise
eligible path. OEM/user HDR switches and physical emission remain unverified.
Android currently advertises `hdrToSdr:false`: API31's optional decoder SDR transfer
request must be confirmed by input-format acknowledgement and SDR output color,
not inferred from API level. Native HDR display loss stops rendering/fails the
session with restart guidance rather than silently clipping. Codec substitution
requires a new session; existing sequential generations/recovery proofs remain.
HDR playback and SurfaceView rotation/overlay behavior need real-device testing.

Explicit `hdrVideoVersion:1` peers have a bounded 45-second post-HELLO
SESSION_REQUEST preparation window. Initial socket HELLO, legacy/future peers,
track setup, liveness and recovery retain their existing deadlines. Preparation
PING/PONG does not extend the absolute 45-second window. The absolute deadline
is checked on each preparation message, including SESSION_REQUEST, as well as
by periodic maintenance, so a late request cannot win a timer race.

## Optional receiver playback statistics

This additive v3 extension matches Windows `ReceiverOptions.cs`; it does not
change framing, flags, mandatory settings or the three TCP connections.
Sender HELLO may offer
`"receiverOptions":{"version":1,"supported":["playbackStats"]}`.
An implementing receiver acknowledges only the intersection of offered and
actually implemented names with the same shape. An empty intersection omits
the entire member; old senders receive no acknowledgement.

Only a checked sender preference and a mutual acknowledgement produce
`"receiverOptions":{"version":1,"playbackStats":true}` in SESSION_REQUEST.
Otherwise the whole member is omitted. A requested but unavailable option
produces a bounded nonfatal sender notice retained with Streaming. The saved
`ShowPlaybackStatistics` Boolean defaults false and is not implicitly serialized
by Settings.request(). Selection is immutable per session, including native
reconfiguration, and never enables a future session.

Version is an exact positive signed-int32 JSON integer token (not `1.0`, `1e0`,
a string, zero, negative or overflow). Supported is a required array in capability
envelopes, with at most 16 distinct strings of 1..64 non-control characters.
Envelopes are at most 2048 original UTF-8 bytes and 16 distinct members, whose
names are also 1..64 non-control characters. Duplicate envelopes/members/names
are protocol errors. Known selection fields require actual JSON Booleans even
when unnegotiated or on a future revision. Unknown bounded names/members are
ignored; unknown positive revisions negotiate none and preserve legacy casting.
Raw strict parsing preserves duplicate, number-token and whitespace byte bounds.
The portable raw fixture is `fixtures/receiver-options.json`.

The Android display reads immutable typed caches at 1 Hz, not diagnostic strings
or UI-thread native queries. Actual presented counts/PTS come from MediaCodec's
frame-rendered callback; scheduled releases are labeled separately. Audio source
PTS maps actual played source content through compensation/filter delay and
silence, not the device frame count. Sync is signed last rendered video PTS minus
played source-audio PTS, positive for video ahead, not end-to-end latency.
Each PTS carries its original monotonic observation time: MediaCodec's rendered
event timestamp for video, and the existing source-clock observation timestamp
for audio (not its later statistics-publication time). Compare only pairs within
10 ms, both no older than 250 ms; unknown, stale, future or noncomparable pairs
yield N/A without entering the peak. No source-clock extrapolation or new native
query is introduced. A valid comparison has up to 10 ms observation separation.
First rates, unknown metrics and disabled audio are N/A; a later idle delta can
legitimately be zero. Encoded queue bounds are not decoded PCM/native padding.
Recent renderer skipped percentage is skipped / (actual presented + skipped);
an empty or unavailable denominator is N/A, not receive-minus-decode loss.
Encoded waiting queues exclude packets already dequeued into codec work.
The connection table's Last activity is cached monotonic age since the last
fully received valid video/audio packet, or the last valid control message.
It is N/A before a packet arrives and does not reuse oldest queue admission age.
Media RTT and control Queued/Span are not applicable (an em dash), not zero.
RTT uses only a matching locally generated heartbeat nonce and monotonic send
time; peer PING is not an RTT sample and no new probes are generated.
Session, generation, epoch, counter regression, recovery and hidden/background
transitions reset rolling baselines. Stop, failure and retirement clear the view.
Default-off and hidden sessions do not run a recurring statistics UI timer.
The top-left translucent overlay takes no focus/touch and uses 16sp normal or
10sp dense diagnostic text, still honoring Android font scaling. This replaces
the earlier 14sp minimum with the user's requested smaller-font presentation.
No network, codec, quality, buffer or audio-rate limit changes.
The full aligned layout uses four equal KPI cells, generation totals, actual
label/value grids and a five-column connection table. A phone-sized viewport
retains every metric with shorter labels, content-measured label widths and
explicitly shared millisecond units in connection headers. Values and units
are not removed; the full shared model remains available independently of these
display abbreviations. Landscape phone mode is bounded to 65% viewport width.
The complete layout remains available in a separate scrollable Full playback
diagnostics view through Advanced settings or a long press on receiving video.
The overlay itself still intercepts no input; the existing video owns the
explicit long-press action. No wire option or remote command is added.
Native tests require the full phone panel to cover less than half the actual
reported Galaxy viewport at 115% font scale, in both orientations, with no
clipped inner text, no ellipsis and no wrapping of short field labels.
Full diagnostics can scroll instead of clipping lower metrics. It keeps
receiver-canvas pixels distinct from its own dialog width, clears on
background/Stop, and does not create a second sampling timer or native query.
No string-space padding or automatic font shrinking is used.

Display dimensions are diagnostic-only, with no wire fields or render-mode
requests. The main video section includes Encoded size and Display mode
(Display px in the compact panel).
Render target is retained only in full display diagnostics and accepts only
verified compositor-target dimensions; the current TextureView/SurfaceView paths
have no such measurement and report N/A. Neither STREAM_CONFIG size,
TextureView/SurfaceView bounds nor display mode is a
valid fallback for that field. Display mode uses the attached display's
active `Display.Mode.physicalWidth/physicalHeight`, cached on display/lifecycle
events rather than queried by the statistics refresh. These are Android-reported
mode dimensions in their mode orientation, not a native-imager/optical-resolution
or scanout guarantee. Full display diagnostics retain App view size separately.
Footer headers and values occupy the same four grid columns; values are
start-aligned directly beneath State, Generation, Playback epoch and Occluded.
The supplemental Scheduled frames / Audio epoch / Device played row uses the
same stacked-grid mechanism with three equal columns and start-aligned values.

The display-only golden manifest `fixtures/playback-statistics-display.json`
uses local `displayContractVersion: 2`, `wireContractChanged: false`. The
Windows canonical comparison manifest is copied without schema changes to
`fixtures/playback-statistics-display-contract.json`. Android retains its
existing platform dimension fields; both begin the video group with
`videoCodec`, `videoDynamicRange` (Stream range), `videoDecodeFormat`
(Profile / depth), and `presentationOutput` (Output). The three new fields
have no numeric unit. Technical identifiers stay technical; new labels,
output words, unknown text and bit-depth text use Android string resources.
The existing compact layout uses localized Range/Prof/depth captions and
removes only separator spacing in these values; full labels and technical
identifiers remain available in the model and accessibility descriptions.
Compact panels use 2dp outer vertical insets (normal panels retain 4dp) to
accommodate the additional rows without changing font size or removing metrics.
Compact landscape panel width is capped at 50% of the viewport so the taller
video metadata group still leaves more than half the player area uncovered.
Within that panel, video and audio receive column shares of 1.35 and 1.25.
Connections and queues occupy a full-width table below them rather than a
narrow third column. Shared content-sized columns align all channel rows and
headers as live queue sizes change, without reducing text size or removing units.
When larger accessibility fonts exceed the available width, the shared columns
shrink and cells wrap rather than clipping the rightmost metrics.
Compact landscape preserves both two-row summary grids side by side, and puts
the unchanged source-skew explanation under audio to use the shorter column's
space rather than growing the full panel.

Incoming HDR10 is validated independently of presentation; PQ/BT.709 comes
from actual decoder output color metadata. Missing SDR metadata remains N/A.
Profile is read from decoder output `KEY_PROFILE`; component depth is reported
only for explicit decoded planar/semi-planar/flexible 8-bit YUV420 or P010 (10), not
opaque Surface formats, profile assumptions, capabilities or buffer precision.
Unknown profile/depth components independently display N/A.
Output is the last matched successful frame-rendered decoder/Surface path,
not a scheduled release or the latest format-change event. Validated
BT2020/PQ/limited direct output can report HDR output, which is not proof of
physical HDR emission or OEM display state. SDR output needs actual SDR
transfer metadata. Production does not request tone mapping; the optional
API31 request/format validation lacks independent runtime mapping proof and
does not publish SDR (tone-mapped). Unknown presented paths clear any previous
classification when rendered. A bounded, player-owned PTS cache keeps ambiguous
duplicate PTS and unmatched/evicted observations unknown, ignores duplicate or
older callbacks, and rejects retired callbacks. Replacement generations start
without old output. Malformed optional statistics metadata is logged and remains
unknown rather than failing otherwise valid playback.
Sampling reads these typed caches without MediaCodec/display queries or SPS
parsing on the UI timer.

The Android manifest
defines common field IDs, labels, order, units and null semantics. It is not a
wire extension or a shared runtime dependency. Native readings are not expected
to have equal numerical values across platforms.
This manifest records the agreed comparison target; it does not assert that a
Windows UI build containing that layout has been deployed. The protocol is
unchanged and no new Windows build is required to validate these Android-only
display changes.

| Item | Aligned meaning | Honest platform difference |
|---|---|---|
| Presented FPS / Presented | Source-picture presentation progress, separate from decoded/scheduled | Android counts MediaCodec frame-rendered callbacks. Windows counts successful presentation API calls, not physical scanout. |
| Queued / Span | Waiting encoded packets only, excluding dequeued/in-flight codec work | Platform admission byte/count/span bounds remain unchanged and need not be equal. Windows' existing diagnostic Snapshot may still include in-flight work; the aligned overlay needs its waiting-only view. |
| Last activity | Age since last received media packet / valid control message | Not oldest queued-packet age, source freshness or a health verdict. |
| A/V skew | Last presented source video PTS minus actually played source audio PTS | Android's played-source position is read from existing typed clock observations; no extra native output query. |
| Peak observed skew | Absolute maximum of the overlay's 1 Hz samples since its baseline | Not a render-thread/per-frame maximum. Any higher-frequency Windows diagnostic peak remains a separate measurement. |
| Playback epoch | Equivalent native playback-instance lifetime, if available | Android reports N/A; audio epoch is not renamed as a full playback epoch. |
| Occluded | Cached platform occlusion status, if available | Android reports N/A, not false. |
| Display mode / diagnostic Render target | Reported active mode dimensions / verified compositor buffer in full diagnostics only | Video target is N/A unless measured, not inferred from view size. Android mode dimensions do not establish optical projection resolution. |
| Android details | Scheduled frames, Audio epoch, Device played, full display diagnostics | Device played includes silence and is not source content time. App view size is diagnostic-only, not rendered pixel resolution. |

Common video codec labels are H.264, H.265 (HEVC), AV1; audio labels are PCM,
Opus, AAC, Off. Network loss remains N/A until measured. Recent silence/discard
values are sample-frame-derived milliseconds; renderer Skipped is not loss.
There is one visible Peak observed skew, labeled "sampled 1 Hz"; the latest
pair of samples is not presented as a true interval maximum.

## Connections and lifecycle

UDP 27183 advertisements are JSON objects with `protocol:"screencopy"`,
`version:3`, `name`, TCP `port`, `state:"available"|"busy"`, and optional
`instanceId`. Use the datagram source address, never an advertised host address.
TCP listener ports are dynamic; 27184 is not an implicit receiver endpoint.

1. Control socket: sender HELLO at session zero, `role:"sender"`, `versions:[3]`,
   `name:"RedrainCast"`. Receiver replies HELLO with receiver role/name/versions.
2. Sender sends SESSION_REQUEST (type 3) with a positive session ID and explicit
   video/audio settings. Receiver replies SESSION_ACCEPT (4), exact enabled
   `tracks:[1]` or `[1,2]`, and a fresh 64-hex-character `mediaToken`.
3. Open **both** video and audio TCP connections to that same endpoint, even for
   video-only sessions. Each sends HELLO at the accepted session ID with
   `role:"media"`, `channel:"video"|"audio"`, and `token:mediaToken`. Each receives
   HELLO acknowledgement containing its channel. Tokens bind to the live control
   session; duplicate, stale and wrong-token attachments fail.
4. STREAM_CONFIG (6) and VIDEO_FRAME (8) use only the video connection (track 1).
   Audio STREAM_CONFIG and AUDIO_PACKET (9) use only the audio connection
   (track 2), and only when audio was accepted. Readiness (7), status, heartbeat,
   stop, error, and session-end use only the control connection.
5. Receiver configures the requested decoder/output before STREAM_READY
   (`ok:true`, matching track/generation). Media transmission waits for readiness
   of every enabled track. A codec/output failure is not readiness.
6. Independent 2-second PING/PONG liveness and bounded startup/write deadlines
   remain active during media congestion. STOP (14, `reason`) is acknowledged by
   SESSION_END (15) after retirement. Fatal ERROR (11, `code`, `message`, `fatal`)
   ends the session. A lost media connection terminates its owning session.
   Cleanup closes all three sockets and retires playback/capture ownership.

Control connects before the token-bound media lanes. Graceful teardown keeps
its reader alive after Stop or media EOF and closes control last. With negotiated
`streamRecoveryVersion:2`, orderly Stop abandons unsent media/control but finishes
each already-selected/started media frame under its original 10-second write
deadline before sending the sender's terminal frame and retiring media sockets.
The Android sender bounds pending orderly drain/terminal delivery at 22 seconds.
After its own STOP is successfully written, its peer acknowledgement wait remains
two seconds: started media has already drained. Legacy and fatal teardown retain
their original two-second total bound.

For receiver-initiated v2 Stop, the 22-second SESSION_END acknowledgement window
starts only after the complete STOP control frame is successfully written/flushed,
not when it is enqueued or an earlier control frame completes. This allows two
started 10-second media-frame drain windows plus terminal acknowledgement.
The receiver retires playback immediately but continues reading/discarding whole
framed media without admission, decoder recreation, rendering or recovery.
Every started media/control frame retains its original 10-second absolute I/O
deadline; idle media reads have no frame deadline. Control's normal 10-second idle
timeout must not truncate the v2 acknowledgement window. Actual I/O failures,
truncated started frames, write deadlines and absent acknowledgements are fatal,
not intentional cancellation. No terminal message reuses a partially written
frame. Legacy receiver-initiated Stop retains its two-second cutoff.
Pending receiver STOP delivery has its own 22-second bound, with each selected
control write still limited to ten seconds. Crossed local Stops acknowledge
terminal intent without adding another terminal frame; retirement still waits
for the already-serialized receiver STOP write to finish successfully.
Local and peer Stop intents are arbitrated atomically before closing media or
choosing a terminal frame. A queued fatal ERROR supersedes an unsent orderly
terminal and cannot be replaced by STOP/SESSION_END. Sender media EOF received
while its terminal write is completing waits for that write's result: successful
delivery permits peer media retirement, but a failed write remains fatal.
Stop halts installed workers before waiting for serialized recovery retirement,
outside the playback-state lock. An in-flight replacement must recheck ownership
and stopping state before installation or readiness publication.
Intentional local startup cancellation can abort transport before playback is ready
without requiring an acknowledgement on that deliberately closed connection.
This exception records the startup-abort intent before closing; a generic
closed control socket or an unexplained peer EOF is not exempt.
Receiving v2 STOP after media attachment still drains started configuration/media
frames before SESSION_END, even before initial STREAM_READY. Capture startup is
cancelled without using the local-startup transport-abort exception.
A media transport failure is provisional: a valid fatal receiver ERROR received
during draining supersedes that fallback, but never an earlier capture, codec or
congestion failure. Matching user-stop cancellation stays nonfatal.

For handled receiver playback/media failures, ERROR is queued before media
retirement. A serialized receiver retirement worker cleans up playback, drains
the control writer and closes control last, waking the reader. Broken
control transport and hard watchdog deadlines still force aborts. These rules
cannot guarantee delivery after a native crash, OS kill or network loss.
Sender diagnostics retain the receiver error code/origin without dumping
arbitrary peer text or application payloads; the bounded error message remains
available to the local user.
Receiver media EOF leaves a 500 ms control-priority grace interval for a Stop
already in flight on the independent control socket. Media admission closes
immediately; the session lease remains held. Without a terminal control message,
the existing timer reports an explicit media failure after that grace instead
of hiding the disconnect or waiting indefinitely.

Receiver session errors belong only to the connection that acquired the active
session. A connection that ends before SESSION_REQUEST must not replace the
receiver's status or report that a cast disconnected, including immediately
after a clean Stop. Setup protocol failures still receive ERROR when possible
and produce a structured connection diagnostic. Unexpected EOF in an owned
session remains fatal; this does not relax framing or disconnect detection.
A new receiving session clears the previous session's UI error. Cleanup alone
does not clear a genuine failure from the session that just ended.

There is no single-socket media mode, negotiated transport override, or old-app
fallback. A receiver role does not obtain a MediaProjection token.

The unified foreground home starts an OS-assigned-port listener and discovery
automatically. Listening alone does not acquire the receiving-session lease.
On Android 17/API 37+, local-network permission is required before discovery,
outgoing connection or incoming listener operation. Denial keeps these stopped;
grant resumes foreground readiness without requesting capture or microphone.
This OS consent gate does not change any HELLO or wire capability.
Outgoing sharing reserves the mutually exclusive role before consent, retires
the listener, and resumes foreground listening only after cancellation or full
sender cleanup. Receiver instance identity survives listener restarts so
discovery can suppress stale self advertisements even when ports change.
The outgoing reservation records when Android's permission/consent flow starts.
Activity recreation cancels an unlaunched reservation without restarting consent;
an already-launched flow retains its request ID for the platform result across
configuration changes and non-finishing Activity destruction. Explicitly finishing
the Activity cancels its pending consent. Stale Activity cancellation cannot release
or overwrite an adopted service request.

Independent media connections must remain independent during bounded admission:
validate/capture track ownership under the receiver lifecycle lock, then wait
for that track's queue outside the shared lock. Per-track socket readers remain
serial, and track shutdown rejects/wakes pending admission. Do not retain a
single-socket read-loop lock around blocking queue offers.
Worker replacement is serialized by the playback-operation lock. Detach retired
workers under the shared lifecycle lock, then close/join them outside it so their
output callbacks can finish. Recheck session ownership before installing and
acknowledging the replacement.

## Framing

All integers are big-endian. Every packet has a 40-byte header:

| Offset | Bytes | Field |
|---|---:|---|
| 0 | 4 | ASCII `SCPY` |
| 4 | 2 | Version 3 |
| 6 | 2 | Packet type |
| 8 | 4 | Payload byte count |
| 12 | 4 | Flags |
| 16 | 8 | Nonnegative session ID |
| 24 | 4 | Track |
| 28 | 4 | Generation |
| 32 | 8 | Signed microsecond PTS |

Control JSON is strict UTF-8: duplicate names, non-integer JSON numbers,
out-of-range signed 32-bit JSON integers, malformed input, and unknown/reserved
packet types fail. Untracked packets use track/generation zero and PTS -1.
Configuration/readiness are tracked but use PTS -1. Media uses positive track
and generation and nonnegative PTS. Limits are 64 KiB control/configuration,
4 MiB video and 256 KiB audio. Media payloads cannot be empty.

Video configuration carries requested `codec`, fixed `width`/`height`, and
canonical Base64 `csd` buffers (Annex B for H.264/H.265). Audio configuration
matches the accepted codec at 48 kHz; RAW is stereo `s16le` with empty CSD.
Canonical OpusHead delay/preroll and AAC AudioSpecificConfig are validated.
PTS, dependencies and codec choices are not rewritten to conceal congestion.

## Fixed-natural rotation extension

Android sender HELLO offers Boolean `videoRotation:true`; a supporting receiver
acknowledges Boolean `videoRotation:true`. Without this capability the unified
sender returns explicit `UNSUPPORTED_CONFIG` before starting capture.

Each video picture's flags contain bit 0 for keyframe and bits 1-2 for clockwise
presentation quarter-turns (0, 90, 180, 270 degrees). Rotation is associated with
that encoded picture's header PTS, including repeat frames. There is **no
separate JSON timestamp envelope**. Encoded payload and encoder raster stay
unchanged through phone rotations.

The sender's GPU samples natural coordinates, including off-center crops, using
Android physical-to-logical conventions. Android routes accepted SDR
(`videoColor` absent) to the existing TextureView path, applying that picture's
rotation and aspect-preserving fitting. Accepted HDR (`videoColor` present)
uses the framework-owned secure SurfaceView directly, never TextureView, and
only supports zero picture rotation. Nonzero HDR picture rotation explicitly
logs/fails `UNSUPPORTED_CONFIG` before decoder input dequeue/queue and output
release rather than silently
ignoring it or converting HDR to SDR. Rotation extension acknowledgement covers
the SDR presentation path; it is not a claim of rotating native HDR output.
The decoder is configured with KEY_ROTATION=0 for every initial format.
Both surfaces remain attached across mode selection; an opaque SDR panel covers
inactive native output, while HDR makes that panel and its TextureView
transparent/non-opaque. MediaCodec retains ownership of HDR buffers/crop.
Codec retirement precedes owned TextureView Surface release; the framework
retains ownership of the HDR holder Surface. Late UI callbacks must
also match the current surface identity; retired destruction/availability
callbacks cannot replace or retire a newer epoch. Presentation callbacks must
still own the live session/generation. On portable displays, automatic Activity
orientation follows the displayed aspect and explicit orientation wins. Decoder
setup/recovery retains the last picture rotation and does not update automatic
orientation until the new generation supplies presentation metadata; the
unrotated codec raster is not a new portrait/landscape instruction. Android
TV and Leanback receivers keep their startup window axis; declared landscape-only
or portrait-only displays use their supported axis in all receiver orientation
modes, taking precedence over the TV default. Picture rotation and
aspect-preserving fitting remain unchanged. Teardown restores the prior Activity
policy in both cases.

The experimental child-SurfaceControl bridge was removed after landscape
rendering went black. Its oracle transposed compositor matrix fields:
[RequestedLayerState](https://android.googlesource.com/platform/frameworks/native/+/android-15.0.0_r1/services/surfaceflinger/FrontEnd/RequestedLayerState.cpp)
applies `set(dsdx,dtdy,dtdx,dsdy)`, and
[Transform](https://android.googlesource.com/platform/frameworks/native/+/android-15.0.0_r1/libs/ui/Transform.cpp)
maps `x'=dsdx*x+dtdy*y`, `y'=dtdx*x+dsdy*y`.
The former 270-degree configuration mapped 1080x1920 into
`[-1920,1080,0,2160]`, entirely outside a 1920x1080 parent. Earlier rectangle
tests therefore provided false confidence, not actual pixel evidence.

Current Windows receiver source supports this extension: HELLO acknowledges true
only for a Boolean true offer and a rotation-capable playback factory. Absent or
false offers and noncapable playback do not negotiate it; nonBoolean offers are
rejected. Wire accepts video flags 0..7, but after media token attachment the
receiver rejects flags above 1 with `PROTOCOL` unless rotation was negotiated.
Reserved video bits and any flags on other packet types remain invalid.
Do not remove the required capability check or silently fall back for old peers.
Windows-to-Android frames without rotation bits legitimately present at zero.

## Evidence

Rotation geometry regressions cover 0/90/180/270 degrees, all four mapped
corners, fixed projector and portable window sizes, cropped/noncropped natural
rasters, and landscape/portrait/landscape reconfiguration. The manually gated
synthetic rendering diagnostic uses the same SDR TextureView path
(an isolated non-secure fixture window permits PixelCopy). It is not an HDR
surface test. Passing unit geometry/route tests alone does not establish physical projector
rendering or HDR emission; the literal-frame diagnostic and actual projector
portrait/landscape retest remain required when an idle, authorized target is
available.

`fixtures/windows-v3-*.hex` were produced and decoded by the actual Windows
protocol source with `tools/WindowsProtocolCheck`, not handwritten packet mocks.
The 18 fixtures retain the original eight and add sender/receiver rotation HELLOs
and all four key/nonkey orientations. Android cross-engine tests consume those
same bytes through both real Android Wire engines. The helper's 204 protocol/shared-helper
cases include strict Windows writer/reader flag rejection and actual
ReceiverServer loopback sessions with explicitly fake/headless playback.
They cover every absent/false/true offer versus supported/unsupported factory,
malformed offers, both token-attached media sockets for video-only sessions,
readiness, equal PTS, generation changes, unnegotiated rejection and STOP/cleanup.
It also verifies a rotation-capable headless factory declines a valid recovery
v1 offer rather than acknowledging playback recovery it does not implement.
Shared recovery helper checks match Android's PCM 120 ms packet boundary,
malformed Opus duration checks, AAC ceiling preroll, PCM sample trim and exact
first/subsequent continuity rules.
These establish framing/lifecycle behavior, **not native/GPU playback or live
Android-to-Windows hardware media acceptance**, which remains unverified.

## Optional independent track recovery v2

This extension is independent of rotation. The sender offers and the receiver
explicitly acknowledges **both** Boolean `streamRecovery:true` and integer
`streamRecoveryVersion:2` in their control HELLOs. NonBoolean capabilities,
noninteger/nonpositive revisions are protocol errors. Absent/false capability,
missing revision or an unsupported positive offer revision does not negotiate
recovery. A sender rejects a true ACK with a missing/incompatible revision.
Revision 1's shared recovery and the unfrozen prototype are not fallbacks.
Windows v0.1.15 does not acknowledge recovery; its strict congestion behavior
remains unchanged. Receipt of a generic STATUS is never an ACK.

### Ownership, messages and acknowledgements

All recovery STATUS packets belong to the currently accepted control session:
header track/generation zero, PTS -1, flags zero. Required JSON fields are
`state` (at most 80 characters), `message` (at most 2048 characters) and
`track` (1 for video, 2 for enabled audio), and `generation` (that track's current
wire generation, positive signed int32).

| Direction | `state` | Additional required fields |
|---|---|---|
| Receiver to sender | `stream-recovery-request` | None |
| Receiver to sender | `stream-recovery-key` | Video only: `keyPtsUs` |
| Receiver to sender | `stream-recovery-complete` | Video: `keyPtsUs`; audio: `audioPtsUs`, never both |

Timestamp values are **canonical nonnegative decimal strings** in signed int64
range: `"0"` or a nonzero leading digit followed by digits, without whitespace,
sign, leading zero, exponent or fraction. They are not JSON numbers. Windows'
signed-int32 validation still applies to every JSON number, including extras.
No packet type or flag bit changes.

The accepted session ID, track and sequential wire generation identify the recovery
transaction; no additional request ID is needed. Invalid/future generations
fail; well-formed stale generations are ignored. Requests are coalesced while
waiting for that track's READY, and repeated old-generation requests cannot start another
reset after the sender advances generation. A new current-generation pressure
request while waiting for source/proof/completion can retire only that track's attempt.
Video KEY and COMPLETE match the exact sent random-access picture PTS; COMPLETE
follows KEY without waiting for audio. Audio COMPLETE proves actual source-sample
submission at/after its own cutoff, with no video key field or dependency.
Matching duplicate proofs are idempotent; conflicting proofs fail. Duplicate STREAM_READY
is a protocol error, not permission to re-enable an epoch.
Audio completion must additionally belong to an actual successfully written
packet's source interval in its current generation, after cutoff trimming.
End timestamps are exclusive. Queued/not-started packets, holes between sent
intervals and impossible future timestamps are not proof of output. A matching
proof racing the completion of its in-flight write is retained without clearing
the original deadline, and completed only after that write succeeds.
Already-written eligibility intervals are coalesced without filling timestamp
holes, and capped at 4096 disjoint intervals per attempt. Exceeding that bound
reports capacity pressure and retries only audio under its original deadline,
rather than accepting guessed proofs or prematurely terminating healthy video.

### Ordered retirement and random access

Receiver admission is nonblocking when negotiated: keep reading/validating
complete framed packets and discard the obsolete epoch, even during slow native
retirement. Sender pressure can initiate the same recovery. Drop only unsent
complete obsolete media chains, never an in-flight frame. This applies to
**all** packet types, including Ping/Pong/STATUS/configuration: each TCP lane has
one serialized writer. Finish its started frame before another frame or Stop;
if a write/deadline fails, retire the socket/session, never reuse a partial frame.
Unsent terminal cleanup may discard pending control/media; started control writes
remain ordered ahead of Stop/SESSION_END. Per-write deadlines remain 10 seconds.

The sender drains capture without changing its encoder/quality/raster, advances
only the affected track's wire generation, and sends cached STREAM_CONFIG behind
that lane's completing old packet. The other lane's queued/in-flight packets,
generation, decoder and output remain unchanged. Each lane's TCP ordering is its
own retirement barrier. The receiver reconstructs only the affected output on
its playback worker and sends READY after real initialization from valid CSD.
Both tracks retain the same PlaybackClock. There is no shared reset path.
The initial barrier is distinct from recovery: neither track activates until
all initial requested tracks are READY. Initial audio retains bounded latest
preroll while waiting, instead of overflowing into an early generation reset.
Its first release trims the obsolete prefix and preserves compressed admission
order. If initial compressed priming has been retired and cannot be safely
released at the initial boundary, restart only audio after both tracks are READY
so the receiver can apply its explicit recovery cutoff. Later track recoveries
do not reintroduce this initial all-tracks barrier.

After video is READY, request a hardware sync frame, retrying no
faster than every 500 ms. Suppress stale/dependent video until a verified fresh
random-access picture: original PTS at/after video READY,
and at most 250 ms old at admission. H.264 requires IDR, H.265 IRAP 16..21, AV1 a
shown key frame (not show-existing/intra-only). Flags alone are insufficient.
Cached CSD must match any embedded parameter/sequence headers; an unannounced
change fails. An announced encoder configuration change is serialized as another
sequential generation, retaining the same unresolved deadline. Decoder-reported
visible raster, including alignment crop, must match that configuration.
Picture PTS and rotation remain associated unchanged through decode/presentation.

Allow dependent video after the verified key so the decoder can produce output.
KEY acknowledges usable decoded output at/after that key, not merely admission.
Pictures more than 250 ms behind the continuing source clock are discarded only
after decode; compressed dependencies are still consumed. A late decoded key
can be followed by usable dependent output without resetting healthy audio.
KEY proof is validated by the live generation and exact sent key PTS, not its
wall-clock age at sender receipt. Legitimate network/decode/control-return delay
does not invalidate a key that met source freshness at admission. KEY and
COMPLETE still cannot arrive at or after that track's first-unresolved deadline.

### Audio resynchronization

Audio recovery advances the audio wire generation, leaving video unchanged.
Its cached STREAM_CONFIG additionally requires canonical int64-string
`recoveryPtsUs`: the sender's current source time plus codec preroll. The format
must match the accepted audio configuration; a format change requires a new
session. Initial configuration has no recovery cutoff.
New native audio gets a fresh decoder/resampler/output epoch, but not a new
PlaybackClock. Audio detach snapshots the presented source position and keeps
it advancing monotonically until the resumed output takes ownership.
Original packet PTS/bytes are never retimed. Opus pre-skip is initial-only.
Only recovering audio is held in its bounded mux while awaiting audio READY
and its own preroll coverage. Healthy audio is never held for a video proof.

Retain enough **duration-aware** prefix around `recoveryPtsUs` for PCM (zero preroll), Opus
(negotiated 80 ms), or AAC-LC (one 960/1024-sample AU). Derive Opus durations from
TOC (including 60/120 ms packets), AAC from ASC and PCM from aligned sample count.
Release compressed audio only with contiguous preroll coverage (one sample of
timestamp rounding tolerance) and a packet extending past the audio cutoff. If audio has
not arrived or has not yet reached that cutoff, continue bounded buffering under the
original deadline. Do not reset just because 250 ms of wall-clock time elapsed.
A present AAC prefix starting after its required preroll start, a known gap in
that monotonic prefix, or RAW first-source audio beyond cutoff + 250 ms requires a
new bounded attempt. Opus source PTS may move backward, so an apparent prefix
gap is WAITING, not proof that the source window is LOST. Coverage is checked
in original admission order, not as a reordered interval union; an unproven
Opus prefix remains bounded by the existing caps and original deadline.
Queue limits do not grow to retain an obsolete source window.
Local cap-pressure eviction of a packet intersecting the selected cutoff's required
Opus decode window is explicit loss evidence, not an inference from PTS order.
The removed decode-chain packet is the evidence even if later equal/reversed
intervals cover the same timestamps; do not reconstruct that chain by sorting
or an interval union.
Latch that evidence for the selected cutoff until coverage is checked; then retry only audio
under the original deadline. Evictions ending at/before the preroll start do not
count. Clear the evidence when retiring the attempt, and collect fresh preroll
before selecting the next audio cutoff. No packet reordering is allowed.
Discard packets whose **end** is at/before the preroll window, not just by
start PTS. Decode retained Opus/AAC packets in order to preserve overlap/state,
then discard decoded samples strictly before the audio cutoff. Before admitting
the first retained packet, the receiver may advance the cutoff to its continuing
clock and discard an obsolete complete prefix before that new preroll window.
Once decoding begins, preserve compressed admission order. Pending decoded
startup PCM is retrimmed against the current clock, so delayed startup never
rewinds the healthy video timeline or fails merely because audio became stale.
Trim PCM on sample-frame
boundaries, including packets straddling the cutoff. Initial pre-skip is not
reapplied during resets. The preroll is intentionally inaudible (80 ms for Opus,
one AU for AAC, plus the straddling prefix); the whole retired backlog is skipped,
not synthesized as silence. Normal small underrun handling remains unchanged.
Android applies bounded FFmpeg compensation after source-domain trimming.
Filter lookahead and converted frame ratios retain their original source spans;
completion/presentation use mapped source timestamps, not nominal output-frame
counts. Each retired native audio epoch releases its resampler, and the next
starts with fresh filter state at the new audio wire generation.
During startup catch-up Android pauses decoding at its existing decoded-FIFO
target while continuing output servicing and the no-progress check, including
after the first source submission. A temporarily full native ring must not
drain a held burst into source-dropping FIFO overflow; remaining packets stay
in the bounded encoded queue. Catch-up ends after the encoded queue drains and
one target's worth of original source audio has actually been presented.
Steady-state low-latency decoded trimming and compensation remain unchanged;
network admission and all caps stay bounded.

Audio COMPLETE proves actual source-audio sample submission (not inserted
silence), with its sample-aligned original `audioPtsUs` at/after the audio cutoff.
There is no key-relative 250 ms A/V failure rule. Temporary offsets are resolved
by stale decoded-data trimming and source-time scheduling, not shared resets.
The sender clears only that track's unresolved deadline after its matching proof.
This is admission/startup resynchronization, not permission to skip compressed
dependencies within a retained epoch. Long-idle/no-fresh-media recovery still
times out; disabled audio does not wait for an audio proof.

### Bounds, Stop and failures

Each track has a **10-second deadline anchored to its first unresolved recovery**
covering reset/cooldown, READY and usable source output. Initial
negotiated startup is also bounded. Repeated reset/config/READY/key/request
events cannot extend it. One track's completion never resets the other's deadline.
Recovery is excluded from the generic absolute setup-phase deadline; its
track-local deadlines and control-read/write liveness checks still apply.
Audio recovery before first playback explicitly uses its own recovery startup
state: video completion cannot restore the initial three-second timeout, and
fresh audio is not compared to an obsolete first-video timestamp. The common
clock remains continuous; ordinary non-recovery startup keeps its original rules.
Before any audio has played, fresh submitted video can advance this bootstrap
clock so audio does not wait behind an obsolete initial video-arrival delay.
Once audio takes clock ownership, video presentation does not re-anchor it.
Native resets are spaced at least one second apart per track;
sync requests at least 500 ms apart. These are rate limits, not new deadlines.
Track generation exhaustion at signed-int32 maximum is fatal, never wraparound.
Sender queue byte limits are track-local: video 8 MiB and audio 512 KiB, matching
receiver track capacity. Their aggregate queued-media bound is 8.5 MiB; neither
lane borrows the other's capacity. Existing PTS/count caps, independent
heartbeats and socket deadlines remain.

Stop serializes resource retirement with reconstruction. Every worker install,
failure/proof callback and READY must still own its accepted session and current
track generation/audio epoch; retired completions cannot revive or poison a
new epoch. Current codec/output failures remain fatal, not success-shaped READY.
Pending Stop cannot start new audio, and SESSION_END follows retirement.
Successful in-flight writes finishing after Stop or capture retirement do not
feed recovery proofs or recheck recovery deadlines. Actual transport-write
failures retain their existing error/teardown handling.
Audio-format changes require a new capture session. A successful recovery keeps
the session/presentation lease; no shared, software, codec, quality or audio fallback.
Fatal errors mean a required stream cannot recover, a current decoder/output
fails, or the transport/protocol is broken; A/V mismatch by itself is not fatal.

The real TCP recovery tests use fake codecs/output; they establish bounded
protocol/worker behavior, not native hardware recovery or live Windows casting.
Prior art consulted: scrcpy
[`demuxer.c`](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/app/src/demuxer.c),
[`decoder.c`](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/app/src/decoder.c)
and [`frame_buffer.c`](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/app/src/frame_buffer.c).
Independent demux/decode and latest-decoded-frame replacement are useful models;
this bounded negotiated recovery contract is not claimed to be scrcpy's policy.
