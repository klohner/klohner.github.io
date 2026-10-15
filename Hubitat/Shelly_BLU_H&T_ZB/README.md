# Shelly BLU H&T ZB (SBHT-203C) – Hubitat drivers

Work in progress. Drivers for the Shelly BLU H&T ZB, a battery-powered Zigbee sleepy end device (temperature, humidity, battery), tested on a Hubitat C-8 (2.5.2).

| File | Status | Purpose |
|---|---|---|
| `Shelly_BLU_HT_ZB_Reference.groovy` | in development (queue engine done, features in progress) | Full reference driver: parser, sleepy-device command queue, verified reporting configuration, self test |
| `Shelly_BLU_HT_ZB_Probe.groovy` | working | Test driver that logs every Zigbee frame and runs timing/reporting experiments |

A simplified temperature / humidity / battery driver will follow.

## Pairing (important)
Use **Add Device → Zigbee → "Pair using strict Zigbee 3.0 mode"**, then press the button **5 times quickly** (LED flashes twice every 2 s for about a minute). Normal pairing makes the sensor leave again.

## Install on the hub from this repository
Drivers Code → New Driver → Import, URL:

`https://raw.githubusercontent.com/klohner/klohner.github.io/master/Hubitat/Shelly_BLU_H%26T_ZB/Shelly_BLU_HT_ZB_Reference.groovy`

## Things learned
- Every report the sensor sends opens a command window of at least 6.5 s; configuration is sent right after a report, no button press needed.
- The sensor enforces no reporting limits, so the driver clamps intervals itself; max interval 0xFFFF switches reporting off.
- A fresh join resets reporting to factory (T/H 60/900 s, battery 300/1200 s); a battery pull keeps the configuration.

Apache-2.0. Use at your own risk; the driver sends Zigbee commands to your sensor.
