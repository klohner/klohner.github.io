/**
 *  Shelly BLU H&T ZB (SBHT-203C) - Simple driver
 *
 *  Temperature, humidity and battery for the Shelly BLU H&T ZB (battery-powered Zigbee sleepy end device).
 *  Documentation, protocol notes and the Reference driver: https://github.com/klohner/klohner.github.io/tree/master/Hubitat/Shelly_BLU_H%26T_ZB
 *
 *  This driver takes the sensor as it is configured: there are no reporting preferences (only temperature / humidity offsets and
 *  debug logging) and a healthStatus attribute (online / offline). The sensor's reporting settings are read back and shown as State Variables (temperatureReporting,
 *  humidityReporting, batteryReporting).
 *    - A new or re-joined sensor is set once to the driver defaults: the factory intervals with calmer change thresholds
 *      (temperature and humidity at least every 15 minutes, 0.1 C / 2 %RH; battery every 20 minutes).
 *    - Commands "Reset Reporting To Factory" (0.01 change) and "Reset Reporting To Recommended" (temperature and humidity
 *      1 hour, 0.1 C / 2 %RH; battery 12 hours, 2 %) put the sensor on either preset.
 *    - For custom settings switch the device to the "Shelly BLU H&T ZB Reference" driver, set them there, then switch back.
 *
 *  PAIRING: Add Device > Zigbee > "Pair using strict Zigbee 3.0 mode" (normal pairing makes the sensor leave again on a C-8),
 *  then press the button 5 times quickly (LED flashes twice every 2 s for about a minute).
 *
 *  SLEEPY DEVICE: the sensor accepts commands only for a few seconds after it has sent something (a report or a rejoin
 *  announcement). Everything this driver sends to it (bindings, reads, reporting settings) is queued and goes out as a burst in
 *  the window after its next report, so results appear after the sensor's next report (normally within 15 minutes).
 *
 *  Licensed under the Apache License, Version 2.0.
 */

import groovy.transform.Field
import java.math.RoundingMode

@Field static final String DRIVER_VERSION = '1.4.1'
@Field static final String BATTERY_TYPE = 'CR2032'
@Field static final int  DEBUG_AUTO_OFF_SECONDS = 1800

// Queue engine timing (measured on the sensor: every frame it sends opens a window >= 6.5 s, ~1 s per command, one at a time).
@Field static final int SEND_BUDGET_MS   = 4500   // first release window after a frame the sensor started (it is awake for >= 6.5 s)
@Field static final int ACK_EXTEND_MS    = 2000   // every answer proves the sensor is awake: the window stays open this long after it
@Field static final int MAX_WINDOW_MS    = 30000  // safety cap for one window, however long the sensor keeps answering
@Field static final int MIN_SPACING_MS   = 250    // next item goes out right after the answer (the answer itself takes 0.3-1 s)
@Field static final int MAX_SENDS_PER_HOUR = 60
@Field static final long REJOIN_COOLDOWN_MS = 600000L
@Field static final int ITEM_TIMEOUT_MS  = 2500   // no answer by then: pause the queue until the sensor's next report
@Field static final int SETTLE_MS        = 250
@Field static final int MAX_ATTEMPTS     = 5
@Field static final int QUEUE_MAX        = 30
@Field static final List CLUSTERS = ['0402', '0405', '0001']
@Field static final Map CLUSTER_SPEC = Collections.unmodifiableMap([
    '0402': [attr: '0000', type: 0x29, name: 'temperature', base: 10, key: 'temperatureReporting'],
    '0405': [attr: '0000', type: 0x21, name: 'humidity',    base: 20, key: 'humidityReporting'],
    '0001': [attr: '0021', type: 0x20, name: 'battery',     base: 30, key: 'batteryReporting']])
// Applied once on a fresh pair / rejoin (a new join resets the sensor to factory reporting): factory intervals with calmer change thresholds, in raw units.
@Field static final Map DEFAULT_REPORTING = Collections.unmodifiableMap([
    '0402': [min: 60, max: 900, delta: 10], '0405': [min: 60, max: 900, delta: 200], '0001': [min: 300, max: 1200, delta: 1]])
