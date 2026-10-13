# G3 Connect v0.2.0 — read-only protocol test

Android native Bluetooth Low Energy utility for the MAX G3. This build is **experimental and read-only**. It never registers a new pairing password, changes scooter settings, flashes firmware, or changes your Segway account binding.

## Functions
- Scan nearby BLE devices (requires Bluetooth, Nearby Devices and Precise Location permission, and Location turned on)
- BLE GATT connect and service/characteristic diagnostics
- Request a higher MTU, subscribe to the G3 notification channel, and reassemble incoming packets
- **PROBE**: attempt non-mutating PRE_COMM handshake on Ninebot Encryption2 Gen3 and report whether a stored key exists
- **AUTHENTICATE USING EXISTING KEY**: optional, uses an existing 16-byte pairing key expressed as 32 hex digits, and does not issue SET_PWD. If the scooter requires the key to be reasserted, authentication may time out; this app will not overwrite it.
- **READ DATA** after successful authentication: battery percentage, odometer and remaining range (G3-tested register indexes); no setting writes
- Safe local diagnostics with Bluetooth MACs and selected scooter name redacted from the share text

## How to test
1. Install the APK release. Turn on Bluetooth and Location. Allow **Nearby devices** and **Precise location**.
2. Power on your scooter and force-stop the official Segway app (Bluetooth connection only allows one app at a time).
3. Tap SCAN, select the device that is definitely your G3, and wait for the protocol status "Gen3 service ready".
4. Tap **PROBE**. Watch for a response like "G3 protocol reply verified; serial …; stored password: true".
5. With a *legitimately recovered existing* pairing key, enter 32 hex characters in the app and tap **AUTHENTICATE USING EXISTING KEY**. Do not paste the key into a chat, screenshot, GitHub issue, or shared diagnostic file.
6. If AUTH succeeds, tap **READ DATA** and report the displayed values to check accuracy against the scooter.
7. If PRE_COMM times out, share the diagnostic log. A BLE connection alone does not confirm protocol authentication.

### Key points
- The advertising name is part of the Generation 3 session crypto; selecting an unnamed or wrong device cannot complete the probe.
- This build **does not recover pairing keys**, and does not intentionally bypass scooter ownership. It can reuse a key you legitimately possess. Android's normal Segway app storage is not accessible without privileges.
- G3 service: \`6e400001-0000-0000-006e-696e65626f74\`. G3 write: \`6e400002-0000-0000-006e-696e65626f74\`. G3 notify: \`6e400004-0000-0000-006e-696e65626f74\`. It does not send protocol commands on the classic Nordic UART service.
- The only BLE control write during setup is the standard GATT CCCD subscription that enables notifications; all Ninebot protocol requests are PRE_COMM, AUTH and READ.
- Use a scooter that is parked and powered on. This is not a riding dashboard; no Android foreground service is used.

## APK
Each push builds with GitHub Actions, publishes an Actions artifact, and, on success, updates a direct APK release.

## Reference
Protocol independently implemented for Android from published **MIT-licensed** work at https://codeberg.org/NootNooot/segway-ninebot-ble. Verified G3 services, handshake expectations and register indexes cross-checked against https://github.com/BobMcGlobus/ha-ninebot (AGPL-3.0). No AGPL source was copied into this app.

## Next
On-device PRE_COMM confirmation, AES authentication validation, and reliable readback. Only then consider adding safety-checked settings writes.
