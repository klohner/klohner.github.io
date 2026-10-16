/**
 *  Shelly BLU H&T ZB (SBHT-203C) - Reference driver
 *
 *  Full-featured driver for the battery-powered Zigbee sleepy end device Shelly BLU H&T ZB (temperature, humidity, battery).
 *  Documentation, protocol notes and the Simple driver: https://github.com/klohner/klohner.github.io/tree/master/Hubitat/Shelly_BLU_H%26T_ZB
 *
 *  FEATURES
 *    - Temperature, humidity (%RH) and battery (% and volts) with hub-side offsets.
 *    - Complete ZCL / ZDO frame parser with Debug and Trace logging (every received frame, raw and decoded).
 *    - Sleepy-device command queue: bindings, Configure Reporting, read-back verification and reads are queued and sent in a
 *      burst right after the sensor's next report (one item at a time, each answer keeps the window open).
 *    - Reporting preferences (shortest time, "report at least every" menus, change thresholds) with the presets
 *      Factory, Default (= factory intervals, 0.1 C / 2 %RH) and Recommended (1 h / 1 h / 12 h) - see the Preferences page.
 *    - Health check, Battery Replaced date, Initialize (cleans leftovers), Self Test and Replay Frame for developers.
 *
 *  PAIRING: Add Device > Zigbee > "Pair using strict Zigbee 3.0 mode" (normal pairing makes the sensor leave again on a C-8),
 *  then press the button 5 times quickly. No button press is needed afterwards: the sensor accepts commands for a few
 *  seconds after each report, and this driver uses that window.
 *
 *  DEVICE FACTS (see Technical_Reference.md)
 *    - Factory reporting: temperature + humidity 60/900 s change 0.01, battery 300/1200 s change 0.5 %. Driver defaults: the same
 *      intervals with 0.1 C / 2 %RH change thresholds.
 *    - The sensor enforces no limits on reporting intervals, so this driver clamps them (CLAMP_* below).
 *    - A maximum interval of 0xFFFF removes the reporting entry (reporting off, value only on Refresh).
 *    - Scaling: temperature int16 / 100 = C (0x8000 invalid); humidity uint16 / 100 = %RH (0xFFFF invalid);
 *      battery uint8 / 2 = % (0xFF invalid); battery voltage uint8 * 0.1 V.
 *    - Device Data: the platform sets manufacturer, model, application, endpointId; this driver adds ieee, swBuildId, zclVersion,
 *      powerSource (decoded, e.g. "Battery") and batteryType (CR2032).
 *
 *  INSTALL NOTE: this driver sends commands to the sensor. Do not keep the Probe driver installed next to it when pairing a
 *  new unit (both match the same device).
 *
 *  Version history: 0.3 queue engine, 0.4 factory reset / battery replaced / health check, 0.5 interval menus, 0.6 Initialize /
 *  power source decoded / command help, 0.7 burst queue and factory-interval defaults, 0.8 Reset Reporting To Recommended.
 *
 *  Licensed under the Apache License, Version 2.0.
 */

import groovy.transform.Field
import java.math.RoundingMode

@Field static final String DRIVER_VERSION = '0.8.1'
@Field static final String BATTERY_TYPE   = 'CR2032'
@Field static final boolean QUEUE_IMPLEMENTED = true
@Field static final int  DEBUG_AUTO_OFF_SECONDS = 1800
@Field static final long CHECKIN_THROTTLE_MS = 3600000L   // lastCheckin is refreshed at most once an hour

// Interval limits applied by this driver (the sensor itself accepts anything except max < min).
@Field static final int CLAMP_TH_MIN_S      = 30      // temperature / humidity: shortest min interval offered
@Field static final int CLAMP_TH_MAXMIN_S   = 60      // shortest max interval offered; after clamping always max >= min (sensor answers 0x87 otherwise)
@Field static final int CLAMP_TH_MAX_S      = 3600    // longest max interval offered (the hub-side "silent" check uses 3x max)
@Field static final int CLAMP_BATT_MIN_S    = 300
@Field static final int CLAMP_BATT_MAX_S    = 3600
@Field static final BigDecimal CLAMP_TEMP_DELTA_MIN = 0.05   // deg C
@Field static final BigDecimal CLAMP_TEMP_DELTA_MAX = 5.0
@Field static final BigDecimal CLAMP_HUM_DELTA_MIN  = 0.5    // % RH
@Field static final BigDecimal CLAMP_HUM_DELTA_MAX  = 20.0
@Field static final int CLAMP_MAX_MAX_S = 64800   // longest 'report at least every' offered (18 hours; ZCL allows up to 0xFFFE)
@Field static final int REPORTING_OFF_MAX_S = 0xFFFF  // ZCL: max interval 0xFFFF = stop reporting (entry removed on this sensor)

// Queue engine timing (all measured on the sensor: every frame it sends opens a window >= 6.5 s, ~1 s per command, one at a time).
@Field static final int SEND_BUDGET_MS   = 4500   // first release window after a frame the sensor started (it is awake for >= 6.5 s)
@Field static final int ACK_EXTEND_MS    = 2000   // every answer proves the sensor is awake: the window stays open this long after it
@Field static final int MAX_WINDOW_MS    = 30000  // safety cap for one window, however long the sensor keeps answering
@Field static final int MIN_SPACING_MS   = 250    // next item goes out right after the answer (the answer itself takes 0.3-1 s)
@Field static final int MAX_SENDS_PER_HOUR = 60   // hard stop against command storms (battery)
@Field static final long REJOIN_COOLDOWN_MS = 600000L   // a full set is queued at most once per 10 min for NWK changes
@Field static final int ITEM_TIMEOUT_MS  = 2500   // no answer by then: pause the queue until the sensor's next report
@Field static final int SETTLE_MS        = 250    // pause after a reply before the next item goes out
@Field static final int MAX_ATTEMPTS     = 5      // sends per item before it is given up
@Field static final int MAX_VERIFY_TRIES = 2      // re-sends of a Configure Reporting whose read-back differed
@Field static final int QUEUE_MAX        = 30
@Field static final List CLUSTERS = ['0402', '0405', '0001']
@Field static final Map CLUSTER_SPEC = Collections.unmodifiableMap([
    '0402': [attr: '0000', type: 0x29, name: 'temperature', base: 10],
    '0405': [attr: '0000', type: 0x21, name: 'humidity',    base: 20],
    '0001': [attr: '0021', type: 0x20, name: 'battery',     base: 30]])
@Field static final Map CMD_ID = Collections.unmodifiableMap(['cr': '06', 'read': '00', 'rcr': '08'])   // ZCL command ids (for Default Response matching)

// Defaults = the sensor's factory intervals (temperature and humidity 15 min, battery 20 min) with calmer change thresholds than the factory 0.01.
@Field static final Map DEFAULTS = Collections.unmodifiableMap([tempMin: 60, tempEvery: '900', tempDelta: 0.10,
                                    humMin: 60, humEvery: '900', humDelta: 2.0,
                                    battMin: 300, battEvery: '1200', tempOffset: 0, humOffset: 0,
                                    logEnable: false, traceEnable: false])

// ZCL status codes (Configure Reporting / Read Reporting / Default Response / Read Attributes)
@Field static final Map ZCL_STATUS = Collections.unmodifiableMap([
    '00': 'SUCCESS', '01': 'FAILURE', '7E': 'NOT_AUTHORIZED', '7F': 'RESERVED_FIELD_NOT_ZERO', '80': 'MALFORMED_COMMAND',
    '81': 'UNSUP_CLUSTER_COMMAND', '82': 'UNSUP_GENERAL_COMMAND', '83': 'UNSUP_MANUF_CLUSTER_COMMAND',
    '84': 'UNSUP_MANUF_GENERAL_COMMAND', '85': 'INVALID_FIELD', '86': 'UNSUPPORTED_ATTRIBUTE', '87': 'INVALID_VALUE',
    '88': 'READ_ONLY', '89': 'INSUFFICIENT_SPACE', '8A': 'DUPLICATE_EXISTS', '8B': 'NOT_FOUND', '8C': 'UNREPORTABLE_ATTRIBUTE',
    '8D': 'INVALID_DATA_TYPE', '8E': 'INVALID_SELECTOR', '8F': 'WRITE_ONLY', 'C3': 'UNSUPPORTED_CLUSTER'])

// Basic cluster attributes that become Device Data. manufacturer / model / application belong to the platform.
@Field static final Map BASIC_DATA = Collections.unmodifiableMap(['0000': 'zclVersion', '0007': 'powerSource', '4000': 'swBuildId'])

// ZCL Basic cluster PowerSource (attribute 0x0007): bits 0-6 = source, bit 7 = battery backup available.
@Field static final Map POWER_SOURCES = Collections.unmodifiableMap([0: 'Unknown', 1: 'Mains (single phase)', 2: 'Mains (3 phase)', 3: 'Battery',
    4: 'DC source', 5: 'Emergency mains (constantly powered)', 6: 'Emergency mains (with transfer switch)'])
@Field static final Map BASIC_LOG_ONLY = Collections.unmodifiableMap(['0001': 'application', '0002': 'stackVersion', '0003': 'hwVersion',
                                                                      '0004': 'manufacturer', '0005': 'model', '0006': 'dateCode'])

// Explanation of the Commands, shown on the Preferences page.
@Field static final String HELP_HTML = '<b>What the Commands do</b><br><span style="font-weight:normal">' +
  '<b>Reporting defaults</b>: temperature and humidity at least every 15 minutes, battery at least every 20 minutes (the sensor\'s factory intervals, marked "(default, factory)" in the menus), with a 0.1 \u00b0C / 2 %RH change threshold. <b>Recommended</b> (marked "(recommended)" in the menus; saves battery): temperature at least every 1 hour (0.1 \u00b0C change), humidity at least every 1 hour (2 %RH change), battery at least every 12 hours (2 % change); shortest time between reports 1 minute (battery 5 minutes). <b>Factory</b>: temperature and humidity 1 minute to 15 minutes or 0.01 change, battery 5 to 20 minutes or 0.5 % change.<br>' +
  '<b>Configure</b>: binds the sensor to the hub and (re)sends all reporting settings, then reads them back. Use after pairing or when something looks wrong.<br>' +
  '<b>Refresh</b>: reads temperature, humidity and battery now. The sensor sleeps, so it is answered after its next report.<br>' +
  '<b>Battery Replaced</b>: records today as the battery change date (batteryLastReplaced).<br>' +
  '<b>Initialize</b>: removes Current States, State Variables, Device Data and scheduled jobs that this driver does not use (leftovers from other drivers or older versions). The work queue is kept.<br>' +
  '<b>Clear Queue</b>: throws away everything waiting to be sent to the sensor (and the failed list). Nothing is sent. Use Configure or save the preferences to queue the work again.<br>' +
  '<b>Reset Reporting To Factory</b>: queues the sensor\'s factory reporting (temperature and humidity 60 s to 15 min or 0.01 change, battery 5 to 20 min). Saving the preferences again re-applies your own settings.<br>' +
  '<b>Reset Reporting To Recommended</b>: queues the recommended reporting: temperature 1 min to 1 hour or 0.1 \u00b0C change, humidity 1 min to 1 hour or 2 %RH change, battery 5 min to 12 hours or 2 % change. Saving the preferences again re-applies your own settings.<br>' +
  '<b>Self Test</b>: developer check. Feeds recorded and constructed frames and the queue-engine scenarios through the real code and reports pass/fail in the Logs (for example "SELFTEST N/N passed"). Nothing is sent to the sensor, nothing is published, no button press is needed.<br>' +
  '<b>Replay Frame</b>: developer tool. Paste a raw Hubitat description string (copy it from the Logs or from a Trace log line) to decode it again as if the sensor had just sent it. Events and Device Data ARE updated, but the queue is not involved and nothing is sent.<br>' +
  '<i>The sensor is asleep except right after it transmits, so anything sent to it goes out after its next report (normally within the "report at least every" time).</i></span>'

