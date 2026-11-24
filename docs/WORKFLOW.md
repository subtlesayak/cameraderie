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

See the [user guide](USAGE.md) for workflows available in the app and the [compatibility matrix](COMPATIBILITY.md) for connection and hardware test results.
