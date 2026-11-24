# Camera compatibility

Cameraderie imports files independently of camera brand through Android’s file picker. This matrix tracks direct connection features by model and transport. Standalone acceptance records the firmware, camera settings, and Android version for each physical run.

| Camera | Transport | Implemented in Cameraderie | Standalone physical acceptance |
| --- | --- | --- | --- |
| DJI Osmo Action 6 | Bluetooth | Discovery, authorization, reconnect, firmware readout, photo shutter, normal-video record start and stop, Photo/Video mode selection. Camera status gates commands and gallery entry. | Pending. Record firmware, confirm results on the camera, and test blocked gallery entry during recording. |
| DJI Osmo Action 6 | Bluetooth → local Wi-Fi | Idle preflight, playback handoff, SD and internal catalogues, Media3 playback of supported proxies, verified original downloads, durable queue, validated range resume and explicit restart. | Pending. Repeat incomplete catalogue and permission recovery, DNG and WAV companions, and return to shooting on the same camera. |
| Sony a6400 | Local Wi-Fi | QR, manual and saved reconnect, catalogue and capture-date browsing, Original/2M/VGA JPEG choices, and verified import. | Pending. Repeat each JPEG option and record model and firmware. |
| Sony a6400 | USB MTP | USB discovery, Android’s access prompt, browse and import files the camera exposes. | Pending physical USB and genuine ARW byte comparison against an independent card read. |

Live preview, focus and exposure are unverified in the initial adapters. The Sony transport currently exposes gallery operations. Add GoPro, Canon, Nikon, and Insta360 after documenting the adapter's supported models and physical results.

## Full media acceptance

- On the Galaxy, browse an actual Action 6 proxy and import the corresponding original. Independently hash the camera original and the imported copy.
- Crop and trim the verified clip, apply the appropriate D-Log M to Rec.709 LUT, and export Main 10. Inspect the exported file with an independent decoder or `ffprobe`, including HEVC profile, colour tags, duration and both tracks. Recheck the imported original's checksum and receipt.
- Import a real a6400 JPEG and compare bytes for the Original choice. Verify resized choices against the offered representation and its receipt.
- Attach a real ride track, compare a known event and clip timestamp, and record any applied offset. For GPX, render a transparent layer in GPStitch and composite it in DaVinci Resolve. Verify at least two visible events or landmarks across the clip and record the overlay profile and measured timing error. [GPStitch overlay-only workflow](https://github.com/Romancha/GPStitch#overlay-only-mode)
- Relink known OpenGrip files and queue entries before retiring the integrated flow. Check unavailable and modified sources, Android permission recovery, process restart, rotation, large text and accessibility traversal.