metadata {
    definition(name: 'Shelly BLU H&T ZB Reference', namespace: 'klohner', author: 'Karl Lohner', singleThreaded: true,
               importUrl: 'https://raw.githubusercontent.com/klohner/klohner.github.io/master/Hubitat/Shelly_BLU_H%26T_ZB/Shelly_BLU_HT_ZB_Reference.groovy') {
        capability 'Sensor'
        capability 'TemperatureMeasurement'
        capability 'RelativeHumidityMeasurement'
        capability 'Battery'
        capability 'Refresh'
        capability 'Configuration'

        attribute 'batteryVoltage', 'number'
        attribute 'configStatus',   'string'   // e.g. "Configured", "Pending: 3 items", "Failed: see log"
        attribute 'healthStatus',   'string'   // online / offline / unknown
        attribute 'lastCheckin',    'string'   // throttled
        attribute 'batteryLastReplaced', 'string'   // set by the batteryReplaced command

        command 'batteryReplaced'
        command 'initialize'
        command 'clearQueue'
        command 'resetReportingToFactory'
        command 'resetReportingToRecommended'
        command 'selfTest'
        command 'replayFrame', [[name: 'Frame description string', type: 'STRING']]

        // Output clusters are unsettled (Identify per Shelly / zigbee2mqtt, OTA per older drivers): list the variants.
        fingerprint profileId: '0104', endpointId: '01', deviceId: '0302', inClusters: '0000,0001,0003,0402,0405',
                    outClusters: '0003', manufacturer: 'Shelly', model: 'BLU H&T ZB', deviceJoinName: 'Shelly BLU H&T ZB'
        fingerprint profileId: '0104', endpointId: '01', deviceId: '0302', inClusters: '0000,0001,0003,0402,0405',
                    outClusters: '0019', manufacturer: 'Shelly', model: 'BLU H&T ZB', deviceJoinName: 'Shelly BLU H&T ZB'
        fingerprint profileId: '0104', endpointId: '01', deviceId: '0302', inClusters: '0000,0001,0003,0402,0405',
                    outClusters: '0003,0019', manufacturer: 'Shelly', model: 'BLU H&T ZB', deviceJoinName: 'Shelly BLU H&T ZB'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0001,0003,0402,0405',
                    manufacturer: 'Shelly', model: 'BLU H&T ZB', deviceJoinName: 'Shelly BLU H&T ZB'
    }

    preferences {
        input name: 'tempMin',   type: 'number',  title: 'Temperature: shortest time between reports (seconds, 30-3600)',  range: '30..3600', defaultValue: 60
        input name: 'tempDelta', type: 'decimal', title: 'Temperature: report when it changes by (\u00b0C, 0.05-5)',            defaultValue: 0.10
        input name: 'tempEvery', type: 'enum',    title: 'Temperature: report at least every', options: ['60': '1 minute (not recommended)', '300': '5 minutes', '900': '15 minutes (default, factory)', '1200': '20 minutes', '1800': '30 minutes', '3600': '1 hour (recommended)', '10800': '3 hours', '21600': '6 hours', '43200': '12 hours', '64800': '18 hours', 'off': 'Off (value only on Refresh)'], defaultValue: '900'
        input name: 'humMin',    type: 'number',  title: 'Humidity: shortest time between reports (seconds, 30-3600)',     range: '30..3600', defaultValue: 60
        input name: 'humDelta',  type: 'decimal', title: 'Humidity: report when it changes by (% RH, 0.5-20)',             defaultValue: 2.0
        input name: 'humEvery',  type: 'enum',    title: 'Humidity: report at least every', options: ['60': '1 minute (not recommended)', '300': '5 minutes', '900': '15 minutes (default, factory)', '1200': '20 minutes', '1800': '30 minutes', '3600': '1 hour (recommended)', '10800': '3 hours', '21600': '6 hours', '43200': '12 hours', '64800': '18 hours', 'off': 'Off (value only on Refresh)'], defaultValue: '900'
        input name: 'battEvery', type: 'enum',    title: 'Battery: report at least every', options: ['60': '1 minute (not recommended)', '300': '5 minutes', '900': '15 minutes', '1200': '20 minutes (default, factory)', '1800': '30 minutes', '3600': '1 hour', '10800': '3 hours', '21600': '6 hours', '43200': '12 hours (recommended)', '64800': '18 hours', 'off': 'Off (value only on Refresh)'], defaultValue: '1200'
        input name: 'tempOffset', type: 'decimal', title: 'Temperature offset (°C, applied by the hub)', defaultValue: 0
        input name: 'humOffset',  type: 'decimal', title: 'Humidity offset (% RH, applied by the hub)',   defaultValue: 0
        input name: 'cmdHelp', type: 'hidden', title: HELP_HTML
        input name: 'logEnable', type: 'bool',    title: 'Debug logging (turns itself off after 30 minutes)', defaultValue: false
        input name: 'traceEnable', type: 'bool',  title: 'Trace logging: log every received frame, raw and decoded (turns itself off after 30 minutes)', defaultValue: false
    }
}

// ------------------------------------------------------------------ lifecycle

void installed() {
    logInfo "installed (v${DRIVER_VERSION})"
    initState()
    writeDeviceData()
    scheduleHealth()
    queueConfig('installed', true, true)
}

void updated() {
    logInfo "preferences saved (v${DRIVER_VERSION})"
    unschedule('debugOff')
    if (pref('logEnable') || pref('traceEnable')) runIn(DEBUG_AUTO_OFF_SECONDS, 'debugOff')
    for (String old in ['tempMax', 'humMax', 'humMode', 'battMax']) {      // settings of versions before 0.5.0
        try { device.removeSetting(old) } catch (Exception e) { /* not present */ }
    }
    initState()
    writeDeviceData()
    scheduleHealth()
    queueConfig('preferences saved', false, false)     // only what differs from what the sensor acknowledged
}

void configure() {
    logInfo 'configure requested'
    initState()
    cleanLegacyData()
    queueConfig('configure', true, false)              // the full set: binds, reporting, reads
}

void refresh() {
    logInfo 'refresh requested'
    initState()
    queueReads(false)
    logInfo "refresh: ${((state.queue ?: []) as List).count { Map i -> i.status == 'queued' }} items queued, read after the next report from the sensor"
    publishStatus()
}

void clearQueue() {
    initState()
    state.queue = []
    state.failed = []
    state.session = null
    unschedule('itemTimeout')
    unschedule('pumpTick')
    logInfo 'queue cleared'
    publishStatus()
}

void resetReportingToFactory() {
    queueRawReporting('factory', [min: 60, max: 900, delta: 1], [min: 60, max: 900, delta: 1], [min: 300, max: 1200, delta: 1],
        'temperature and humidity 60/900 s change 0.01, battery 300/1200 s change 0.5 %')
}

// Recommended: temperature 1 min / 1 hour / 0.1 degC, humidity 1 min / 1 hour / 2 %RH, battery 5 min / 12 hours / 2 % (raw: 10, 200, 4).
void resetReportingToRecommended() {
    queueRawReporting('recommended', [min: 60, max: 3600, delta: 10], [min: 60, max: 3600, delta: 200], [min: 300, max: 43200, delta: 4],
        'temperature 60/3600 s change 0.1 C, humidity 60/3600 s change 2 %RH, battery 300/43200 s change 2 %')
}

private void queueRawReporting(String what, Map t, Map h, Map b, String text) {
    initState()
    if (!testing()) logInfo "reset reporting to ${what} requested: ${text}"
    state.remove('needsConfig')
    state.gen = ((state.gen ?: 0) as int) + 1
    state.verifyTries = [:]
    state.failed = []
    state.queue = []                      // everything else is dropped, including an in-flight item (its late reply finds no match)
    // raw values: these deliberately bypass the clamps (factory deltas are below the offered minimums)
    Map byCl = ['0402': t, '0405': h, '0001': b]
    for (String cl in CLUSTERS) {
        Map spec = CLUSTER_SPEC[cl] as Map
        Map fp = new LinkedHashMap(byCl[cl] as Map)
        fp.off = false
        enqueue('cr', cl, spec.attr as String, (spec.base as int) + 1, fp)
    }
    if (!testing()) logInfo 'saving the preferences again re-applies your own settings'
    publishStatus()
}

void batteryReplaced() {
    String d = new Date().format('yyyy-MM-dd', location.timeZone)
    sendEvent(name: 'batteryLastReplaced', value: d, descriptionText: "${device.displayName} battery replaced ${d}".toString())
    logInfo "battery replacement recorded: ${d}"
}

// Offline when nothing was received for 3x the temperature max interval (min 10 min).
void healthCheck() {
    initState()
    long last = (state.lastRxAt ?: 0L) as long
    if (!last) return
    // what the SENSOR holds (last acknowledged), not what the preferences say; a pending change must not shorten the limit
    String cfg = ((state.applied ?: [:]) as Map)['0402']?.cfg
    int tMax = cfg ? Integer.parseInt(cfg.split('/')[1]) : Math.max(900, (buildDesired()['0402'] as Map).max as int)
    if (tMax >= REPORTING_OFF_MAX_S) return            // temperature reporting is off: silence is expected
    long limit = Math.max(600000L, 3L * tMax * 1000L)
    if (now() - last > limit && state.health != 'offline') {
        state.health = 'offline'
        sendEvent(name: 'healthStatus', value: 'offline', descriptionText: "${device.displayName} is offline (silent for more than ${(int) (limit / 60000L)} min)".toString())
        log.warn "${device.displayName}: no frame from the sensor for more than ${(int) (limit / 60000L)} min: marked offline"
    }
}

private void scheduleHealth() { runEvery15Minutes('healthCheck') }

void debugOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
    device.updateSetting('traceEnable', [value: 'false', type: 'bool'])
    logInfo 'debug and trace logging switched off'
}

// ------------------------------------------------------------------ parse

List parse(String description) {
    initState()
    if (state.testCapture != null) {         // a self test that was cut off must never swallow real events
        state.remove('testCapture')
        state.remove('testData')
    }
    long t = now()
    long prev = (state.lastRxAt ?: 0L) as long
    state.lastRxAt = t                       // one small write per frame; frames arrive about once a minute

    Map f = decodeFrame(description)

    if (traceOn()) {
        long dt = prev ? (t - prev) : 0L
        log.trace "${device.displayName}: RX [+${fmtDt(dt)}] ${f.kind} ${description} => ${f.decoded}"
    }
    markOnline(t)
    onFrame(f)
    return []
}

// Called after every decoded frame (queue engine entry point, see the engine section below).
//   reply == true  : the frame answers one of our requests (ack candidates)
//   reply == false : device-initiated frame (report, announce, ...): the sensor is awake, a command window is open
private void onFrame(Map f) {
    if (!QUEUE_IMPLEMENTED) return
    String k = f.kind as String
    try {
        if (state.needsConfig == true) queueConfig('driver started', false, false)
        if (k == 'ANNCE') {
            onAnnounce(f)
        } else if (!testing() && state.nwk != null && device.deviceNetworkId && !device.deviceNetworkId.equalsIgnoreCase(state.nwk as String)) {
            String nwk = device.deviceNetworkId.toUpperCase()      // the platform moved the device to a new address and we missed the announce
            state.nwk = nwk
            handleRejoin(nwk)
        }
        boolean matched = ackFrame(f)
        if (f.reply == true) {
            if (matched && state.session) keepWindowOpen()
        } else if (state.session || hasQueued()) {
            openSession(k)
        }
    } catch (Exception e) {
        logWarn "queue engine error ${e.class.simpleName}: ${e.message}"
    }
}

// Classify one description string and decode it. Never throws. Events and Device Data are written as side effects.
private Map decodeFrame(String description) {
    Map f = [kind: 'UNKNOWN', decoded: '', reply: false, cluster: '', attr: '', ok: true, data: []]
    Map m = null
    try {
        m = zigbee.parseDescriptionAsMap(description)
    } catch (Exception e) {
        m = null
    }
    try {
        if (description?.startsWith('catchall:') && m) {
            decodeCatchall(m, f)
        } else if (description?.startsWith('read attr -')) {
            Map r = (m && m.cluster) ? m : rawReadAttrFallback(description)
            decodeReadAttr(r, f)
        } else {
            f.kind = 'UNKNOWN'
            f.decoded = description?.startsWith('catchall:') ? 'catchall: platform map parse failed' : 'unrecognised description prefix'
            logDebug "unhandled description: ${description}"
        }
    } catch (Exception e) {
        f.kind = 'PARSE_ERROR'
        f.ok = false
        f.decoded = "PARSE ERROR ${e.class.simpleName}: ${e.message}"
        long tn = now()
        if (tn - ((state.lastParseWarnAt ?: 0L) as long) > 60000L) {       // at most one warning a minute
            state.lastParseWarnAt = tn
            logWarn "${f.decoded} for: ${description}"
        }
    }
    return f
}

