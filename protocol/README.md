# Unified SCPY v3 contract

Authority: current RedrainCast Windows `ScrcpySend.Core/SenderSession.cs`,
`ScrcpyReceive.Core/ReceiverServer.cs`, `Protocol.cs`, and `Discovery.cs`.
The sibling checkout is not modified by the Android build or protocol probe.

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
its reader alive after Stop or media EOF: send the terminal intent, retire both
media sockets, drain the peer's ERROR/STOP/SESSION_END, and close control last.
The sender's existing two-second teardown deadline bounds that drain, including
terminal write/capture cleanup; an absent acknowledgement fails explicitly.
Intentional startup cancellation can abort transport before playback is ready
without requiring an acknowledgement on that deliberately closed connection.
This exception records the startup-abort intent before closing; a generic
closed control socket or an unexplained peer EOF is not exempt.
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
Android physical-to-logical conventions. Receiver decoder output is rendered by
TextureView and presented using that picture's rotation. Late UI callbacks must
still own the live session/generation. On portable displays, automatic Activity
orientation follows the displayed aspect and explicit orientation wins. Android
TV, Leanback and declared landscape-only displays keep the Activity landscape in
all receiver orientation modes; picture rotation and aspect-preserving fitting
remain unchanged. Teardown restores the prior Activity policy in both cases.

Current Windows receiver source supports this extension: HELLO acknowledges true
only for a Boolean true offer and a rotation-capable playback factory. Absent or
false offers and noncapable playback do not negotiate it; nonBoolean offers are
rejected. Wire accepts video flags 0..7, but after media token attachment the
receiver rejects flags above 1 with `PROTOCOL` unless rotation was negotiated.
Reserved video bits and any flags on other packet types remain invalid.
Do not remove the required capability check or silently fall back for old peers.
Windows-to-Android frames without rotation bits legitimately present at zero.

## Evidence

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

## Optional bounded stream recovery v1

This extension is independent of rotation. The sender offers and the receiver
explicitly acknowledges **both** Boolean `streamRecovery:true` and integer
`streamRecoveryVersion:1` in their control HELLOs. NonBoolean capabilities,
noninteger/nonpositive revisions are protocol errors. Absent/false capability,
missing revision or an unsupported positive offer revision does not negotiate
recovery. A sender rejects a true ACK with a missing/incompatible revision.
The revision distinguishes this contract from Android 0.3.0's unfrozen prototype.
Windows v0.1.15 does not acknowledge recovery; its strict congestion behavior
remains unchanged. Receipt of a generic STATUS is never an ACK.

### Ownership, messages and acknowledgements

All recovery STATUS packets belong to the currently accepted control session:
header track/generation zero, PTS -1, flags zero. Required JSON fields are
`state` (at most 80 characters), `message` (at most 2048 characters) and
`generation` (current video generation, positive signed int32).

| Direction | `state` | Additional required fields |
|---|---|---|
| Receiver to sender | `stream-recovery-request` | None |
| Receiver to sender | `stream-recovery-key` | `keyPtsUs` |
| Receiver to sender | `stream-recovery-complete` | `keyPtsUs`; `audioPtsUs` only when audio is enabled |

Timestamp values are **canonical nonnegative decimal strings** in signed int64
range: `"0"` or a nonzero leading digit followed by digits, without whitespace,
sign, leading zero, exponent or fraction. They are not JSON numbers. Windows'
signed-int32 validation still applies to every JSON number, including extras.
No packet type or flag bit changes.

The accepted session ID plus sequential video generation identify the recovery
transaction; no additional request ID is needed. Invalid/future generations
fail; well-formed stale generations are ignored. Requests are coalesced while
waiting for READY/key, and repeated old-generation requests cannot start another
reset after the sender advances generation. A new current-generation pressure
request while waiting for proof/audio/completion can retire that attempt.
KEY must match the exact sent random-access picture PTS. COMPLETE must follow
KEY and actual audio release (or KEY alone for disabled audio). Matching duplicate
proofs are idempotent; conflicting PTS/audio proofs fail. Duplicate STREAM_READY
is a protocol error, not permission to re-enable an epoch.

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
the video wire generation, and sends STREAM_CONFIG behind the completing old
video frame. Only one bounded queued chain plus an in-flight frame can precede
that boundary; obsolete queued media is removed rather than endlessly extending
the backlog. Video TCP ordering is the barrier; the audio socket has no barrier.
The receiver reconstructs video/audio outputs on a dedicated serialized worker,
discards delayed old decoder output, and sends READY only after configuring real
outputs from valid CSD. No first-keyframe/READY dependency cycle is allowed.

After all enabled tracks are READY, request a hardware sync frame, retrying no
faster than every 500 ms. Suppress stale/dependent video until a verified fresh
random-access picture: original PTS at/after READY, adequate audio-preroll time,
and at most 250 ms old at admission. H.264 requires IDR, H.265 IRAP 16..21, AV1 a
shown key frame (not show-existing/intra-only). Flags alone are insufficient.
Cached CSD must match any embedded parameter/sequence headers; an unannounced
change fails. An announced encoder configuration change is serialized as another
sequential generation, retaining the same unresolved deadline. Decoder-reported
visible raster, including alignment crop, must match that configuration.
Picture PTS and rotation remain associated unchanged through decode/presentation.

Allow dependent video after the verified key so the decoder can produce output.
KEY acknowledges decoded output at/after that key, not merely packet admission.
KEY proof is validated by the live generation and exact sent key PTS, not its
wall-clock age at sender receipt. Legitimate network/decode/control-return delay
does not invalidate a key that met source freshness at admission. KEY and
COMPLETE still cannot arrive at or after the first-unresolved 10-second deadline.

