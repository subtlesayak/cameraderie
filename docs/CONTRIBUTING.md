# Camera adapter contributions

The `camera` package describes the capture capabilities that the UI can expose. The DJI capture adapter derives its available commands from the active authorization, camera status, shooting mode, and pending command state. DJI browsing and transfer transport live in `gallery`. Sony Wi-Fi and USB paths live in `sony`. Shared import receipts, browsing of saved files, and the Media3 editor are in `gallery/media`.

For a new adapter, record the physical camera model, firmware and Android version, supported connection method, required authorization, and the source of protocol information. Keep discovery and camera commands behind a user action. Report capability and confirmed state independently from write success. Protect active recording and release the existing session before starting another transport.

Add synthetic protocol and failure fixtures for the new behavior and record which checks were repeated on real hardware. Use public, redistributable fixtures. Keep private captures, credentials, signing material, and user media outside the repository.