// factory reporting of the sensor (raw units: 0.01 degC, 0.01 %RH, 0.5 % battery)
@Field static final Map FACTORY = Collections.unmodifiableMap([
    '0402': [min: 60, max: 900, delta: 1], '0405': [min: 60, max: 900, delta: 1], '0001': [min: 300, max: 1200, delta: 1]])
// recommended reporting (raw units): temperature 1 min / 1 hour / 0.1 C, humidity 1 min / 1 hour / 2 %RH, battery 5 min / 12 hours / 2 %
@Field static final Map RECOMMENDED = Collections.unmodifiableMap([
    '0402': [min: 60, max: 3600, delta: 10], '0405': [min: 60, max: 3600, delta: 200], '0001': [min: 300, max: 43200, delta: 4]])
@Field static final Map CMD_ID = Collections.unmodifiableMap(['cr': '06', 'read': '00', 'rcr': '08'])

@Field static final Map ZCL_STATUS = Collections.unmodifiableMap([
    '00': 'SUCCESS', '01': 'FAILURE', '7E': 'NOT_AUTHORIZED', '7F': 'RESERVED_FIELD_NOT_ZERO', '80': 'MALFORMED_COMMAND',
    '81': 'UNSUP_CLUSTER_COMMAND', '82': 'UNSUP_GENERAL_COMMAND', '83': 'UNSUP_MANUF_CLUSTER_COMMAND',
    '84': 'UNSUP_MANUF_GENERAL_COMMAND', '85': 'INVALID_FIELD', '86': 'UNSUPPORTED_ATTRIBUTE', '87': 'INVALID_VALUE',
    '88': 'READ_ONLY', '89': 'INSUFFICIENT_SPACE', '8A': 'DUPLICATE_EXISTS', '8B': 'NOT_FOUND', '8C': 'UNREPORTABLE_ATTRIBUTE',
    '8D': 'INVALID_DATA_TYPE', '8E': 'INVALID_SELECTOR', '8F': 'WRITE_ONLY', 'C3': 'UNSUPPORTED_CLUSTER'])

// Basic cluster attributes that become Device Data (manufacturer / model / application belong to the platform).
// ZCL Basic cluster PowerSource (attribute 0x0007): bits 0-6 = source, bit 7 = battery backup available.
@Field static final Map POWER_SOURCES = Collections.unmodifiableMap([0: 'Unknown', 1: 'Mains (single phase)', 2: 'Mains (3 phase)', 3: 'Battery',
    4: 'DC source', 5: 'Emergency mains (constantly powered)', 6: 'Emergency mains (with transfer switch)'])
@Field static final Map BASIC_DATA = Collections.unmodifiableMap(['0000': 'zclVersion', '0007': 'powerSource', '4000': 'swBuildId'])

// Explanation of the Commands, shown on the Preferences page.
@Field static final String HELP_HTML = '<b>What the Commands do</b><br><span style="font-weight:normal">' +
  '<b>Reporting</b>: this driver does not change the sensor\'s reporting. It reads what the sensor holds and shows it in the State Variables temperatureReporting, humidityReporting and batteryReporting. A new or re-paired sensor is set once to the defaults: temperature and humidity at least every 15 minutes (0.1 \u00b0C / 2 %RH change), battery at least every 20 minutes (the sensor\'s factory intervals). For other settings, switch the device to the Reference driver, set them there and switch back.<br>' +
  '<b>Offline detection</b>: healthStatus shows online / offline. The sensor is marked offline when it has sent nothing for three times its temperature maximum interval (at least 10 minutes), for example with the battery out or out of range. It is online again with the next report.<br>' +
  '<b>Configure</b>: binds the sensor to the hub and reads back its reporting settings (does not change them). Use after pairing or when something looks wrong.<br>' +
  '<b>Refresh</b>: reads temperature, humidity, battery and the reporting settings now. The sensor sleeps, so it is answered after its next report.<br>' +
  '<b>Battery Replaced</b>: records today as the battery change date (batteryLastReplaced).<br>' +
  '<b>Initialize</b>: removes Current States, State Variables, Device Data and scheduled jobs that this driver does not use (leftovers from other drivers or older versions). The work queue is kept.<br>' +
  '<b>Reset Reporting To Factory</b>: queues the sensor\'s factory reporting: temperature and humidity 1 minute to 15 minutes or 0.01 change, battery 5 to 20 minutes or 0.5 % change.<br>' +
  '<b>Reset Reporting To Recommended</b>: queues the recommended reporting, which saves battery: temperature 1 minute to 1 hour or 0.1 \u00b0C change, humidity 1 minute to 1 hour or 2 %RH change, battery 5 minutes to 12 hours or 2 % change.<br>' +
  '<i>The sensor is asleep except right after it transmits, so anything sent to it goes out after its next report (at most about 15 minutes).</i></span>'

