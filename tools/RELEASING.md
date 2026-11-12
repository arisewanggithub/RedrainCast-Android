# Public Android releases

Future APKs and distribution materials belong in
[RedrainCast-Android releases](https://github.com/arisewanggithub/RedrainCast-Android/releases),
alongside the public Android source. GitHub's automatic source archives contain
Android source at the release tag. The workflow uploads only the five reviewed
distribution assets, never debug/test APKs, logs or signing keys.

This configuration applies to new versions only; it does not migrate or
republish historical releases. The product website links to the Android releases
listing, which works before the first release exists. Windows distribution is
unchanged.

## Required configuration and permissions

The manually dispatched `Public Android release` workflow runs on
GitHub-hosted `ubuntu-latest` with JDK 21, the pinned Android SDK/NDK/CMake,
Python 3 and Bash. Signing requires four existing Actions secrets:

- `ANDROID_RELEASE_KEYSTORE_BASE64`: a dedicated production keystore.
- `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_PASSWORD`,
  `ANDROID_RELEASE_KEY_ALIAS`: credentials. Never put them in Git or logs.

The signing/build job keeps `contents: read`. Only the separate `publish` job,
enabled by `publish=true` after signing succeeds, receives `contents: write`.
It uses the ephemeral same-repository `GITHUB_TOKEN`, downloads the same signed
candidate, verifies checksums and rechecks exact current main. It atomically
creates a new tag at `reviewed_commit` (GitHub's create-ref operation, never an
update/force), verifies the remote tag's peeled commit, then creates a **draft**
using `gh release create --verify-tag`. It verifies the tag again after upload.
It has no signing-secret inputs.
No cross-repository PAT or `RELEASE_PUBLISH_TOKEN` is needed. Never copy a
personal login token into Actions. Forks, PRs and non-main dispatches cannot
reach signing/publication. No repository permission settings need changing.

Release files use a private per-run/per-attempt directory, with keystore removal
on signing exit and always-run cleanup of that precise directory after artifact
upload. A hard process kill can prevent cleanup; securely remove only that
attempt's leftover directory, never shared runner directories.

Keep an independent durable backup of the production key outside Git. Losing
it prevents future in-place APK updates. Production signing differs from debug
signing; switching certificates can require uninstalling and losing app data.
Do not uninstall or change device signing automatically.

On Windows, `tools\release-signing.ps1 -Directory <outside-Git-directory>
-ConfigureSecrets` creates an RSA-3072 JKS key (CN=RedrainCast, 10,000-day validity)
only if neither key nor credentials exists, or reuses the existing matching pair.
Set `JAVA_HOME` to JDK 21 first. It protects only its new files/staging directory
for the current user and SYSTEM, never the whole destination folder.
Passwords use the OS CSPRNG; keytool receives environment variables and
`gh secret set` receives stdin. Never log/upload the key, credential file or
exports. Temporary Downloads storage is not an independent durable backup.

The pinned production certificate SHA-256 is
`79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`.
Key rotation requires explicit review and a signing-lineage/update plan.

## Exact-build LGPL relink materials

`release-kit.py` produces `android-lgpl-relink.tar.gz` from the same signed build:

- SHA-256-pin the public AAR, complete FFmpeg source, configuration/build helpers
  and compliance materials; never compile FFmpeg or access another checkout.
- Prove each JNI ELF matches the APK after the same stripping operation.
- Package matching application-native relocatable objects for all three ABIs,
  removing debug metadata and rejecting developer paths/credential-shaped data.
- Preserve exact NDK/API/ABI, link flags, archive order, static C++ runtime,
  soname and 16 KiB alignment; reject unexpected dependencies.
- Relink all ABIs, verify ELF machines/alignment and exact JNI exports, and
  reject unresolved symbols. This is a link/ELF smoke, not a device test.
- Bind APK/object/source hashes and source commit in the manifest and checksums;
  include `relink.py` accepting explicit modified FFmpeg archives.

The kit excludes raw Ninja/compile-command files, logs, signing material,
developer paths and application source (available separately in this repository).
Headers/configuration are included in the public AAR/build materials.
See [kit instructions](release-kit-README.md). Review licensing/privacy contents
before publication; mechanical checks are not legal advice.

## Version identity for future releases

Use the full version consistently in embedded `versionName`, tag and asset:

| Kind | Version | Tag | APK |
|---|---|---|---|
| RC | `0.5.0-rc.1` | `v0.5.0-rc.1` | `RedrainCast-0.5.0-rc.1.apk` |
| Beta | `0.5.0-beta.2` | `v0.5.0-beta.2` | `RedrainCast-0.5.0-beta.2.apk` |
| Alpha | `0.5.0-alpha.1` | `v0.5.0-alpha.1` | `RedrainCast-0.5.0-alpha.1.apk` |
| Stable | `0.5.0` | `v0.5.0` | `RedrainCast-0.5.0.apk` |

Use three canonical numeric components and optional `-alpha.N`, `-beta.N` or
`-rc.N` (positive N, no leading zeroes). No underscores, build metadata or bare
`-rc`. Commit the embedded version before review; the workflow never rewrites it.
Increase `versionCode` for every distributed build, including RC-to-stable.
The workflow derives prerelease status from the suffix and always creates a
draft. Stable publication requires separate explicit owner approval, including
known limitations. Do not alter the historical plain-version v0.4.6 preview.

## Build, review and publish

1. Validate changes and select exact committed current Android `main`.
   Only manual dispatch on `refs/heads/main` in this repository can sign.
2. Dispatch with matching `tag`, `source_validated=true`, full 40-character
   `reviewed_commit` and **`publish=false`**. Checkout, dispatch and live main must
   equal that SHA, rechecked before signing and draft upload. If main moves,
   review the new revision and restart; never approve stale artifacts.
3. Review the `signed-testing-candidate` artifact (3-day retention): production
   APK, checksums, certificate, licenses and exact-build relink kit. On this public
   repository, artifacts and workflow logs must not be treated as confidential.
4. Repeat on the same reviewed revision with `publish=true`. The publication job
   creates a draft in **RedrainCast-Android**, using `GITHUB_TOKEN`. Review the
   newly built candidate too. Nothing automatically makes the release live.
5. After explicit publication approval, publish the reviewed draft. Preserve
   rendering, shutdown, signing-migration and native/runtime acceptance limits
   in notes; automation is not Windows interoperability or physical-device proof.

Missing signing prerequisites, write permission or tag conflicts fail explicitly.
Never replace or reuse an existing release or tag, even one at the correct SHA
without a release. Atomic ref creation rejects a concurrent creator too.
Lookup/network errors fail closed. A failure after tag creation may leave that
new tag or a draft behind; stop and report it, never automatically delete,
overwrite or retry publication with that tag. A post-upload mismatch fails the
job and leaves the release a draft, not an approved/public release.
Before manually publishing any draft, recheck its tag binding as well.
Updated apps check only this repository's stable metadata; until then a 404 is
logged without affecting casting. Already-installed v0.4.18 and older apps still
check the historical repository until manually updated; old binaries/links are
not rewritten by this configuration change.

## Manual publication fallback

An explicitly authorized maintainer may use existing local `gh` authorization;
never export that login token into Actions. First require a successful
`publish=false` candidate run at reviewed current main, including tests/lint,
signing, certificate/alignment and all three ABI relink checks.
Verify all checksums, APK identity/certificate/ABIs and the manifest's exact
source/APK/object binding. Never mix builds, objects or source revisions.

Use these five explicit asset names, not a wildcard over local files:

```powershell
$tag = "v0.5.0-rc.1"
$version = $tag.Substring(1)
$kind = python3 -B .\tools\release-guard.py --release-tag $tag --apk-version $version
if ($LASTEXITCODE -ne 0) { throw "Invalid release version identity" }
$options = @("--draft")
if ($kind -eq "prerelease") { $options += "--prerelease" }
$env:GITHUB_REPOSITORY = "arisewanggithub/RedrainCast-Android"
$env:REVIEWED_COMMIT = $reviewedCommit
python3 -B .\tools\release-guard.py --release-tag $tag --create-tag
if ($LASTEXITCODE -ne 0) { throw "Tag reservation or source binding failed" }
$assets = @(
    "$candidate\RedrainCast-$version.apk",
    "$candidate\SHA256SUMS",
    "$candidate\signing-certificate.txt",
    "$candidate\licenses.tar.gz",
    "$candidate\android-lgpl-relink.tar.gz"
)
gh release create $tag @assets --repo arisewanggithub/RedrainCast-Android `
    --verify-tag @options --title "RedrainCast $tag" --notes-file $reviewedNotes
if ($LASTEXITCODE -ne 0) { throw "Draft creation failed; do not reuse the reserved tag" }
python3 -B .\tools\release-guard.py --release-tag $tag --verify-tag
if ($LASTEXITCODE -ne 0) { throw "Draft tag source binding failed; do not publish" }
```

`$reviewedCommit` must be the exact full SHA used by the successful candidate run.
Stop if the tag/release exists. Download draft assets with authorized `gh` access
and recheck every hash against the candidate before publishing.
After publication, anonymously download all five assets (for example `curl.exe
-q`, without authentication), compare hashes and verify APK signing/version/ABIs
and licensing/relink binding again. On mismatch, return the release to draft and
report failure. Do not upload secrets, keys, credential exports, build logs or
debug/test APKs. Signing exports stay outside Git and asset directories.
