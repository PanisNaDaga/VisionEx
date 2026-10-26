# VisionEx Android Starter App

Native Android/Kotlin starter project for the VisionEx smart-glasses capstone.

Included: device-local register/login, BLE scan/connect for a device named `VISIONEX`, drowsiness status, battery messages, phone and glasses alert requests, alert acknowledgement, per-account settings and event history, emergency contact storage, an SMS composer that always requires user confirmation, last-known-location link, manual event simulation, and manual SMS testing.

## BLE UUIDs
Edit `app/src/main/java/com/visionex/app/BleVisionExManager.kt` and replace `SERVICE_UUID`, `TX_NOTIFY_UUID`, and `RX_WRITE_UUID` with the UUIDs used by the XIAO ESP32-S3 Sense firmware. The current UUIDs are placeholders.

## ESP32 -> Android messages
Send UTF-8 BLE notifications such as:
`NORMAL`
`DROWSY`
`BATTERY:85`

The Android app sends `ACK` when an alert is acknowledged, `ALERT:1` when a drowsiness alert starts, and `ALERT:0` when it clears or is acknowledged. The ESP32 firmware must parse `ALERT:1` / `ALERT:0` and activate or stop its vibration motor and buzzer according to the saved `VIBRATION_ENABLED`, `BUZZER_ENABLED`, and `VIBRATION` settings. The repository contains the Android app, not the ESP32 firmware, so these commands must be implemented in the hardware firmware too.

## Open and run
1. Install Android Studio.
2. Open the `VisionExAndroid` folder.
3. Let Gradle sync.
4. Connect a physical Android phone with USB debugging enabled.
5. Run the app, register a device-local account, and grant Nearby Devices, Location, and notification permissions as requested.
6. Tap `Scan for VisionEx`.

Use a physical phone because BLE and SMS are not fully testable in the standard emulator.

SMS is never sent silently by the app. When the selected alert threshold is reached, VisionEx opens a prefilled SMS draft; the user must review it and tap Send. The app does not request the SEND_SMS permission. Test only with a consenting recipient. Do not perform induced-drowsiness testing on public roads.

## Version 0.0.5 account and alert updates

- Added device-local account registration and login. Passwords are stored as salted PBKDF2 hashes rather than plain text. Accounts and settings do not sync between devices; this is a prototype login, not a cloud identity provider.
- Added Android phone vibration, a short alert tone, and a high-priority notification when a new drowsiness condition is detected while the app is running.
- Added the BLE command protocol `ALERT:1` to activate enabled glasses alerts and `ALERT:0` to stop them. The ESP32 firmware must implement these commands; it is not included in this repository.
- Changed emergency SMS behavior to open a prefilled message draft that the user must send manually. The alert counter is no longer reset merely because the SMS composer opened.
- Added per-account settings and event history, along with a Log out button.


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