metadata {
    definition(name: 'Shelly BLU H&T ZB', namespace: 'klohner', author: 'Karl Lohner', singleThreaded: true,
               importUrl: 'https://raw.githubusercontent.com/klohner/klohner.github.io/master/Hubitat/Shelly_BLU_H%26T_ZB/Shelly_BLU_HT_ZB_Simple.groovy') {
        capability 'Sensor'
        capability 'TemperatureMeasurement'
        capability 'RelativeHumidityMeasurement'
        capability 'Battery'
        capability 'Refresh'
        capability 'Configuration'

        attribute 'configStatus', 'string'
        attribute 'batteryLastReplaced', 'string'   // set by the batteryReplaced command

        attribute 'healthStatus', 'string'      // online / offline / unknown
        command 'resetReportingToFactory'
        command 'resetReportingToRecommended'
        command 'batteryReplaced'
        command 'initialize'

        // Output clusters differ between firmware/driver descriptions: list the variants.
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
        input name: 'cmdHelp', type: 'hidden', title: HELP_HTML
        input name: 'tempOffset', type: 'decimal', title: 'Temperature offset (°C, applied by the hub)', defaultValue: 0
        input name: 'humOffset',  type: 'decimal', title: 'Humidity offset (% RH, applied by the hub)',   defaultValue: 0
        input name: 'logEnable', type: 'bool', title: 'Debug logging (turns itself off after 30 minutes)', defaultValue: false
    }
}

// ------------------------------------------------------------------ lifecycle

void installed() {
    initState()
    writeDeviceData()
    queueSetup('installed', true)
}

void updated() {
    unschedule('debugOff')
    if (settings?.logEnable) runIn(DEBUG_AUTO_OFF_SECONDS, 'debugOff')
    initState()
    writeDeviceData()
}

// Configure: bind the clusters and read what the sensor is configured for (reporting itself is not changed).
void configure() {
    initState()
    writeDeviceData()
    queueSetup('configure')
}

// Refresh: read the current values and the sensor's reporting settings (State Variables).
void refresh() {
    initState()
    queueReads(false)
    for (String cl in CLUSTERS) {
        Map spec = CLUSTER_SPEC[cl] as Map
        enqueue('rcr', cl, spec.attr as String, (spec.base as int) + 1)
    }
    logInfo 'refresh: values and reporting settings will be read after the next report from the sensor'
    publishStatus()
}

// Puts the sensor's reporting back to the factory settings (temperature + humidity 60/900 s, change 0.01; battery 300/1200 s).
void resetReportingToFactory() { queueReportingSet(FACTORY, 'factory') }

void resetReportingToRecommended() { queueReportingSet(RECOMMENDED, 'recommended') }

private void queueReportingSet(Map set, String what) {
    initState()
    state.failed = []              // pending setup items (bindings, reads) stay queued; cr items replace any older ones with the same key
    for (String cl in CLUSTERS) {
        Map spec = CLUSTER_SPEC[cl] as Map
        enqueue('cr', cl, spec.attr as String, (spec.base as int) + 1, new LinkedHashMap(set[cl] as Map))
    }
    logInfo "reset to ${what} reporting: sent after the next report from the sensor"
    publishStatus()
}

