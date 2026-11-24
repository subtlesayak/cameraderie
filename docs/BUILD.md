# Build and verify Cameraderie

## Build and install

Use JDK 21, Android SDK 37.2, and Android Build Tools 36.0.0. Set `ANDROID_HOME` or add `sdk.dir` to an untracked `local.properties`.

```sh
./gradlew --max-workers=2 :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK installs as `app.cameraderie`. Android 12 and later are supported.

## Tests

```sh
./gradlew --max-workers=2 :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r app.cameraderie.test/app.cameraderie.NativeChecks
```

## Release signing

```sh
./gradlew --max-workers=2 :app:assembleRelease
apksigner sign --ks "$CAMERADERIE_KEYSTORE" --out Cameraderie.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose --print-certs Cameraderie.apk
```

Keep the release key outside the repository and use the same identity for updates. Before publishing, check the application ID, version, and non-debuggable manifest.

## Verification

The USB adapter build passed 594 JVM tests, 39 native checks on an Android 12 emulator, debug and release assembly, and lint with zero errors. The preceding Galaxy S24 FE build passed 39 native checks. Cameraderie also passed the JVM suite and debug assembly with `-PcameraModulesDir=../OpenGrip`. The release APK’s signature was checked using a dedicated verification key. Live-camera checks are tracked in the [compatibility matrix](COMPATIBILITY.md).
