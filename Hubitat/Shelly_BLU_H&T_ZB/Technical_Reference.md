# Shelly BLU H&T ZB (SBHT-203C) — Technical Reference (D7)

Version 1.0 · 2026-10-15 · covers Reference driver 0.6.2, Simple driver 1.1.1, Probe driver. Detail and evidence live in `zigbee_protocol_spec.md`, `sed_lifecycle.md`, `design_queue_state.md`, `technical_knowledge_base.md` and the dated test reports; this page is the consolidated summary. Evidence grades: [V] verified on our units, [D] vendor/standard documentation, [C] community sources.

## 1. The device
- Battery Zigbee 3.0 sleepy end device (CR2032, vendor claim "3 years" with no stated conditions) with a Sensirion SHT40 sensor; also BLE/BTHome. Single endpoint 0x01, profile 0x0104 (HA), device id 0x0302.
- In clusters: 0000 Basic, 0001 Power Configuration, 0003 Identify, 0402 Temperature, 0405 Humidity. Out cluster varies by firmware (0003 and/or 0019); the drivers list all variants as fingerprints.
- Identity (Hubitat Device Data): manufacturer `Shelly`, model `BLU H&T ZB`, application `71`, endpointId `01` (platform-owned); the drivers add `ieee`, `swBuildId` (e.g. `260306/v1.2.12@9`), `zclVersion` (`59` = 0x59, a Shelly constant), `powerSource` (`Battery`, from ZCL 0x03) and `batteryType` (`CR2032`).

## 2. Data and scaling
| Cluster / attribute | Type | Conversion | Invalid |
|---|---|---|---|
| 0402 / 0000 temperature | int16 | raw / 100 = °C | 0x8000 |
| 0405 / 0000 humidity | uint16 | raw / 100 = %RH | 0xFFFF |
| 0001 / 0021 battery % | uint8 | raw / 2 = % | 0xFF |
| 0001 / 0020 battery voltage | uint8 | raw × 0.1 V (Reference driver only) | 0xFF |
Events: `temperature` (hub scale, offset applied), `humidity` (unit `%RH`, offset applied, clamped 0–100), `battery` (%).

## 3. Pairing and joining
- Use **strict Zigbee 3.0 pairing** (Add Device → Zigbee → "Pair using strict Zigbee 3.0 mode"), then press the button 5 times quickly (LED flashes twice every 2 s for about 1 minute). Normal pairing makes the sensor leave again on the C-8.
- A fresh join (new network address) resets bindings and reporting to factory. A battery pull rejoins in about 10 s with the same address and keeps its configuration.
- A single button press sends nothing over Zigbee that the drivers need; no button press is needed for configuration.

## 4. Sleepy-device command delivery
- Every frame the sensor initiates (report, rejoin announce) opens a **command window of at least 6.5 s**; it answers about one command per second, one at a time. Outside a window commands are lost.
- Both drivers therefore queue everything (bind, configure reporting, read reporting configuration, read attribute) and send it only inside a window: send budget 4.5 s, 0.9 s spacing, 3 s item timeout, 5 attempts, max 60 sends per hour, rejoin cooldown 10 min, queue limit 30. Replies are matched by content; items are replaced by key; a stale-item sweep recovers lost timers.
- Settings changes take effect after the sensor's next report (at most its configured maximum interval). `configStatus` shows Pending / Working / Ready / Failed.

## 5. Reporting configuration
- Factory: temperature and humidity min 60 s / max 900 s / change 0.01; battery 300 s / 1200 s / change 0.5 %.
- Driver defaults (battery-friendly): temperature and humidity min 60 s, max 1 hour, change 0.1 °C / 2 %RH; battery min 300 s, max 12 hours, change 0.5 %. The Simple driver applies them only on a fresh pair or rejoin.
- The sensor enforces no limits, so the Reference driver clamps (min 30–3600 s, max 60 s–18 h); max < min is rejected (0x87). Max interval 0xFFFF succeeds but removes the reporting entry (Read Reporting Configuration then answers 0x8B NOT_FOUND): reporting is off and the value only arrives on Refresh.
- Reference driver menus ("report at least every"): 1 minute (not recommended), 5, 15, 30 minutes, 1 hour, 3, 6, 12, 18 hours, Off. Battery default 12 hours, others 1 hour.

## 6. Drivers
| | Reference 0.6.2 | Simple 1.1.1 |
|---|---|---|
| Purpose | everything, with diagnostics | temperature, humidity, battery |
| Reporting | preferences (min, change, "at least every" menus) | none: accepts what the sensor holds; shown as State Variables `temperatureReporting`, `humidityReporting`, `batteryReporting` |
| Commands | Configure, Refresh, Battery Replaced, Initialize, Clear Queue, Reset Reporting To Factory, Self Test, Replay Frame | Configure, Refresh, Battery Replaced, Initialize, Reset Reporting To Factory |
| Extras | health check, lastCheckin, batteryVoltage, trace logging, 61 self-test checks, command help panel | offsets, debug logging |
Both: offsets applied by the hub, Device Data standard of §1, queue engine of §4, Initialize (removes unused Current States, State Variables, Device Data and scheduled jobs; keeps the queue). To use non-standard reporting with the Simple driver: switch to the Reference driver, set it, switch back.
The Probe driver logs every frame and runs the timing experiments; do not keep it installed next to the others for new pairings (they share fingerprints).

## 7. User instructions
1. Pair in strict Zigbee 3.0 mode, 5 quick button presses, sensor near the hub.
2. Afterwards do nothing: setup is sent in the first window and values appear within minutes.
3. Changing settings needs no button press; they are sent after the sensor's next report.
4. Battery re-inserted: the sensor rejoins by itself and keeps its settings.
5. Silent for longer than 3× its maximum interval: re-insert the battery; if still silent, remove and pair again in strict mode.

## 8. Known limitations
- Findings come from two units (A: older logs; B: all live tests), one hub model (C-8, platform 2.5.2.135) and firmware 1.1.4/1.2.12; other firmware or parent topology can change timings.
- Window lengths have ±0.5 s granularity; first-boot behaviour cannot be repeated without a factory reset.
- A late reply does not resume an already closed window; saving preferences drops a pending battery-pull verify.
- Offline detection of the Reference driver is based on the temperature maximum interval and was not exercised live.
- The older-C-7 pairing hypothesis and the listening-window profile are untested.
- Vendor documentation gives no recommended reporting configuration; the "3 years" battery claim is unverified under our defaults.

## 9. Changelog
- Probe: frame capture and timing experiments.
- Reference 0.3.0: queue engine. 0.4.0: factory reset, battery replaced, health check. 0.5.0: "report at least every" menus. 0.6.x: Initialize, power source decoded, `%RH`, 12 h battery default, command help panel.
- Simple 1.0.0: first version. 1.1.x: offsets, Battery Replaced, Initialize, same Device Data, defaults on fresh join.
