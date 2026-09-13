# Building and publishing Hearth

The source is self-contained. Use the [README toolchain and gates](../README.md#build).
The application ID remains `com.sal7one.transiber` for compatibility; Hearth is the
visible name. Both flavors are arm64, Android 9+, with playback capture on Android 10+.

| Variant | Network | Translation/download differences |
| --- | --- | --- |
| play | Explicit cloud connections and HTTPS downloads | Includes Google ML Kit SDK/packs and provider clients |
| foss | No Internet/network-state permissions | No ML Kit, cloud actions or download delegation; import models locally |

Both flavors use the same application ID within a build type, so they replace one
another rather than installing side by side. Do not uninstall to switch if you need
to retain app-private history, keys and models. Both QA artifacts use `.qa` and debug
signing. Different computers may have different debug keys, so their QA APKs may not
upgrade each other. Debug builds use `.debug`; release uses the base ID.

## Before publishing

1. Run tests, native host suites, package tests, both QA builds and
   `python3 scripts/verify-release.py`. The verifier checks permissions, native
   dependency closure, 16 KB page alignment, packaged vendor hashes and provenance.
2. Scan all Git history with Gitleaks, including tags/branches:
   `gitleaks git --log-opts='--all' --redact=100 .`. CI pins Gitleaks and its checksum.
   Also scan the final source archive with `gitleaks dir --redact=100` after extraction.
3. Check `git status --short`, `git diff --check`, and ignored tracked files with
   `git ls-files --cached --ignored --exclude-standard`. No credentials, model
   weights, APKs, local SDK configuration or signing material belong in source.
4. Build from a clean checkout with ANDROID_HOME set. Keep the existing tagged
   native libraries and license assets; do not download model weights into Git.
5. Include changelog, privacy, third-party notices and actual tested-device limits.
   Enable GitHub private vulnerability reporting before directing users to it.

Create a source-only archive from the reviewed commit, never by zipping the working
folder (which contains ignored models/builds/local configuration):

```sh
git archive --format=zip --prefix=hearth/ HEAD -o /tmp/hearth-source.zip
```

The repository's Android workflow builds both QA APKs and uploads them as CI
artifacts. It does not publish releases or contain production signing secrets.
Creating the GitHub repository, pushing and publishing a release are explicit
maintainer actions. No repository URL is assumed in these instructions.

## Production signing

Release builds deliberately have **no signing configuration**. These commands produce
unsigned APKs for both flavors:

```sh
./gradlew :app:assemblePlayRelease :app:assembleFossRelease
python3 scripts/verify-release.py --build-type release
```

Create and securely back up a release keystore outside this repository using Android
Studio's **Generate Signed Bundle / APK** flow, or use an existing release key. With
Android build-tools 36.0.0 installed, the command-line signing flow is:

```sh
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -P 16 -f 4 \
  app/build/outputs/apk/play/release/app-play-release-unsigned.apk /tmp/hearth-play-aligned.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign \
  --ks /absolute/private/path/hearth-release.jks --ks-key-alias hearth \
  --out /tmp/hearth-play-release.apk /tmp/hearth-play-aligned.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs /tmp/hearth-play-release.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 -v 4 /tmp/hearth-play-release.apk
```

The signing tool prompts for passwords; do not put them in shell arguments or Git.
Repeat for foss using its unsigned APK. Keep the same key for future release
upgrades and bump versionCode/versionName per release. APKs and a SHA-256 checksum
file belong in GitHub release assets, not committed source. Google Play publication
additionally requires its current store declarations and signing workflow; a passing
local build is not store approval.
