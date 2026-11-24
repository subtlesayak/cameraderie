# Camera adapter contributions

The `camera` package describes the capture capabilities that the UI can expose. The DJI capture adapter derives its available commands from the active authorization, camera status, shooting mode, and pending command state. DJI browsing and transfer transport live in `gallery`. Sony Wi-Fi and USB paths live in `sony`. Shared import receipts, browsing of saved files, and the Media3 editor are in `gallery/media`.

## Shared DJI libraries

The app uses `:camera-core` for DJI protocol commands and state and `:camera-dji-ble` for Bluetooth discovery, connection, and reconnect. The libraries are shared with OpenGrip. Cameraderie includes a snapshot of both libraries so a standalone checkout can build directly.

The [shared camera modules v0.1.0 release](https://github.com/subtlesayak/cameraderie/releases/tag/camera-modules-v0.1.0) packages both modules from revision `8b893f7`. Download the archive and its `.sha256` file, then check and extract them in the same directory.

```sh
shasum -a 256 -c cameraderie-camera-modules-v0.1.0.tar.gz.sha256
tar -xzf cameraderie-camera-modules-v0.1.0.tar.gz
```

In either app checkout, point Gradle at the absolute path to the extracted `cameraderie-camera-modules-v0.1.0` directory.

```sh
sh ./gradlew --max-workers=2 -PcameraModulesDir=/absolute/path/to/cameraderie-camera-modules-v0.1.0 :app:testDebugUnitTest :app:assembleDebug
```

For a shared change in progress, use the other checkout’s modules. The following command uses an adjacent OpenGrip checkout.

```sh
./gradlew --max-workers=2 -PcameraModulesDir=../OpenGrip :app:testDebugUnitTest :app:assembleDebug
```

Cameraderie owns its private camera preferences, permissions, session lifecycle, and gallery UI. Update the bundled libraries together when bringing in a shared change.

## Adding an adapter

For a new adapter, record the physical camera model, firmware and Android version, supported connection method, required authorization, and the source of protocol information. Keep discovery and camera commands behind a user action. Report capability and confirmed state independently from write success. Protect active recording and release the existing session before starting another transport.

Add synthetic protocol and failure fixtures for the new behavior and record which checks were repeated on real hardware. Use public, redistributable fixtures. Keep private captures, credentials, signing material, and user media outside the repository.
