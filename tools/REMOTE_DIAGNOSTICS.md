# Remote diagnostics collector tests

For current collection, privacy and interpretation, use the
[troubleshooting runbook](../TROUBLESHOOTING.md#collect-live-paired-v2-diagnostics).
The [v2 protocol](../protocol/REMOTE_DIAGNOSTICS.md) is the authoritative
implementation reference. These tests do not contact devices or establish
physical playback or on-device overhead.

The collector tests require three ignored Android-generated fixtures. Configure
`JAVA_HOME` to a JDK 21 installation and `ANDROID_HOME` or `ANDROID_SDK_ROOT`
to the installed Android SDK (including platform 37) before running the
commands. On Windows ARM64, Robolectric native libraries may require an x64
JDK. For example, an x64 JDK might be installed at
`$env:LOCALAPPDATA\Copilot\Toolchains\jdk-21.0.13-x64` and the SDK at
`$env:LOCALAPPDATA\Android\Sdk`; these are examples, not defaults. Set paths
explicitly only if needed. Gradle may also use `local.properties`; ensure its
`sdk.dir`, if present, points to the intended SDK. The checks below honor the
existing environment and fail rather than replacing it:

The collector excludes receiver `audio.timing`, `audio.timing.work`,
`audio.timing.detail`, and `audio.timing.owner` by default.
For startup work tracing explicitly use
`--receiver-flags session,recovery,audio.transport,audio.queue,audio.output,audio.timing,audio.timing.work,video.progress,transport.health`.
Add `audio.timing.detail` to that explicit list for decode/write CPU boundaries,
queue age/span, output capacity/underruns, and sampled boundary overhead.
For paired receiver playback-lock owner intervals also add `audio.timing.owner`;
it requires detail. Complete spans show observed monitor ownership only, not
whether a waiting callback was blocked or descheduled. Activation, lost
endpoints and incomplete tails leave ownership unknown; see
`protocol/REMOTE_DIAGNOSTICS.md`.
Work requires base timing; selecting only `audio.timing` preserves v1 tracing.
The collector rejects work selection if the sender does not advertise it; the
sender rejects receiver setup if the receiver lacks it. There is no automatic
work tracing or silent downgrade. Strict v2 batch parsing requires work selection.
Nested work boundaries separate preparation, gate waits/diagnostic
callbacks, resampling, playback start and refill/timing queries; current-thread
CPU snapshots distinguish worker CPU from wall time without attributing mixed
output writes to the most recent packet. See the protocol vocabulary and
coverage caveats in `protocol/REMOTE_DIAGNOSTICS.md`. The collector preserves
raw event batches and the independent marker in retention pages. It does not
diagnose the capture or infer audible output.

```powershell
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe') -PathType Leaf)) {
    throw 'Set JAVA_HOME to a valid JDK 21 installation.'
}
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -version
if ($LASTEXITCODE -ne 0) { throw 'JAVA_HOME java failed to run.' }
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    throw 'Set ANDROID_HOME or ANDROID_SDK_ROOT to the installed Android SDK.'
}
foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
    if ($sdk -and -not (Test-Path -LiteralPath (Join-Path $sdk 'platforms\android-37') -PathType Container) -and
        -not (Test-Path -LiteralPath (Join-Path $sdk 'platforms\android-37.0') -PathType Container)) {
        throw "SDK missing platform 37: $sdk"
    }
}
$fixtures = @(
    'app\build\diagnostics-contract\receiver-priority-tail-fetch.json',
    'app\build\diagnostics-contract\receiver-timing-fetch.json',
    'app\build\diagnostics-contract\receiver-legacy-capture.json'
)
foreach ($fixture in $fixtures) {
    if (Test-Path -LiteralPath $fixture) {
        if (-not (Test-Path -LiteralPath $fixture -PathType Leaf)) { throw "Not a file: $fixture" }
        git check-ignore -q -- $fixture
        if ($LASTEXITCODE -ne 0 -or @(git ls-files -- $fixture).Count -ne 0) {
            throw "Fixture is not ignored and untracked: $fixture"
        }
        Remove-Item -LiteralPath $fixture -ErrorAction Stop
    }
}
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks `
    --tests 'org.screencopy.receiver.net.ReceiverProxyRetirementTest.boundedTailProtectsFailureAfterRoutineBudgetExhaustion' `
    --tests 'dev.redrain.cast.diagnostics.DiagnosticVideoTimingTest.fullyPopulatedReceiverFetchRetainsClockCorrectionsWithinFrameBound' `
    --tests 'dev.redrain.cast.diagnostics.DiagnosticVideoTimingTest.legacyReceiverCaptureProducesOnlyLegacyBoundedRecords'
if ($LASTEXITCODE -ne 0) { throw "Fixture generation failed" }
foreach ($fixture in $fixtures) {
    if (-not (Test-Path -LiteralPath $fixture -PathType Leaf)) {
        throw "Missing generated fixture: $fixture"
    }
}
```

These produce `receiver-priority-tail-fetch.json`, `receiver-timing-fetch.json`
and `receiver-legacy-capture.json`, respectively, in
`app\build\diagnostics-contract`. Keep the fixture checks
fail-fast; do not rely on stale files or skipped fixture-dependent tests.

```powershell
$previousFixtureSetting = $env:DIAGNOSTICS_REQUIRE_FIXTURE
try {
    $env:DIAGNOSTICS_REQUIRE_FIXTURE = '1'
    python -B .\tools\diagnostics-test.py
    if ($LASTEXITCODE -ne 0) { throw "Collector tests failed" }
    python -B .\tools\diagnostics-v2-test.py
    if ($LASTEXITCODE -ne 0) { throw "v2 collector tests failed" }
} finally {
    $env:DIAGNOSTICS_REQUIRE_FIXTURE = $previousFixtureSetting
}
```

The explicit fixture checks above are also required: `diagnostics-v2-test.py`
has fixture-dependent skips that do not consult `DIAGNOSTICS_REQUIRE_FIXTURE`.