// Offline detection: the sensor reports at least every "max interval" (read back into state.tMax), so silence for more than three
// times that (at least 10 minutes) means it is offline (battery out, out of range). Not checked when temperature reporting is off.
void healthCheck() {
    initState()
    long last = (state.lastRxAt ?: 0L) as long
    if (!last) return
    if (state.tMax == null) return                         // not read back from the sensor yet (Refresh or Configure reads it)
    int tMax = state.tMax as int
    if (tMax <= 0) return                                  // change-only or off: silence is expected
    long limit = Math.max(600000L, 3L * tMax * 1000L)
    if (now() - last > limit && state.health != 'offline') {
        state.health = 'offline'
        sendEvent(name: 'healthStatus', value: 'offline', descriptionText: "${device.displayName} is offline (silent for more than ${(int) (limit / 60000L)} min)".toString())
        log.warn "${device.displayName}: no frame from the sensor for more than ${(int) (limit / 60000L)} min: marked offline"
    }
}

private void markOnline() {
    if (state.health != 'online') {
        state.health = 'online'
        sendEvent(name: 'healthStatus', value: 'online', descriptionText: "${device.displayName} is online".toString())
    }
}

private void scheduleHealth() {
    runEvery15Minutes('healthCheck')
    state.healthSched = DRIVER_VERSION
}

void batteryReplaced() {
    String d = new Date().format('yyyy-MM-dd', location.timeZone)
    sendEvent(name: 'batteryLastReplaced', value: d, descriptionText: "${device.displayName} battery replaced ${d}".toString())
    logInfo "battery replacement recorded: ${d}"
}

void debugOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
    logInfo 'debug logging switched off'
}

// ------------------------------------------------------------------ parse

List parse(String description) {
    initState()
    state.lastRxAt = now()                  // one small write per frame
    markOnline()
    Map f = decodeFrame(description)
    logDebug "RX ${f.kind} ${description} => ${f.decoded}"
    onFrame(f)
    return []
}