private void decodeCatchall(Map m, Map f) {
    Integer cl = (m.clusterInt ?: -1) as Integer
    String cmd = (m.command ?: '').toString().toUpperCase()
    List data = (m.data ?: []) as List
    boolean general = m.isClusterSpecific?.toString() != 'true' && m.isManufacturerSpecific?.toString() != 'true'   // plain general ZCL command
    boolean zdo = m.profileId == '0000'
    f.cluster = hex4(m.clusterId)
    f.data = data

    if (zdo && cl == 0x0013) {
        f.kind = 'ANNCE'
        f.decoded = handleAnnounce(data, f)
    } else if (zdo && cl == 0x8021) {
        f.kind = 'BIND_RSP'
        f.reply = true
        String st = data.size() > 1 ? data[1].toString().toUpperCase() : '??'
        f.ok = (st == '00')
        f.decoded = "bind_rsp seq=${data ? data[0] : '??'} status=${st}${f.ok ? '' : ' FAILED'}"
    } else if (zdo && cl == 0x8033) {
        f.kind = 'MGMT_BIND_RSP'
        f.reply = true
        f.ok = data.size() > 1 && data[1].toString() == '00'
        f.decoded = decodeMgmtBind(data)
    } else if (zdo && cl == 0x0002) {
        f.kind = 'NODE_DESC_REQ'
        f.decoded = "node_desc_req from the sensor seq=${data ? data[0] : '??'} (a Zigbee 3.0 post-join step)"
    } else if (zdo && cl == 0x0000) {
        f.kind = 'NWK_ADDR_REQ'
        f.decoded = "nwk_addr_req from the sensor data=${data}"
    } else if (zdo) {
        f.kind = 'ZDO_OTHER'
        f.decoded = "zdo cluster=${m.clusterId} data=${data}"
    } else if (cmd == '07' && general) {
        f.kind = 'CR_RSP'
        f.reply = true
        f.decoded = decodeCrRsp(data, f)
    } else if (cmd == '09' && general) {
        f.kind = 'RCR_RSP'
        f.reply = true
        f.decoded = decodeRcrRsp(data, f)
    } else if (cmd == '0B' && general) {
        f.kind = 'DEFAULT_RSP'
        f.reply = true
        // data = [command id, status]
        String st = data.size() > 1 ? data[1].toString().toUpperCase() : '??'
        f.ok = (st == '00')
        f.decoded = "default_rsp for command ${data ? data[0] : '??'} status ${st} ${ZCL_STATUS[st] ?: ''}${f.ok ? '' : ' -> REFUSED'}".trim()
    } else if (cmd == '0D' && general) {
        f.kind = 'DISC_RSP'
        f.reply = true
        f.decoded = decodeDiscover(f.cluster as String, data)
    } else if (cmd == '01' && general) {
        // a read response delivered as catchall (the usual form is "read attr -")
        f.kind = 'RD_RSP'
        f.reply = true
        if (data.size() >= 3) {
            f.attr = (data[1].toString() + data[0].toString()).toUpperCase()
            f.ok = (data[2].toString() == '00')
        }
        f.decoded = "catchall read_rsp cluster=${f.cluster} attr ${f.attr} status ${data.size() >= 3 ? data[2] : '??'}"
    } else if (cmd == '0A' && general) {
        f.kind = 'REPORT'
        f.decoded = "catchall report cluster=${f.cluster} data=${data}"
    } else {
        f.kind = 'CATCHALL_OTHER'
        f.decoded = "cluster=${m.clusterId} cmd=${cmd} specific=${m.isClusterSpecific} data=${data}"
    }
}

private void decodeReadAttr(Map r, Map f) {
    if (!r) {
        f.kind = 'UNKNOWN'
        f.ok = false
        f.decoded = 'read attr: empty map'
        return
    }
    String cmd = (r.command ?: '').toString().toUpperCase()
    if (cmd == '0A') {
        f.kind = 'REPORT'
    } else if (cmd == '01') {
        f.kind = 'RD_RSP'
        f.reply = true
    } else {
        f.kind = 'RD_OTHER'
        f.reply = !r.fallback
        logDebug "read attr with unexpected command '${cmd}': ${r}"
    }
    f.cluster = hex4(r.cluster)
    f.attr = hex4(r.attrId)
    List<String> parts = []
    boolean allOk = true
    boolean anyInvalid = false
    Map first = handleOneAttr(f.cluster as String, r.attrId?.toString(), r.value?.toString(), r.encoding?.toString(), r.status?.toString(), f.kind as String)
    parts << first.text
    allOk = allOk && first.ok
    anyInvalid = anyInvalid || (first.invalid == true)
    for (Object a in ((r.additionalAttrs ?: []) as List)) {
        Map x = a as Map
        Map one = handleOneAttr(f.cluster as String, x.attrId?.toString(), x.value?.toString(), x.encoding?.toString(), x.status?.toString(), f.kind as String)
        parts << one.text
        allOk = allOk && one.ok
        anyInvalid = anyInvalid || (one.invalid == true)
    }
    f.ok = allOk
    f.invalid = anyInvalid     // the sensor answered, but with its 'no value' marker: acks use f.reply, not f.ok
    f.decoded = parts.join(' | ')
}

// Returns [text: decoded description, ok: boolean]. Publishes events / Device Data for the known attributes.
private Map handleOneAttr(String cluster, String attrRaw, String value, String enc, String status, String kind) {
    String attr = hex4(attrRaw)
    String what = "${cluster}/${attr} enc=${enc} val=${value}${status ? ' status=' + status : ''}"
    boolean isError = (status != null && status != '00' && status != '0') || (enc == '86')
    if (isError) return [text: "${what} -> ERROR/UNSUPPORTED".toString(), ok: false]

    try {
        if (cluster == '0402' && attr == '0000') {
            int raw = signed16(value)
            if (raw == -32768) return [text: "${what} -> temperature INVALID (0x8000), ignored".toString(), ok: true, invalid: true]
            BigDecimal c = (new BigDecimal(raw).divide(100G, 2, RoundingMode.HALF_UP) + (testing() ? 0G : toDec(pref('tempOffset')))).setScale(2, RoundingMode.HALF_UP)
            if (testing()) {
                emit([name: 'temperature', value: c, unit: '°C'])
            } else {
                def tv = convertTemperatureIfNeeded(c, 'C', 2)
                emit([name: 'temperature', value: tv, unit: "°${location.temperatureScale}",
                      descriptionText: "${device.displayName} temperature is ${tv}°${location.temperatureScale}".toString()])
            }
            return [text: "${what} -> ${c} C".toString(), ok: true]
        }
        if (cluster == '0405' && attr == '0000') {
            int raw = Integer.parseInt(value, 16)
            if (raw == 0xFFFF) return [text: "${what} -> humidity INVALID (0xFFFF), ignored".toString(), ok: true, invalid: true]
            BigDecimal h = (new BigDecimal(raw).divide(100G, 2, RoundingMode.HALF_UP) + (testing() ? 0G : toDec(pref('humOffset')))).setScale(2, RoundingMode.HALF_UP)
            h = h.max(0G).min(100G)
            emit([name: 'humidity', value: h, unit: '%RH',
                  descriptionText: "${device.displayName} humidity is ${h} %RH".toString()])
            return [text: "${what} -> ${h} %RH".toString(), ok: true]
        }
        if (cluster == '0001' && attr == '0021') {
            int raw = Integer.parseInt(value, 16)
            if (raw == 0xFF) return [text: "${what} -> battery INVALID (0xFF), ignored".toString(), ok: true, invalid: true]
            int pct = Math.max(0, Math.min(100, (int) Math.round(raw / 2.0d)))
            emit([name: 'battery', value: pct, unit: '%',
                  descriptionText: "${device.displayName} battery is ${pct}%".toString()])
            return [text: "${what} -> ${pct} %".toString(), ok: true]
        }
        if (cluster == '0001' && attr == '0020') {
            int raw = Integer.parseInt(value, 16)
            if (raw == 0xFF) return [text: "${what} -> battery voltage INVALID (0xFF), ignored".toString(), ok: true, invalid: true]
            BigDecimal v = new BigDecimal(raw).divide(10G, 1, RoundingMode.HALF_UP)
            emit([name: 'batteryVoltage', value: v, unit: 'V',
                  descriptionText: "${device.displayName} battery voltage is ${v} V".toString()])
            return [text: "${what} -> ${v} V".toString(), ok: true]
        }
        if (cluster == '0000') {
            String shown = (attr == '0007') ? powerSourceText(value) : value      // string attributes (type 0x41/0x42) arrive already converted to text by the platform
            if (BASIC_DATA[attr]) {
                setData(BASIC_DATA[attr] as String, shown)
                return [text: "${what} -> data ${BASIC_DATA[attr]}=${shown}".toString(), ok: true]
            }
            if (BASIC_LOG_ONLY[attr]) {
                return [text: "${what} -> ${BASIC_LOG_ONLY[attr]}=${shown} (platform-owned, not stored by this driver)".toString(), ok: true]
            }
        }
    } catch (Exception e) {
        return [text: "${what} -> DECODE ERROR ${e.class.simpleName}: ${e.message}".toString(), ok: false]
    }
    return [text: "${what} (attribute not used)".toString(), ok: true]
}

// Device_annce: seq, NWK (LE 2), IEEE (LE 8), capability
private String handleAnnounce(List data, Map f) {
    if (data.size() < 12) return "annce (short) data=${data}"
    String nwk = (data[2].toString() + data[1].toString()).toUpperCase()
    String ieee = ((3..10).collect { data[it].toString() }.reverse()).join('').toUpperCase()
    String cap = data[11].toString()
    String prev = state.nwk as String
    f.nwk = nwk
    f.nwkChanged = (prev != null && !prev.equalsIgnoreCase(nwk))
    if (state.replayMode == true) return "annce nwk=${nwk} ieee=${ieee} cap=${cap} (replay, nothing recorded)".toString()
    if (testing()) {                         // self test: no state writes, no log lines
        setData('ieee', ieee)
        return "annce nwk=${nwk} ieee=${ieee} cap=${cap} (self test)".toString()
    }
    String note
    if (prev == null) {
        note = 'first announce'
    } else if (prev != nwk) {
        note = "NWK CHANGED ${prev} -> ${nwk} (rejoin)"
        logInfo "announce with a new network address ${nwk} (was ${prev}): the sensor has rejoined"
    } else {
        note = 'same NWK (announce only)'
    }
    state.nwk = nwk
    setData('ieee', ieee)
    return "annce nwk=${nwk} ieee=${ieee} cap=${cap} ${note}".toString()
}

private String decodeCrRsp(List data, Map f) {
    // Configure Reporting Response: [00] = every record succeeded, otherwise records of [status, direction, attr LE 2]
    String text
    if (data.size() == 1 && data[0].toString() == '00') {
        text = 'SUCCESS'
    } else if (data.isEmpty()) {
        f.ok = false
        text = 'empty'
    } else {
        List<String> recs = []
        boolean allOk = true
        for (int i = 0; i + 3 < data.size(); i += 4) {
            String st = data[i].toString().toUpperCase()
            String a = (data[i + 3].toString() + data[i + 2].toString()).toUpperCase()
            if (st != '00') allOk = false
            recs << "attr ${a} ${st} ${ZCL_STATUS[st] ?: '?'}".toString()
        }
        if (data.size() % 4 != 0) {
            allOk = false
            recs << "(truncated: ${data.size() % 4} stray bytes)".toString()
        }
        f.ok = allOk
        text = recs ? recs.join(', ') : "unparsed data=${data}"
    }
    return "cr_rsp cluster=${f.cluster} ${text}".toString()
}

// Size in bytes of the "reportable change" field for a ZCL data type (analog types only; discrete types carry none).
private int changeLen(String typeHex) {
    int t
    try { t = Integer.parseInt(typeHex, 16) } catch (Exception e) { return 0 }
    if (t >= 0x20 && t <= 0x2F) return (t & 0x07) + 1 > 8 ? 0 : ((t & 0x07) + 1)     // u8..u64, s8..s64
    if (t == 0x38) return 2
    if (t == 0x39) return 4
    if (t == 0x3A) return 8
    if (t >= 0xE0 && t <= 0xE2) return 4
    return 0
}

