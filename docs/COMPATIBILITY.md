# Camera compatibility

Cameraderie imports files independently of camera brand through Android’s file picker. This matrix tracks direct connection features by model and transport. Standalone acceptance records the firmware, camera settings, and Android version for each physical run.

| Camera | Transport | Implemented in Cameraderie | Standalone physical acceptance |
| --- | --- | --- | --- |
| DJI Osmo Action 6 | Bluetooth | Discovery, authorization, reconnect, firmware readout, photo shutter, normal-video record start and stop, Photo/Video mode selection. Camera status gates commands and gallery entry. | Pending. Record firmware, confirm results on the camera, and test blocked gallery entry during recording. |
| DJI Osmo Action 6 | Bluetooth → local Wi-Fi | Idle preflight, playback handoff, SD and internal catalogues, Media3 playback of supported proxies, verified original downloads, durable queue, validated range resume and explicit restart. | Pending. Repeat incomplete catalogue and permission recovery, DNG and WAV companions, and return to shooting on the same camera. |
| Sony a6400 | Local Wi-Fi | QR, manual and saved reconnect, catalogue and capture-date browsing, Original/2M/VGA JPEG choices, and verified import. | Pending. Repeat each JPEG option and record model and firmware. |
| Sony a6400 | USB MTP | USB discovery, Android’s access prompt, browse and import files the camera exposes. | Pending physical USB and genuine ARW byte comparison against an independent card read. |

Live preview, focus and exposure are unverified in the initial adapters. The Sony transport currently exposes gallery operations. Add GoPro, Canon, Nikon, and Insta360 after documenting the adapter's supported models and physical results.

## Protocol compatibility across models

| Protocol or connection | Initial models and candidates | What to verify |
| --- | --- | --- |
| USB PTP/MTP | Sony a6400, a6100 and a6600, Nikon Z6III and Z8, and Fujifilm X-T5. Canon EOS R50 offers a USB photo-transfer mode and is a candidate for interface discovery. | Check the advertised interface and Android permission, read each storage unit, browse the catalogue, and compare a JPEG, video, and supported RAW import with the camera original. |
| Sony local media transfer | Sony a6400, with a6100 and a6600 as candidates. | Check QR and manual Wi-Fi, Sony identity, a usable media catalogue, offered JPEG sizes, and original file downloads on each firmware. |
| DJI R SDK over Bluetooth | DJI Osmo Action 6, Action 5 Pro, Action 4, and Osmo 360. | Check authorization, fresh status and command acknowledgments for each model. Verify the gallery Wi-Fi handoff separately. |
| DJI USB file transfer | DJI Osmo Action 6, Action 5 Pro, Action 4, Action 3, Osmo 360, and Osmo Nano where DJI lists Android OTG. | Identify whether the phone sees PTP/MTP or a volume. Verify the appropriate Cameraderie import path using originals. |
| Open GoPro | GoPro HERO12 Black and HERO13 Black among GoPro’s documented models. | Build and verify a dedicated Bluetooth and HTTP adapter with feature checks for each firmware and transport. |
| USB storage and SD cards | Any camera whose files use formats Cameraderie imports, including storage exposed by Insta360 X4 or X5 if Android mounts it. | Use Android’s file picker and verify file names, read access, supported formats, and original hashes. |

**Sony.** Sony documents the a6400, a6100, and a6600 in the same Imaging Edge Mobile family. All three offer an MTP mode. This makes the a6100 and a6600 useful candidates for the a6400 workflow. Confirm that each Wi-Fi session exposes the media services and file representations Cameraderie needs. [Sony mobile app compatibility](https://www.sony.com/electronics/support/e-mount-body-ilce-5000-series/ilce-5100/articles/00284526) · [a6400 USB guide](https://helpguide.sony.net/ilc/1810/v1/en/print.pdf) · [a6100 USB guide](https://helpguide.sony.net/ilc/1950/v1/en/contents/TP0002647429.html) · [a6600 USB guide](https://helpguide.sony.net/ilc/1940/v1/en/contents/TP0002647429.html)

**DJI.** DJI documents the same R SDK on Action 6, Action 5 Pro, Action 4, and Osmo 360. Use the camera’s reported model, firmware, and confirmed status to gate commands. DJI documents USB/OTG transfer separately. Confirm playback, storage IDs, proxy URLs, and resumable originals separately for each wireless gallery adapter. [DJI R SDK](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo/blob/main/docs/protocol.md) · [DJI OTG models and setup](https://repair.dji.com/help/content?customId=01700007307&documentType=&lang=en&paperDocType=ARTICLE&re=US&spaceId=17)

**Nikon and other manufacturers.** Nikon exposes an MTP/PTP mode for Android on the Z6III and Z8. Fujifilm documents Android PTP import on the X-T5. Canon documents a photo-transfer mode on the EOS R50. The general USB adapter selects devices by transfer interface and confirms access by opening the session and reading its storage. Open GoPro exposes its own API, and Insta360 documents USB file-transfer mode. Their features can be added by transport as each is verified. [Nikon Z6III](https://onlinemanual.nikonimglib.com/z6III/en/nwm_usb_data_connection_365.html) · [Nikon Z8](https://onlinemanual.nikonimglib.com/z8/en/nwm_usb_%20data_connection_357.html) · [Fujifilm X-T5](https://app.fujifilm-dsc.com/en-int/manual/x-t5//connections/usage_usb/) · [Canon EOS R50](https://cam.start.canon/en/C011/manual/html/UG-09_Set-up_0230.html) · [Android MTP](https://developer.android.com/reference/android/mtp/MtpDevice) · [Open GoPro](https://gopro.github.io/OpenGoPro/) · [Insta360 File Transfer](https://onlinemanual.insta360.com/x4air/en-us/operating-tutorials/connect/files)

## Full media acceptance

- On the Galaxy, browse an actual Action 6 proxy and import the corresponding original. Independently hash the camera original and the imported copy.
- Crop and trim the verified clip, apply the appropriate D-Log M to Rec.709 LUT, and export Main 10. Inspect the exported file with an independent decoder or `ffprobe`, including HEVC profile, colour tags, duration and both tracks. Recheck the imported original's checksum and receipt.
- Import a real a6400 JPEG and compare bytes for the Original choice. Verify resized choices against the offered representation and its receipt.
- Attach a real ride track, compare a known event and clip timestamp, and record any applied offset. For GPX, render a transparent layer in GPStitch and composite it in DaVinci Resolve. Verify at least two visible events or landmarks across the clip and record the overlay profile and measured timing error. [GPStitch overlay-only workflow](https://github.com/Romancha/GPStitch#overlay-only-mode)
- Relink known OpenGrip files and queue entries before retiring the integrated flow. Check unavailable and modified sources, Android permission recovery, process restart, rotation, large text and accessibility traversal.
