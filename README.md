# G3 Connect — v0.1.1 diagnostic

Private, on-device Android Bluetooth Low Energy (BLE) scanner for the **Segway Ninebot MAX G3**. Designed for testing on a Samsung Galaxy S23 Ultra, and usable for basic BLE discovery of other scooters such as the G30P.

## What works at this stage

- Scan nearby BLE devices and choose one to connect.
- Establish a GATT link, discover services/characteristics, report UART candidates and MTU.
- Subscribe to one known UART notification characteristic if supported; count packets (do not decode them).
- Export a diagnostic log with Bluetooth MAC addresses redacted.

**Not yet implemented:** Segway encrypted authentication, scooter telemetry, pairing passwords, settings reads/writes, changing account binding, firmware modifications. A BLE GATT connection **does not** mean you are logged into the scooter.

## Build APK

Every push to `main` runs **Actions → Build Android APK**. Download the `G3-Connect-v0.1.1-Diagnostic-APK` Actions artifact and extract `app-debug.apk`. The artifact is a ZIP containing the APK; do not install the ZIP itself.

Alternatively build with Android Studio using Java 17, Gradle 8.11.1, Android Gradle Plugin 8.9.2 and Android SDK 35.

## Test steps

1. Install the APK. Enable Bluetooth and grant *Nearby devices* permission.
2. Turn the G3 on and fully close the official Segway app.
3. Tap **SCAN**, then tap the device matching your scooter. BLE names may be empty; use proximity/signal strength.
4. Inspect the **Bluetooth connection** details and **Local diagnostic log**. On Android, some BLE devices are visible only when advertising.
5. Use **SHARE LOG** if you want to show diagnostics. Review before sharing. Do **not** publish scooter pairing passwords, account credentials, or unredacted identifiers.

## Safety

All scooter-configuration controls are disabled in this milestone. No authentication commands or motor/firmware changes are sent. The only BLE write is an optional standard Client Characteristic Configuration Descriptor subscription to a notification channel. Leave the scooter parked for testing.

## Roadmap

- v0.2: identify the G3 service and confirm the Gen2 authenticated protocol on this scooter/firmware.
- Then implement verified telemetry and safe, range-checked controls with readback for energy recovery, start speed, charge limit, auto power-off and sound volume, subject to supported firmware and correct authentication.

Reverse-engineering reference: [ha-ninebot](https://github.com/BobMcGlobus/ha-ninebot) (AGPL-3.0); this project does not copy third-party protocol implementation.
