# Using Cameraderie

## Import files

1. Open **Import from files or a memory card**. Choose individual files or a source folder from Android’s picker.
2. Choose **Import to phone** to save photos and videos in `DCIM/Cameraderie` and other supported files in `Download/Cameraderie`. Use **Import to folder** to pick a destination.
3. Open **Library** to see the saved copy, capture information, and import receipt. Use **Share** to send selected files through Android.

File import works independently of camera brand. Supported files include JPEG, HEIF, TIFF, common camera RAW formats, and MP4, MOV, and AVCHD video. You can also save WAV, M4A, MP3, and FLAC audio, GPX tracks, and Insta360 INSV and INSP files. INSV and INSP are preserved for use in compatible software. Cameraderie can read the Insta360 camera model and capture time from the original file. If the camera recorded time without a timezone, Library shows it as camera time. Thumbnail and playback support depend on the file format and the phone.

## Connect directly to a camera

Choose the adapter in Cameraderie and follow its pairing or connection prompt. The [compatibility matrix](COMPATIBILITY.md) lists connection methods and available features. If the camera is recording or busy, finish the capture before opening playback. To continue interrupted downloads, reconnect to the same camera, refresh its catalogue, and open **Download queue**. Review any prompts to restart a transfer.

### Connect over USB

1. Connect the camera with a data cable to an Android phone with USB host support. Choose PTP, MTP, or the camera’s media-transfer option.
2. Tap **Connect a USB camera** in Cameraderie and grant Android access to the selected device. When the catalogue loads, select the files to import.
3. Choose the phone album or a destination folder. Cameraderie verifies the saved bytes against the transferred file. If Android presents the camera as a drive instead, use **Import from files or a memory card**.

### Sony USB viewfinder

1. Set **USB Connection** to **PC Remote** on the camera. To save test photos to the card, set **PC Remote Settings → Still Img. Save Dest.** to **PC+Camera** before connecting and use single-shot drive. For video, set the mode dial to Movie before connecting.
2. In Cameraderie, open **Open Sony USB viewfinder**, tap **Connect over USB**, and grant Android access. The viewfinder shows the shooting controls the camera reports for its current settings.
3. When recording, tap **Stop recording** and wait for the camera to report idle. To browse files on the card over USB, disconnect, change the camera to MTP, and reconnect via **Connect a USB camera**.

## Organize a project

1. Open **Projects** and create a project. Use **New shoot** for a day, location, or sequence. Project and shoot notes are saved on the phone.
2. Use **Add from Library** to choose verified imports. Add files from the project view to keep them unassigned, or add them inside a shoot to assign them there. Camera originals, audio, and GPX tracks can share a shoot.
3. Tap a project file and choose **Move to shoot** to change its assignment. Use the shoot’s menu to edit its notes or move it earlier or later in the project.
4. Tap a file and choose **Mark as selected take**. The mark is saved in the project and follows the file when you move it to another shoot.
5. Tap a file to open it or edit a supported original video. An MP4 exported from a project appears under **Exports** in the source shoot and in Library. Tap the export to open it or return to the verified original. Pausing and resuming preserves the project association.

Choose **Filter media** from the menu in Library, Projects, or a shoot. Combine camera, capture date, and file format. Library and the project overview also let you choose a shoot or **Unassigned**. In a project or shoot, **Selected takes only** shows marked originals and their linked exports. Exports use the camera and capture date from their verified source when available. Earlier imports with missing metadata appear under **Camera not recorded** or **Capture date not recorded**.

The [Himalayan Diaries example](WORKFLOW.md#himalayan-diaries) shows how to organize media from several cameras in one project.

## Edit a video

Open a verified original from **Library** and tap **Edit video**. Set the crop directly in the preview and drag the two trim handles to choose the range. Use **Find LUT** to choose a `.cube` file and **Video format** to select the encoder. The app shows Main 10 availability for the device and source. **Export MP4** publishes a separate file. When a compatible checkpoint is available, **Resume export** continues it. You can start a fresh export if the checkpoint fails verification.

## Send a cut to DaVinci Resolve

1. Open a verified original from a shoot or Library and set the trim in the video editor. Choose **Save Resolve package** and save the ZIP to a location you can open on your computer.
2. Unzip it. Put the original, with its exact filename, in a `media` folder beside the timeline files. Compare the source SHA-256 with `manifest.json` before relinking.
3. Import `timeline.otio` in Resolve. `timeline.xml` is the Final Cut Pro 7 XML fallback. Relink to the original if Resolve prompts for media. Both timelines use frame-rounded versions of the cut and include source audio when present. The manifest also records the exact trim in milliseconds.
4. Apply `look.cube` and the crop settings in the manifest when you want to reproduce the phone edit. If a ride is attached, the `ride` folder contains the original track and its saved alignment. Highlight markers inside the selected trim are placed on the exported timeline.

In GPStitch, use the exported GPX for a transparent overlay. A ride sample’s position on the cut is its timestamp minus the saved clip start, plus the saved ride offset, minus the trim start. Use the alignment JSON to check matching events in the video and track before placing the rendered overlay in Resolve. [GPStitch overlay workflow](https://github.com/Romancha/GPStitch#overlay-only-mode) · [Resolve timeline import guide](https://documents.blackmagicdesign.com/UserManuals/DaVinciResolveReferenceManual.pdf)

### Send selected project takes

1. Mark the videos you want as **Selected take**. To keep a trim, crop, and LUT without rendering an MP4, open the video from the project or shoot, edit it, and choose **Save cut to project**. The saved cut follows the original when you move it to another shoot.
2. Open the project or shoot menu and choose **Save selected takes for Resolve**. The project export follows shoot order, then the files’ order within each shoot. Each clip uses its saved project cut, its latest linked published trim if available, or its full length.
3. Unzip the package and place each original at the path recorded in `manifest.json`. Numbered media folders keep clips with identical filenames distinct. Each clip has its own trim, camera source, and checksums, with LUT and ride files under its numbered `clips` folder when available.
4. Import `timeline.otio` in Resolve. If you use the XML fallback, clip placement and markers are rounded to the timeline frame rate. Both formats keep gaps in the audio track where a clip has no source audio.

## Attach ride telemetry

Open **Ride telemetry** from the editor and attach a GPX track or route, a CSV with a timestamp column, or a JSON sample array. Timestamp fields such as `time`, `timestamp`, and `timestamp_ms` accept ISO 8601 timestamps with offsets or Unix timestamps in seconds or milliseconds.

Confirm the clip’s start time and check the overlap with the track. A positive offset moves samples later in the clip. To mark a highlight, position the video playhead before opening the telemetry screen. Use **Save ride file** and **Save alignment JSON** to export both files. The association is stored against the verified original’s checksum.

## Relink an existing library

1. In OpenGrip, use **Imported Images → Export transfer for Cameraderie** to save a JSON transfer file.
2. Open **Relink OpenGrip library** in Cameraderie and select the file.
3. Choose each folder that contains originals. Older phone album imports are typically in `DCIM/GripBridge`. Cameraderie links a receipt after the selected file’s name, size, and SHA-256 match.
4. Select **Restore download queue** to bring over eligible entries. Authorize the same camera in Cameraderie and refresh its catalogue. Restored entries start from the beginning.

Check the linked files and receipts before retiring the previous library.
