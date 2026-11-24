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

The import-history upgrade build passed 602 JVM tests, debug and release assembly, and lint with zero errors. Forty native checks passed on an Android 12 emulator. The preceding Galaxy S24 FE build passed 39 native checks. Cameraderie also passed the JVM suite and debug assembly with `-PcameraModulesDir=../OpenGrip`. The release APK’s signature was checked using a dedicated verification key. Live-camera checks are tracked in the [compatibility matrix](COMPATIBILITY.md).

The version 1 to version 2 SQLite upgrade rebuilds the indexes used for completion sorting and stage pagination while keeping journal rows in place. The Android 12 upgrade test covered complete, legacy, staged, verified, and interrupted rows and completed a verified folder import after upgrading. Opening Library after an in-place update of the Galaxy test build preserved all eight existing journal rows and their SHA-256 receipts.
