# Camera compatibility

Cameraderie imports files independently of camera brand through Android’s file picker. This matrix tracks direct connection features by model and transport. Physical test records include the camera settings and Android version, plus the firmware version when available.

| Camera | Transport | Implemented in Cameraderie | Standalone physical acceptance |
| --- | --- | --- | --- |
| DJI Osmo Action 6 | Bluetooth | Discovery, authorization, reconnect, firmware readout, photo shutter, normal-video record start and stop, Photo/Video mode selection. Camera status gates commands and gallery entry. | Pending. Record firmware, confirm results on the camera, and test blocked gallery entry during recording. |
| DJI Osmo Action 6 | Bluetooth → local Wi-Fi | Idle preflight, playback handoff, SD and internal catalogues, Media3 playback of supported proxies, verified original downloads, durable queue, validated range resume and explicit restart. | Pending. Repeat incomplete catalogue and permission recovery, DNG and WAV companions, and return to shooting on the same camera. |
| Sony α7 III (ILCE-7M3) | Local Wi-Fi | Sony media discovery, gallery browsing, and JPEG import. | QR, model discovery, catalogue, saved reconnect, and Original/2M/VGA JPEG passed on Galaxy S24 FE. [Hardware test details](#sony-α7-iii-hardware-test) |
| Sony α7 III (ILCE-7M3) | USB PC Remote | USB viewfinder, camera identity, and firmware detection. | Live-view frames verified with an independent macOS PTP probe on firmware 4.0. Galaxy verification pending. |
| Sony α7 III (ILCE-7M3) | USB MTP | Sony documents MTP access to the card in slot 1. | Pending physical USB and independent comparison with a card original. |
| Sony a6400 | Local Wi-Fi | QR, manual and saved reconnect, catalogue and capture-date browsing, Original/2M/VGA JPEG choices, and verified import. | Pending. Repeat each JPEG option and record model and firmware. |
| Sony a6400 | USB PC Remote | Candidate for the same Sony USB viewfinder. | Pending physical test. |
| Sony a6400 | USB MTP | USB discovery, Android’s access prompt, browse and import files the camera exposes. | Pending physical USB and genuine ARW byte comparison against an independent card read. |

Sony USB PC Remote and Sony Wi-Fi media transfer expose separate features. Sony shutter, recording, focus, exposure, and settings controls in Cameraderie remain pending. Each camera family tracks physical support by model, firmware, and transport.

## Protocol compatibility across models

| Protocol or connection | Initial models and candidates | What to verify |
| --- | --- | --- |
| USB PTP/MTP | Sony α7 III, a6400, a6100 and a6600, Nikon Z6III and Z8, and Fujifilm X-T5. Canon EOS R50 offers a USB photo-transfer mode and is a candidate for interface discovery. | Check the advertised interface and Android permission, read each storage unit, browse the catalogue, and compare a JPEG, video, and supported RAW import with the camera original. |
| Sony USB PC Remote | Sony lists α7 III, a6400, and other Alpha models in its Camera Remote Command family. | Test Sony PTP discovery, displayed live view, autofocus, shutter, recording status and controls, settings, and return from import to shooting on each model. [Sony Camera Remote Command](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html) |
| Sony local media transfer | Sony α7 III tested for QR, browsing, and Original JPEG. Sony a6400, a6100, and a6600 are candidates for the same workflow. | Check QR and manual Wi-Fi, Sony identity, a usable media catalogue, offered JPEG sizes, and original file downloads on each firmware. |
| DJI R SDK over Bluetooth | DJI Osmo Action 6, Action 5 Pro, Action 4, and Osmo 360. | Check authorization, fresh status and command acknowledgments for each model. Verify the gallery Wi-Fi handoff separately. |
| DJI USB file transfer | DJI Osmo Action 6, Action 5 Pro, Action 4, Action 3, Osmo 360, and Osmo Nano where DJI lists Android OTG. | Identify whether the phone sees PTP/MTP or a volume. Verify the appropriate Cameraderie import path using originals. |
| Open GoPro | GoPro HERO12 Black and HERO13 Black among GoPro’s documented models. | Build and verify a dedicated Bluetooth and HTTP adapter with feature checks for each firmware and transport. |
| USB storage and SD cards | Any camera whose files use formats Cameraderie imports, including storage exposed by Insta360 X4 or X5 if Android mounts it. | Use Android’s file picker and verify file names, read access, supported formats, and original hashes. |

**Sony.** Sony documents the a6400, a6100, and a6600 in the same Imaging Edge Mobile family. All three offer an MTP mode. This makes the a6100 and a6600 useful candidates for the a6400 workflow. Confirm that each Wi-Fi session exposes the media services and file representations Cameraderie needs. [Sony mobile app compatibility](https://www.sony.com/electronics/support/e-mount-body-ilce-5000-series/ilce-5100/articles/00284526) · [a6400 USB guide](https://helpguide.sony.net/ilc/1810/v1/en/print.pdf) · [a6100 USB guide](https://helpguide.sony.net/ilc/1950/v1/en/contents/TP0002647429.html) · [a6600 USB guide](https://helpguide.sony.net/ilc/1940/v1/en/contents/TP0002647429.html)

**DJI.** DJI documents the same R SDK on Action 6, Action 5 Pro, Action 4, and Osmo 360. Use the camera’s reported model, firmware, and confirmed status to gate commands. DJI documents USB/OTG transfer separately. Confirm playback, storage IDs, proxy URLs, and resumable originals separately for each wireless gallery adapter. [DJI R SDK](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo/blob/main/docs/protocol.md) · [DJI OTG models and setup](https://repair.dji.com/help/content?customId=01700007307&documentType=&lang=en&paperDocType=ARTICLE&re=US&spaceId=17)

**Nikon and other manufacturers.** Nikon exposes an MTP/PTP mode for Android on the Z6III and Z8. Fujifilm documents Android PTP import on the X-T5. Canon documents a photo-transfer mode on the EOS R50. The general USB adapter selects devices by transfer interface and confirms access by opening the session and reading its storage. Open GoPro exposes its own API, and Insta360 documents USB file-transfer mode. Their features can be added by transport as each is verified. [Nikon Z6III](https://onlinemanual.nikonimglib.com/z6III/en/nwm_usb_data_connection_365.html) · [Nikon Z8](https://onlinemanual.nikonimglib.com/z8/en/nwm_usb_%20data_connection_357.html) · [Fujifilm X-T5](https://app.fujifilm-dsc.com/en-int/manual/x-t5//connections/usage_usb/) · [Canon EOS R50](https://cam.start.canon/en/C011/manual/html/UG-09_Set-up_0230.html) · [Android MTP](https://developer.android.com/reference/android/mtp/MtpDevice) · [Open GoPro](https://gopro.github.io/OpenGoPro/) · [Insta360 File Transfer](https://onlinemanual.insta360.com/x4air/en-us/operating-tutorials/connect/files)

## Sony α7 III hardware test

On 24 November 2026, Cameraderie connected a physical Sony α7 III to a Galaxy S24 FE running Android 17. The camera was in **Send to Smartphone → Select on Smartphone**. QR pairing opened the Wi-Fi session and discovered the camera’s UPnP ContentDirectory service. The completed catalogue contained 789 photo or other entries and reported zero videos. Saved Wi-Fi reconnect reopened the gallery without another QR scan. Keep the camera’s **Select on Smartphone** screen open for reconnect.

| JPEG choice | Saved dimensions | Saved bytes | Source |
| --- | --- | ---: | --- |
| Original | 6000 × 4000 | 13,172,736 | Full-size JPEG from the camera |
| 2M | 1732 × 1154 | 385,097 | Resized on the phone from the verified camera download |
| VGA | 640 × 426 | 63,348 | Resized on the phone from the verified camera download |

All three copies retained the capture date. Separate SHA-256 checks of the files in the Cameraderie album matched the completed import receipts. The original JPEG's saved bytes matched the file streamed from the camera. All three copies appeared in the offline Library after disconnecting.

Firmware, RAW, video, USB MTP, and a separate hash of the card original remain to be verified. Sony documents the α7 III’s transfer mode and USB MTP access to the card in slot 1. [Sony Wi-Fi guide](https://helpguide.sony.net/ilc/1720/v1/en/contents/TP0001653186.html) · [Sony USB guide](https://helpguide.sony.net/ilc/1720/v1/en/contents/TP0001629775.html)

### USB PC Remote

The α7 III reported firmware 4.0 in PC Remote mode. An independent macOS Image Capture PTP probe opened the Sony session, read the camera properties, and decoded four 1024 × 576 live-view frames. The camera advertised shutter and focus commands. Command effects and safe recording handoff need separate tests.

To try the Android viewfinder, set **Ctrl w/ Smartphone** to **Off** and **USB Connection** to **PC Remote**, then attach the camera to the phone with a data cable. In Cameraderie, open **Sony USB viewfinder** and tap **Connect over USB**. [Sony USB setup](https://helpguide.sony.net/ilc/1720/v1/en/contents/TP0001629775.html)

## Camera acceptance checklist

Record the camera model, firmware, Android device and version, lens, shooting mode, and transport. Check each connection mode the camera exposes and record the result for each feature.

- Discover, pair or grant USB access, reconnect, and recover from denied permission and disconnect.
- Confirm that the preview responds to changes in the scene. Check shutter, autofocus, exposure settings, recording start and stop, and camera-reported recording state where supported.
- During recording, try to open the gallery and confirm the app protects the recording. Once idle, browse and import, then return to shooting and confirm the camera is ready.
- Verify the catalogue on each accessible storage location. Import a JPEG, video, and RAW when available. Compare saved originals against an independent card read, and check filenames, timestamps, and receipts.
- For transfers that support a queue or resume, interrupt one download and verify resumed bytes, duplicate detection, and a clean retry.

## Full media acceptance

- On the Galaxy, browse an actual Action 6 proxy and import the corresponding original. Independently hash the camera original and the imported copy.
- Crop and trim the verified clip, apply the appropriate D-Log M to Rec.709 LUT, and export Main 10. Inspect the exported file with an independent decoder or `ffprobe`, including HEVC profile, colour tags, duration and both tracks. Recheck the imported original's checksum and receipt.
- Import a real a6400 JPEG and compare bytes for the Original choice. Verify resized choices against the offered representation and its receipt.
- Attach a real ride track, compare a known event and clip timestamp, and record any applied offset. For GPX, render a transparent layer in GPStitch and composite it in DaVinci Resolve. Verify at least two visible events or landmarks across the clip and record the overlay profile and measured timing error. [GPStitch overlay-only workflow](https://github.com/Romancha/GPStitch#overlay-only-mode)
- Relink known OpenGrip files and queue entries before retiring the integrated flow. Check unavailable and modified sources, Android permission recovery, process restart, rotation, large text and accessibility traversal.