private String decodeRcrRsp(List data, Map f) {
    // Read Reporting Configuration Response: records
    //   failure           : [status, direction, attr LE 2]
    //   success, dir 0    : [00, 00, attr LE 2, type, min LE 2, max LE 2, change (analog types only)]
    //   success, dir 1    : [00, 01, attr LE 2, timeout LE 2]
    try {
        if (data.size() < 4) { f.ok = false; return "rcr_rsp cluster=${f.cluster} (short) data=${data}" }
        List<String> recs = []
        List<Map> parsed = []
        f.rcr = parsed                       // structured copy for the read-back check: [attr, status, type, min, max, chg]
        boolean allOk = true
        int i = 0
        while (i + 4 <= data.size()) {
            String st = data[i].toString().toUpperCase()
            String dir = data[i + 1].toString()
            String attr = (data[i + 3].toString() + data[i + 2].toString()).toUpperCase()
            if (!f.attr) f.attr = attr
            if (st != '00') {
                allOk = false
                parsed << [attr: attr, status: st]
                String extra = (st == '8B') ? ' -> no reporting entry (reporting is OFF for this attribute)' : ''
                recs << "attr ${attr} ${st} ${ZCL_STATUS[st] ?: '?'}${extra}".toString()
                i += 4
            } else if (dir == '01') {
                recs << "attr ${attr} receive-side timeout".toString()
                i += 6
            } else {
                if (i + 9 > data.size()) { allOk = false; recs << "attr ${attr} (short success record)".toString(); break }
                String type = data[i + 4].toString().toUpperCase()
                int mn = Integer.parseInt(data[i + 6].toString() + data[i + 5].toString(), 16)
                int mx = Integer.parseInt(data[i + 8].toString() + data[i + 7].toString(), 16)
                int n = changeLen(type)
                String chg = ''
                if (n > 0 && i + 9 + n <= data.size()) chg = data.subList(i + 9, i + 9 + n).reverse().join('').toUpperCase()
                parsed << [attr: attr, status: '00', type: type, min: mn, max: mx, chg: (chg ? Long.parseLong(chg, 16) : -1L)]
                recs << "attr ${attr} type ${type} holds min ${mn} s, max ${mx} s, change raw 0x${chg}".toString()
                i += 9 + n
            }
        }
        f.ok = allOk
        return "rcr_rsp cluster=${f.cluster} ${recs.join(' | ')}".toString()
    } catch (Exception e) {
        f.ok = false
        return "rcr_rsp decode error ${e.message} data=${data}".toString()
    }
}

private String decodeDiscover(String cluster, List data) {
    // Discover Attributes Response: [discoveryComplete, (attr LE 2, type)*]
    if (!data) return "disc_rsp cluster=${cluster} (empty)"
    List<String> found = []
    for (int i = 1; i + 2 < data.size(); i += 3) {
        found << "${(data[i + 1].toString() + data[i].toString()).toUpperCase()}:t${data[i + 2]}".toString()
    }
    return "disc_rsp cluster=${cluster} complete=${data[0]}${data[0].toString() == '00' ? ' INCOMPLETE (list truncated)' : ''} attrs=${found}".toString()
}

private String decodeMgmtBind(List d) {
    // Mgmt_Bind_rsp: seq, status, total, startIndex, count, entries[srcIEEE 8, srcEP 1, cluster 2, mode 1, dst...]
    try {
        if (d.size() >= 2 && d[1].toString() != '00') return "mgmt_bind_rsp status=${d[1]} (error) data=${d}".toString()
        if (d.size() < 5) return "mgmt_bind_rsp (short) data=${d}"
        String st = d[1].toString()
        int start = Integer.parseInt(d[3].toString(), 16)
        int total = Integer.parseInt(d[2].toString(), 16)
        int cnt = Integer.parseInt(d[4].toString(), 16)
        List<String> out = []
        int i = 5
        for (int n = 0; n < cnt; n++) {
            if (i + 12 > d.size()) break
            String cl = (d[i + 10].toString() + d[i + 9].toString()).toUpperCase()
            int mode = Integer.parseInt(d[i + 11].toString(), 16)
            i += 12
            if (mode == 3 && i + 9 <= d.size()) {
                List<String> bytes = []
                for (int k = 7; k >= 0; k--) bytes << d[i + k].toString()
                out << "${cl}->${bytes.join('').toUpperCase()}/ep${d[i + 8]}".toString()
                i += 9
            } else if (mode == 1 && i + 2 <= d.size()) {
                out << "${cl}->group ${d[i + 1]}${d[i]}".toString()
                i += 2
            } else {
                out << "${cl}->mode ${mode}?".toString()
                break
            }
        }
        String summary = "mgmt_bind_rsp status=${st} total=${total} listed=${cnt} entries=${out}"
        if (st == '00' && total == 0) summary += ' -> BINDING TABLE EMPTY'
        if (total > start + cnt) summary += " (partial: ${start + cnt} of ${total} entries listed)"
        return summary.toString()
    } catch (Exception e) {
        return "mgmt_bind_rsp decode error ${e.message} data=${d}".toString()
    }
}

// read attr - raw: <DNI 4><EP 2><CLUSTER 4><SIZE 2><ATTR 4 LE><TYPE-or-STATUS 2><VALUE...>
// Only used when the platform map has no cluster. Numbers are byte-reversed; strings are left alone. The frame
// is flagged "fallback" and is never treated as a reply to one of our requests.
private Map rawReadAttrFallback(String description) {
    def mt = (description =~ /read attr - raw: *([0-9A-Fa-f]{4})([0-9A-Fa-f]{2})([0-9A-Fa-f]{4})([0-9A-Fa-f]{2})([0-9A-Fa-f]{4})([0-9A-Fa-f]{2})([0-9A-Fa-f]*)/)
    if (!mt) return [:]
    String attrLe = mt[0][5]
    String attr = attrLe.substring(2, 4) + attrLe.substring(0, 2)
    String enc = mt[0][6].toUpperCase()
    String le = mt[0][7]
    int t = 0
    try { t = Integer.parseInt(enc, 16) } catch (Exception e) { t = 0 }
    boolean numeric = (t >= 0x20 && t <= 0x31) || t == 0x10 || t == 0x18 || t == 0x19
    String val = le
    if (numeric) {
        StringBuilder be = new StringBuilder()
        for (int i = le.length() - 2; i >= 0; i -= 2) be.append(le.substring(i, i + 2))
        val = be.toString()
    }
    return [cluster: mt[0][3], command: '', attrId: attr, encoding: enc, value: val, fallback: true]
}

// ------------------------------------------------------------------ events, Device Data (test mode aware)

private boolean testing() { return state.testCapture != null }

private void emit(Map evt) {
    if (testing()) {
        List c = state.testCapture as List
        c << [name: evt.name.toString(), value: evt.value.toString(), unit: (evt.unit ?: '').toString()]
        state.testCapture = c
        return
    }
    sendEvent(evt)
}

private void setData(String k, String v) {
    if (testing()) {
        Map d = (state.testData ?: [:]) as Map
        d[k] = v
        state.testData = d
        return
    }
    if (device.getDataValue(k) != v) device.updateDataValue(k, v)
}

private void markOnline(long t) {
    if (testing()) return
    if (state.health != 'online') {
        state.health = 'online'
        sendEvent(name: 'healthStatus', value: 'online', descriptionText: "${device.displayName} is online".toString())
    }
    if ((t - ((state.lastCheckinAt ?: 0L) as long)) >= CHECKIN_THROTTLE_MS) {
        state.lastCheckinAt = t
        sendEvent(name: 'lastCheckin', value: new Date(t).format("yyyy-MM-dd HH:mm:ss", location.timeZone))
    }
}

// ------------------------------------------------------------------ self test (command selfTest)

void replayFrame(String frame) {
    // test hook: decode one recorded description string. Events and Device Data ARE published, the queue engine is NOT
    // involved (nothing is sent, no network address is recorded).
    initState()
    state.replayMode = true
    try {
        Map f = decodeFrame(frame)
        logInfo "replayFrame (decode only): ${f.kind} ${f.decoded}"
    } finally {
        state.remove('replayMode')
    }
}

void selfTest() {
    initState()
    List<Map> vectors = selfTestVectors()
    int pass = 0
    int engineTotal = 0
    List<String> failures = []
    try {
        for (Map v in vectors) {
            state.testCapture = []
            state.testData = [:]
            Map f = decodeFrame(v.d as String)
            List cap = (state.testCapture ?: []) as List
            Map dat = (state.testData ?: [:]) as Map
            List<String> bad = []
            if (v.kind != f.kind) bad << "kind ${f.kind} (want ${v.kind})".toString()
            if (v.containsKey('ok') && v.ok != f.ok) bad << "ok ${f.ok} (want ${v.ok})".toString()
            Map ev = (v.ev ?: [:]) as Map
            for (String k in ev.keySet()) {
                Map hit = cap.find { Map c -> c.name == k } as Map
                if (hit == null) {
                    bad << "no ${k} event".toString()
                } else if (!sameNumber(hit.value.toString(), ev[k].toString())) {
                    bad << "${k}=${hit.value} (want ${ev[k]})".toString()
                }
            }
            if (v.noEvents && cap) bad << "unexpected events ${cap}".toString()
            Map wantData = (v.data ?: [:]) as Map
            for (String k in wantData.keySet()) {
                if (dat[k] != wantData[k]) bad << "data ${k}=${dat[k]} (want ${wantData[k]})".toString()
            }
            if (v.has && !(f.decoded as String).contains(v.has as String)) bad << "text lacks '${v.has}': ${f.decoded}".toString()
            if (bad) {
                failures << "FAIL ${v.n}: ${bad.join('; ')}".toString()
            } else {
                pass++
                logDebug "PASS ${v.n}: ${f.decoded}"
            }
        }
        state.testCapture = []               // engine scenarios decode frames too (events are discarded)
        Map eng = runEngineTests()
        pass += (eng.pass as int)
        engineTotal = (eng.total as int)
        failures.addAll(eng.failures as List)
    } finally {
        state.remove('testCapture')
        state.remove('testData')
    }
    logInfo "SELFTEST ${pass}/${vectors.size() + engineTotal} passed (${vectors.size()} frame vectors + ${engineTotal} engine checks, v${DRIVER_VERSION})"
    for (String s in failures) log.warn "${device.displayName}: ${s}"
}

private boolean sameNumber(String a, String b) {
    try { return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0 } catch (Exception e) { return a == b }
}

