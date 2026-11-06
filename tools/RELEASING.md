# Public Android releases

Application source stays in the private RedrainCast-Android repository. Public
APKs and distribution materials go only to
[RedrainCast-Releases](https://github.com/arisewanggithub/RedrainCast-Releases).
The manually triggered `Public Android prerelease` workflow never publishes a
source archive, debug APK, instrumentation APK, build log or signing keystore.
GitHub's automatic source links in that public repository contain only its public
download documentation, not this application.

## Required configuration

Publication is intentionally blocked until a maintainer configures:

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

## LGPL distribution prerequisite

Provide a reviewed `android-lgpl-relink` artifact in a successful workflow run
of this private repository. It must contain:

- `android-lgpl-relink.tar.gz`: corresponding FFmpeg source/build configuration,
  licenses, and the application-native object files and reproducible instructions
  needed to relink the shipped FFmpeg-linked JNI libraries with modified LGPL
  libraries for all three ABIs.
- `version.txt`: exact APK version such as `0.4.6`.
- `SHA256SUMS`: checksums covering both files.

Review that archive before uploading it: no private Kotlin/Java source,
credentials, absolute developer paths, device identifiers or unrelated files.
The public native-package release supplies FFmpeg source/build materials, but
does not by itself supply the application-specific relink kit. The workflow
checks version and hashes; those checks do not replace the maintainer's
licensing/privacy review. No relink artifact producer is configured yet.

## Publishing

Run the workflow manually with `tag` matching the APK's version and
`relink_run_id` referencing the successful run holding the reviewed artifact.
It runs unit tests/lint, builds the optimized release APK, checks the package
version, signs/verifies/alignment-checks it, and creates a public **prerelease**
with APK, checksums, certificate, notices and relink kit.

Do not publish a stable release while the first-rotation rendering issue remains
unresolved. The initial setup does not generate a production key, configure a
publishing token, or upload an APK. Missing prerequisites fail explicitly.
