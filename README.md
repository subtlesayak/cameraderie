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

Cameraderie requires Android 12 (API 31) or later. File and memory card imports use Android’s storage picker. For a direct camera connection, use the transport and permissions listed for your camera. [Camera compatibility](docs/COMPATIBILITY.md)

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

Cameraderie is available under the [MIT License](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for upstream terms.