// Frames marked (real) were captured from the sensor; (built) were constructed from the ZCL / ZDO layout.
private List<Map> selfTestVectors() {
    String hdr = 'catchall: 0104 @CL@ 01 01 0040 00 A264 00 00 0000 @CMD@ 01 '
    String zhdr = 'catchall: 0000 @CL@ 00 00 0040 00 A264 00 00 0000 00 00 '
    return [
        [n: 'report temperature 24.71 (real)', kind: 'REPORT', ev: [temperature: '24.71'],
         d: 'read attr - raw: A2640104020A000029A709, dni: A264, endpoint: 01, cluster: 0402, size: 0A, attrId: 0000, encoding: 29, command: 0A, value: A709'],
        [n: 'report humidity 90.57 (real)', kind: 'REPORT', ev: [humidity: '90.57'],
         d: 'read attr - raw: A2640104050A0000216123, dni: A264, endpoint: 01, cluster: 0405, size: 0A, attrId: 0000, encoding: 21, command: 0A, value: 6123'],
        [n: 'report battery 200 -> 100 % (real)', kind: 'REPORT', ev: [battery: '100'],
         d: 'read attr - raw: A26401000108210020C8, dni: A264, endpoint: 01, cluster: 0001, size: 08, attrId: 0021, encoding: 20, command: 0A, value: C8'],
        [n: 'read response humidity 40.56 (real)', kind: 'RD_RSP', ev: [humidity: '40.56'],
         d: 'read attr - raw: A2640104050C000021D80F, dni: A264, endpoint: 01, cluster: 0405, size: 0C, attrId: 0000, encoding: 21, command: 01, value: D80F'],
        [n: 'read response ZCL version (real)', kind: 'RD_RSP', data: [zclVersion: '59'], noEvents: true,
         d: 'read attr - raw: A2640100000A00002059, dni: A264, endpoint: 01, cluster: 0000, size: 0A, attrId: 0000, encoding: 20, command: 01, value: 59'],
        [n: 'battery voltage 3.4 V (built)', kind: 'REPORT', ev: [batteryVoltage: '3.4'],
         d: 'read attr - raw: A2640100010820002022, dni: A264, endpoint: 01, cluster: 0001, size: 08, attrId: 0020, encoding: 20, command: 0A, value: 22'],
        [n: 'negative temperature -3.50 (built)', kind: 'REPORT', ev: [temperature: '-3.50'],
         d: 'read attr - raw: A2640104020A000029A2FE, dni: A264, endpoint: 01, cluster: 0402, size: 0A, attrId: 0000, encoding: 29, command: 0A, value: A2FE'],
        [n: 'invalid temperature 0x8000 is ignored (built)', kind: 'REPORT', ok: true, noEvents: true, has: 'INVALID',
         d: 'read attr - raw: A2640104020A0000290080, dni: A264, endpoint: 01, cluster: 0402, size: 0A, attrId: 0000, encoding: 29, command: 0A, value: 0080'],
        [n: 'swBuildId string (real)', kind: 'RD_RSP', data: [swBuildId: '260306/v1.2.12@9'], noEvents: true,
         d: 'read attr - raw: A2640100002A004042103236303330362F76312E322E31324039, dni: A264, endpoint: 01, cluster: 0000, size: 2A, attrId: 4000, encoding: 42, command: 01, value: 103236303330362F76312E322E31324039'],
        [n: 'model string is platform-owned (real)', kind: 'RD_RSP', noEvents: true, has: 'model=BLU H&T ZB',
         d: 'read attr - raw: A2640100001E0500420A424C5520482654205A42, dni: A264, endpoint: 01, cluster: 0000, size: 1E, attrId: 0005, encoding: 42, command: 01, value: 0A424C5520482654205A42'],
        [n: 'manufacturer string is platform-owned (real)', kind: 'RD_RSP', noEvents: true, has: 'manufacturer=Shelly',
         d: 'read attr - raw: A26401000016040042065368656C6C79, dni: A264, endpoint: 01, cluster: 0000, size: 16, attrId: 0004, encoding: 42, command: 01, value: 065368656C6C79'],
        [n: 'power source 0x03 is decoded to Battery (real)', kind: 'RD_RSP', data: [powerSource: 'Battery'], noEvents: true,
         d: 'read attr - raw: A2640100000A07003003, dni: A264, endpoint: 01, cluster: 0000, size: 0A, attrId: 0007, encoding: 30, command: 01, value: 03'],
        [n: 'unsupported attribute (built)', kind: 'RD_RSP', ok: false, noEvents: true,
         d: 'read attr - raw: A2640100000C02008600, dni: A264, endpoint: 01, cluster: 0000, size: 0C, attrId: 0002, encoding: 86, command: 01, value: 00'],
        [n: 'configure reporting OK (real)', kind: 'CR_RSP', ok: true, has: 'SUCCESS',
         d: hdr.replace('@CL@', '0405').replace('@CMD@', '07') + '00'],
        [n: 'configure reporting INVALID_VALUE (real)', kind: 'CR_RSP', ok: false, has: 'INVALID_VALUE',
         d: hdr.replace('@CL@', '0402').replace('@CMD@', '07') + '87000000'],
        [n: 'read reporting config 60/900/0x000A (real)', kind: 'RCR_RSP', ok: true, has: 'min 60 s, max 900 s, change raw 0x000A',
         d: hdr.replace('@CL@', '0402').replace('@CMD@', '09') + '00000000293C0084030A00'],
        [n: 'read reporting config NOT_FOUND = off (real)', kind: 'RCR_RSP', ok: false, has: 'reporting is OFF',
         d: hdr.replace('@CL@', '0405').replace('@CMD@', '09') + '8B000000'],
        [n: 'default response refused (built)', kind: 'DEFAULT_RSP', ok: false, has: 'REFUSED',
         d: hdr.replace('@CL@', '0402').replace('@CMD@', '0B') + '0A81'],
        [n: 'discover attributes (built)', kind: 'DISC_RSP', ok: true, has: '0000:t29',
         d: hdr.replace('@CL@', '0402').replace('@CMD@', '0D') + '01000029010029020029FDFF21'],
        [n: 'device announce (built, synthetic IEEE)', kind: 'ANNCE', data: [ieee: 'AABBCCFFFE000001'], has: 'nwk=085F',
         d: 'catchall: 0000 0013 00 00 0040 00 085F 00 00 0000 00 00 815F08010000FEFFCCBBAA80'],
        [n: 'bind response (real)', kind: 'BIND_RSP', ok: true,
         d: 'catchall: 0000 8021 00 00 0040 00 085F 00 00 0000 00 00 9700'],
        [n: 'mgmt bind response 1 entry (built)', kind: 'MGMT_BIND_RSP', ok: true, has: '0402->AABBCCFFFE000002/ep01',
         d: zhdr.replace('@CL@', '8033') + '0500010001010000FEFFCCBBAA01020403020000FEFFCCBBAA01'],
        [n: 'unknown description', kind: 'UNKNOWN', noEvents: true,
         d: 'enroll request endpoint 01']
    ]
}

// ------------------------------------------------------------------ queue engine
// Everything sent to the sensor is a queue item. The sensor is asleep except right after it transmits (report, announce,
// ...): that opens a COMMAND WINDOW of >= 6.5 s in which it answers about one command per second. So: queue now, send
// only inside a window, one item at a time, match replies by content, retry in the next window.

// ---- desired configuration (preferences -> clamped values). ov = test override map.
private Object gp(Map o, String k) { (o != null && o.containsKey(k)) ? o[k] : pref(k) }

private int deltaRaw(Object v, BigDecimal lo, BigDecimal hi) {
    BigDecimal d = toDec(v).max(lo).min(hi)
    return d.multiply(100G).setScale(0, RoundingMode.HALF_UP).intValue()      // 0.01 degC / 0.01 % per unit
}

private int everySeconds(Object v, int dflt) {
    String t = (v == null) ? '' : v.toString()
    if (t == 'off') return REPORTING_OFF_MAX_S
    int x
    try { x = new BigDecimal(t).intValue() } catch (Exception e) { x = dflt }
    return Math.max(CLAMP_TH_MAXMIN_S, Math.min(CLAMP_MAX_MAX_S, x))
}

private Map buildDesired(Map ov = null) {
    Map o = (ov != null) ? ov : ((testing() && state.testPrefs != null) ? (state.testPrefs as Map) : null)
    int tMin = clampInt(gp(o, 'tempMin'), CLAMP_TH_MIN_S, CLAMP_TH_MAX_S, 60)
    int tEvery = everySeconds(gp(o, 'tempEvery'), 900)
    boolean tOff = (tEvery == REPORTING_OFF_MAX_S)
    int tMax = tOff ? tEvery : Math.max(tMin, tEvery)          // never below min (the sensor answers 0x87)
    int tDelta = deltaRaw(gp(o, 'tempDelta'), CLAMP_TEMP_DELTA_MIN, CLAMP_TEMP_DELTA_MAX)
    int hMin = clampInt(gp(o, 'humMin'), CLAMP_TH_MIN_S, CLAMP_TH_MAX_S, 60)
    int hEvery = everySeconds(gp(o, 'humEvery'), 900)
    boolean hOff = (hEvery == REPORTING_OFF_MAX_S)
    int hMax = hOff ? hEvery : Math.max(hMin, hEvery)
    int hDelta = deltaRaw(gp(o, 'humDelta'), CLAMP_HUM_DELTA_MIN, CLAMP_HUM_DELTA_MAX)
    int bMin = clampInt(gp(o, 'battMin'), CLAMP_BATT_MIN_S, CLAMP_BATT_MAX_S, 300)
    int bEvery = everySeconds(gp(o, 'battEvery'), 1200)
    boolean bOff = (bEvery == REPORTING_OFF_MAX_S)
    int bMax = bOff ? bEvery : Math.max(bMin, bEvery)
    // max interval 0xFFFF removes the reporting entry on this sensor (= off, value only on Refresh)
    return ['0402': [min: tMin, max: tMax, delta: tDelta, off: tOff],
            '0405': [min: hMin, max: hMax, delta: hDelta, off: hOff],
            '0001': [min: bMin, max: bMax, delta: 1, off: bOff]]
}

private String cfgOf(Map c) { "${c.min}/${c.max}/${c.delta}".toString() }

// ---- queue helpers
private boolean hasQueued() { ((state.queue ?: []) as List).any { Map i -> i.status == 'queued' } }

private Map enqueue(String kind, String cluster, String attr, int prio, Map p = null) {
    List q = (state.queue ?: []) as List
    String key = "${kind}:${cluster}:${attr}".toString()
    q.removeAll { Map i -> i.key == key && i.status == 'queued' }          // replace, never append a duplicate
    if (q.size() >= QUEUE_MAX) {
        logWarn "queue full (${q.size()}), not adding ${key}"
        return null
    }
    int id = ((state.nextId ?: 1) as int)
    state.nextId = id + 1
    Map item = [id: id, gen: ((state.gen ?: 0) as int), key: key, kind: kind, cluster: cluster, attr: attr, prio: prio,
                status: 'queued', tries: 0, p: (p ?: [:]), createdAt: now()]
    q << item
    state.queue = q
    return item
}

// Build the work list. full = also bind and (re)send every setting; otherwise only what differs from state.applied.
private void queueConfig(String reason, boolean full, boolean resetApplied) {
    initState()
    state.gen = ((state.gen ?: 0) as int) + 1
    state.remove('needsConfig')
    state.verifyTries = [:]
    state.failed = []
    if (resetApplied) {
        state.applied = [:]
        state.bound = [:]
        state.remove('configuredAt')
    }
    state.queue = ((state.queue ?: []) as List).findAll { Map i -> i.status == 'sent' }    // anything that only waited is replaced
    Map desired = buildDesired()
    Map applied = (state.applied ?: [:]) as Map
    Map bound = (state.bound ?: [:]) as Map
    List<String> what = []
    for (String cl in CLUSTERS) {
        Map spec = CLUSTER_SPEC[cl] as Map
        Map d = desired[cl] as Map
        int base = spec.base as int
        if (full || !bound[cl]) {
            enqueue('bind', cl, '', base, [:])
            what << "bind ${spec.name}".toString()
        }
        Map ap = applied[cl] as Map
        if (full || ap == null || ap.cfg != cfgOf(d)) {
            enqueue('cr', cl, spec.attr as String, base + 1, d)
            what << "reporting ${spec.name} ${cfgOf(d)}${d.off ? ' (off)' : ''}".toString()
        } else if (ap.verified != true) {
            enqueue('rcr', cl, spec.attr as String, 50 + (base / 10 as int), d)
            what << "verify ${spec.name}".toString()
        }
    }
    if (full) queueReads(true)
    int n = ((state.queue ?: []) as List).count { Map i -> i.status == 'queued' } as int
    if (!testing()) logInfo "${reason}: ${n} item${n == 1 ? '' : 's'} queued, sent after the next report from the sensor${what ? ' (' + what.join(', ') + ')' : ''}"
    publishStatus()
}

private void queueReads(boolean basic) {
    enqueue('read', '0001', '0021', 60)
    enqueue('read', '0001', '0020', 61)
    enqueue('read', '0402', '0000', 62)
    enqueue('read', '0405', '0000', 63)
    if (basic) {                                  // identity values that become Device Data, only while missing
        if (!device.getDataValue('zclVersion')) enqueue('read', '0000', '0000', 70)
        if (!device.getDataValue('powerSource')) enqueue('read', '0000', '0007', 71)
        if (!device.getDataValue('swBuildId')) enqueue('read', '0000', '4000', 72)
    }
}

