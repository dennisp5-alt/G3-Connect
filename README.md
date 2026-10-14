# G3 Connect v0.6.0 — read-only protocol test

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

## v0.6.0 diagnostic additions
Your scooter's advertised BLE name was confirmed against the serial displayed in ScooterHacking Utility. Its BLE/VCU/MCU/BMS versions and odometer are useful test references, but the UI's snapshot date may be historical.

PRE_COMM previously timed out. This build attempts (1) Gen3 proprietary service write without response, (2) Gen3 with response, (3) Gen2 Nordic UART without response and (4) Gen2 Nordic with response. The two service notification characteristics are subscribed independently. Each attempt waits 3.2 seconds; unsuccessful attempts move to the next candidate automatically. No SET_PWD, firmware flashing, region manipulation or setting change is sent.

The separate CPU IDs (`vcu`, `mcu`) and six-byte `*_rand` registers reported by ScooterHacking relate to controller identities/firmware security. They are not the existing 16-byte BLE pairing key and are not stored, sent or embedded in G3 Connect. Do not publish them.

## v0.6.0 encrypted-handshake repair
G3 Connect v0.2.1 observed a Nordic notification during Gen2 PRE_COMM but decoded it as the unrelated command `0x1C`. The Gen2 receive path had a reproducible bug: it always used the zero keystream input, although Gen2 uses the firmware constant `FW_DATA`. The input now matches the transmit path, and non-SN frames undergo a checksum check before their command or serial information is trusted.

A deterministic Java smoke test now constructs synthetic Gen2 and Gen3 PRE_COMM responses and verifies round-trip command decoding, serial parsing and rejection of incorrect keys before the Android APK is built. This is a software validation, **not proof of successful scooter authentication**. Keep the scooter parked and do not enter the scooter's controller CPU IDs/random registers as a Bluetooth key. Existing pairing is preserved; SET_PWD is never sent.

## v0.6.0 — authenticated read-only settings
Now that hardware tests confirmed the user's Gen2/Nordic handshake and AUTH, READ DATA retrieves current MAX G3 VCU values: battery, odometer, estimated range, temperature, ride mode, energy recovery, start speed, auto power-off, sound volume and custom-button assignment. A setting with an unrecognised value is shown as unverified raw. Missing register replies time out and are marked unavailable. Do not assume estimated range equals a different app's range; compare under the same ride mode and charge. Charge limit depends on identifying the BMS board and remains unimplemented.

Only authenticated READ commands and the initial PRE_COMM/AUTH are sent. No new pairing/SET_PWD, no safety-critical setting writes, firmware, traction-control writes, or region changes. The existing key remains user-entered and is not persisted.

## v0.6.0 — first guarded setting controls
- Faster startup protocol: prefer the confirmed Gen2/Nordic UART without-acknowledgement handshake; retain all four fallback combinations.
- After a successful authenticated READ DATA, enable **Sound volume** (20/40/60/80/100%) and **Auto power-off** (5/10/15/20/30 minutes) only when their current raw readings are within the documented range.
- Each proposed change opens a confirmation dialog; a single bounded Ninebot 0x03 write is sent on explicit user approval, followed by an authenticated register read after 750ms. No automatic retries, no blind rollback, and no success claim without matching readback.
- Settings read-only: energy recovery, kick-start speed (raw 0), custom button (raw 8), ride mode, etc. Charge limit, BMS board and traction control are not modified.
- Does NOT save scooter password or generate any SET_PWD command. Authentication protocol is unchanged.
- All configuration tests should be performed while the scooter is parked. Check the actual sound and auto-off behaviour and report discrepancies before using other settings.

## v0.6.0 — verified-setting UI fix
The user verified volume changes 100→80→60→20→60% via G3 response reads. The original code reported success then triggered Android CalledFromWrongThreadException when updating control buttons from a BLE callback. All verification success, mismatch and short-read UI updates are now dispatched on Android's main/UI thread. Encrypted transport, settings allowlist and pairing behaviour are unchanged. Please test while parked and stop if the scooter behaves unexpectedly.

## v0.6.0 — energy recovery and BMS exploration
- Adds **Energy Recovery** (Off/Low/Standard) using documented VCU register 0x70, confirmed by real-world G3 readings. Requires authentication, valid initial value, parked-scooter confirmation, and matching readback. Changes can affect deceleration, so test cautiously in a safe area using normal brakes.
- Adds **BMS CHECK** button to examine the battery controller without settings writes. Once fully authenticated, tries BMS board addresses 0x07,0x22,0x23 via 0x8C pack-voltage read and accepts only a plausible 35–65V reading. Then reads register 0x82 for charge limit. Does not write charge limit.
- All previous volume/auto-off controls and existing pairing-key login preserved. No speed-limit, firmware, region, motor, or charger settings writes. No key embedded or persisted.
- UI/operation gating prevents parallel BMS reads and setting writes. Readback success must be confirmed on the scooter before treating energy recovery as tested.

## v0.6.0 — charging-limit control and battery-health diagnostics
- Charge-limit control on an authenticated battery board, only after a plausible 35–65V voltage and a valid 80–100% charge-limit read. Options 80/85/90/95/100%, charger disconnected, separate explicit confirmation. A single BMS write with acknowledgement (0x02 → 0x05) is followed by a fresh authenticated 0x82 read. Never reports success without matching readback; does not automatically retry or rollback. A timeout, mismatch, or transport rejection locks further writes during the session.
- Expanded battery diagnostic reads: 0xF9 fourth byte state-of-health, 0x8D signed current in 0.01 A, 0xA0 thirteen cell voltages in mV (reports min/max/spread), plus BMS voltage/charge limit. Data comes from controller and requires scooter-side validation. No persistent password.
- Keep v0.5.0 as known-good baseline for regen and v0.4.1 for volume/auto-off. No speed, firmware, account-binding or pairing change.

## v0.6.1 — G3 charge-limit ACK compatibility
User hardware validation: G3 accepted a single write to BMS board 0x07, register 0x82 (100%→95%), but v0.6.0 did not receive a valid 0x05 write acknowledgement. A subsequent independent BMS read confirmed **95%**. Fix: when the write ACK is absent, perform exactly one *read-only* 0x82 verification and report success only if it matches the requested 80–100% stepped value. An atypical WRITE ACK index is logged and also requires readback. Never retransmit the write; if the read fails or mismatches, mark result unknown, lock charging-limit control and instruct manual verification. This does not change the existing BLE key, pairing, firmware or other controls.

## v0.6.2 — alternate AUTH payload diagnostic
A fresh HCI snoop capture verified the **original recovered key** still cryptographically encodes G3 Connect's outgoing 14-byte AUTH packets correctly, but they go unanswered in current sessions. In earlier captured successful ScooterHacking sessions with the same key, command 0x5D used an **empty** AUTH payload. Later ScooterHacking sessions used a different encryption key, though no extractable new key was established from the capture. It is not yet proven whether this stems from pairing changes or separate account/application secrets.

This version offers a distinct **TEST ALTERNATE AUTH (NO PAIRING)** button after successful PRE_COMM. Enter the existing 16-byte key; press the alternate button once. It sends a single zero-payload AUTH (0x5D) using the existing secret and the latest scooter challenge. It never sends SET_PWD, modifies keys or changes firmware. Do not press repeatedly; disconnect/reconnect and fresh PROBE if it fails. The original AUTH flow remains available and unchanged. This is a diagnostic only; it is not known to restore authentication against the scooter's present pairing state.