// reply == true: the frame answers one of our requests; reply == false: the sensor started it (it is awake, a window is open)
private void onFrame(Map f) {
    try {
        if (state.needsConfig == true) queueSetup('driver started')
        if (f.kind == 'ANNCE') {
            onAnnounce(f)
        } else if (state.nwk != null && device.deviceNetworkId && !device.deviceNetworkId.equalsIgnoreCase(state.nwk as String)) {
            String nwk = device.deviceNetworkId.toUpperCase()      // the platform moved the device to a new address and we missed the announce
            state.nwk = nwk
            handleRejoin(nwk)
        }
        if (f.kind == 'RCR_RSP') recordReporting(f)
        boolean matched = ackFrame(f)
        if (f.reply == true) {
            if (matched && state.session) keepWindowOpen()
        } else if (state.session || hasQueued()) {
            openSession(f.kind as String)
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

private Map handleOneAttr(String cluster, String attrRaw, String value, String enc, String status, String kind) {
    String attr = hex4(attrRaw)
    String what = "${cluster}/${attr} val=${value}${status ? ' status=' + status : ''}"
    boolean isError = (status != null && status != '00' && status != '0') || (enc == '86')
    if (isError) return [text: "${what} -> ERROR/UNSUPPORTED".toString(), ok: false]
    try {
        if (cluster == '0402' && attr == '0000') {
            int raw = signed16(value)
            if (raw == -32768) return [text: "${what} -> temperature INVALID, ignored".toString(), ok: true, invalid: true]
            BigDecimal c = (new BigDecimal(raw).divide(100G, 2, RoundingMode.HALF_UP) + toDec(settings?.tempOffset)).setScale(2, RoundingMode.HALF_UP)
            def tv = convertTemperatureIfNeeded(c, 'C', 2)
            sendEvent(name: 'temperature', value: tv, unit: "°${location.temperatureScale}",
                      descriptionText: "${device.displayName} temperature is ${tv}°${location.temperatureScale}".toString())
            return [text: "${what} -> ${c} C".toString(), ok: true]
        }
        if (cluster == '0405' && attr == '0000') {
            int raw = Integer.parseInt(value, 16)
            if (raw == 0xFFFF) return [text: "${what} -> humidity INVALID, ignored".toString(), ok: true, invalid: true]
            BigDecimal h = (new BigDecimal(raw).divide(100G, 2, RoundingMode.HALF_UP) + toDec(settings?.humOffset)).setScale(2, RoundingMode.HALF_UP).max(0G).min(100G)
            sendEvent(name: 'humidity', value: h, unit: '%RH', descriptionText: "${device.displayName} humidity is ${h} %RH".toString())
            return [text: "${what} -> ${h} %RH".toString(), ok: true]
        }
        if (cluster == '0001' && attr == '0021') {
            int raw = Integer.parseInt(value, 16)
            if (raw == 0xFF) return [text: "${what} -> battery INVALID, ignored".toString(), ok: true, invalid: true]
            int pct = Math.max(0, Math.min(100, (int) Math.round(raw / 2.0d)))
            sendEvent(name: 'battery', value: pct, unit: '%', descriptionText: "${device.displayName} battery is ${pct}%".toString())
            return [text: "${what} -> ${pct} %".toString(), ok: true]
        }
        if (cluster == '0000' && BASIC_DATA[attr]) {
            String shown = (attr == '0007') ? powerSourceText(value) : value      // string attributes arrive already converted to text by the platform
            setData(BASIC_DATA[attr] as String, shown)
            return [text: "${what} -> data ${BASIC_DATA[attr]}=${shown}".toString(), ok: true]
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
    String prev = state.nwk as String
    f.nwk = nwk
    f.nwkChanged = (prev != null && !prev.equalsIgnoreCase(nwk))
    state.nwk = nwk
    setData('ieee', ieee)
    return "annce nwk=${nwk} ieee=${ieee}${f.nwkChanged ? ' NEW ADDRESS (rejoin)' : ''}".toString()
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

private void setData(String k, String v) {
    if (device.getDataValue(k) != v) device.updateDataValue(k, v)
}

// ------------------------------------------------------------------ State Variables with the sensor's reporting settings

private String fmtShort(int s) {
    if (s < 60) return "${s} sec".toString()
    if (s % 3600 == 0) return "${s.intdiv(3600)} hr".toString()
    if (s % 60 == 0) return "${s.intdiv(60)} min".toString()
    return "${s} sec".toString()
}

private String fmtLong(int s) {
    if (s % 3600 == 0) { int h = s.intdiv(3600); return h == 1 ? '1 hour' : "${h} hours".toString() }
    if (s % 60 == 0) { int m = s.intdiv(60); return m == 1 ? '1 minute' : "${m} minutes".toString() }
    return "${s} seconds".toString()
}

// e = [attr, status, type, min, max, chg] from the Read Reporting Configuration response
private String describeReporting(String cl, Map e) {
    if (e.status == '8B') return 'off (reports only on Refresh)'
    if (e.status != '00') return "not available (${ZCL_STATUS[e.status] ?: e.status})".toString()
    long chg = (e.chg as long)
    String delta = ''
    if (chg >= 0) {
        BigDecimal d = (cl == '0001') ? new BigDecimal(chg).divide(2G) : new BigDecimal(chg).divide(100G, 2, RoundingMode.HALF_UP)
        delta = "${d.stripTrailingZeros().toPlainString()}${cl == '0402' ? ' °C' : ' %'}".toString()
    }
    int mn = e.min as int
    int mx = e.max as int
    if (mx == 0 || mx == 0xFFFF) return "${fmtShort(mn)} minimum, no periodic report${delta ? ', only when it changes by ' + delta : ''}".toString()
    return "${fmtShort(mn)} minimum, at least every ${fmtLong(mx)}${delta ? ' or ' + delta + ' change' : ''}".toString()
}

private void recordReporting(Map f) {
    Map spec = CLUSTER_SPEC[f.cluster as String] as Map
    if (spec == null) return
    Map e = ((f.rcr ?: []) as List).find { Map r -> r.attr == hex4(spec.attr) } as Map
    if (e == null) return
    if (f.cluster == '0402') {
        int mx = (e.status == '00') ? (e.max as int) : 0
        state.tMax = (mx == 0xFFFF) ? 0 : mx
    }
    String txt = describeReporting(f.cluster as String, e)
    if (state[spec.key as String] != txt) {
        state[spec.key as String] = txt
        logInfo "${spec.name} reporting: ${txt}"
    }
}

// ------------------------------------------------------------------ queue engine
// Everything sent to the sensor is a queue item (kinds: bind, cr = configure reporting (factory reset only), rcr = read
// reporting configuration, read = read attribute). The sensor is asleep except right after it transmits: that opens a COMMAND
// WINDOW of >= 6.5 s in which it answers about one command per second. So: queue now, send only inside a window, one item
// at a time, match replies by content, retry in the next window.

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

// ---- queue helpers
private boolean hasQueued() { ((state.queue ?: []) as List).any { Map i -> i.status == 'queued' } }

private void queueSetup(String reason, boolean applyDefaults = false) {
    initState()
    state.gen = ((state.gen ?: 0) as int) + 1
    state.remove('needsConfig')
    state.failed = []
    state.queue = ((state.queue ?: []) as List).findAll { Map i -> i.status == 'sent' }
    for (String cl in CLUSTERS) {
        Map spec = CLUSTER_SPEC[cl] as Map
        enqueue('bind', cl, '', spec.base as int)
        if (applyDefaults) enqueue('cr', cl, spec.attr as String, (spec.base as int) + 1, new LinkedHashMap(DEFAULT_REPORTING[cl] as Map))
        enqueue('rcr', cl, spec.attr as String, (spec.base as int) + 1)
    }
    queueReads(true)
    logInfo "${reason}: ${((state.queue ?: []) as List).count { Map i -> i.status == 'queued' }} items queued${applyDefaults ? ' (including the default reporting settings)' : ''}, sent after the next report from the sensor"
    publishStatus()
}

private void queueReads(boolean basic) {
    enqueue('read', '0001', '0021', 60)
    enqueue('read', '0402', '0000', 62)
    enqueue('read', '0405', '0000', 63)
    if (basic) {                                  // identity values for Device Data, only while missing
        if (!device.getDataValue('zclVersion')) enqueue('read', '0000', '0000', 70)
        if (!device.getDataValue('powerSource')) enqueue('read', '0000', '0007', 71)
        if (!device.getDataValue('swBuildId')) enqueue('read', '0000', '4000', 72)
    }
}

private void onAnnounce(Map f) {
    if (f.nwkChanged == true) {
        handleRejoin(f.nwk as String)
    } else if (state.nwk != null) {                 // same address: battery change or restart, the settings may have changed
        long t = now()
        if ((t - ((state.lastVerifyQueueAt ?: 0L) as long)) < 900000L) return
        state.lastVerifyQueueAt = t
        for (String cl in CLUSTERS) {
            Map spec = CLUSTER_SPEC[cl] as Map
            enqueue('rcr', cl, spec.attr as String, (spec.base as int) + 1)
        }
        logDebug 'announce with the same network address: reporting settings will be read again'
    }
}

// A new network address means a fresh join: bindings are gone and the sensor is back at factory reporting.
private void handleRejoin(String nwk) {
    long t = now()
    if ((t - ((state.lastRejoinAt ?: 0L) as long)) < REJOIN_COOLDOWN_MS) {
        logWarn "rejoin to ${nwk} again within 10 minutes: not queueing the setup a second time"
        return
    }
    state.lastRejoinAt = t
    queueSetup('rejoin', true)
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
    if (!sendAllowed()) {
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
    unschedule('pumpTick')
    unschedule('itemTimeout')
    if (s != null) {
        logDebug "S${s.id} window closed: ${s.sent} sent, ${s.acked} answered, ${((state.queue ?: []) as List).size()} left"
        if ((s.sent as int) > 1) logInfo "queue window: ${s.sent} sent, ${s.acked} answered in ${String.format('%.1f', (now() - (s.openedAt as long)) / 1000.0)} s, ${((state.queue ?: []) as List).size()} left"
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
    if (!zid) throw new IllegalStateException('device.zigbeeId not available')
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
    switch (item.kind as String) {
        case 'bind':
            if (f.ok == true) {
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
                ackItem(item)
                Map spec = CLUSTER_SPEC[item.cluster] as Map
                enqueue('rcr', item.cluster as String, item.attr as String, (spec.base as int) + 1)      // read back what the sensor now holds
            } else {
                failItem(item, "configure reporting refused: ${f.decoded}")
            }
            break
        default:               // rcr (recorded when it arrived) and read: any answer completes it
            ackItem(item)
            break
    }
}

// configStatus is derived from the queue, never a separate truth
private void publishStatus() {
    List q = (state.queue ?: []) as List
    List fl = (state.failed ?: []) as List
    String text
    if (fl) {
        text = "Failed: ${(fl.last() as Map).reason}. Use Configure to try again.".toString()
    } else if (q) {
        Map s = state.session as Map
        if (s != null && now() > (s.budgetEnd as long) + ITEM_TIMEOUT_MS) s = null           // stale window
        text = s ? "Working: ${q.size()} left".toString() : "Pending: ${q.size()} item${q.size() == 1 ? '' : 's'}, sent after the next report from the sensor".toString()
    } else {
        text = 'Ready'
    }
    if (device.currentValue('configStatus') != text) sendEvent(name: 'configStatus', value: text)
}

// ------------------------------------------------------------------ state schema

private void initState() {
    if (state.drv != 'simple-1') {
        // First run, or the device was switched from another driver (Reference, Probe): drop its jobs and state.
        unschedule()
        state.clear()
        state.drv         = 'simple-1'
        state.needsConfig = true      // the first frame queues the setup (cleared by queueSetup)
        state.queue       = []        // items: id, gen, key, kind(bind|cr|rcr|read), cluster, attr, prio, status(queued|sent), tries, p, sentAt
        state.nextId      = 1
        state.gen         = 0
        state.sid         = 0
        state.session     = null      // open command window: id, trigger, openedAt, budgetEnd, sent, acked
        state.failed      = []
        state.nwk         = device.deviceNetworkId
        state.health      = 'unknown'
        for (String a in ['lastCheckin', 'batteryVoltage']) {      // attributes of the Reference driver
            try { device.deleteCurrentState(a) } catch (Exception e) { /* not present */ }
        }
    }
    if (state.healthSched != DRIVER_VERSION) scheduleHealth()      // also covers installs that predate the health check
}


// ------------------------------------------------------------------ Initialize: remove everything this driver does not use
@Field static final List USED_ATTRIBUTES = ['temperature', 'humidity', 'battery', 'configStatus', 'batteryLastReplaced', 'healthStatus']
@Field static final List USED_STATE = ['drv', 'needsConfig', 'queue', 'nextId', 'gen', 'sid', 'session', 'failed', 'nwk', 'lastParseWarnAt', 'lastRejoinAt', 'lastVerifyQueueAt', 'sendHour', 'lastRxAt', 'health', 'healthSched', 'tMax', 'temperatureReporting', 'humidityReporting', 'batteryReporting']
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
    scheduleHealth()
    if (settings?.logEnable) runIn(DEBUG_AUTO_OFF_SECONDS, 'debugOff')
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

// ------------------------------------------------------------------ Device Data (same as the Reference driver)

private void writeDeviceData() {
    device.updateDataValue('batteryType', BATTERY_TYPE)
    for (String k in ['mfrName', 'modelId', 'appVersion']) {        // legacy copies of platform-owned values
        if (device.getDataValue(k) != null) device.removeDataValue(k)
    }
    normalizePowerSource()
    // ieee, swBuildId, zclVersion and powerSource are written from announce / Basic read responses.
    // manufacturer, model, application and endpointId belong to the platform: never written here.
}

private BigDecimal toDec(Object v) {
    try { return (v == null || v.toString().trim() == '') ? 0G : new BigDecimal(v.toString()) } catch (Exception e) { return 0G }
}

// ------------------------------------------------------------------ helpers

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

private void logInfo(String m)  { log.info  "${device.displayName}: ${m}" }
private void logDebug(String m) { if (settings?.logEnable) log.debug "${device.displayName}: ${m}" }
private void logWarn(String m)  { log.warn  "${device.displayName}: ${m}" }