// Queue a Read Reporting Configuration per cluster, compared against the desired settings.
private void queueVerify() {
    long t = now()
    if (!testing() && (t - ((state.lastVerifyQueueAt ?: 0L) as long)) < 900000L) return
    state.lastVerifyQueueAt = t
    Map desired = buildDesired()
    for (String cl in CLUSTERS) {
        Map spec = CLUSTER_SPEC[cl] as Map
        Map want = desired[cl] as Map
        String cfg = ((state.applied ?: [:]) as Map)[cl]?.cfg     // the sensor should still hold what it last acknowledged (e.g. a factory reset)
        if (cfg) {
            List<String> pr = cfg.split('/') as List
            want = [min: pr[0] as int, max: pr[1] as int, delta: pr[2] as int, off: ((pr[1] as int) == REPORTING_OFF_MAX_S)]
        }
        enqueue('rcr', cl, spec.attr as String, 50 + ((spec.base as int) / 10 as int), want)
    }
    if (!testing()) logInfo 'sensor announced itself again with the same network address (battery change?): its settings will be read back'
    publishStatus()
}

private void onAnnounce(Map f) {
    if (f.nwkChanged == true) {
        handleRejoin(f.nwk as String)
    } else if (state.nwk != null) {
        queueVerify()
    }
}

// A new network address means a fresh join: the sensor is back at factory reporting with empty bindings.
private void handleRejoin(String nwk) {
    long t = now()
    if (!testing() && (t - ((state.lastRejoinAt ?: 0L) as long)) < REJOIN_COOLDOWN_MS) {
        logWarn "rejoin to ${nwk} again within 10 minutes: not queueing the full set a second time (the earlier one is still valid)"
        return
    }
    state.lastRejoinAt = t
    if (!testing()) logInfo "rejoin with a new network address ${nwk}: a fresh join resets the sensor to factory reporting, queueing the full set"
    queueConfig('rejoin', true, true)
}

// ---- command windows
private void openSession(String trigger) {
    long t = now()
    Map s = state.session as Map
    if (s != null) {
        s.budgetEnd = t + SEND_BUDGET_MS          // another frame from the sensor: a new window starts, keep going
    } else {
        int sid = ((state.sid ?: 0) as int) + 1
        state.sid = sid
        s = [id: sid, trigger: trigger, openedAt: t, budgetEnd: t + SEND_BUDGET_MS, sent: 0, acked: 0]
        logDebug "S${sid} window opened by ${trigger}, ${((state.queue ?: []) as List).count { Map i -> i.status == 'queued' }} queued"
    }
    state.session = s
    pump()
}

// The sensor just answered: it is demonstrably awake, so keep the window open and send the next item at once.
private void keepWindowOpen() {
    Map s = state.session as Map
    if (s == null) return
    long t = now()
    if (t + ACK_EXTEND_MS > (s.budgetEnd as long)) {
        s.budgetEnd = t + ACK_EXTEND_MS
        state.session = s
    }
    pumpSoon()
}

void pumpTick() { pump() }

private void pumpSoon() {
    if (testing()) { pump(); return }
    Map s = state.session as Map
    long since = s?.lastSendAt ? (now() - (s.lastSendAt as long)) : MIN_SPACING_MS
    runInMillis(Math.max(SETTLE_MS as long, MIN_SPACING_MS - since), 'pumpTick', [overwrite: true])
}

private void pump() {
    Map s = state.session as Map
    if (s == null) return
    List q = (state.queue ?: []) as List
    Map stale = q.find { Map i -> i.status == 'sent' && (now() - ((i.sentAt ?: 0L) as long)) > (ITEM_TIMEOUT_MS + 1000L) } as Map
    if (stale != null) {                                                   // self-heal: a timer was lost (reboot, code update)
        handleTimeout(stale)
        return
    }
    if (q.any { Map i -> i.status == 'sent' }) return                      // one item at a time
    Map nxt = null
    if (now() < Math.min(s.budgetEnd as long, (s.openedAt as long) + MAX_WINDOW_MS)) {
        List waiting = q.findAll { Map i -> i.status == 'queued' }
        waiting.sort { Map a, Map b -> ((a.prio as int) <=> (b.prio as int)) ?: ((a.id as int) <=> (b.id as int)) }
        nxt = waiting ? (waiting[0] as Map) : null
    }
    if (nxt == null) {
        closeSession()
        return
    }
    if (!testing() && !sendAllowed()) {
        closeSession()
        return
    }
    sendItem(nxt, s)
}

// At most MAX_SENDS_PER_HOUR commands per hour, whatever the queue holds.
private boolean sendAllowed() {
    long t = now()
    Map h = (state.sendHour ?: [:]) as Map
    if (!h.start || (t - (h.start as long)) > 3600000L) h = [start: t, n: 0, warned: false]
    if ((h.n as int) >= MAX_SENDS_PER_HOUR) {
        if (h.warned != true) {
            h.warned = true
            logWarn "hourly send limit of ${MAX_SENDS_PER_HOUR} commands reached: the rest of the queue waits (battery protection)"
        }
        state.sendHour = h
        return false
    }
    h.n = (h.n as int) + 1
    state.sendHour = h
    return true
}

private void closeSession() {
    Map s = state.session as Map
    state.session = null
    if (!testing()) {
        unschedule('pumpTick')
        unschedule('itemTimeout')
    }
    if (s != null) {
        logDebug "S${s.id} window closed: ${s.sent} sent, ${s.acked} answered, ${((state.queue ?: []) as List).size()} left"
        if (!testing() && (s.sent as int) > 1) logInfo "queue window: ${s.sent} sent, ${s.acked} answered in ${String.format('%.1f', (now() - (s.openedAt as long)) / 1000.0)} s, ${((state.queue ?: []) as List).size()} left"
    }
    publishStatus()
}

private void sendItem(Map item, Map s) {
    String cmd
    try {
        cmd = buildCmd(item)
    } catch (Exception e) {
        failItem(item, "could not build the command: ${e.message}")
        pump()
        return
    }
    item.status = 'sent'
    item.sentAt = now()
    item.tries = ((item.tries ?: 0) as int) + 1
    s.sent = ((s.sent ?: 0) as int) + 1
    state.queue = state.queue
    state.session = s
    if (testing()) {
        List tx = (state.testTx ?: []) as List
        tx << "${item.kind}|${item.cluster}|${cmd}".toString()
        state.testTx = tx
        return
    }
    s.lastSendAt = item.sentAt
    state.session = s
    logDebug "TX S${s.id} +${fmtDt(now() - (s.openedAt as long))} item ${item.id} ${item.kind} ${item.cluster}/${item.attr} try ${item.tries}: ${cmd}"
    runInMillis(ITEM_TIMEOUT_MS, 'itemTimeout', [overwrite: true, data: [id: item.id]])      // timer first: nothing can leave an item 'sent' without one
    try {
        sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZIGBEE))
    } catch (Exception e) {
        logWarn "send failed for item ${item.id}: ${e.message}"
        unschedule('itemTimeout')
        item.status = 'queued'
        item.tries = Math.max(0, ((item.tries ?: 1) as int) - 1)
        state.queue = state.queue
        s.budgetEnd = now()
        state.session = s
        pump()
    }
}

// ---- command builders (library builders, 'delay' entries removed, one string per send; same forms as the proven Probe)
private String buildCmd(Map item) {
    int cl = Integer.parseInt(item.cluster as String, 16)
    String zid = device.zigbeeId
    if (!zid) {
        if (testing()) zid = '0000000000000000'
        else throw new IllegalStateException('device.zigbeeId not available')
    }
    if (item.kind == 'bind') {
        return "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x${item.cluster} {${zid}} {}".toString()
    }
    int at = Integer.parseInt(item.attr as String, 16)
    Map p = (item.p ?: [:]) as Map
    if (item.kind == 'cr') {
        Map spec = CLUSTER_SPEC[item.cluster] as Map
        List<String> crs = zigbee.configureReporting(cl, at, (spec.type as int), (p.min as int), (p.max as int), (p.delta as int), [destEndpoint: 0x01], 0)
        String c = crs.find { String x -> x.startsWith('he cr ') }
        if (!c) throw new IllegalStateException("no 'he cr' string in builder output: ${crs}")
        return c.toString()
    }
    List<String> cmds = (item.kind == 'read') ? zigbee.readAttribute(cl, at, [destEndpoint: 0x01], 0)
                                              : zigbee.reportingConfiguration(cl, at, [destEndpoint: 0x01], 0)
    List<String> real = cmds.findAll { String x -> !x.startsWith('delay') }
    String out = real ? real[0].toString() : ''
    String frameStart = (item.kind == 'read') ? '{10 00 00 ' : '{10 00 08 '
    if (!out.startsWith('he raw ') || !out.contains(frameStart)) throw new IllegalStateException("unexpected command form: ${out}")
    return out
}

// ---- replies
void itemTimeout(Map data) {
    Map item = ((state.queue ?: []) as List).find { Map i -> i.id == data?.id && i.status == 'sent' } as Map
    if (item != null) handleTimeout(item)
}

private void handleTimeout(Map item) {
    item.lastStatus = 'no answer'
    if (((item.tries ?: 0) as int) >= MAX_ATTEMPTS) {
        failItem(item, "no answer from the sensor after ${item.tries} tries")
    } else {
        item.status = 'queued'
        state.queue = state.queue
        logDebug "no answer for item ${item.id} ${item.kind} ${item.cluster}/${item.attr} (try ${item.tries}), will retry in the next window"
    }
    Map s = state.session as Map
    if (s != null) {                               // an unanswered command means the window is over
        s.budgetEnd = now()
        state.session = s
    }
    pump()
}

// Match one received frame against the sent / previously sent items. Returns true when it completed or failed an item.
private boolean ackFrame(Map f) {
    List q = (state.queue ?: []) as List
    List cand = q.findAll { Map i -> i.status == 'sent' || ((i.tries ?: 0) as int) > 0 }
    cand.sort { Map a, Map b -> ((a.status == 'sent' ? 0 : 1) <=> (b.status == 'sent' ? 0 : 1)) }
    Map hit = null
    switch (f.kind as String) {
        case 'BIND_RSP':
            hit = cand.find { Map i -> i.kind == 'bind' } as Map
            break
        case 'CR_RSP':
            hit = cand.find { Map i -> i.kind == 'cr' && i.cluster == f.cluster } as Map
            break
        case 'RCR_RSP':
            hit = cand.find { Map i -> i.kind == 'rcr' && i.cluster == f.cluster } as Map
            break
        case 'RD_RSP':
        case 'RD_OTHER':
        case 'REPORT':
            hit = cand.find { Map i -> i.kind == 'read' && i.cluster == f.cluster && hex4(i.attr) == f.attr } as Map
            break
        case 'DEFAULT_RSP':
            String cmdId = (f.data && f.data.size() > 0) ? f.data[0].toString().toUpperCase() : ''
            hit = cand.find { Map i -> i.kind != 'bind' && i.cluster == f.cluster && CMD_ID[i.kind] == cmdId } as Map
            if (hit != null && f.ok == true) hit = null          // a SUCCESS default response says nothing more than the real reply will
            if (hit != null) {
                cancelItemTimer(hit)
                failItem(hit, "sensor refused the command: ${f.decoded}")
                return true
            }
            break
    }
    if (hit == null) return false
    finishItem(hit, f)
    return true
}

private void cancelItemTimer(Map item) {
    if (item == null || item.status != 'sent') return          // a late reply for another item must not disarm the timer of the item in flight
    if (testing()) {
        state.testCancels = ((state.testCancels ?: 0) as int) + 1
        return
    }
    unschedule('itemTimeout')
}

private void ackItem(Map item) {
    state.queue = ((state.queue ?: []) as List).findAll { Map i -> i.id != item.id }
    Map s = state.session as Map
    if (s != null) {
        s.acked = ((s.acked ?: 0) as int) + 1
        state.session = s
    }
}

private void failItem(Map item, String reason) {
    state.queue = ((state.queue ?: []) as List).findAll { Map i -> i.id != item.id }
    List fl = (state.failed ?: []) as List
    fl << [id: item.id, kind: item.kind, cluster: item.cluster, attr: item.attr, reason: reason]
    if (fl.size() > 10) fl = fl.subList(fl.size() - 10, fl.size())
    state.failed = fl
    logWarn "item ${item.id} ${item.kind} ${item.cluster}/${item.attr} FAILED: ${reason}"
}

