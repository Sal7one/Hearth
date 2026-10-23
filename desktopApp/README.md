# Hearth desktop

Compose Multiplatform desktop UI, sharing the `hearth-core` pipeline with separate
desktop runtime, persistence and loopback-integration adapters. Android remains an
independent application target.

```sh
./gradlew -PhearthDesktopOnly=true :desktopApp:desktopTest :desktopApp:run
./gradlew -PhearthDesktopOnly=true :desktopApp:packageDmg             # macOS host
./gradlew -PhearthDesktopOnly=true :desktopApp:packageMsi :desktopApp:packageExe  # Windows host
```

Use JDK 17 (the pinned Gradle wrapper supports this build toolchain). Installer tasks run on their matching operating system;
configuring Windows formats on macOS does not build or validate Windows packages.
Packaging uses the full JDK 17 toolchain, independently of Android Studio's Gradle
daemon runtime. Override it with `-PhearthDesktopJavaHome=/path/to/full-jdk` if needed.
`-PhearthDesktopVersion=1.0.0` supplies the three-part installer version. Desktop
installer versions are independent of Android; macOS requires a nonzero major.
Signing/notarization and Windows signing require the owner's separate identities.
The macOS native bundle requires macOS 13.3 or later and a matching CPU architecture.

The app reads PCM16 WAV directly and other audio/video through the platform media
adapter (AVFoundation on macOS, Media Foundation on Windows); missing adapters or
unsupported codecs produce visible errors. It supports explicit microphone recording,
typed text, independent recognition/translation/voice selections, final transcript
export, generated-WAV playback and a floating caption window. Runtime errors and
unavailable native adapters remain visible. Models and credentials are separate
from installers; importing a model does not silently activate a pipeline stage.
An explicit setup import reconnects matching, fully verified installed catalog models;
missing models and provider credentials still require configuration.

The local integration server starts only after the user chooses Start. Its OBS
URL has a read-only overlay capability, separate from the explicitly copied API
bearer token. Neither reveals provider credentials.

Packaging follows the [official Compose desktop distribution guide](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html).

Native build instructions, platform coverage and module ownership: [desktop guide](../docs/desktop.md).