### Audio resynchronization

Audio wire generation and negotiated format remain unchanged. New native audio
gets a fresh clock/output epoch; original packet PTS/bytes are never retimed.
Midstream Opus reconstruction uses pre-skip zero; the header pre-skip is
initial-only. Until KEY proof, source audio stays in the existing bounded mux,
dropping only its obsolete prefix on pressure. Receiver audio admission/output
remains gated; old socket backlog continues to be consumed/discarded.

On KEY, retain enough **duration-aware** prefix for PCM (zero preroll), Opus
(negotiated 80 ms), or AAC-LC (one 960/1024-sample AU). Derive Opus durations from
TOC (including 60/120 ms packets), AAC from ASC and PCM from aligned sample count.
Release compressed audio only with contiguous preroll coverage (one sample of
timestamp rounding tolerance) and a packet extending past key PTS. If audio has
not arrived or has not yet reached the key, continue bounded buffering under the
original deadline. Do not reset just because 250 ms of wall-clock time elapsed.
A present AAC prefix starting after its required preroll start, a known gap in
that monotonic prefix, or RAW first-source audio beyond key + 250 ms requires a
new bounded attempt. Opus source PTS may move backward, so an apparent prefix
gap is WAITING, not proof that the source window is LOST. Coverage is checked
in original admission order, not as a reordered interval union; an unproven
Opus prefix remains bounded by the existing caps and original deadline.
Queue limits do not grow to retain an obsolete source window.
Local cap-pressure eviction of a packet intersecting the selected key's required
Opus decode window is explicit loss evidence, not an inference from PTS order.
The removed decode-chain packet is the evidence even if later equal/reversed
intervals cover the same timestamps; do not reconstruct that chain by sorting
or an interval union.
Latch that evidence for the selected key until its proof is handled; then retry
under the original deadline. Evictions ending at/before the preroll start do not
count. Clear the evidence when retiring the attempt, and collect fresh preroll
before selecting the next compressed-audio key. This is local state only, with
no new wire fields or packet reordering.
Discard packets whose **end** is at/before the preroll window, not just by
start PTS. Decode retained Opus/AAC packets in order to preserve overlap/state,
then discard decoded samples strictly before key PTS. Trim PCM on sample-frame
boundaries, including packets straddling the cutoff. Initial pre-skip is not
reapplied during resets. The preroll is intentionally inaudible (80 ms for Opus,
one AU for AAC, plus the straddling prefix); the whole retired backlog is skipped,
not synthesized as silence. Normal small underrun handling remains unchanged.
Android applies bounded FFmpeg compensation after source-domain trimming.
Filter lookahead and converted frame ratios retain their original source spans;
completion/presentation use mapped source timestamps, not nominal output-frame
counts. Each retired native audio epoch releases its resampler, and the next
starts with fresh filter state while the wire audio generation stays unchanged.
During startup catch-up Android pauses decoding at its existing decoded-FIFO
target while continuing output servicing and the no-progress check, including
after the first source submission. A temporarily full native ring must not
drain a held burst into source-dropping FIFO overflow; remaining packets stay
in the bounded encoded queue. Catch-up ends after the encoded queue drains and
one target's worth of original source audio has actually been presented.
Steady-state low-latency decoded trimming and compensation remain unchanged;
network admission and all caps stay bounded.

COMPLETE proves the first actual source-audio sample submission (not inserted
silence), with its sample-aligned original `audioPtsUs` at/after key and no more
than 250 ms later. Video-only sends COMPLETE after KEY without `audioPtsUs`.
The sender clears the unresolved deadline only after this matching proof.
This is admission/startup resynchronization, not permission to skip compressed
dependencies within a retained epoch. Long-idle/no-fresh-media recovery still
times out; disabled audio does not wait for an audio proof.
This is a recovery-v1 semantic correction without new fields/types. Older peers
may retain the stricter receipt-age behavior; negotiation alone does not prove
that their implementation includes the correction.

### Bounds, Stop and failures

One **10-second deadline anchored to the first unresolved recovery** covers
reset/cooldown, READY, sync retries, key decode and A/V completion. Initial
negotiated startup is also bounded. Repeated reset/config/READY/key/request
events cannot extend it. Native resets are spaced at least one second apart;
sync requests at least 500 ms apart. These are rate limits, not new deadlines.
Video generation exhaustion at signed-int32 maximum is fatal, never wraparound.
Existing byte/PTS/count caps, independent heartbeats and socket deadlines remain.

Stop serializes resource retirement with reconstruction. Every worker install,
failure/proof callback and READY must still own its accepted session and current
video generation/audio epoch; retired completions cannot revive or poison a
new epoch. Current codec/output failures remain fatal, not success-shaped READY.
Pending Stop cannot start new audio, and SESSION_END follows retirement.
Audio-format changes require a new capture session. A successful recovery keeps
the session/presentation lease; no software, codec, quality or audio fallback.

The real TCP recovery tests use fake codecs/output; they establish bounded
protocol/worker behavior, not native hardware recovery or live Windows casting.
Prior art consulted: scrcpy
[`demuxer.c`](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/app/src/demuxer.c),
[`decoder.c`](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/app/src/decoder.c)
and [`frame_buffer.c`](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/app/src/frame_buffer.c).
Independent demux/decode and latest-decoded-frame replacement are useful models;
this bounded negotiated recovery contract is not claimed to be scrcpy's policy.
