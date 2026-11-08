# RedrainCast for Android

One native Android app for **Send** or **Receive**, with one active role at a time.
Display name: **RedrainCast**. Application ID: `dev.redrain.cast`.
Android 11/API 30 or later is required for the unified capture/hardware-codec
policy; there is no migration from the retired applications.

## Use

**Playback statistics (default off):** Settings on the sending Android device
has **Show playback statistics on receiver (next cast)** at the bottom of
**Advanced**, with a diagnostic-use hint. Save it before starting
the next cast. Updated Android/Windows receivers negotiate this optional v1
feature; an older receiver continues casting without the overlay and the sender
retains a nonfatal unavailable notice. Old senders never activate it.
The display-only translucent overlay stays above receiver video/fullscreen,
uses 16sp text on larger windows and dense 10sp diagnostic text on phones,
refreshing cached typed counters once per second. Phone mode retains the full
metrics rather than replacing them with a six-row summary. It uses measured,
content-sized short labels, shared units and tighter spacing; the landscape
panel is bounded to 65% of the player width. At the Galaxy-sized test viewport
and 115% font scaling it covers less than half the screen area in either
orientation. It respects system font scaling, without altering phone settings
or automatically shrinking fonts to force a fit.
Hold the receiving video for **Full playback diagnostics**, or open that action
under Settings > Advanced. The separate diagnostics view scrolls to retain every
metric on smaller viewports or larger accessibility font settings. It is unavailable
until receiver statistics are actually selected and active; it never enables a
cast or changes the sender preference.
Larger windows keep the full aligned panel: four equal KPI cells, three equal
current-generation totals with labels above numbers, VIDEO STREAM and AUDIO &
SYNC label/value grids, and the connection/queue table. Numeric cells align to
the right instead of padding strings. The full diagnostics view uses this same
grid, stacking sections where needed, with a reachable footer rather than
compressing text into the viewport.
It distinguishes requested FPS/bitrate from measured payload and actual rendered
frames, encoded queues from decoded PCM/device padding, and played source audio
from the device clock. A/V sync is video minus played source audio (not latency);
renderer skips are not network packet loss. Unavailable metrics show N/A.
The main video section separates **Encoded size** and **Display mode**
(**Display px** in the compact panel).
Render target is kept only in Full playback diagnostics: it is N/A on the
current video paths because their final compositor buffer dimensions are
not measured; encoded/view/mode sizes are never substituted for that
measurement. Display mode is the attached
display's reported active Android mode dimensions, in that mode's orientation,
not proof of native optical projection or physical scanout. App view size is
available separately in Full playback diagnostics and is not labeled as rendered
resolution. Footer values share the same left-aligned columns as their titles
(Playing directly below State). Scheduled frames, Audio epoch and Device played
also use three equal label-over-value columns instead of a cramped inline row.
Skew is compared only when both cached source observations are at most 250 ms
old and within 10 ms of one another. Otherwise it is N/A and does not increase
the observed peak; cached PTS is never extrapolated across source-clock changes.
The comparison has up to 10 ms observation-time separation, not sub-millisecond
measurement precision. The incoming-session transition closes any idle full
diagnostics dialog before playback starts.
Media Last activity is the age of the last received packet, not oldest queue age.
Queues contain waiting packets only, excluding dequeued codec work.
Peak observed skew is the maximum absolute value sampled at 1 Hz since the
current baseline, not a per-frame peak. Android's actual MediaCodec-rendered
Presented values differ from Windows' accepted presentation API calls; neither
claims screen scanout. Playback epoch and Occluded are N/A on Android because
there is no equivalent lifetime/occlusion signal; Audio epoch and Scheduled
frames remain separate Android details. Common labels/order/units are tested
against `protocol/fixtures/playback-statistics-display.json`.
Local display contract v2 adds **Stream range**, **Profile / depth**, and
**Output** immediately after Codec; the network contract is unchanged.
Stream range separates validated incoming HDR10 from the decoder's reported
transfer (PQ/BT.709). Missing SDR color evidence is N/A, not assumed SDR.
Profile comes from the decoder output format; depth is shown only for explicit
decoded YUV component formats (8-bit or P010 10-bit). An opaque Surface does
not establish depth, even with a Main 10 profile.
Output is cached only after a matching MediaCodec frame-rendered callback for
the submitted picture's validated output format. **HDR output** means the
BT2020/PQ/limited direct Surface path, not physical HDR emission, display-wire
protocol or an OEM HDR switch. Capabilities and negotiated HDR alone cannot
establish it. The optional tone-map request has no independent runtime mapping
proof, so it does not report **SDR (tone-mapped)**. Missing evidence stays N/A.
Metadata is cached on decoder/output events, never queried on the 1-Hz refresh;
retired players and unmatched callbacks cannot publish old presentation paths.
The copied Windows comparison manifest is
`protocol/fixtures/playback-statistics-display-contract.json`; Android retains
its existing display-mode/app-view diagnostic fields and typography.
Compact landscape panels reserve 40% of the player width for unobscured video;
the native-rendered area checks retain their strict less-than-half limit.
The video metadata column receives more width than the short queue column to
avoid turning the added rows and existing raster values into a taller panel.
Landscape KPI and generation-total grids share a horizontal summary row,
retaining their title-over-value cells. The source-skew explanation sits below
the shorter audio column instead of adding another full-width panel row.
Native-rendering tests inspect inner text bounds and the real reported Galaxy
canvas dimensions at 450 dpi and 115% font scaling, including summary area and
short-label wrapping. Full diagnostics tests scroll to the footer and retain the
original receiver canvas value. Robolectric does not provide a
Windows ARM64 native renderer; those tests require a supported host JVM (an x64
Java process under Windows emulation works). This test-host choice does not
change the app's Android ABI or require any phone installation.
Stop/failure/background hides it; session/generation/audio-epoch/recovery changes
reset baselines. No phone permissions, capture, network probes or audio/quality
settings are changed by statistics.