private void finishItem(Map item, Map f) {
    cancelItemTimer(item)
    String cl = item.cluster as String
    Map spec = (CLUSTER_SPEC[cl] ?: [:]) as Map
    switch (item.kind as String) {
        case 'bind':
            if (f.ok == true) {
                Map b = (state.bound ?: [:]) as Map
                b[cl] = true
                state.bound = b
                ackItem(item)
            } else if (((item.tries ?: 0) as int) >= 2) {
                failItem(item, "bind refused: ${f.decoded}")
            } else {
                item.status = 'queued'
                state.queue = state.queue
            }
            break
        case 'cr':
            if (f.ok == true) {
                Map ap = (state.applied ?: [:]) as Map
                ap[cl] = [cfg: cfgOf(item.p as Map), verified: false, at: now()]
                state.applied = ap
                ackItem(item)
                enqueue('rcr', cl, item.attr as String, 50 + (((spec.base ?: 10) as int) / 10 as int), item.p as Map)     // read back what the sensor now holds
            } else {
                failItem(item, "configure reporting refused: ${f.decoded}")
            }
            break
        case 'rcr':
            ackItem(item)
            verifyResult(item, f)
            break
        default:               // read: any answer (even UNSUPPORTED) completes it
            ackItem(item)
            break
    }
}

// Compare the sensor's Read Reporting Configuration answer with what we asked for.
private void verifyResult(Map item, Map f) {
    String cl = item.cluster as String
    Map spec = (CLUSTER_SPEC[cl] ?: [:]) as Map
    Map exp = (item.p ?: [:]) as Map
    Map e = ((f.rcr ?: []) as List).find { Map r -> r.attr == hex4(item.attr) } as Map
    boolean match
    if (e == null) {
        match = false
    } else if (exp.off == true) {
        match = (e.status == '8B')                     // no entry = reporting off
    } else {
        match = (e.status == '00' && (e.min as int) == (exp.min as int) && (e.max as int) == (exp.max as int) && (e.chg as long) == (exp.delta as long))
    }
    Map ap = (state.applied ?: [:]) as Map
    if (match) {
        ap[cl] = [cfg: cfgOf(exp), verified: true, at: now()]
        state.applied = ap
        Map vt0 = (state.verifyTries ?: [:]) as Map
        vt0.remove(cl)
        state.verifyTries = vt0
        if (!testing()) logInfo "${spec.name} reporting verified by read-back: ${cfgOf(exp)}${exp.off == true ? ' (off)' : ''}"
        if (CLUSTERS.every { String c -> (ap[c] as Map)?.verified == true }) state.configuredAt = now()
    } else {
        Map vt = (state.verifyTries ?: [:]) as Map
        int tries = ((vt[cl] ?: 0) as int)
        logWarn "${spec.name} read-back differs: sensor says ${f.decoded}; wanted ${cfgOf(exp)}"
        ap.remove(cl)
        state.applied = ap
        state.remove('configuredAt')
        if (tries < MAX_VERIFY_TRIES) {
            vt[cl] = tries + 1
            state.verifyTries = vt
            enqueue('cr', cl, item.attr as String, ((spec.base ?: 10) as int) + 1, exp)
        } else {
            List fl = (state.failed ?: []) as List
            fl << [id: item.id, kind: 'verify', cluster: cl, attr: item.attr, reason: "${spec.name} settings do not stick (the sensor holds other values, see the log)"]
            state.failed = fl
            logWarn "${spec.name}: the sensor keeps different reporting settings after ${MAX_VERIFY_TRIES} tries, giving up"
        }
    }
}

// ---- status text (configStatus is derived from the queue, never a separate truth)
private void publishStatus() {
    List q = (state.queue ?: []) as List
    List fl = (state.failed ?: []) as List
    Map ap = (state.applied ?: [:]) as Map
    int n = q.size()
    String text
    if (fl) {
        text = "Failed: ${(fl.last() as Map).reason}. Use Configure to try again."
    } else if (n > 0) {
        Map s = state.session as Map
        if (s != null && now() > (s.budgetEnd as long) + ITEM_TIMEOUT_MS) s = null           // stale window
        text = s ? "Configuring: ${s.acked} done, ${n} left" : "Pending: ${n} item${n == 1 ? '' : 's'}, sent after the next report from the sensor"
    } else if (CLUSTERS.every { String c -> (ap[c] as Map)?.verified == true }) {
        long at = ((state.configuredAt ?: now()) as long)
        text = "Configured at ${new Date(at).format('yyyy-MM-dd HH:mm', location.timeZone)}"
    } else if (ap) {
        text = 'Applied, not yet verified'
    } else {
        text = 'Not configured yet'
    }
    setConfigStatus(text)
}

// ------------------------------------------------------------------ engine tests (run by selfTest, sandboxed)

@Field static final List ENGINE_KEYS = ['queue', 'applied', 'bound', 'failed', 'session', 'gen', 'sid', 'nextId', 'verifyTries',
                                         'nwk', 'configuredAt', 'lastVerifyQueueAt', 'lastRejoinAt', 'needsConfig', 'sendHour', 'testTx', 'testPrefs', 'testCancels']

private Map snapshotEngine() {
    Map snap = [:]
    for (String k in ENGINE_KEYS) {
        Object v = state[k]
        snap[k] = (v == null) ? null : deepCopy(v)
    }
    return snap
}

private Object deepCopy(Object v) {
    if (v instanceof Map) {
        Map m = [:]
        ((Map) v).each { Object k, Object x -> m[k] = deepCopy(x) }
        return m
    }
    if (v instanceof List) return ((List) v).collect { Object x -> deepCopy(x) }
    return v
}

private void restoreEngine(Map snap) {
    for (String k in ENGINE_KEYS) {
        if (snap[k] == null) state.remove(k) else state[k] = snap[k]
    }
}

private Map runEngineTests() {
    Map snap = snapshotEngine()
    int pass = 0
    int total = 0
    List<String> fails = []
    Closure check = { String name, boolean ok, String detail ->
        total++
        if (ok) pass++ else fails << "FAIL engine ${name}: ${detail}".toString()
    }
    String hdr = 'catchall: 0104 @CL@ 01 01 0040 00 A264 00 00 0000 @CMD@ 01 '
    Closure frame = { String cl, String cmd, String data -> hdr.replace('@CL@', cl).replace('@CMD@', cmd) + data }
    String REPORT_T = 'read attr - raw: A2640104020A000029A709, dni: A264, endpoint: 01, cluster: 0402, size: 0A, attrId: 0000, encoding: 29, command: 0A, value: A709'
    String BIND_OK = 'catchall: 0000 8021 00 00 0040 00 A264 00 00 0000 00 00 9700'
    String ANNCE = 'catchall: 0000 0013 00 00 0040 00 085F 00 00 0000 00 00 815F08010000FEFFCCBBAA80'
    Closure feed = { String d -> onFrame(decodeFrame(d)) }
    Closure reset = { Map prefs ->
        state.queue = []
        state.applied = [:]
        state.bound = [:]
        state.failed = []
        state.session = null
        state.gen = 0
        state.sid = 0
        state.nextId = 1
        state.verifyTries = [:]
        state.testTx = []
        state.testPrefs = new LinkedHashMap(DEFAULTS) + (prefs ?: [:])
        state.nwk = '085F'
        state.remove('configuredAt')
        state.remove('lastVerifyQueueAt')
        state.remove('lastRejoinAt')
        state.remove('needsConfig')
        state.testCancels = 0
    }
    Closure txs = { return ((state.testTx ?: []) as List) }
    Closure queue = { return ((state.queue ?: []) as List) }
    try {
        // E1 full set is ordered bind/CR per cluster, then reads
        reset(null)
        queueConfig('test', true, true)
        List sorted = queue().sort(false) { Map a, Map b -> (a.prio as int) <=> (b.prio as int) }
        String order = sorted.take(6).collect { Map i -> "${i.kind}:${i.cluster}".toString() }.join(',')
        check('E1 full set order', order == 'bind:0402,cr:0402,bind:0405,cr:0405,bind:0001,cr:0001', order)
        check('E1 reads queued', sorted.count { Map i -> i.kind == 'read' } >= 4, "${sorted.size()} items")

        // E2 a report opens a window and releases exactly one item
        feed(REPORT_T)
        check('E2 one item sent', txs().size() == 1, "${txs().size()} sent")
        check('E2 first is the temperature bind', txs() && txs()[0].toString().startsWith('bind|0402|zdo bind '), txs().toString())
        feed(REPORT_T)
        check('E2 no second send while one is outstanding', txs().size() == 1, "${txs().size()} sent")

        // E3 bind reply completes it and releases the CR
        feed(BIND_OK)
        check('E3 bound recorded', ((state.bound ?: [:]) as Map)['0402'] == true, state.bound?.toString())
        check('E3 CR follows', txs().size() == 2 && txs()[1].toString().startsWith('cr|0402|'), txs().toString())

        // E4 CR reply records the applied config, queues the read-back, releases the next bind
        feed(frame('0402', '07', '00'))
        Map ap = (((state.applied ?: [:]) as Map)['0402'] ?: [:]) as Map
        check('E4 applied recorded unverified', ap.cfg == '60/900/10' && ap.verified == false, ap.toString())
        check('E4 read-back queued', queue().any { Map i -> i.kind == 'rcr' && i.cluster == '0402' }, queue().toString())
        check('E4 next is humidity bind', txs().size() == 3 && txs()[2].toString().startsWith('bind|0405|'), txs().toString())

        // E5 read-back match verifies
        reset(null)
        queueVerify()
        feed(REPORT_T)
        check('E5 verify starts with rcr 0402', txs().size() == 1 && txs()[0].toString().startsWith('rcr|0402|'), txs().toString())
        feed(frame('0402', '09', '00000000293C0084030A00'))
        ap = (((state.applied ?: [:]) as Map)['0402'] ?: [:]) as Map
        check('E5 verified', ap.verified == true && ap.cfg == '60/900/10', ap.toString())
        check('E5 next rcr 0405', txs().size() == 2 && txs()[1].toString().startsWith('rcr|0405|'), txs().toString())

        // E6 read-back mismatch re-queues the CR (bounded)
        reset(null)
        queueVerify()
        feed(REPORT_T)
        feed(frame('0402', '09', '00000000293C0058020A00'))
        check('E6 CR re-queued', queue().any { Map i -> i.kind == 'cr' && i.cluster == '0402' }, queue().toString())
        check('E6 try counted', ((state.verifyTries ?: [:]) as Map)['0402'] == 1, state.verifyTries?.toString())
        check('E6 not applied', ((state.applied ?: [:]) as Map)['0402'] == null, state.applied?.toString())

        // E7 humidity off: max 0xFFFF; NOT_FOUND on read-back means verified
        reset([humEvery: 'off'])
        Map d = buildDesired()
        check('E7 off desired', (d['0405'] as Map).max == 65535 && (d['0405'] as Map).off == true, d['0405'].toString())
        queueVerify()
        feed(REPORT_T)
        feed(frame('0402', '09', '00000000293C0084030A00'))
        feed(frame('0405', '09', '8B000000'))
        ap = (((state.applied ?: [:]) as Map)['0405'] ?: [:]) as Map
        check('E7 off verified by NOT_FOUND', ap.verified == true, ap.toString())

        // E8 clamps
        reset([tempMin: 1, tempEvery: '99999', tempDelta: 0.001, humMin: 5, humEvery: '60', humDelta: 99, battMin: 5, battEvery: '60'])
        d = buildDesired()
        check('E8 temperature clamp', cfgOf(d['0402'] as Map) == '30/64800/5', cfgOf(d['0402'] as Map))
        check('E8 humidity clamp', cfgOf(d['0405'] as Map) == '30/60/2000', cfgOf(d['0405'] as Map))
        check('E8 battery clamp', cfgOf(d['0001'] as Map) == '300/300/1', cfgOf(d['0001'] as Map))
        reset([humMin: 1200, humEvery: '300'])
        d = buildDesired()
        check('E8b max never below min', cfgOf(d['0405'] as Map) == '1200/1200/200', cfgOf(d['0405'] as Map))
        reset([tempEvery: 'off', battEvery: '64800'])
        d = buildDesired()
        check('E8c off for temperature, 18 h for battery', (d['0402'] as Map).off == true && (d['0402'] as Map).max == 65535 && cfgOf(d['0001'] as Map) == '300/64800/1', "${d['0402']} ${d['0001']}")

        // E9 no answer: bounded retries, then FAILED with a reason
        reset(null)
        queueConfig('test', true, true)
        for (int n = 0; n < MAX_ATTEMPTS; n++) {
            feed(REPORT_T)
            Map sent = queue().find { Map i -> i.status == 'sent' } as Map
            if (sent != null) handleTimeout(sent)
        }
        List fl = (state.failed ?: []) as List
        check('E9 given up after MAX_ATTEMPTS', fl.size() == 1 && fl[0].reason.toString().contains('no answer'), fl.toString())
        check('E9 window closed', state.session == null, state.session?.toString())

        // E10 a new save replaces what only waited
        reset(null)
        queueConfig('test', true, true)
        queueConfig('test2', true, false)
        List keys = queue().collect { Map i -> i.key }
        check('E10 no duplicate keys', keys.size() == keys.unique(false).size() && queue().every { Map i -> i.gen == 2 }, keys.toString())

        // E11 announce with a new NWK: full set, applied reset
        reset(null)
        state.nwk = 'AAAA'
        state.applied = ['0402': [cfg: '1/2/3', verified: true]]
        feed(ANNCE)
        check('E11 rejoin queues binds', queue().count { Map i -> i.kind == 'bind' } == 3, queue().toString())
        check('E11 applied reset', (state.applied ?: [:]).isEmpty(), state.applied?.toString())

        // E12 announce with the same NWK: read-back only
        reset(null)
        feed(ANNCE)
        check('E12 verify only', queue().count { Map i -> i.kind == 'rcr' } == 3 && queue().count { Map i -> i.kind == 'bind' } == 0, queue().toString())

        // E13 a refusing Default Response fails the item at once and the next one goes out
        reset(null)
        queueConfig('test', true, true)
        feed(REPORT_T)
        feed(BIND_OK)
        feed(frame('0402', '0B', '0681'))
        fl = (state.failed ?: []) as List
        check('E13 refused CR failed', fl.size() == 1 && fl[0].kind == 'cr', fl.toString())
        check('E13 next item released', txs().size() == 3 && txs()[2].toString().startsWith('bind|0405|'), txs().toString())

        // E14 a late reply for another item must not disarm the timer of the item in flight
        reset(null)
        queueConfig('test', true, true)
        feed(REPORT_T)                                              // bind 0402 is now 'sent'
        Map late = enqueue('read', '0405', '0000', 5)               // a read that was sent earlier and re-queued
        late.tries = 1
        feed('read attr - raw: A2640104050A0000216123, dni: A264, endpoint: 01, cluster: 0405, size: 0A, attrId: 0000, encoding: 21, command: 0A, value: 6123')
        check('E14 foreign ack leaves the timer alone', ((state.testCancels ?: 0) as int) == 0 && queue().any { Map i -> i.kind == 'bind' && i.status == 'sent' }, "cancels=${state.testCancels}")
        check('E14 the late read was credited', !queue().any { Map i -> i.kind == 'read' && i.cluster == '0405' && i.attr == '0000' }, queue().toString())

        // E15 a lost timer cannot leave an item stuck: the next frame sweeps it
        reset(null)
        queueConfig('test', true, true)
        feed(REPORT_T)
        Map stuck = queue().find { Map i -> i.status == 'sent' } as Map
        stuck.sentAt = now() - 20000L
        feed(REPORT_T)
        check('E15 stale item released', stuck.status == 'queued' && ((stuck.tries ?: 0) as int) == 1, stuck.toString())

        // E16 factory reset queues Configure Reporting with the raw factory values, no binds, clamps bypassed
        reset(null)
        state.testPrefs = new LinkedHashMap(DEFAULTS) + [tempDelta: 0.5]
        resetReportingToFactory()
        List fq = queue().findAll { Map i -> i.kind == 'cr' }
        check('E16 three CRs, no binds', fq.size() == 3 && !queue().any { Map i -> i.kind == 'bind' }, queue().toString())
        check('E16 factory values', fq.every { Map i -> (i.cluster == '0001') ? cfgOf(i.p as Map) == '300/1200/1' : cfgOf(i.p as Map) == '60/900/1' }, fq.toString())
        feed(REPORT_T)
        check('E16 first send is the temperature CR', txs().size() == 1 && txs()[0].toString().startsWith('cr|0402|'), txs().toString())

        // E17 an announce after a factory reset reads back against what the sensor was given, not against the preferences
        reset(null)
        state.applied = ['0402': [cfg: '60/900/1', verified: true], '0405': [cfg: '60/900/1', verified: true], '0001': [cfg: '300/1200/1', verified: true]]
        queueVerify()
        Map rq = queue().find { Map i -> i.kind == 'rcr' && i.cluster == '0402' } as Map
        check('E17 verify expects the applied values', rq != null && cfgOf(rq.p as Map) == '60/900/1', rq?.toString())
    } catch (Exception e) {
        total++
        fails << "FAIL engine exception ${e.class.simpleName}: ${e.message}".toString()
    } finally {
        restoreEngine(snap)
    }
    return [pass: pass, total: total, failures: fails]
}

