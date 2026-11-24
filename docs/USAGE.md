# Using Cameraderie

## Import files

1. Open **Import from files or a memory card**. Choose individual files or a source folder from Android’s picker.
2. Choose **Import to phone album** to save to `DCIM/Cameraderie`, or **Import to folder** to grant access to a destination.
3. Open **Library** to see the saved copy, capture information, and import receipt. Use **Share** to send selected files through Android.

File import works independently of camera brand. Supported files include JPEG, HEIF, TIFF, common camera RAW formats, and MP4, MOV, and AVCHD video. Thumbnail and playback support depend on the file format and the phone.

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
2. Use **Add from Library** to choose verified imports. Add files from the project view to keep them unassigned, or add them inside a shoot to assign them there.
3. Tap a project file and choose **Move to shoot** to change its assignment. Use the shoot’s menu to edit its notes or move it earlier or later in the project.
4. Tap a file to open it or edit a supported original video. Project and shoot assignments preserve the original Library files and their receipts.

The [Himalayan Diaries example](WORKFLOW.md#himalayan-diaries) shows how to organize media from several cameras in one project.

## Edit a video

Open a verified original from **Library** and tap **Edit video**. Set the crop directly in the preview and drag the two trim handles to choose the range. Use **Find LUT** to choose a `.cube` file and **Video format** to select the encoder. The app shows Main 10 availability for the device and source. **Export MP4** publishes a separate file. When a compatible checkpoint is available, **Resume export** continues it. You can start a fresh export if the checkpoint fails verification.

## Attach ride telemetry

Open **Ride telemetry** from the editor and attach a GPX track or route, a CSV with a timestamp column, or a JSON sample array. Timestamp fields such as `time`, `timestamp`, and `timestamp_ms` accept ISO 8601 timestamps with offsets or Unix timestamps in seconds or milliseconds.

Confirm the clip’s start time and check the overlap with the track. A positive offset moves samples later in the clip. To mark a highlight, position the video playhead before opening the telemetry screen. Use **Save ride file** and **Save alignment JSON** to export both files. The association is stored against the verified original’s checksum.

## Relink an existing library

1. In OpenGrip, use **Imported Images → Export transfer for Cameraderie** to save a JSON transfer file.
2. Open **Relink OpenGrip library** in Cameraderie and select the file.
3. Choose each folder that contains originals. Older phone album imports are typically in `DCIM/GripBridge`. Cameraderie links a receipt after the selected file’s name, size, and SHA-256 match.
4. Select **Restore download queue** to bring over eligible entries. Authorize the same camera in Cameraderie and refresh its catalogue. Restored entries start from the beginning.

Check the linked files and receipts before retiring the previous library.
