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
