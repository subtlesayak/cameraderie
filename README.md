# Cameraderie

**See. Shoot. Save.** An Android camera companion for the Sony a6400 and DJI Osmo Action 6, with an offline media library and on-device editing. Cameraderie was separated from **OpenGrip Code130** to give its camera gallery, transfers, and editor a standalone app. OpenGrip handles physical remotes, mappings, and automation.

## Workflows

- **DJI Osmo Action 6.** Authorize the camera over Bluetooth. Use the shooting controls for photo shutter, normal-video recording, and mode selection when the camera reports a compatible state. Open the gallery to join the camera’s Wi-Fi, browse SD or internal storage, preview supported recordings, and queue originals.
- **Sony a6400.** Join camera Wi-Fi by QR code, manual entry, or a saved connection, or connect a USB cable and grant Android access. Choose Original, 2M, or VGA for compatible JPEG imports. The camera and transport determine which files and representations are offered.
- **Library.** Select files or a folder from Android’s picker. Save verified imports to the phone album or a chosen writable folder. Open and share saved files through Android. The library shows capture information and records import checksums.
- **Editing.** Open a verified original video from Library. Crop in the preview, trim with the filmstrip, save a full-resolution frame, or apply a `.cube` LUT and export a separate MP4. Main 10 output depends on the device encoder and source. Supported export checkpoints can resume locally.
- **Ride telemetry.** Attach a GPX track or route, a CSV with a timestamp column, or a JSON sample array. Use ISO 8601 timestamps with offsets or absolute Unix timestamps in seconds or milliseconds. Edit the clip start time and signed offset, save playhead highlights, and export the original ride file and alignment JSON. Cameraderie keeps the attachment linked to the verified video checksum.

The [compatibility matrix](docs/COMPATIBILITY.md) lists device evidence and pending capabilities. Camera Wi-Fi and Bluetooth requests are initiated locally. Files leave the device when you explicitly choose a destination or another app in Android’s picker or share sheet.

## Relink OpenGrip imports

1. In OpenGrip, open **Imported Images → Export transfer for Cameraderie** and save the JSON transfer file.
2. In Cameraderie, open **Relink OpenGrip library** and choose that JSON file. For each original destination, choose the folder and grant access. Older phone album imports are typically in `DCIM/GripBridge`.
3. Cameraderie checks filenames, sizes, and SHA-256 hashes before linking receipts to the selected files. Review the linked library and open a verified original before retiring the OpenGrip workflow.
4. To restore eligible DJI queue entries, select **Restore download queue**. Authorize the same camera in Cameraderie, reconnect, and refresh its catalogue. Restored entries start from the beginning. Cameraderie’s own interrupted downloads can resume from its private verified staging when supported.

## Build

Use JDK 21, Android SDK 37.2, and Android Build Tools 36.0.0. Set `ANDROID_HOME` or `sdk.dir` in an untracked `local.properties`.

```sh
./gradlew --max-workers=2 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w app.cameraderie.test/app.cameraderie.NativeChecks
```

Application ID `app.cameraderie`, version `0.1.0`. Assemble `:app:assembleRelease` and sign the unsigned APK with the Cameraderie release key kept outside the repository. Use a stable key for updates. Verify a signed release with `apksigner verify --verbose --print-certs` and check its application ID and non-debuggable manifest before distribution.

New adapters should record model, firmware, transport, evidence, and unsupported or untested capabilities. See [adapter contributions](docs/CONTRIBUTING.md), the [MIT license](LICENSE), and [upstream notices](THIRD_PARTY_NOTICES.md).
