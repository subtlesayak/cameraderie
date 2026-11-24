# Cameraderie

**See. Shoot. Save.**

Cameraderie is a brand-agnostic Android app for browsing camera media, importing originals, and editing video. It was separated from **OpenGrip** as a standalone app.

- Work with media from cameras, memory cards, and phone storage through Android’s file picker.
- Keep filenames, capture information, and checksums in an offline library.
- Edit and export separate files on your phone. Share them through Android when you choose.

## Features

- [Camera connections](docs/COMPATIBILITY.md) for discovery, shooting controls, media browsing, and proxy playback where the adapter supports them.
- [Verified imports](docs/USAGE.md#import-files) to the phone album or a selected folder, with duplicate detection and resumable camera downloads where supported.
- [Video editing](docs/USAGE.md#edit-a-video) with in-preview crop, filmstrip trimming, full-resolution frame extraction, `.cube` LUTs, and AVC or 10-bit HEVC export.
- [Ride telemetry](docs/USAGE.md#attach-ride-telemetry) from GPX, CSV, or JSON, aligned by capture time with an adjustable offset and highlight markers.
- [Library relinking](docs/USAGE.md#relink-an-existing-library) for existing files, verified receipts, and eligible download queue entries.

## Prerequisites

Cameraderie requires Android 12 (API 31) or later. File and memory card imports use Android’s storage picker.

Any camera that implements a protocol supported by Cameraderie should work with the corresponding adapter if it meets the adapter’s model and firmware requirements. Available features depend on the commands the camera exposes. [Camera compatibility](docs/COMPATIBILITY.md)

## Camera models

| Camera models | Connection to try | Cameraderie status |
| --- | --- | --- |
| DJI Osmo Action 6 | Bluetooth and local Wi-Fi | Shooting and gallery adapter implemented. Physical verification pending. |
| DJI Osmo Action 5 Pro, Action 4, Osmo 360 | DJI R SDK over Bluetooth and USB file transfer where offered | Candidates for model-by-model testing. |
| DJI Osmo Action 3, Osmo Nano | USB file transfer | Candidates for wired transfer testing. |
| Sony a6400 (ILCE-6400) | Sony media transfer over local Wi-Fi and USB MTP | Gallery adapter implemented. Physical verification pending. |
| Sony a6100 (ILCE-6100), a6600 (ILCE-6600) | Sony media transfer over local Wi-Fi and USB MTP | Candidates for the same media adapter and the general USB path. |
| Nikon Z6III, Z8 | USB MTP/PTP | Candidates for the general USB path. |
| Fujifilm X-T5, Canon EOS R50 | USB PTP or the camera’s photo transfer mode | Candidates for wired transfer testing. |
| GoPro HERO12 Black, HERO13 Black | Open GoPro over Bluetooth and HTTP | Candidates for a separate adapter. |
| Insta360 X4, X5 | USB file transfer | Candidates for Android’s storage picker and future adapter work. |
| Other cameras | USB PTP/MTP, USB storage, or a memory card | Use the connection mode exposed by the camera and supported by the phone. |

The USB adapter discovers standard PTP/MTP interfaces across camera brands. Wireless browsing and shooting controls depend on each camera’s protocol and capabilities. [Protocol analysis and hardware verification](docs/COMPATIBILITY.md)

## Tested

- **Android device.** 39 native checks passed on an Android 12 emulator. The earlier Galaxy S24 FE build also passed 39 native checks.
- **Build.** 594 JVM tests passed. Debug and release APKs assembled, and lint reported zero errors. [Build verification](docs/BUILD.md#verification)
- **Direct camera connections.** Physical testing is pending for the standalone app. Results will be recorded by camera model, firmware, and transport. [Camera compatibility](docs/COMPATIBILITY.md)

## Get the app

[Build and install from source](docs/BUILD.md#build-and-install). The Android package name is `app.cameraderie`.

## Usage examples

- **Import from a memory card.** Attach a reader, open **Import from files or a memory card**, then choose the files or source folder and a destination.
- **Edit a clip.** Open a verified video in **Library**, tap **Edit video**, and set the crop and trim. Choose a LUT and output format, then tap **Export MP4**.
- **Save a frame.** Scrub to a position in the video editor and tap **Save full-resolution frame**.
- **Align a ride.** Open **Ride telemetry** from the video editor, select a ride file, then adjust the offset or mark the playhead.

## Documentation

- [User guide](docs/USAGE.md)
- [Camera compatibility](docs/COMPATIBILITY.md)
- [Building, testing, and signing](docs/BUILD.md)
- [Contributing camera adapters](docs/CONTRIBUTING.md)

Cameraderie is available under the [Apache License 2.0](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for upstream terms.
