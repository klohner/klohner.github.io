# Shelly BLU H&T ZB (SBHT-203C) – Hubitat drivers

Work in progress. Drivers for the Shelly BLU H&T ZB, a battery-powered Zigbee sleepy end device (temperature, humidity, battery), tested on a Hubitat C-8 (2.5.2).

| File | Status | Purpose |
|---|---|---|
| `Shelly_BLU_HT_ZB_Simple.groovy` | testing | Simple driver: temperature, humidity, battery. No reporting preferences (it takes the sensor as configured and shows the sensor's reporting as State Variables); offsets, Battery Replaced, Initialize, Reset Reporting To Factory |
| `Shelly_BLU_HT_ZB_Reference.groovy` | testing | Full reference driver: parser, sleepy-device command queue, reporting menus ("report at least every"), offsets, health check, trace logging, Self Test, Replay Frame, Initialize |
| `Shelly_BLU_HT_ZB_Probe.groovy` | working | Test driver that logs every Zigbee frame and runs timing/reporting experiments |

Default reporting set by the drivers: the sensor's factory intervals, temperature and humidity at least every 15 minutes (change 0.1 °C / 2 %RH, 60 s minimum) and battery at least every 20 minutes. Longer intervals (menus in the Reference driver) save battery; Shelly publishes no recommendation for its "3 years" claim. "Reset Reporting To Factory" restores the exact factory values.
To use other reporting settings with the Simple driver, switch the device to the Reference driver, set them there, and switch back.

## Pairing (important)
Use **Add Device → Zigbee → "Pair using strict Zigbee 3.0 mode"**, then press the button **5 times quickly** (LED flashes twice every 2 s for about a minute). Normal pairing makes the sensor leave again.

## Install on the hub from this repository
Drivers Code → New Driver → Import, URL (Simple or Reference):

`https://raw.githubusercontent.com/klohner/klohner.github.io/master/Hubitat/Shelly_BLU_H%26T_ZB/Shelly_BLU_HT_ZB_Simple.groovy`

`https://raw.githubusercontent.com/klohner/klohner.github.io/master/Hubitat/Shelly_BLU_H%26T_ZB/Shelly_BLU_HT_ZB_Reference.groovy`

## Things learned
- Every report the sensor sends opens a command window of at least 6.5 s; configuration is sent right after a report, no button press needed.
- The sensor enforces no reporting limits, so the driver clamps intervals itself; max interval 0xFFFF switches reporting off.
- A fresh join resets reporting to factory (T/H 60/900 s, battery 300/1200 s); a battery pull keeps the configuration.

See [Technical_Reference.md](Technical_Reference.md) for the protocol, sleepy-device delivery rules, known limitations and changelog.

Apache-2.0. Use at your own risk; the driver sends Zigbee commands to your sensor.