- Opening the app **automatically starts receiving readiness and discovery**.
  The receiver card shows READY and its OS-assigned TCP endpoints, with copy
  buttons; availability is advertised on UDP 27183. Readiness is not an active
  receiving session and never requests capture or microphone permission.
  On Android 17/API 37 and later, Android additionally requires **local network
  access** (in the Nearby devices permission group) for LAN discovery, sending
  and receiving. The app asks before opening LAN sockets. Denial keeps networking
  stopped and shows an Allow local network action; permanently denied permission
  can be changed through the app's Android settings. This never approves capture
  or microphone access, and older Android versions do not receive this prompt.
- The Windows-inspired home has **Send screen** and **Receive screen** cards:
  stacked on portrait phones, side-by-side on landscape viewports at least
  600 dp wide, and side-by-side on wide displays (840 dp or more) regardless of
  orientation. Very narrow split-screen windows remain stacked. Main headings,
  **Nearby receivers**, **Send**/**Stop**, **Settings**, and **Connection help**
  follow Windows terminology; capture-permission help stays Android-specific.
  Nearby receivers update automatically; this app's current and retired self endpoints are excluded.
  Choose Send on a receiver row, or **Connect by IP : Port** for manual entry.
- Sending reserves the outgoing role and **fully closes the local listener
  before requesting Android consent**. Receiving shows PAUSED. Cancellation,
  denial, startup failure, and normal sender teardown release the reservation;
  listening automatically resumes while the app is foregrounded. A restarted
  listener can get a different port, so its displayed/advertised endpoints refresh.
- **Sharing settings** are accessed through the header settings button and saved
  only on Save. Codec, size, FPS, bitrate, orientation and audio are the primary
  controls; crop and detailed audio options are under Advanced. Selection fields
  open standard Android single-choice dialogs with the current option checked.
  Selecting updates only the draft; cancelling a choice retains its prior value,
  and cancelling Sharing settings discards all edits. Child dialogs close with
  their parent, and the settings scroll position is retained.
  Receiver orientation follows the displayed video in Auto mode on phones and
  tablets; explicit Portrait/Landscape overrides still apply there. Displays
  declaring only landscape or only portrait support keep their supported axis
  in every mode. Android TV and Leanback receivers otherwise keep their startup
  window axis. Pictures and per-picture rotation still fit either window without
  stretching. This avoids unsupported window transitions on fixed-axis receivers;
  it does not change the sender raster, codec or quality. Devices without these
  platform declarations retain the phone/tablet behavior.
  Only codecs with
  an eligible hardware video encoder on this device are offered. An unsupported
  saved codec requires an explicit supported selection; it is not silently
  replaced. Encoder names are selected automatically, including when loading
  older saved manual overrides. Raw PCM hides the encoded-audio bitrate control
  and uses fixed 48 kHz stereo s16le instead.
  Invalid settings and persistence failures stay visible without codec/quality/
  audio fallback. Errors appear at the top; each new home error is brought into
  view once, rather than repeatedly stealing scroll position during updates.
  Cancel does not change saved settings.
- Android's screen-sharing consent is required for every new sending session.
  Audio permission is requested only when sending with audio enabled. Playback
  capture is subject to Android's source-app capture policy; DRM/protected content
  cannot be captured. Microphone capture uses the microphone foreground-service
  type. Notification Stop remains available when the activity is backgrounded.
- Accepted SDR uses the proven MediaCodec-to-TextureView presentation path;
  accepted HDR uses direct MediaCodec-to-SurfaceView, with secure-surface/window
  protection. Decoded video alone triggers fullscreen playback, with no initial
  banner, local stop button, or touch/D-pad overlay. The sender controls normal
  session start/stop. A sender stop returns the receiver to readiness without
  closing its healthy listener.
  Native HDR10 reception requires explicit v1 HDR negotiation, a hardware HEVC
  Main10 HDR10 decoder and HDR10 support on the attached display's current mode.
  SDR senders keep their existing protocol and playback path. Android does not
  advertise HDR-to-SDR conversion without verified decoder support; losing HDR
  output ends the HDR session explicitly and requires an SDR restart.
  Capability reporting and JVM tests are not proof of real-device HDR scanout.
  HDR bitstream admission failures retain their specific validation reason in
  the playback error, alongside structured stage/generation diagnostics, without
  exposing encoded pictures or vendor exception text.
  Unsupported decoder selection preserves the requested MIME, raster, frame
  rate and profile in the SELECT error. Per-hardware-candidate logs include
  profile/level support, raster bounds/alignment, size support, frame-rate range
  at that exact raster, and format/size-rate support. Generic maximum resolution
  or frame rate is not a guarantee that their combination is supported.
  Long candidate details are explicitly truncated to the bounded 1100-character
  selection reason within the existing 2048-character error envelope; full
  per-candidate diagnostics remain in the ScreenCopyReceiver log.
  Rejection does not change HDR gates, settings or the protocol. A manual
  1920x1080@60 retry is an option, not a compatibility guarantee; SDR is required
  when native HDR display support is unavailable.
  Routing uses the accepted `videoColor`, not the codec name: unmarked HEVC is
  SDR, and HDR never enters TextureView even for a tone-mapping request.
  SDR rotates its TextureView by each picture's 0/90/180/270-degree metadata
  with aspect-preserving fitting. HDR keeps the framework-owned SurfaceView
  unrotated and supports only zero picture rotation; nonzero HDR rotation
  explicitly logs/fails `UNSUPPORTED_CONFIG` before decoder input dequeue/queue
  and before output release. Nonzero HDR initial format rotation is rejected
  before decoder creation.
  KEY_ROTATION remains zero, so there is only one SDR rotation owner.
  Both views stay attached; the SDR panel is opaque black during SDR and
  transparent/non-opaque during HDR, avoiding decoder-surface teardown or
  leaking old native video into SDR letterboxes. Codec slots, including detached
  pending retirements, close before the owned TextureView Surface is released.
  Retired TextureView/holder callbacks are identity-checked and cannot close or
  replace a newer surface epoch.
  The experimental child-SurfaceControl geometry bridge was removed after it
  placed landscape video entirely outside the parent; its earlier matrix unit
  oracle had transposed compositor coefficients and was not pixel-render proof.
  Physical projector and HDR retests still require an authorized idle target.
  Encoded mastering chromaticities tolerate at most one normalized 1/50000 unit
  of hardware requantization; luminance/light levels and negotiated wire metadata
  remain exact.
  Leaving the activity retires the listener and playback resources; it does not
  create a background receiver service. An outgoing cast can continue in its
  foreground service, and readiness resumes when the app returns after it ends.
  Listen only on a trusted LAN: discovery and casting are not encrypted or
  authenticated remote-access services.
- While sending, RedrainCast's own controls are capturable rather than redacted
  to black. Secure-window protection is restored before receiving resumes.
  Other apps' protected windows/DRM content can still appear black by Android
  policy; the app does not bypass those protections.

The sender uses MediaProjection, hardware MediaCodec video encoding, an EGL
natural-coordinate mapping/crop, and a **fixed encoder raster across rotations**.
Picture-PTS-associated rotation metadata rotates presentation, not the encoder.
Compressed frames are never arbitrarily removed from a retained dependency
chain. With mutually negotiated stream recovery, obsolete chains are discarded
as a unit and playback restarts from a fresh keyframe on the same connection.
Queues retain their explicit byte/time limits and requested quality; genuine
codec, transport and recovery-timeout errors remain terminal and visible.

## Bounded stream recovery

Android peers offer and explicitly acknowledge Boolean `streamRecovery:true`
**and integer `streamRecoveryVersion:1`** in HELLO. Both peers must be updated;
the unfrozen 0.3.0 prototype's Boolean-only ACK is incompatible.
On receiver queue pressure the TCP readers continue consuming framed data rather
than waiting on decoder admission. The receiver requests recovery and discards
the obsolete epoch. Sender-side queue pressure can initiate the same recovery.

The sender does not cancel partially written packets. It discards unsent
obsolete media, sends cached video configuration at the next sequential video
generation on the video socket, and waits for readiness. It then requests a
hardware keyframe and suppresses dependent video until a verified random-access
picture whose original capture PTS is at or after readiness. Audio remains held
in the existing bounded queue until an explicit decoded-key proof. Codec, bitrate, raster,
rotation metadata and timestamps are not substituted or rewritten.

The receiver rebuilds video and audio outputs on a dedicated playback worker,
then acknowledges the new video generation. Audio wire generation is unchanged;
local audio playback gets a fresh clock/output epoch, without reapplying initial
Opus pre-skip. After decoded-key proof, packet-end-aware admission preserves
Opus's 80 ms/AAC's one-AU preroll and overlapping packets; decoded PCM is trimmed
on sample boundaries before key PTS. Old audio cannot leak into the new epoch.
An explicit completion proof requires actual source-audio submission, not silence
(or decoded video alone with audio disabled).
Presentation ownership, the cast session and control heartbeats remain alive.

Native decoder resets are limited to one per second; sync requests are spaced
at least 500 ms apart. The ten-second deadline starts at the **first unresolved
recovery** and cannot be extended by more resets, readiness or keyframes.
Source key admission remains at most 250 ms old. Decoded-key proofs match the
live generation and exact sent key PTS; 350-800 ms of legitimate decode/control
transit does not invalidate them. Audio waits for actual contiguous source
coverage under that same deadline, not a 250 ms wall-clock timer. A provably lost
RAW/AAC source window retries without increasing queue caps. An apparent Opus
prefix gap waits because its source timestamps may move backward; packet/decode
order is never rewritten to make the coverage appear contiguous.
An actual local cap eviction of a packet in the selected key's required Opus
preroll is separate loss evidence; eviction of only obsolete packets is not.
The loss is of retained decode-chain data, even when later equal/reversed
timestamps appear to cover the same interval; overlap cannot reconstruct it.
That evidence is scoped to the current key/attempt and cleared on reset. A
compressed-audio retry first collects fresh preroll under the unchanged
first-unresolved deadline.
The first source-audio sample must
still lie within key PTS through key PTS + 250 ms. Older recovery-v1 peers may
still apply the stricter receipt-age rule and need an implementation update.
Regression coverage fills the actual 500 ms held-audio queue through an 1100 ms
proof delay: evicting the required RAW prefix causes exactly one new attempt,
then matching source audio completes it. Entirely absent PCM instead waits in
one generation through deadline minus one nanosecond and fails at exactly the
original ten-second deadline, without a reset storm.
Stop retires pending work and cannot produce late readiness or
start new audio output. Audio format changes require a new capture session.
An unsupported or non-acknowledging peer retains the strict congestion policy;
Windows rotation-only builds currently do not negotiate this recovery extension.
See [the exact recovery v1 contract](protocol/README.md#optional-bounded-stream-recovery-v1)
for schemas, duplicate/stale rules, partial-write protection and A/V cutoff policy.

## Windows compatibility boundary

The transport follows the **actual current RedrainCast Windows implementation**,
not the retired Android single-socket layout: control, video, and audio TCP
connections use the same listener port, with session/token attachment. Both media
connections are associated even when audio is disabled.

**Current Windows receiver source supports negotiated per-picture rotation.**
It echoes Boolean `videoRotation:true` only when the sender offers true and its
playback factory supports rotation. Android's sender still requires that HELLO
acknowledgement: old/noncapable peers must be rejected explicitly rather than
switching raster modes or silently showing incorrectly rotated pictures.
Windows-to-Android receive does not need this extension: Windows' logical-raster
frames display at rotation zero. Android-to-Android negotiates the extension.
Source support and local protocol checks are not a claim of full Windows parity.
The existing dirty Windows worktree is intentionally not modified here.

Live Android/Windows interoperability and user-approved screen capture must be
validated on idle, consenting devices before calling this app production-ready.
Socket/codec-fixture tests alone are **not** live interoperability.

### Live acceptance still required

On idle, explicitly approved devices, start with **H.264**, not an automatic H.265
cast. Install the new package side-by-side; do not uninstall the existing apps.

1. Android-to-Android: approve sending through Android's own consent UI. Use an
   asymmetric off-center crop with distinct corner content. Rotate through all
   four orientations and verify the same natural region, unchanged encoder
   width/height, correct per-picture presentation, and no compressed-frame loss.
2. Verify video-only, then requested playback/microphone audio, lip sync, explicit
   unsupported-codec errors, and restoration of the previous local mute state.
3. Stop from each endpoint, revoke projection, background the receiver, and change
   networks; confirm sockets/codecs/audio resources retire before role switching.
4. Windows-to-Android: use the actual Windows app and its discovered listener
   addresses. Confirm picture/audio playback, heartbeat, stop and reconnect.
5. Android-to-Windows: use a rotation-capable Windows receiver and confirm the
   Boolean HELLO acknowledgement before capture. Verify all four orientations,
   unchanged encoder raster, audio, stop and reconnect. Old/noncapable receivers
   must still fail explicitly. Live acceptance remains unverified.

Receiver `FLAG_SECURE` is intentional. Do not disable it for screenshots;
presentation pixel checks must use a separate synthetic instrumentation fixture.

## Build and tests

Uses the checked-in Gradle 9.8.1 wrapper, AGP 9.4.1 (built-in Kotlin), JDK 21,
Android SDK/API 37 (`compileSdk` and `targetSdk`), Build-Tools 37.0.0,
NDK 27.2.12479018 and CMake 3.22.1. Minimum Android remains API 30.
Set `JAVA_HOME` and `ANDROID_HOME` to your installed toolchains.
FFmpeg's minimal LGPL-only `libavutil`/`libswresample` libraries are consumed
as a pinned, SHA-256-verified public AAR/Prefab package. Fresh clones and cleared
native caches download the package rather than compile FFmpeg. Local and app-CI
builds compile the small JNI wrapper and existing bundled Opus only. Missing
prebuilts fail explicitly; there is no automatic slow source-build fallback. See
[FFmpeg build and source/relink information](third_party/FFMPEG.md).

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain
```

Ordinary release builds are optimized and **unsigned**. The manually triggered
[public prerelease workflow](tools/RELEASING.md) signs and publishes only after
production signing, cross-repository access and reviewed LGPL relink materials
are configured. No production key material or in-app APK installer is included.
Debug builds use application ID `dev.redrain.cast.debug`, launcher name
**RedrainCast Debug**, and version name `0.4.13-debug` (the release version plus
`-debug`). Release remains `dev.redrain.cast`, **RedrainCast**, version `0.4.13`,
code `26`; both variants retain source namespace `dev.redrain.cast`.
The distinct IDs allow debug and release to be installed side by side, with
separate app data, saved settings and permissions; installing debug does not
replace release. Older debug APKs using the release ID do not migrate to the
new debug ID. Debug keeps normal development signing; release signing is unchanged.

Build only (these commands do not install or launch either app):

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --console=plain
```

Outputs are `app\build\outputs\apk\debug\app-debug.apk`,
`app\build\outputs\apk\release\app-release-unsigned.apk`, and
`app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk`.
Debug instrumentation uses package `dev.redrain.cast.debug.test` and targets
`dev.redrain.cast.debug`; component class names retain their source packages.
The launch update check compares the base version without the debug-only suffix,
preserving alpha/beta/rc ordering and strict public release validation. It still
offers public **release** pages, not debug updates: a downloaded release APK updates
the separately installed release app (subject to its signing certificate), never
the debug app. The displayed debug version retains `-debug`.
Ordinary push/PR CI runs validation only; public publication is manual and gated.
The separate public
[RedrainCast-Native repository](https://github.com/arisewanggithub/RedrainCast-Native)
owns manual, parallel ABI builds and immutable native-package releases. Both
developer machines and application CI consume that same package without a
GitHub Packages read token. Only a native source/toolchain/configuration change
requires rebuilding the published package. End users still install one universal
APK, not a separate APK per ABI.
CI installs the stable/base `platforms;android-37.0` package. SDK setup requests
`platform-tools`, not the obsolete Android `tools` package. Android 17 permission
grant/denial is covered by targeted policy/UI tests; SDK-30 Robolectric simulation
does not claim full Android-17 platform enforcement. Receiving remains a visible
Activity, sending starts its projection/microphone foreground service from the
user-approved Activity flow, and durable mute recovery runs after Activity resume
to respect Android 17's background-audio restrictions.
Mute ownership is process-wide: Activity recovery must not unmute a live
projection or clear its durable restoration marker. Owner cleanup restores the
previous state; failed cleanup leaves the marker available for a later recovery.

### Startup update checks

Like PartialCast, each new Activity launch checks the public GitHub release
metadata anonymously on a background worker; configuration recreation retains
the same check and ordinary resume does not repeat it. Only a **newer stable
release** with a completed, version-matching universal APK in
`arisewanggithub/RedrainCast-Releases` can prompt. Drafts and testing prereleases
are excluded, including the historical 0.4.6 preview and the 0.4.10 RC builds.
When no stable release is available, GitHub's `/releases/latest` endpoint returns
404; this is logged without presenting an update or affecting casting.
Future testing versions use an explicit `-alpha.N`, `-beta.N` or `-rc.N`
suffix in the embedded app version, GitHub tag and APK filename. An installed
`0.5.0-rc.1` can recognize stable `0.5.0` as newer, while still ignoring other
prereleases. Android versionCode must increase for that transition. The existing
0.4.6 preview is left unchanged; see [release versioning](tools/RELEASING.md#version-identity-for-future-releases).

The prompt waits for a resumed, focused, idle home with no active Send/Receive
role or permission/settings dialog. Incoming casting takes priority, and
backgrounding/recreation does not count as choosing "Later." Update failures
are logged without blocking discovery or capture consent.
An error arriving after the update prompt appears withdraws that prompt too;
the update remains available after the user dismisses the actual error, without
starting another metadata request.

"Download in browser" opens the validated release page only after an explicit
press, so the user can read warnings and download its APK through the browser.
There is no background APK download, automatic installation, unknown-sources
permission request or silent uninstall. Android still controls installation and
signing compatibility; a production-signed release cannot replace a debug-signed
development install in place.

Metadata responses are bounded to 256 KiB with connection/read timeouts,
cancellation and rate-limit cooldown. Redirects and cross-repository release
or APK URLs are rejected. No credentials, capture content, microphone data or
device identifiers are sent by the update checker.

### Manual synthetic rendering diagnostics

Debug builds include a non-exported `SyntheticVideoProbeActivity`; release builds
do not. Its instrumented tests use synthetic colors, a hardware H.265 encoder,
the production SDR decoder/TextureView presentation, including first-rotation/Auto
window-resize races and a separate sender normalization probe.

These are opt-in diagnostics, not automatic casting tests. Never install or run
instrumentation during a cast. First verify the requested device identity,
no active projection/session/services, and an unlocked, awake screen. Install
the diagnostic debug and androidTest APKs only after that gate. The explicit
`syntheticVideoProbe=true` argument is required; omit these tests from ordinary
instrumentation runs unless the manual safety gate has been satisfied.

```text
adb -s <verified-device> shell am instrument -w -e syntheticVideoProbe true -e class org.screencopy.receiver.SyntheticVideoRenderingDiagnosticTest#firstHevcQuarterTurnRacesAutoWindowResizeAcrossFreshStarts dev.redrain.cast.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s <verified-device> shell am instrument -w -e syntheticVideoProbe true -e class org.screencopy.receiver.SyntheticVideoRenderingDiagnosticTest#firstProjectionResizeRacesAutoWindowWithIndependentCropAcrossFreshStarts dev.redrain.cast.debug.test/androidx.test.runner.AndroidJUnitRunner
```

PixelCopy reads only the isolated synthetic window, never protected production
content. Remove the debug/test helpers afterward if no longer needed; the separate
release installation and its data are untouched.
The intermittent tiny-strip rendering issue is still unresolved: the initial
diagnostic Activity setup crash was corrected, but the retry was blocked by a
locked receiver. Compilation is not a valid native pixel-rendering baseline.

### Verified locally

- Latest review follow-up passed the **478-test full JVM suite** with zero
  failures/errors/skips, lint, and all APK builds. Stop after acceptance but
  before playback records an intentional startup abort rather than requiring
  an acknowledgement on a deliberately closed connection. Non-startup closure
  still fails explicitly. New application/sender errors withdraw an existing
  update modal; dismissing the real error makes that same update available
  again without another metadata request. The unpublished candidate remains
  **0.4.10/code 20**; no phone or public release was changed.
- Future prerelease naming passed **16 focused updater tests**, the
  **474-test full JVM suite**, **26 release-tooling tests**, workflow lint, and
  all APK builds (Android lint: zero errors, 10 existing warnings). A temporary
  release build embedded `0.4.10-rc.1`; its matching tag was accepted and a
  mismatched stable tag rejected. The ordinary `0.4.10` APK and output metadata
  were restored by hash afterward. Existing public releases were not changed.
- Startup browser update checks **0.4.10/code 20** passed **39 focused tests**
  and the **470-test full JVM suite**, lint (zero errors, 10 existing warnings),
  and debug/optimized unsigned release/androidTest APK builds. Metadata tests
  cover stable-only selection, numeric version comparison, strict public-repo
  links, completed APK assets, response limits, HTTP failures, cancellation and
  rate-limit cooldown. Lifecycle tests cover one check across recreation,
  foreground/focus and active-role deferral, "Later", late results, off-main
  networking and explicit browser navigation without an installer/service.
  No APK was downloaded automatically, no phone was modified, and this candidate
  has not been published.
- Control-last teardown **0.4.9/code 19** passed **19 focused tests** and the
  **447-test full JVM suite**, lint (zero errors, 10 existing warnings), and
  debug/optimized unsigned release/androidTest APK builds. The sender retains
  control reading through a bounded two-second drain; both media sockets close
  before control. A valid receiver fatal error can supersede a provisional
  media-disconnect diagnosis, but not an earlier genuine capture/codec failure.
  Real-TCP tests cover delayed Stop acknowledgement and receiver ERROR, cleanup
  PINGs, missing terminal response, and playback retirement before control close.
  A 500 ms receiver control-priority grace handles media EOF arriving before an
  in-flight Stop without hiding genuine unexplained disconnects. These are JVM
  protocol tests, not live JMGO/projector acceptance. No phone was modified and
  no public release was published for this candidate.
- Non-finishing destruction follow-up **0.4.6/code 16** passed the **419-test
  full JVM suite**, lint (zero errors, 10 existing warnings), and debug/optimized
  unsigned release builds. Launched consent survives framework destruction
  without an explicit Activity finish, including the "Don't keep activities"
  scenario; explicit finishes still cancel. Both idle phones were updated and
  their installed hashes matched the signed debug APK. No permission/capture
  automation, commits or pushes were performed.
- Consent-lifecycle fix **0.4.5/code 15** passed **20 focused lifecycle/request
  tests** and the **417-test full JVM suite** with zero failures/errors/skips.
  Lint has zero errors and 10 existing warnings; debug and optimized unsigned
  release builds passed. Recreation cancels unlaunched reservations without
  restarting consent, preserves launched permission/projection results, and
  cannot let stale Activity cancellation overwrite an adopted service's status.
  Both identity-verified idle phones were updated and their installed hashes
  matched the signed debug APK. No app launch or permission/capture automation
  was performed.
- Review fixes **0.4.4/code 14** passed all **7 new focused regressions** and
  the **411-test full JVM suite**, with zero failures/errors/skips. Lint reports
  zero errors and 10 existing warnings; debug and optimized unsigned release
  builds passed. Activity recovery cannot restore a live projection's mute,
  failed owner restoration remains recoverable, and decoder replacement joins
  retired workers outside the callback-facing lifecycle lock. A new receive
  session clears stale errors without hiding genuine current-session failures.
  Both identity-verified idle phones were updated and their installed APK hashes
  matched the signed debug build. No app launch, permission grant, or live
  casting/capture automation was performed.
- Receiver error-reporting fix **0.4.3/code 13** passed all **24 receiver
  protocol/lifecycle tests**, lint, and debug/optimized unsigned release builds.
  Two new real-TCP regressions failed before the fix with the false
  `Session error: PROTOCOL` status. Unowned setup connections can no longer
  publish cast errors after a clean Stop; peer ERROR replies and structured
  diagnostics remain, and genuine active-session disconnects remain fatal.
  Both phones were updated while idle and their installed APK hashes verified.
  No app launch or permission/capture automation was used. These are protocol
  regressions, not a new live MediaProjection casting/Stop acceptance run.
- Prebuilt integration **0.4.2/code 12** passed **402 JVM tests** with zero
  failures/errors/skips, lint with zero errors (10 existing warnings), and
  debug/optimized unsigned release builds. The release retains all three ABIs,
  FFmpeg notices and 16 KiB native ZIP alignment.
  An isolated source snapshot with empty native outputs and no cached FFmpeg
  package downloaded the pinned public AAR and built all three native Debug ABIs
  in **4 minutes 27 seconds**. Existing non-native Gradle downloads were reused;
  this was not a literal fresh Git clone or an entirely empty toolchain cache.
  Compile-command audits found only the app's JNI and bundled Opus sources,
  with **zero FFmpeg C compilation**. The cached offline native rebuild passed
  in 90 seconds. Scratch-only wrong-checksum and missing-package tests both
  failed explicitly without source fallback. All five core FFmpeg native tests
  passed on the identity-verified idle OnePlus with the installed, hash-matched
  0.4.2 APK. The temporary test package was removed; no capture/mic/LAN consent
  was automated. This validates package consumption and core JNI behavior,
  not resolution of the previously observed audio-startup issue below.
- SDK update **0.4.1/code 11** compiles/targets API 37 with Build-Tools 37.0.0
  and retains min API 30. **402 JVM tests** (including 18 targeted home/
  permission/lifecycle tests), lint and all APK variants passed. Both installed
  debug APKs were checksum-verified on idle devices.
  Galaxy runs API 37 and displayed the OS local-network permission prompt;
  it was left for the user, not approved through ADB. OnePlus/API 33 passed the
  five native FFmpeg tests. Its 2-second PCM confirmation passed on retry, but
  the 15-second confirmation reported 3982 missing submitted source frames;
  native audio acceptance is therefore not claimed for this SDK update.
  This remains an open audio/startup investigation, separate from the verified
  SDK metadata and LAN permission gate. No buffer limits were relaxed.
- The preceding **0.4.0 full run passed 398 tests**, with zero failures/errors/skips.
  Added coverage includes filter-delay/source-span accounting, compensated
  source/output ratios, fractional partial writes, explicit resampler failures
  and fixed nominal native playback during software compensation.
  Recovery v1 coverage includes decoded-key/completion proofs, the first-request
  deadline across resets, codec-aware preroll, sample-accurate production PCM
  submission, actual-output raster guards and stale admission/Stop races.
  Coverage includes native choices, measured landscape card layout, fixed-natural
  geometry, bounded queues, three-socket lifecycle, golden fixtures, per-lane
  diagnostics, real-socket media-admission isolation in both directions, and
  negotiated sender-to-receiver keyframe recovery without releasing the session.
  Recovery tests use real TCP and explicitly fake codecs/outputs; they cover
  queue pressure, original PTS/rotation, audio epoch/pre-skip reset, live control
  during decoder reconstruction, stopped-epoch admission, Stop races and genuine
  decoder failure. They are not live hardware capture/recovery acceptance.
  The blocked-video/audio-progress regression failed before the receiver lock
  correction and passed afterward. Run with
  `JAVA_TOOL_OPTIONS=-Djava.util.concurrent.ForkJoinPool.common.parallelism=3`;
  the initial tests/lint/debug/release/native-test-APK build with FFmpeg took
  27 minutes 17 seconds on Windows ARM64; final JNI rebuilds reused the archives.
- Android lint, debug APK, and optimized unsigned release APK builds passed.
  APK metadata confirms `RedrainCast`, `dev.redrain.cast`, min SDK 30, and
  arm64-v8a/armeabi-v7a/x86_64 native libraries.
- The **0.4.0 debug APK (code 10)** passed signature verification and was installed
  and checksum-verified on both identity-verified idle phones. OnePlus resumed
  its home after native tests (receiver surface readiness still requires an awake,
  foreground Activity); Galaxy was safely stopped with no app service/projection
  and was updated in the background without changing the user's foreground.
  Both peers must acknowledge recovery v1 for this feature to negotiate.
  No active cast was interrupted and no capture consent was automated. Live
  user-approved recovery/interoperability validation remains unverified.
- Historically, on the real OnePlus AudioTrack, the silent-PCM single-step test failed on
  0.3.1 with **240 submitted frames instead of 2880**, then passed on 0.3.2.
  The native refill test and continuous silent-PCM clock/rate test passed on
  **both phones** (two tests each). Only synthetic silent PCM was played; no
  screen capture, microphone recording or H.265 cast was started. Temporary
  instrumentation packages were removed. Sustained user-cast acceptance remains
  separate from this demonstrated native refill fix.
- The FFmpeg JNI conversion/continuity/lifecycle tests and stable-AudioTrack PCM
  tests passed on **both phones (10 tests each)**. Both completed a 120-second
  scripted software-compensation run with **zero steady-input underruns**,
  119 compensation updates and exact source accounting: 5759983 source frames
  submitted plus 17 retained filter frames equals 5760000 input frames.
  Physical playback rate stayed nominal. Tests distinguish steady input from
  the artificial source-ended tail; that tail can report underruns and is not
  included in the steady-input assertion. This is native silent-PCM evidence,
  not a claim of sustained mixed-video/network or Windows casting acceptance.
- Eighteen golden framing fixtures round-tripped against actual Windows Wire,
  retaining the original eight unchanged and adding rotation HELLOs and all
  key/nonkey orientations. The helper also passed 251 protocol/shared-helper cases: actual
  ReceiverServer loopback negotiation/admission and strict reader/writer flags.
  Playback is explicitly fake/headless; no native/GPU or live casting is claimed.
  Shared Windows recovery helper checks independently cover Android's PCM 120 ms
  cap, malformed Opus packet boundaries, AAC preroll rounding, sample-frame
  cutoff and first/subsequent coverage tolerance. They also call
  `RecoveryDeadline.Complete(monotonicMs)` at the original deadline boundary,
  require late completion to retain pending state, and distinguish absent audio,
  monotonic source loss and unproven Opus coverage. The canonical Opus source-order
  JSON fixture is embedded in the checker, so both verification and generation
  into a fresh directory check equal and large reversed PTS without sorting.
  Receiver options add 30 shared raw-schema cases and eight actual Windows Core
  negotiation/immutable-selection/Stop cases. Android statistics validation
  includes real three-socket legacy/recovery fixtures, typed-counter baselines,
  exact stats-on/off PCM identity and unchanged native-query counts, and
  Robolectric setting/layout/input/background checks. These are not physical
  phone, native rendered-pixel or live Android/Windows casting acceptance.

These are build/protocol/install results, not live capture/interoperability or
optimized-release device-runtime acceptance. The old Android checkouts remain
clean at the source baseline commits, and Windows sources were not edited.

With the sibling Windows checkout present, regenerate or verify the golden
framing fixtures against its **actual** protocol writer/reader:

```powershell
dotnet run --project .\tools\WindowsProtocolCheck\WindowsProtocolCheck.csproj -- verify .\protocol\fixtures
```

The Android cross-engine tests consume the same golden frames. The helper links
the portable Windows receiver Core sources and shared
`src\RedrainCast.Protocol\StreamRecovery.cs` into its own build, not a project reference;
it does not modify that checkout or write its build caches. Its loopback cases
attach both token-authenticated media sockets even for video-only sessions and
exercise capability negotiation, readiness, equal PTS, generation changes,
unnegotiated rotation rejection and STOP/cleanup.
Its headless playback factory explicitly leaves stream recovery unsupported;
rotation-only fixtures do not acknowledge or exercise native recovery.
Use `-p:WindowsRoot=...` to point MSBuild at a different Windows checkout.

### Reading congestion diagnostics

The sender's media budget bounds queued per-track PTS span to 500 ms and local
admission waiting to 500 ms, independently of its 8 MiB byte limit. A video
`PTS_SPAN` failure can therefore occur with only about 1 MiB queued; it is not an
out-of-memory failure. A receiver `origin=PEER` report means the peer raised the
error, not that the local decoder reported a fault.

Control, video and audio have independent TCP writers. In-flight duration,
admitted age and last-write duration are now tracked separately for each lane.
Older shared-writer diagnostics could incorrectly show `inflight=NONE` after an
unrelated lane completed while video remained blocked. Queue limits, timestamps,
requested bitrates and compressed dependencies are unchanged by this diagnostic
correction. Socket backpressure alone does not distinguish Wi-Fi transport stalls
from receiver admission/drain stalls; do not claim a particular root cause without
correlated evidence.

Receiver media admission is also independent per track. Ownership, generation
and packet validation remain under a short shared lifecycle lock, but a bounded
queue wait occurs outside it. Each media socket has one serial reader, so its
configuration cannot overtake its own in-progress admission; stopped workers reject
late admission during cleanup. Holding the shared lock while waiting on one
track would block the other track and defeat the three-socket transport.
Real-socket fault-injection tests cover both directions of this failure.

Receiver PCM servicing is independent of input arrival. Before waiting for
another encoded packet, and after decoding, the audio worker refills the
currently writable native output capacity in bounded chunks. Five milliseconds
is a write-chunk/silence-cushion size, not a per-step refill quota: native mixers
may require a complete 40 ms block. A zero nonblocking write yields immediately;
partial writes preserve samples/PTS and the existing 250 ms no-progress limit.
Pre-input servicing never inserts silence ahead of already-queued PCM; the small
silence cushion is considered only after input/decode servicing finds a gap.
No decoded-FIFO, native-buffer, network-queue, rate or quality limit is enlarged.
During startup catch-up, decoding pauses whenever the FIFO reaches its existing
50 ms target, including after the first actual source-sample write. This keeps
a held startup burst from discarding source samples while the native ring is
temporarily full. Additional source packets stay in the existing bounded encoded
queue. Startup catch-up ends only after that queue drains and at least one
target's worth of original source audio has actually been presented.
Output servicing and the 250 ms no-progress deadline continue while waiting;
network admission remains independent and bounded. Packet-local overflow and
steady-state compensation/overflow retain their existing behavior, including
bounded decoded trimming to recover low latency after repeated short gaps.
Regression tests exercise a 3921-frame native ring with a 1920-frame mixer
quantum, PCM refill before input admission, partial/zero writes and bounded
silence. These establish servicing behavior, not a claim that every possible
native/network underrun has been eliminated.
Prior art: scrcpy's
[`audio_player.c`](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/app/src/audio_player.c)
satisfies the audio device's whole requested amount through bounded chunks.

### FFmpeg audio compensation

Version 0.4.0 bundles pinned FFmpeg 7.1.3 `libswresample`/`libavutil` through JNI.
Decoded PCM passes through a persistent resampler; bounded clock compensation
changes its output sample count instead of repeatedly reconfiguring AudioTrack.
AudioTrack is prepared once at nominal rate and stays at that rate. Hardware
video encoding, audio codec choices, wire PTS and all existing queue/buffer
ceilings remain unchanged; there is no native-failure identity fallback.

Original source spans survive filter lookahead, packet boundaries and changing
conversion ratios. Presentation maps accepted output frames back to fractional
source-sample positions, with explicit silence spans; partial writes cannot
accumulate per-chunk rounding loss. Retired sessions discard their owned filter
state, and every new audio epoch gets a fresh resampler. Source frames retained
in the filter are accounted for rather than mislabeled as packet loss.

### Investigation leading to the new audio path

The refill fix is not a claim that the live cutout problem is fully solved.
Reference comparison uses official scrcpy 5.0.1, tag commit
`a60891aea193d92e7e5c3942700eca63f9d19a5f`, with its Apache-2.0 license retained.
scrcpy waits for its target PCM preroll, fills the whole output request (including
accounted underrun silence), and uses FFmpeg resampling for compensation.
RedrainCast 0.3.2 started from its first synchronized packet, kept a small
native silence cushion, and updated Android playback parameters for compensation.
These are different output/control architectures, not interchangeable queue code.

Controlled 15-second local silent-PCM probes reproduced remaining gaps without
Wi-Fi, capture, or video decoding. On OnePlus, repeated nominal native rate calls
produced 22 underruns and a roughly 160 ms positive-write gap; suppressing
unchanged calls reduced this to three underruns and preserved all 720000 source
frames in that run. Increasing the native ring to the existing 130 ms ceiling or
using audio thread priority did not independently eliminate the problem.
Direct continuously supplied AudioTrack output ran for ten seconds with zero
underruns, including a run with frequent timestamp queries (maximum query time
about 0.75 ms). These probes motivated the software-compensation path above;
neither disabling compensation nor increasing limits was shipped as a fallback.
A live official scrcpy capture baseline and sustained mixed-video acceptance
still need approval. Recovery proof-age/transport-latency handling is corrected
separately from FFmpeg adoption, without retiming source PTS or enlarging queues.
The separate intermittent OnePlus startup probe loss (3982 original source
frames in reported two- and fifteen-second runs) is not established as fixed by
that recovery correction. Exact source accounting excludes frames still held
in the FFmpeg filter; native probes retain their exact submission/presentation
assertions. A fresh 0.4.6 probe lost 2062 source frames; a 0.4.7 probe with only
the first-write guard lost 6862. The latter processed all 115200 input frames,
submitted its first source sample at the original 1000000 us, and reported
exactly 6862 decoded-FIFO dropped output frames. This isolates those observed
losses to decoding past writable output capacity after the first write, rather
than missing capture packets or unaccounted filter delay. Bounded startup
catch-up pacing at the existing FIFO target addresses that path without
increasing buffers, relaxing the loss threshold, or disabling steady-state
low-latency gap recovery; sustained mixed-video acceptance remains separate.
The native probe logs the first submitted source PTS, processed source frames
and observed decoded-FIFO drops to distinguish startup trimming from filter delay.
The startup catch-up correction passes the JVM regressions, including unchanged
steady-state gap recovery, but its exact real-AudioTrack probe rerun is still
pending. Do not treat those unit results as native or live-casting acceptance.

Arbitrarily dropping queued compressed H.264/H.265/AV1 packets is not safe:
subsequent pictures may reference them. The receiver can skip presentation of
already-decoded pictures without breaking dependencies. Negotiated recovery
instead discards the obsolete chain and rebuilds decoder/audio state before
accepting a fresh keyframe. It does not enlarge or silently disable queue limits.

## Source and notices

Media/capture code is derived from the merged ScrcpySend baseline
`6a9370c4a1101b4830b49c0e773e72cf9ca46743`
and ScrcpyReceive `6606b7b22054b65a0e72e9bc39501d65c240d9cd`.
Original internal package names are retained for media-engine provenance and JNI
binding stability; they do not define the new application identity.
No Shizuku, ADB capture, legacy single-socket transport, or old-app data migration
is part of this app.

Bundled Opus source archive/checksum and required third-party notices/licenses are
preserved. See the in-app **Third-party notices** and `third_party/`.
FFmpeg is built with GPL, nonfree and version-3 code disabled. Its LGPL-2.1 text,
upstream notices, pinned source and relinking/build information are included;
see [FFmpeg distribution obligations](third_party/FFMPEG.md) before distributing
APK binaries outside this development setup.
No new license grant for the private application source is implied.
