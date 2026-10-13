# G3 Connect — v0.1.2 diagnostic

Private, on-device Android Bluetooth Low Energy (BLE) scanner for the **Segway Ninebot MAX G3**. Designed for testing on a Samsung Galaxy S23 Ultra, and usable for basic BLE discovery of other scooters such as the G30P.

## What works at this stage

- Scan nearby BLE devices and choose one to connect.
- Establish a GATT link, discover services/characteristics, report UART candidates and MTU.
- Subscribe to one known UART notification characteristic if supported; count packets (do not decode them).
- Export a diagnostic log with Bluetooth MAC addresses redacted.

**Not yet implemented:** Segway encrypted authentication, scooter telemetry, pairing passwords, settings reads/writes, changing account binding, firmware modifications. A BLE GATT connection **does not** mean you are logged into the scooter.

## Build APK

Every push to `main` runs **Actions → Build Android APK**. Download the `G3-Connect-v0.1.2-Diagnostic-APK` Actions artifact and extract `app-debug.apk`. The artifact is a ZIP containing the APK; do not install the ZIP itself.

Alternatively build with Android Studio using Java 17, Gradle 8.11.1, Android Gradle Plugin 8.9.2 and Android SDK 35.

## Test steps

1. Install the APK. Enable Bluetooth and grant *Nearby devices* and *Precise location* permissions.
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

## 0.1.2 scan troubleshooting
Version 0.1.1 received zero BLE advertisement callbacks on a Galaxy S23 Ultra. This version improves visibility into the cause:
- Declare and request *Precise location* in addition to *Nearby devices*, without `neverForLocation` advertisement filtering. No GPS/location data is collected or uploaded.
- Warn if Location services are off. Turn on Android's global **Location** switch (Quick Settings) before scanning.
- **SYSTEM CHECK** reports Bluetooth support/state, Android version, location toggle, runtime permissions.
- **SCAN** operates up to 25 seconds with extended advertisements included and reports callbacks and unique devices.
- **PAIRED** lists already-bonded Bluetooth devices as a separate diagnostic (not proof of scooter authorization).
- **LOCATION** opens Android's system Location settings.
- Hold the screen awake during scanning; unfiltered BLE scans can be paused by Android when the screen is off.
- Close Segway and other apps connected to the scooter, power on scooter, stand close, try only one scan initially.
If other BLE peripherals are also missing, test a trusted scanner such as nRF Connect to separate phone/permissions issues from app code. If nRF Connect finds none, check the system Location and Nearby devices settings, and restart phone Bluetooth.