// ------------------------------------------------------------------ state schema

private void initState() {
    if (state.drv != 'ref-q2') {
        // First run, or the device was switched from another driver (e.g. the Probe): drop its jobs and state.
        unschedule()
        state.clear()
        state.drv     = 'ref-q2'
        state.needsConfig = true     // first frame after a driver swap / first run: queue the configuration (cleared by queueConfig)
        state.queue   = []       // items: id, gen, key, kind(bind|cr|rcr|read), cluster, attr, prio, status(queued|sent), tries, p, sentAt
        state.nextId  = 1
        state.gen     = 0        // bumped by every queueConfig(): newer generation replaces everything that was only waiting
        state.sid     = 0        // command-window counter (S1, S2, ...)
        state.session = null     // open command window: id, trigger, openedAt, budgetEnd, sent, acked
        state.applied = [:]      // per cluster: cfg "min/max/delta" the sensor acknowledged, verified (read back), at
        state.bound   = [:]      // per cluster: true once Bind_rsp was SUCCESS
        state.failed  = []       // items given up: reason shown in configStatus
        state.verifyTries = [:]
        state.nwk     = device.deviceNetworkId
        state.lastCheckinAt = 0L
        state.health  = 'unknown'
    }
    if (state.healthSched != DRIVER_VERSION) {      // also covers installs that predate the health check
        scheduleHealth()
        state.healthSched = DRIVER_VERSION
    }
}


// ------------------------------------------------------------------ Initialize: remove everything this driver does not use
@Field static final List USED_ATTRIBUTES = ['temperature', 'humidity', 'battery', 'batteryVoltage', 'configStatus', 'healthStatus', 'lastCheckin', 'batteryLastReplaced']
@Field static final List USED_STATE = ['drv', 'needsConfig', 'queue', 'nextId', 'gen', 'sid', 'session', 'applied', 'bound', 'failed', 'verifyTries', 'nwk', 'lastCheckinAt', 'health', 'healthSched', 'lastParseWarnAt', 'lastRejoinAt', 'lastRxAt', 'lastVerifyQueueAt', 'sendHour', 'configuredAt', 'replayMode']
@Field static final List USED_DATA = ['manufacturer', 'model', 'application', 'endpointId', 'ieee', 'swBuildId', 'zclVersion', 'powerSource', 'batteryType', 'inClusters', 'outClusters']

// Removes Current States, State Variables, Device Data and scheduled jobs that this driver does not use (left over from other
// drivers or older versions). The work queue and everything the driver relies on are kept. Safe to run at any time.
void initialize() {
    initState()
    List gone = []
    try {
        for (String n in ((device.getCurrentStates() ?: []).collect { it.name as String }).unique()) {
            if (!(n in USED_ATTRIBUTES)) { device.deleteCurrentState(n); gone << "attribute ${n}".toString() }
        }
    } catch (Exception e) { logWarn "initialize: could not clean Current States: ${e.message}" }
    try {
        for (String k in new ArrayList(state.keySet() as Collection)) {
            if (!(k in USED_STATE)) { state.remove(k); gone << "state ${k}".toString() }
        }
    } catch (Exception e) { logWarn "initialize: could not clean State Variables: ${e.message}" }
    try {
        for (String k in new ArrayList(((device.getData() ?: [:]) as Map).keySet())) {
            if (!(k in USED_DATA)) { device.removeDataValue(k); gone << "data ${k}".toString() }
        }
    } catch (Exception e) { logWarn "initialize: could not clean Device Data: ${e.message}" }
    normalizePowerSource()
    unschedule()
    Map inFlight = ((state.queue ?: []) as List).find { Map i -> i.status == 'sent' } as Map      // keep the queue's own timers alive
    if (inFlight != null) runInMillis(ITEM_TIMEOUT_MS, 'itemTimeout', [overwrite: true, data: [id: inFlight.id]])
    else if (state.session) pumpSoon()
    state.healthSched = DRIVER_VERSION
    scheduleHealth()
    if (pref('logEnable') || pref('traceEnable')) runIn(DEBUG_AUTO_OFF_SECONDS, 'debugOff')
    logInfo "initialize: removed ${gone ? gone.join(', ') : 'nothing'}; scheduled jobs reset"
}

// 0x03 = Battery. Shown as text in Device Data; an already-decoded text is left alone.
private String powerSourceText(String raw) {
    String r = raw?.trim()
    if (!r || !(r ==~ /(?i)[0-9a-f]{1,2}/)) return raw
    int v = Integer.parseInt(r, 16)
    String base = POWER_SOURCES[v & 0x7F] ?: "Unknown (0x${r.toUpperCase()})".toString()
    return ((v & 0x80) != 0) ? "${base} (battery backup)".toString() : base
}

// Earlier versions stored the raw code (e.g. "03"): convert it.
private void normalizePowerSource() {
    String p = device.getDataValue('powerSource')
    if (p != null) {
        String t = powerSourceText(p)
        if (t != p) device.updateDataValue('powerSource', t)
    }
}

// ------------------------------------------------------------------ Device Data (standard, see spec)

private void writeDeviceData() {
    device.updateDataValue('batteryType', BATTERY_TYPE)
    cleanLegacyData()
    normalizePowerSource()
    // ieee, swBuildId, zclVersion and powerSource are written from announce / Basic read responses.
    // manufacturer, model, application and endpointId belong to the platform: never written here.
}

private void cleanLegacyData() {
    for (String k in ['mfrName', 'modelId', 'appVersion']) {
        if (device.getDataValue(k) != null) {
            device.removeDataValue(k)
            logDebug "removed legacy Device Data key ${k}"
        }
    }
}

// ------------------------------------------------------------------ helpers

// settings are empty until the first Save, so every preference read goes through here.
private Object pref(String k) { settings?.get(k) != null ? settings.get(k) : DEFAULTS[k] }

private BigDecimal toDec(Object v) {
    try { return (v == null) ? 0G : new BigDecimal(v.toString()) } catch (Exception e) { return 0G }
}

private int clampInt(Object v, int lo, int hi, int dflt) {
    int x
    try { x = (v == null) ? dflt : (v as BigDecimal).intValue() } catch (Exception e) { x = dflt }
    return Math.max(lo, Math.min(hi, x))
}

private int signed16(String hex) {
    int v = Integer.parseInt(hex, 16)
    return v > 32767 ? v - 65536 : v
}

private String hex4(def v) {
    String s = (v ?: '').toString().toUpperCase().replace('0X', '')
    while (s.length() < 4) s = '0' + s
    return s
}

private String fmtDt(long ms) {
    return ms >= 60000L ? "${(ms / 1000L).longValue()}s" : String.format('%.3fs', ms / 1000.0d)
}

private void setConfigStatus(String s) {
    if (testing()) return
    if (device.currentValue('configStatus') != s) sendEvent(name: 'configStatus', value: s)
}

private boolean traceOn() { return pref('traceEnable') as boolean }

private void logInfo(String m)  { log.info  "${device.displayName}: ${m}" }
private void logDebug(String m) { if (pref('logEnable')) log.debug "${device.displayName}: ${m}" }
private void logWarn(String m)  { if (!testing()) log.warn "${device.displayName}: ${m}" }   // quiet while the self test runs
