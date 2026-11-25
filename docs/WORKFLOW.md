# Project and shoot workflow

Cameraderie’s product goal is an offline workspace organized around the project you are making. The Library holds original files, capture information, and verification receipts. A project references media from the Library. Within a project, shoots group material by a day, location, sequence, or unit of work.

## Himalayan Diaries

| Shoot | Material |
| --- | --- |
| Day 1 · Departure | Sony a6400 stills, DJI Osmo Action 6 riding footage, an Insta360 X4 clip, and a GPX track. |
| Day 2 · Mountain pass | Sony landscape stills, Action 6 helmet footage, X4 viewpoints, location audio, and notes on selected takes. |

Both shoots belong to one project. You can review the whole trip, open just the mountain-pass shoot, or filter either view by camera, capture time, or format.

## Target workflow

1. **Create a project and its shoots.** Name and order the shoots as the production takes shape. Add notes at project or shoot level.
2. **Import and verify.** Bring in originals from supported camera connections, memory cards, and local folders. Keep exact filenames, capture metadata, and SHA-256 receipts in the Library.
3. **Assign media to shoots.** Use capture time to help find the right material and correct assignments manually when camera clocks differ. Files can stay unassigned while you sort them. Moving an item between shoots changes its project reference and preserves the original file and receipt.
4. **Review and select.** Browse a shoot across camera brands. Keep RAW and JPEG pairs together, and associate video with its proxy, audio, and telemetry where available. Mark selected takes and add notes.
5. **Edit, export, and back up.** Open a verified clip from its shoot, apply the crop, trim, and look, and save a separate export. Keep the result associated with its shoot and source. Back up selected originals and project metadata to user-selected storage and verify the copy.

## Acceptance

- Create **Malaysian Diaries** with **Day 1 · Departure** and **Day 2 · Mountain pass**. Use verified media from the a6400, Action 6, and X4, plus audio, a ride track, and shoot notes.
- Review both shoots offline after restarting the app. Confirm that project and shoot views show the correct media and that filters work across camera brands.
- Reassign a verified original between shoots and confirm that its filename, SHA-256, and Library receipt are unchanged.
- Open a selected take from a shoot, export a separate file, and verify its association with the shoot and original. Check that a verified backup can be read from the selected destination.

## Emulator verification

On an Android 12 emulator, Cameraderie created Malaysian Diaries and both shoots, assigned a verified JPEG and MP4, moved the JPEG to Day 2, and reopened the project offline after a process restart. Independent readback confirmed both original hashes and Library receipts were unchanged. This check used synthetic media.

A follow-up used a saved Sony a6400 JPEG identified by EXIF and an Action 6 MP4 from the wireless test. Cameraderie imported both through the file picker and assigned the JPEG to Day 1 and the MP4 to Day 2. The published APK updated in place and reopened the project offline. The saved copies matched the source SHA-256 values and their Library receipts.

For the linked-export check, a synthetic video was trimmed and exported as a separate H.264 MP4 from **Day 2 · Mountain pass**. After an offline process restart, the shoot listed the export and its original. Independent readback decoded the export, matched its SHA-256 receipt, and confirmed the source file and original receipt were unchanged.

The initial audio, track, and Insta360 file tests used synthetic WAV, GPX, INSV, and INSP fixtures on Android 12. The native test verified the saved bytes, filenames, duplicate detection, and persistence of the shoot assignments after reopening the project database. A separate file-picker run imported WAV, GPX, and INSV together from one folder. Physical X4 card and USB import and 360° playback remain to be verified.

The filter check used the verified Sony a6400 JPEG and DJI Osmo Action 6 MP4 imported through Android’s file picker. The app recorded each camera and capture date from the file. With networking off after a cold restart, the Sony JPEG appeared in Day 1 and the Action 6 clip in Day 2. Combining camera, capture date, format, and shoot in the project showed only the matching clip. The Day 2 shoot and the Library shoot filter also returned that clip. Independent readback confirmed both saved files matched their original bytes and SHA-256 receipts.

The selected-take check upgraded the same Malaysian Diaries project in place. Both camera originals stayed marked after an offline restart. The Sony JPEG moved from Day 1 to Day 2 and back with the same filename, SHA-256, and Library receipt. Day 1 also reopened with a published 828-point GPX track, and Day 2 with a WAV derived from the Action 6 recording. The imported WAV and GPX matched their source files byte for byte. [GPX sample publisher](https://www.360camsters.com/360-cameras/insta360-x4/)

In Day 2, a marked synthetic clip was shortened in the editor and exported as a separate AVC/AAC MP4. **Selected takes only** displayed both the marked clip and its linked export after an offline restart. Independent readback decoded the export, matched its SHA-256 receipt, and verified the exact original receipt and shoot in the export’s metadata.

The three-camera check imported the complete 1,183,762,666-byte X4 INSV through Android’s file picker and added it to **Day 2 · Mountain pass**. Cameraderie read the X4 model and the 21:31:45 camera time on April 15, 2024 from the file’s trailer. Independent SHA-256 readback matched the original after the staging copy was removed and the app restarted offline. Both shoots retained their media and notes. Combining camera, capture date, and INSV filters in Day 2 returned only the X4 original. Physical X4 USB and card-reader mounting remain to be checked. [Original sample publisher](https://360rumors.com/insta360-x4-review/) · [Insta360 trailer reference](https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/QuickTimeStream.pl#L3144) · [Camera clock field](https://github.com/AdrianEddy/telemetry-parser/blob/master/src/insta360/extra_info.rs)

See the [user guide](USAGE.md) for workflows available in the app and the [compatibility matrix](COMPATIBILITY.md) for connection and hardware test results.
