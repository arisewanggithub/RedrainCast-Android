# Public Android releases

Application source stays in the private RedrainCast-Android repository. Public
APKs and distribution materials go only to
[RedrainCast-Releases](https://github.com/arisewanggithub/RedrainCast-Releases).
The manually triggered `Public Android release candidate` workflow never publishes a
source archive, debug APK, instrumentation APK, build log or signing keystore.
GitHub's automatic source links in that public repository contain only its public
download documentation, not this application.

## Required configuration

Signing is blocked unless all four signing secrets are configured. Public
draft upload additionally requires the scoped publisher secret:

- `ANDROID_RELEASE_KEYSTORE_BASE64`: a dedicated production keystore.
- `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_PASSWORD`,
  `ANDROID_RELEASE_KEY_ALIAS`: its credentials. Do not put these in Git or logs.
- `RELEASE_PUBLISH_TOKEN`: a fine-grained token limited to Contents read/write
  for the public downloads repository. The private repository's ordinary
  `GITHUB_TOKEN` cannot publish releases into another repository.

Keep an independently backed-up production key outside the repository. Losing
it prevents future in-place APK updates. Production signing is different from
the debug certificate used on the development phones; switching certificates
requires uninstalling that existing app and loses its local data. Do not
uninstall or change device signing automatically.

On Windows, `tools\release-signing.ps1 -Directory <outside-Git-directory>
-ConfigureSecrets` creates a dedicated RSA-3072 JKS key (CN=RedrainCast,
10,000-day validity) only if neither key nor credentials exists, or reuses the
existing matching pair. Set `JAVA_HOME` to JDK 21 first. It protects only its
new files/staging directory for the current Windows user and SYSTEM, never the
whole destination folder. Passwords come from the OS CSPRNG; keytool receives
them through environment variables and `gh secret set` through stdin.
Never commit, upload as an artifact, or log the key/credential file. A temporary
Downloads directory is **not** an independent durable backup. Back up both
files securely before publishing.

The configured production certificate SHA-256 is
`79f906220de117d61c2d15bdfcef179a4e3e48cfa0c76b637c7af986c037fad3`;
the workflow pins and checks it after signing. Key rotation requires explicit
review and breaks normal in-place updates without a signing-lineage plan.

Create a fine-grained PAT using
[GitHub token settings](https://github.com/settings/personal-access-tokens/new?name=RedrainCast%20release%20publisher&description=Upload%20reviewed%20Android%20testing%20drafts&target_name=arisewanggithub&expires_in=30&contents=write).
Select **only RedrainCast-Releases**, Contents read/write, and a short expiry.
Set it as `RELEASE_PUBLISH_TOKEN` in the private application's Actions secrets
(for example, `gh secret set RELEASE_PUBLISH_TOKEN --repo
arisewanggithub/RedrainCast-Android`, entering it interactively). The settings
link prefills the owner, permission and 30-day expiry, **not repository
selection**: select only the public downloads repository yourself, then verify
the permissions before creating it. GitHub's documented
[template parameters](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#pre-filling-fine-grained-personal-access-token-details-using-url-parameters)
do not include repository selection.
Never copy the existing broad `gh` login token into Actions. A properly scoped
GitHub App installation token is also acceptable, but account consent cannot be
manufactured by the workflow.

## Automatic LGPL relink materials

`release-kit.py` now produces `android-lgpl-relink.tar.gz` from the **same**
release build, after signing; no external artifact run ID is needed. It:

- Pins/downloads the public AAR, corresponding complete FFmpeg source,
  configuration/build helpers and compliance materials by SHA-256. It does not
  compile FFmpeg or access the private native-build checkout.
- Proves each original JNI ELF matches the APK byte-for-byte after the same
  `llvm-strip --strip-unneeded` operation, refusing ambiguous/missing objects.
- Packages the three matching relocatable JNI application objects, removing
  only debug metadata to avoid publishing private source/build paths. It
  rejects private-path/credential-shaped object or metadata contents.
- Preserves CMake link flags, static C++ runtime, exact NDK/API/ABI, static
  archive order, soname and 16 KiB alignment. Unexpected dependencies/flags
  fail instead of silently falling back.
- Relinks all three ABIs using the packaged objects/public FFmpeg libraries,
  checks ELF machines/LOAD alignment and the exact APK's JNI export sets, and
  refuses unresolved symbols. This is a link/ELF smoke, not a device test.
- Includes a manifest binding APK/object/source hashes and commit, internal
  checksums, and `relink.py` accepting explicit **modified** FFmpeg archives.

The archive includes no private application source, raw Ninja/compile-command
files, logs, signing material or developer paths. Full headers/configuration
are in the included public AAR and build materials. See
[kit instructions](release-kit-README.md), including modified-APK signing.
Maintainers must still review the candidate's licensing/privacy contents before
publishing; mechanical checks are not legal advice.

## Publishing

### Version identity for future releases

Use the same full version everywhere:

| Kind | Embedded `versionName` | GitHub tag | APK asset |
|---|---|---|---|
| Release candidate | `0.5.0-rc.1` | `v0.5.0-rc.1` | `RedrainCast-0.5.0-rc.1.apk` |
| Beta | `0.5.0-beta.2` | `v0.5.0-beta.2` | `RedrainCast-0.5.0-beta.2.apk` |
| Alpha | `0.5.0-alpha.1` | `v0.5.0-alpha.1` | `RedrainCast-0.5.0-alpha.1.apk` |
| Stable | `0.5.0` | `v0.5.0` | `RedrainCast-0.5.0.apk` |

Supported versions have three canonical numeric components and an optional
`-alpha.N`, `-beta.N` or `-rc.N` suffix, where N starts at 1 without leading
zeroes. Underscore suffixes, build metadata, and bare `-rc` are not accepted.
Set the embedded version in `app/build.gradle.kts` before reviewing/committing
the release source; the workflow does not rewrite it from the requested tag.
Every distributed build must increase Android's numeric `versionCode`, including
RC-to-stable transitions (for example: RC1 code 21, RC2 code 22, stable code 23).
Removing a suffix alone does not make an APK installable over a higher code.

The workflow derives GitHub's prerelease flag from the suffix rather than an
independent toggle. It validates the exact embedded APK/tag identity, preserves
the suffix in the APK filename and relink manifest, and always uploads a
**draft**. Only suffixed versions receive `--prerelease`; a bare version creates
a stable-designated draft requiring separate final review before publication.
The stable-only app updater accepts installed prerelease versions and considers
their matching final stable version newer, but never offers another prerelease.

The already published plain-version `v0.4.6` testing release is a historical
exception. Do not retag, rename its assets, rebuild it or rewrite its checksums.

1. Finish and validate the application changes first. Select the exact committed
   revision on private `main`; do not sign an unfinished audio/rendering
   candidate as ready. Only manual dispatch in the private repository on
   `refs/heads/main` can start the release job, never PRs, forks or tags.
2. Run the workflow manually with `tag` matching the APK version,
   `source_validated=true`, `reviewed_commit` containing the full 40-character
   SHA of current `main`, and **`publish=false`** (the default).
   Checkout, dispatch and live remote `main` must all equal that reviewed SHA;
   this is rechecked before signing and public draft upload. If `main` moves,
   review the new revision and restart instead of approving stale artifacts.
   It tests/lints, builds/signs the optimized APK, pins the certificate,
   alignment-checks it and creates/smokes the relink kit.
3. Review the private `signed-testing-candidate` artifact (7-day retention):
   production APK, checksums, certificate, licenses and relink kit.
4. With the scoped publisher configured, repeat at the same reviewed revision
   with `publish=true` to create a public-repository **draft** of the inferred kind.
   Review that newly built candidate too. No release is automatically made live.
   Manually publish only after final asset/compliance/runtime review.

Do not publish a stable release while the first-rotation rendering issue remains
unresolved or claim unverified Windows interoperability. Draft upload does not
mean the candidate is approved. The workflow cannot create a scoped PAT without
the owner's external authorization. Missing prerequisites fail explicitly.

## Initial manual publication with existing local authorization

A maintainer explicitly authorized to publish may use their existing local
`gh` login for the initial release without installing that broad login token
in Actions. This does **not** configure automatic cross-repository publication;
`RELEASE_PUBLISH_TOKEN` still requires a separately approved scoped credential.

First run the private-candidate workflow with `publish=false` and the exact
reviewed current-main SHA. Require its successful tests/lint/signing and all
three ABI relink checks. Download only `signed-testing-candidate`; verify its
complete checksums, APK version/code/certificate/ABIs and kit manifest binding
to that exact signed APK, native objects and source revision. Never rebuild a
dirty newer working tree under an older version or borrow mismatched objects.

For future releases, use these **five explicit asset names** from the verified
private candidate directory, not a wildcard over local files. The example uses
an RC; for stable candidates remove the suffix in the committed app version and
tag, increment versionCode, and complete stable-readiness review first:

```powershell
$tag = "v0.5.0-rc.1"
$version = $tag.Substring(1)
$kind = python3 -B .\tools\release-guard.py --release-tag $tag --apk-version $version
if ($LASTEXITCODE -ne 0) { throw "Invalid release version identity" }
$options = @("--draft")
if ($kind -eq "prerelease") { $options += "--prerelease" }
$assets = @(
    "$candidate\RedrainCast-$version.apk",
    "$candidate\SHA256SUMS",
    "$candidate\signing-certificate.txt",
    "$candidate\licenses.tar.gz",
    "$candidate\android-lgpl-relink.tar.gz"
)
gh release create $tag @assets --repo arisewanggithub/RedrainCast-Releases `
    --target main @options --title "RedrainCast $tag" --notes-file $reviewedNotes
```

Stop if that release/tag unexpectedly exists; do not overwrite another
publisher's work. Inspect the draft and download its assets using authorized
`gh` access, rechecking every hash against the private candidate. Only then
publish it with that same inferred kind; do not promote an RC to stable merely
by toggling GitHub's flag or renaming its asset. Draft assets cannot be fetched
anonymously; after publishing, download all five assets anonymously (for
example with `curl.exe -q`, without authentication headers), compare their
hashes again and verify APK signing/version/ABIs and the licensing/relink kit.
If published bytes fail verification, return the release to draft and report
the failure rather than advertising a successful download.

Do not upload keystores, credential files, base64/PFX exports, private source,
build logs or debug/test APKs. Signing exports stay outside Git and release
asset directories. Preserve the known rendering issue and signing-migration
warnings in the release notes.
