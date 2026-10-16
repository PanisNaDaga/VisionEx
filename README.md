# VisionEx Android Starter App

Native Android/Kotlin starter project for the VisionEx smart-glasses capstone.

Included: BLE scan/connect for a device named `VISIONEX`, drowsiness status, battery messages, alert acknowledgement, local event history, emergency contact storage, optional automatic emergency SMS after 3 unacknowledged alerts, last-known-location link, manual event simulation, and manual SMS testing.

## BLE UUIDs
Edit `app/src/main/java/com/visionex/app/BleVisionExManager.kt` and replace `SERVICE_UUID`, `TX_NOTIFY_UUID`, and `RX_WRITE_UUID` with the UUIDs used by the XIAO ESP32-S3 Sense firmware. The current UUIDs are placeholders.

## ESP32 -> Android messages
Send UTF-8 BLE notifications such as:
`NORMAL`
`DROWSY`
`BATTERY:85`

The Android app sends `ACK` back when an alert is acknowledged.

## Open and run
1. Install Android Studio.
2. Open the `VisionExAndroid` folder.
3. Let Gradle sync.
4. Connect a physical Android phone with USB debugging enabled.
5. Run the app and grant Nearby Devices, Location, and SMS permissions.
6. Tap `Scan for VisionEx`.

Use a physical phone because BLE and SMS are not fully testable in the standard emulator.

Automatic emergency SMS is OFF by default. Test only with a consenting recipient. Do not perform induced-drowsiness testing on public roads.


## Version 0.0.4 camera testing and alert fixes

The Camera tab adds a CameraX live preview with front/back camera selection. It can analyze frames using a YOLO open/closed-eye detector, calculate PERCLOS over a sliding observation window, and apply a continuous-eye-closure microsleep override. Default test settings are a 60-second PERCLOS window, a 60% PERCLOS threshold, and a 2-second microsleep threshold. These can be adjusted in the Camera tab.

### Trained eye model

Gradle downloads the ONNX model during the first Android build, verifies its SHA-256 checksum, and packages it into the APK under `app/src/main/assets/visionex_open_closed_eye.onnx`. The first time **Download / load trained YOLO model** is pressed, the app copies that bundled model into its private data directory and verifies it again before opening it. Internet access is required when building the APK if the model asset is not already cached locally; the installed app can then run the camera model without downloading the weights on first use.

Model source: [MichalMlodawski/open-closed-eye-detection](https://huggingface.co/MichalMlodawski/open-closed-eye-detection), an open/closed-eye YOLOv8 model published under **CC BY-NC-SA 4.0**. Give attribution when redistributing and observe the non-commercial and share-alike conditions. Review the model card before any use beyond non-commercial research.

The Camera tab includes a selector for which YOLO output class represents closed eyes because model label order must be confirmed by testing the selected model. Calibrate this setting with several open-eye and closed-eye examples while parked before relying on the results.

### Safety and limitations

Use camera testing while the vehicle is parked. Do not deliberately induce drowsiness or perform eye-closure tests while driving. Front camera is the intended phone-camera option for a driver-facing test; the back camera is included for comparison and general testing. CameraX, YOLO detections, PERCLOS, and microsleep thresholds are experimental research functions and must be validated on the target phone before reporting measured accuracy or response time. The app is not a certified driver-safety system.


## Version 0.0.4 patch notes

- Kept the camera integration test tab from v0.0.3, including front/back camera selection, CameraX preview, YOLO eye-state analysis, PERCLOS settings, and a configurable continuous-eye-closure override.
- Fixed alert acknowledgment so the app resets the unacknowledged alert count and live status, subject to new incoming detections.
- Fixed automatic SMS counting so it uses the same unacknowledged-alert count shown in the app, preventing an old separate counter from triggering a stale SMS after acknowledgment.
- Set the Android app version to 0.0.4 (version code 4).
