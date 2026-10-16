/**
 *  Shelly BLU H&T ZB (SBHT-203C) - Probe (diagnostic driver)
 *
 *  DIAGNOSTIC TOOL used to develop the Reference and Simple drivers; most people do not need it. Its wake-up findings below
 *  were superseded by strict Zigbee 3.0 pairing (see Technical_Reference.md).
 *
 *  Purpose: measure and log what the sensor sends and accepts. It records every Zigbee frame (raw + decoded +
 *  timing) in a ring buffer and the hub log, publishes temperature / humidity / battery events (every frame,
 *  also when the value did not change), groups the sensor's frames into "rejoin runs", and flags frames that
 *  arrive OUTSIDE a run (spontaneous payloads). Commands are queued and sent only inside a wake session.
 *
 *  What a wake is on this device (measured on unit B, firmware 1.1.4 and 1.2.12, 2026-10-15): the sensor's Zigbee
 *  radio is reachable only during the ~4 minute run after FIVE quick button presses (hub in pairing mode). Every
 *  Device_annce in the run opens a command window of about 12 s. One press, warming the sensor and BLE state do not
 *  wake it. Reports have been seen only a few seconds after a session inside a run.
 *
 *  Operator commands (all queue; nothing is sent until the next announce):
 *      queueJoinSet            binds + identity + reporting read-back + current values (REPLACES the queue)
 *      refresh                 reads temperature, humidity, battery
 *      queueReporting          Configure Reporting for temperature + humidity (min, max, changes), then read-back
 *      queueReportingBattery   Configure Reporting for battery, then read-back
 *      queueReportingTest      shortcut: min 10 s, max 120 s, 0.10 C, 0.5 %
 *      queueReportingRestore   factory values: 60 / 900 s, change 0.01
 *      queueDiscover           ZCL Discover Attributes on one cluster (read only)
 *      queueIdentify           Identify (LED stays on for N seconds)
 *  It never writes an attribute.
 *
 *  v0.4 experiment set (2026-10-15, after reporting 10/120 produced no report outside a run):
 *      - at EVERY announce of a run: read the sensor's binding table (ZDO Mgmt_Bind_req), read back the temperature
 *        reporting, then re-send the three binds and the reporting config (what the old unit-A driver did), then read T/H
 *      - liveness pings (Read Basic ZCLVersion) 14 / 17 / 20 s after every announce: when does the sensor stop answering?
 *      - announce-to-announce intervals; Node_Desc_req / NWK_addr_req from the sensor are named in the log
 *      - quiet-time watch measures from the last frame (v0.3 measured from the first frame ever)
 *
 *  v0.5 (2026-10-15, after strict-Zigbee-3.0 pairing made the sensor stay joined and report by itself):
 *      - the sensor listens for about a second or more right after EVERY report it sends: queued items are sent then
 *      - queueReportingRaw: Configure Reporting with UNRESTRICTED numbers (limit tests, disabling a report with max 65535)
 *      - probeAfterReport: one Basic read N seconds after successive reports, to profile the listening window
 *
 *  Design references (project docs): sed_lifecycle.md R3/R5/R17/R20-R23, design_queue_state.md,
 *  zigbee_protocol_spec.md, unit_b_wake_and_reporting_results_2026-10-15.md.
 *
 *  Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */

import groovy.transform.Field
import java.math.RoundingMode

@Field static final String DRIVER_VERSION = '0.5.1-probe'

// ---- timing constants (sed_lifecycle.md parameter register) ----
@Field static final long SEND_BUDGET_MS   = 12000L   // stop releasing new items this long after the session opened
@Field static final long ITEM_TIMEOUT_MS  = 8000L    // an unanswered item goes back to the queue
@Field static final long SETTLE_MS        = 300L     // minimum gap between two sends
@Field static final long RELEASE_AFTER_MS = 1000L    // release the next item even without an ack after this long
@Field static final long SESSION_GAP_MS   = 6000L    // idle time that ends a session after the budget
@Field static final long SESSION_MAX_MS   = 30000L   // hard stop
@Field static final long TICK_MS          = 500L
@Field static final int  MAX_OUTSTANDING  = 2
@Field static final int  MAX_TRIES        = 5   // charged tries = sends in sessions where the device answered at least once
@Field static final long MIN_SESSION_GAP_MS = 20000L // do not open a new session sooner than this after one closed
@Field static final int  RING_MAX         = 120
@Field static final long CHECKIN_THROTTLE_MS = 300000L
@Field static final int  DEBUG_AUTO_OFF_SECONDS = 1800
@Field static final long RUN_GAP_MS       = 150000L  // frames closer together than this belong to one rejoin run (largest gap seen inside a run: 102 s)
@Field static final int  RUNS_MAX         = 12
@Field static final int  FACTORY_MAX_S    = 900      // factory reporting maximum (temperature / humidity)

metadata {
    definition(name: 'Shelly BLU H&T ZB Probe', namespace: 'klohner', author: 'Karl Lohner', singleThreaded: true) {
        capability 'Sensor'
        capability 'TemperatureMeasurement'
        capability 'RelativeHumidityMeasurement'
        capability 'Battery'
        capability 'Refresh'

        attribute 'batteryVoltage', 'number'
        attribute 'configStatus',   'string'
        attribute 'lastCheckin',    'string'
        attribute 'lastReport',     'string'
        attribute 'watch',          'string'

        command 'summary'
        command 'dumpRing', [[name: 'Last N lines', type: 'NUMBER']]
        command 'runs'
        command 'queueJoinSet'
        command 'clearQueue'
        command 'replayFrame', [[name: 'Frame description string', type: 'STRING']]
        command 'resetProbe'
        command 'queueReporting', [[name: 'Min seconds (1-3600)', type: 'NUMBER'], [name: 'Max seconds (min-65000)', type: 'NUMBER'],
                                   [name: 'Temperature change C (0.01-10)', type: 'NUMBER'], [name: 'Humidity change % (0.01-50)', type: 'NUMBER']]
        command 'queueReportingBattery', [[name: 'Min seconds (60-3600)', type: 'NUMBER'], [name: 'Max seconds (min-65000)', type: 'NUMBER'],
                                          [name: 'Change % (0.5-25)', type: 'NUMBER']]
        command 'queueReportingTest'
        command 'queueReportingRestore'
        command 'queueDiscover', [[name: 'Cluster', type: 'ENUM', constraints: ['0000', '0001', '0003', '0402', '0405']]]
        command 'queueIdentify', [[name: 'Seconds (1-30)', type: 'NUMBER']]
        command 'queueBindingTable'
        command 'queueReportingRaw', [[name: 'Cluster', type: 'ENUM', constraints: ['0402', '0405', '0001']], [name: 'Min seconds (0-65535)', type: 'NUMBER'],
                                      [name: 'Max seconds (0-65535)', type: 'NUMBER'], [name: 'Change, raw units (0-65535): 0.01 C / 0.01 % / 0.5 %', type: 'NUMBER']]
        command 'queueReadAttr', [[name: 'Cluster', type: 'ENUM', constraints: ['0000', '0001', '0003', '0402', '0405']], [name: 'Attribute (hex, e.g. 0001 or 4000)', type: 'STRING']]
        command 'probeAfterReport', [[name: 'Offsets in seconds, comma separated (0.3-50), empty = off', type: 'STRING']]

        // The model string was never read from any unit, so no fingerprint uses it. The output clusters are unsettled
        // (Identify per Shelly / zigbee2mqtt, OTA per older drivers), so several variants are listed.
        fingerprint profileId: '0104', endpointId: '01', deviceId: '0302', inClusters: '0000,0001,0003,0402,0405',
                    outClusters: '0003', manufacturer: 'Shelly', deviceJoinName: 'Shelly BLU H&T ZB (Probe)'
        fingerprint profileId: '0104', endpointId: '01', deviceId: '0302', inClusters: '0000,0001,0003,0402,0405',
                    outClusters: '0019', manufacturer: 'Shelly', deviceJoinName: 'Shelly BLU H&T ZB (Probe)'
        fingerprint profileId: '0104', endpointId: '01', deviceId: '0302', inClusters: '0000,0001,0003,0402,0405',
                    outClusters: '0003,0019', manufacturer: 'Shelly', deviceJoinName: 'Shelly BLU H&T ZB (Probe)'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0001,0003,0402,0405',
                    manufacturer: 'Shelly', deviceJoinName: 'Shelly BLU H&T ZB (Probe)'
        fingerprint profileId: '0104', manufacturer: 'Shelly', deviceId: '0302',
                    deviceJoinName: 'Shelly BLU H&T ZB (Probe)'
    }

    preferences {
        input name: 'logFrames', type: 'bool', title: 'Log every frame at info level (recommended while probing)', defaultValue: true
        input name: 'autoRefreshOnRun', type: 'bool', title: 'Queue a read of temperature, humidity and battery at the start of every rejoin run', defaultValue: true
        input name: 'reconfigEveryAnnounce', type: 'bool', title: 'Experiment: at every announce read the binding table, re-bind and re-send the reporting config', defaultValue: true
        input name: 'windowProbe', type: 'bool', title: 'Experiment: liveness pings 14, 17 and 20 s after every announce', defaultValue: true
        input name: 'watchEnable', type: 'bool', title: 'Quiet-time watch: once a minute compare the silence with the configured reporting interval (log + "watch" attribute)', defaultValue: true
        input name: 'logEnable', type: 'bool', title: 'Debug logging (turns itself off after 30 minutes)', defaultValue: false
    }
}

// ------------------------------------------------------------------ lifecycle

void installed() {
    logInfo "Installed, driver ${DRIVER_VERSION}. Join session starts in 3 s (binds + identity reads; NO Configure Reporting)."
    addRing('CB', "installed() dni=${device.deviceNetworkId}")
    ensureInit()
    scheduleWatch()
    runInMillis(3000L, 'startJoinSession', [overwrite: true])
}

void updated() {
    logInfo 'Preferences saved.'
    addRing('CB', 'updated()')
    unschedule('logsOff')
    if (settings.logEnable) {
        runIn(DEBUG_AUTO_OFF_SECONDS, 'logsOff')
        logInfo 'Debug logging is ON and will turn itself off in 30 minutes.'
    }
    ensureInit()
    scheduleWatch()
}

private void scheduleWatch() {
    unschedule('watchTick')
    state.watchSched = true
    if (settings.watchEnable != false) runEvery1Minute('watchTick')
}

void logsOff() {
    logInfo 'Debug logging turned off.'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

void startJoinSession() {
    startSession('JOIN')
}

void refresh() {
    // The device is asleep: queue three reads; they are sent at the next announce of a rejoin run.
    queueItem('read', '0402', '0000')
    queueItem('read', '0405', '0000')
    queueItem('read', '0001', '0021')
    logInfo 'Refresh queued (3 reads). They are sent when the sensor next rejoins: put the hub in Zigbee pairing mode, then press the sensor button 5 times quickly. One press does not wake it.'
    publishConfigStatus()
}

void queueJoinSet() {
    buildJoinQueue()
    logInfo "Join set queued again (${state.queue?.size()} items)."
    publishConfigStatus()
}

void clearQueue() {
    state.queue = []
    closeSession('cleared')
    logInfo 'Queue cleared.'
    publishConfigStatus()
}

void queueReportingTest() {
    // "every couple of minutes": min 10 s, max 120 s, 0.10 C, 0.5 %
    queueReporting(10, 120, 0.10G, 0.5G)
}

void queueReportingRestore() {
    // back to the factory values observed on both firmware builds (60 / 900 / change 1 = 0.01)
    queueFront('rcr', '0405', '0000', null)
    queueFront('rcr', '0402', '0000', null)
    queueFront('cr', '0405', '0000', [type: 0x21, min: 60, max: FACTORY_MAX_S, delta: 1])
    queueFront('cr', '0402', '0000', [type: 0x29, min: 60, max: FACTORY_MAX_S, delta: 1])
    state.crSpec = [min: 60, max: FACTORY_MAX_S, t: 1, h: 1]
    logInfo 'Reporting RESTORE-to-factory queued at the FRONT (T/H 60/900/0.01), then read-back.'
    publishConfigStatus()
}

void queueReporting(def minS, def maxS, def tDeltaC, def hDeltaPct) {
    Integer mn = toIntOrNull(minS)
    Integer mx = toIntOrNull(maxS)
    BigDecimal td = toDecOrNull(tDeltaC)
    BigDecimal hd = toDecOrNull(hDeltaPct)
    if (mn == null || mx == null || td == null || hd == null) { logWarn 'queueReporting: all four values must be numbers.'; return }
    int tRaw = td.multiply(100G).setScale(0, RoundingMode.HALF_UP).intValue()
    int hRaw = hd.multiply(100G).setScale(0, RoundingMode.HALF_UP).intValue()
    String err = null
    if (mn < 1 || mn > 3600) err = 'min must be 1-3600 s'
    else if (mx < mn || mx > 65000) err = 'max must be between min and 65000 s (0 and 65535 are refused: they change or stop reporting)'
    else if (tRaw < 1 || tRaw > 1000) err = 'temperature change must be 0.01-10 C'
    else if (hRaw < 1 || hRaw > 5000) err = 'humidity change must be 0.01-50 %'
    if (err) { logWarn "queueReporting refused: ${err}"; return }
    // queueFront inserts at index 0, so call in reverse: final order = cr 0402, cr 0405, rcr 0402, rcr 0405
    queueFront('rcr', '0405', '0000', null)
    queueFront('rcr', '0402', '0000', null)
    queueFront('cr', '0405', '0000', [type: 0x21, min: mn, max: mx, delta: hRaw])
    queueFront('cr', '0402', '0000', [type: 0x29, min: mn, max: mx, delta: tRaw])
    state.crSpec = [min: mn, max: mx, t: tRaw, h: hRaw]
    logInfo "Reporting queued at the FRONT: T/H min ${mn} s, max ${mx} s, change ${td} C / ${hd} %, then read-back. Sent at the next announce of a rejoin run."
    publishConfigStatus()
}

void queueReportingBattery(def minS, def maxS, def deltaPct) {
    Integer mn = toIntOrNull(minS)
    Integer mx = toIntOrNull(maxS)
    BigDecimal dp = toDecOrNull(deltaPct)
    if (mn == null || mx == null || dp == null) { logWarn 'queueReportingBattery: all three values must be numbers.'; return }
    int raw = dp.multiply(2G).setScale(0, RoundingMode.HALF_UP).intValue()   // half-percent units
    String err = null
    if (mn < 60 || mn > 3600) err = 'min must be 60-3600 s'
    else if (mx < mn || mx > 65000) err = 'max must be between min and 65000 s'
    else if (raw < 1 || raw > 50) err = 'change must be 0.5-25 %'
    if (err) { logWarn "queueReportingBattery refused: ${err}"; return }
    queueFront('rcr', '0001', '0021', null)
    queueFront('cr', '0001', '0021', [type: 0x20, min: mn, max: mx, delta: raw])
    logInfo "Battery reporting queued at the FRONT: min ${mn} s, max ${mx} s, change ${dp} %, then read-back."
    publishConfigStatus()
}

/** v0.5 limit tests. Same allow-list as before (temperature, humidity, battery percentage); numbers are NOT restricted to the
 *  sensible range, the sensor's answer (status 0x00 or an error code) is the result. max 65535 = stop reporting (reversible
 *  with a later Configure Reporting; a fresh join also resets to factory). Does not touch state.crSpec. */
void queueReportingRaw(def cluster, def minS, def maxS, def changeRaw) {
    String c = (cluster ?: '').toString().trim().toUpperCase()
    Map spec = ['0402': [attr: '0000', type: 0x29, chMax: 32767], '0405': [attr: '0000', type: 0x21, chMax: 65535], '0001': [attr: '0021', type: 0x20, chMax: 255]]
    Integer mn = toIntOrNull(minS)
    Integer mx = toIntOrNull(maxS)
    Integer ch = toIntOrNull(changeRaw)
    if (!spec.containsKey(c) || mn == null || mx == null || ch == null) { logWarn 'queueReportingRaw: cluster must be 0402/0405/0001 and min, max, change must be numbers.'; return }
    Map sp = spec[c] as Map
    if (mn < 0 || mn > 65535 || mx < 0 || mx > 65535 || ch < 0 || ch > (sp.chMax as int)) { logWarn "queueReportingRaw refused: min/max 0-65535, change 0-${sp.chMax} for ${c}."; return }
    queueFront('rcr', c, sp.attr as String, null)
    queueFront('cr', c, sp.attr as String, [type: sp.type as int, min: mn, max: mx, delta: ch, raw: true])
    logInfo "RAW Configure Reporting queued at the FRONT: cluster ${c} min ${mn} s, max ${mx} s, change raw ${ch}, then read-back. Sent after the next report or announce."
    publishConfigStatus()
}

/** v0.5: read any attribute (read only), e.g. the sensor's MinMeasuredValue / MaxMeasuredValue (0402/0001, 0402/0002). */
void queueReadAttr(def cluster, def attr) {
    String c = hex4(cluster)
    String a = hex4(attr)
    if (!(c in ['0000', '0001', '0003', '0402', '0405']) || !(a ==~ /[0-9A-F]{4}/)) { logWarn 'queueReadAttr refused: cluster must be on the sensor input list and the attribute a 4-digit hex number.'; return }
    queueItem('read', c, a)
    logInfo "Read of ${c}/${a} queued (read only)."
    publishConfigStatus()
}

/** v0.5: one Read Basic ZCLVersion, N seconds after successive reports (offsets cycle). Shows how long after a report the sensor still answers. */
void probeAfterReport(def csv) {
    List<String> offs = []
    (csv ?: '').toString().split(',').each { String x ->
        BigDecimal v = toDecOrNull(x.trim())
        if (v != null && v >= 0.3G && v <= 50G) offs << v.toString()
    }
    if (!offs) { state.remove('profile'); logInfo 'Report-offset ping profile: OFF.'; return }
    state.profile = [offs: offs, idx: 0, lastArm: 0L, results: []]
    logInfo "Report-offset ping profile ON: one Basic read at +${offs.join(', +')} s after successive reports (cycling)."
}

private void profileOnReport(long t) {
    Map p = state.profile as Map
    if (!p || hasPending() || state.session) return
    if (t - ((p.lastArm ?: 0L) as long) < 8000L) return        // the second frame of a report pair is not a new report
    List offs = (p.offs ?: []) as List
    if (!offs) return
    int i = ((p.idx ?: 0) as int)
    BigDecimal off = new BigDecimal(offs[i % offs.size()].toString())
    p.idx = i + 1
    p.lastArm = t
    state.profile = p
    runInMillis((off * 1000G).longValue(), 'profilePing', [overwrite: false, data: [off: off.toString(), rep: t, nwk: state.nwk]])
}

void profilePing(Map d) {
    if (!state.profile) return
    if (d?.nwk && state.nwk != d.nwk) return
    try {
        List c = zigbee.readAttribute(0x0000, 0x0000, [destEndpoint: 0x01], 0)
        String cmd = c.find { !it.toString().startsWith('delay') }?.toString()
        if (!cmd) return
        long sentAt = now()
        addRing('PROFILE', "read +${d.off} s after the report: ${cmd}")
        sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZIGBEE))
        runInMillis(5000L, 'profileCheck', [overwrite: false, data: [off: d.off, sentAt: sentAt]])
    } catch (Exception e) {
        logWarn "profilePing error ${e.class.simpleName}: ${e.message}"
    }
}

void profileCheck(Map d) {
    long sentAt = (d.sentAt as long)
    long got = ((state.lastBasicRxAt ?: 0L) as long)
    boolean ok = got >= sentAt
    String res = ok ? "+${d.off} s: REPLIED after ${fmtDt(got - sentAt)}" : "+${d.off} s: NO reply within 5 s"
    Map p = (state.profile ?: [:]) as Map
    List r = (p.results ?: []) as List
    r << res
    if (r.size() > 40) r = r.drop(r.size() - 40)
    p.results = r
    state.profile = p
    addRing('PROFILE', res)
    logInfo "PROFILE read ${res}"
}

void queueDiscover(def cluster) {
    String c = hex4(cluster)
    if (!(c in ['0000', '0001', '0003', '0402', '0405'])) { logWarn "queueDiscover refused: cluster ${c} is not on the sensor's input list."; return }
    queueItem('disc', c, '0000')
    logInfo "Discover Attributes queued for cluster ${c} (read only)."
    publishConfigStatus()
}

void queueIdentify(def seconds) {
    Integer s = toIntOrNull(seconds)
    if (s == null || s < 1 || s > 30) { logWarn 'queueIdentify refused: seconds must be 1-30.'; return }
    List q = (state.queue ?: []) as List
    q.removeAll { Map x -> x.status == 'queued' && x.kind == 'ident' }
    int id = ((state.nextId ?: 1) as int)
    state.nextId = id + 1
    q << [id: id, kind: 'ident', cluster: '0003', attr: '0000', p: [seconds: s], noAck: true, status: 'queued', tries: 0, charged: 0, lastSession: 0, sentAt: 0L, ackedAt: 0L]
    state.queue = q
    logInfo "Identify (${s} s, LED on) queued. No reply is expected."
    publishConfigStatus()
}

void queueBindingTable() {
    queueFront('mbind', '0033', '0000', null)
    logInfo 'Binding-table read (ZDO Mgmt_Bind_req) queued at the FRONT.'
    publishConfigStatus()
}

/** v0.4 experiment: what the sensor needs at EVERY announce of a rejoin run. Final order:
 *  mbind, rcr 0402, bind 0402, bind 0405, bind 0001, cr 0402, cr 0405, read 0402, read 0405 */
private void queueAnnouncePlan() {
    if (!state.crSpec) logInfo 'No saved reporting spec: re-sending the test values 10/120 s, 0.10 C, 0.5 %.'
    Map sp = (state.crSpec ?: [min: 10, max: 120, t: 10, h: 50]) as Map
    // keep the queue small: drop finished items older than 10 minutes
    List q0 = (state.queue ?: []) as List
    q0.removeAll { Map x -> (x.status in ['acked', 'failed', 'done']) && (now() - ((x.ackedAt ?: 0) as long)) > 600000L }
    state.queue = q0
    queueFront('read', '0405', '0000', null)
    queueFront('read', '0402', '0000', null)
    queueFront('cr', '0405', '0000', [type: 0x21, min: sp.min as int, max: sp.max as int, delta: sp.h as int])
    queueFront('cr', '0402', '0000', [type: 0x29, min: sp.min as int, max: sp.max as int, delta: sp.t as int])
    queueFront('bind', '0001', null, null)
    queueFront('bind', '0405', null, null)
    queueFront('bind', '0402', null, null)
    queueFront('rcr', '0402', '0000', null)
    queueFront('mbind', '0033', '0000', null)
}

void livePing(Map d) {
    if (settings.windowProbe == false) return
    if (d?.nwk && state.nwk != d.nwk) { addRing('PING', "#${d?.n} skipped: a newer announce (${state.nwk}) replaced ${d?.nwk}"); return }
    try {
        List c = zigbee.readAttribute(0x0000, 0x0000, [destEndpoint: 0x01], 0)
        String cmd = c.find { !it.toString().startsWith('delay') }?.toString()
        if (!cmd) return
        long since = now() - (((d?.a) ?: now()) as long)
        String note = "PING #${d?.n} +${fmtDt(since)} after announce ${d?.nwk} (now dni ${device.deviceNetworkId}): ${cmd}"
        addRing('PING', note)
        logInfo note
        sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZIGBEE))
    } catch (Exception e) {
        logWarn "livePing error ${e.class.simpleName}: ${e.message}"
    }
}

private Integer toIntOrNull(def v) {
    try { return v == null ? null : new BigDecimal(v.toString()).intValue() } catch (Exception e) { return null }
}

private BigDecimal toDecOrNull(def v) {
    try { return v == null ? null : new BigDecimal(v.toString()) } catch (Exception e) { return null }
}

void resetProbe() {
    unschedule('sessionTick')
    unschedule('startJoinSession')
    unschedule('watchTick')
    state.clear()
    ensureInit()
    scheduleWatch()
    logInfo 'Probe state reset (ring buffer, counters, runs, queue rebuilt).'
}

// ------------------------------------------------------------------ parse

def parse(String description) {
    ensureInit()
    if (!state.watchSched) scheduleWatch()      // after a driver swap neither installed() nor updated() runs
    if (state.session && (now() - (state.session.openedAt as long)) > SESSION_MAX_MS + 5000L) closeSession('stale')
    long t = now()
    long dt = state.lastRxAt ? (t - (state.lastRxAt as long)) : 0L
    state.lastRxAt = t
    if (!state.firstRxAt) state.firstRxAt = t
    state.rxCount = ((state.rxCount ?: 0) as int) + 1

    // keep the DNI/NWK bookkeeping honest (the platform changes the DNI itself after a rejoin)
    String dni = device.deviceNetworkId
    if (state.dni != null && state.dni != dni) {
        logInfo "Device network id changed ${state.dni} -> ${dni}"
        addRing('NWK', "dni ${state.dni} -> ${dni}")
    }
    state.dni = dni

    Map m = null
    try {
        m = zigbee.parseDescriptionAsMap(description)
    } catch (Exception e) {
        m = null
    }

    String kind = 'RAW_OTHER'
    String decoded = ''
    boolean replyToUs = false

    try {
        if (description?.startsWith('catchall:') && m) {
            Integer cl = (m.clusterInt ?: -1) as Integer
            String cmd = (m.command ?: '').toString().toUpperCase()
            List data = (m.data ?: []) as List
            boolean gen = m.isClusterSpecific?.toString() != 'true'   // general ZCL command (not a cluster-specific one such as Identify)
            if (cl == 0x0013) {
                kind = 'ANNCE'
                decoded = handleAnnounce(data)
            } else if (cl == 0x8021) {
                kind = 'BIND_RSP'
                replyToUs = true
                String st = data.size() > 1 ? data[1].toString() : '??'
                decoded = "bind_rsp seq=${data ? data[0] : '??'} status=${st}"
                ackBind(st)
            } else if (cl == 0x8033) {
                kind = 'MGMT_BIND_RSP'
                replyToUs = true
                decoded = decodeMgmtBind(data)
                ackByCluster('mbind', '0033', data.size() > 1 && data[1].toString() == '00')
            } else if (m.profileId == '0000' && cl == 0x0002) {
                kind = 'NODE_DESC_REQ'
                decoded = "node_desc_req from sensor seq=${data ? data[0] : '??'} (asks the hub's node descriptor: Zigbee 3.0 post-join step) data=${data}"
            } else if (m.profileId == '0000' && cl == 0x0000) {
                kind = 'NWK_ADDR_REQ'
                decoded = "nwk_addr_req from sensor data=${data} (resolving an IEEE, e.g. the hub as binding destination)"
            } else if (m.profileId == '0000') {
                kind = 'ZDO_OTHER'
                decoded = "zdo cluster=${m.clusterId} data=${data}"
            } else if (cmd == '07' && gen) {
                kind = 'CR_RSP'
                replyToUs = true
                decoded = "cr_rsp cluster=${m.clusterId} data=${data}"
                ackCr(hex4(m.clusterId), data)
            } else if (cmd == '09' && gen) {
                kind = 'RCR_RSP'
                replyToUs = true
                decoded = "rcr_rsp cluster=${m.clusterId} data=${data}" + decodeRcr(hex4(m.clusterId), data)
                ackByCluster('rcr', hex4(m.clusterId))
            } else if (cmd == '0B' && gen) {
                kind = 'DEFAULT_RSP'
                replyToUs = true
                decoded = "default_rsp cluster=${m.clusterId} data=${data}"
                // data = [command id, status]; a non-zero status means the device refused that command
                if (data.size() >= 2 && data[1].toString() != '00') {
                    failOutstanding(hex4(m.clusterId), data[0].toString(), "default_rsp status ${data[1]} for cmd ${data[0]}")
                    decoded += ' -> REFUSED'
                }
            } else if (cmd == '0D' && gen) {
                kind = 'DISC_RSP'
                replyToUs = true
                decoded = decodeDiscover(hex4(m.clusterId), data)
                ackByCluster('disc', hex4(m.clusterId))
            } else if (cmd == '01' && gen) {
                kind = 'RD_RSP'
                replyToUs = true
                decoded = "catchall read_rsp cluster=${m.clusterId} data=${data}"
                if (data.size() >= 3) {
                    String a = (data[1].toString() + data[0].toString()).toUpperCase()
                    ackRead(hex4(m.clusterId), a, data[2].toString() == '00')
                    decoded += " -> attr ${a} status ${data[2]}"
                }
            } else if (cmd == '0A' && gen) {
                kind = 'REPORT'
                decoded = "catchall report cluster=${m.clusterId} data=${data}"
            } else {
                kind = 'CATCHALL_OTHER'
                decoded = "cluster=${m.clusterId} cmd=${cmd} specific=${m.isClusterSpecific} data=${data}"
            }
        } else if (description?.startsWith('read attr -')) {
            Map r = (m && m.cluster) ? m : rawReadAttrFallback(description)
            String cmd = (r?.command ?: '').toString().toUpperCase()
            if (cmd == '0A') {
                kind = 'REPORT'
            } else if (cmd == '01') {
                kind = 'RD_RSP'
                replyToUs = true
            } else {
                kind = 'RD_OTHER'
                replyToUs = true
            }
            decoded = handleAttributeMap(r, kind)
        } else {
            decoded = "unrecognised description prefix"
        }
    } catch (Exception e) {
        decoded = "PARSE ERROR ${e.class.simpleName}: ${e.message}"
        logWarn "${decoded} for: ${description}"
    }

    if (kind == 'RD_RSP' && description?.contains('cluster: 0000')) state.lastBasicRxAt = t
    state.kinds = (state.kinds ?: [:]) as Map
    state.kinds[kind] = ((state.kinds[kind] ?: 0) as int) + 1

    String line = "RX [+${fmtDt(dt)}] ${kind} ${description} => ${decoded}"
    addRing(kind, "+${fmtDt(dt)} ${description} => ${decoded}")
    if (settings.logFrames != false) logInfo line else logDebug line

    publishCheckin(t)
    if (!state.replay && kind == 'ANNCE') {
        // a new announce = a new association: anything still in flight was addressed to the old network address
        if (state.session) closeSession('new announce')
        List qq = (state.queue ?: []) as List
        qq.each { Map x -> if (x.status == 'sent') x.status = 'queued' }
        state.queue = qq
        if (settings.reconfigEveryAnnounce != false) queueAnnouncePlan()
        if (settings.windowProbe != false) {
            String nwkNow = state.nwk
            [[1, 14000L], [2, 17000L], [3, 20000L]].each { List pp ->
                runInMillis(pp[1] as long, 'livePing', [overwrite: false, data: [n: pp[0], a: t, nwk: nwkNow]])
            }
        }
    }
    if (!state.replay) {
        trackRun(t, kind, replyToUs)
        if (kind == 'REPORT') { noteReport(t); profileOnReport(t) }
    }

    // a device-initiated frame (not a reply to one of our requests) is a possible wake: flush leftovers
    if (!replyToUs && !state.session && hasPending()) {
        long since = t - ((state.lastSessionEnd ?: 0) as long)
        if (since >= MIN_SESSION_GAP_MS || kind == 'ANNCE') startSession(kind == 'ANNCE' ? 'ANNOUNCE' : 'FRAME')
    } else if (state.session) {
        state.session.lastRxAt = t
        if (replyToUs) state.session.replied = true
        pump()
    }
    return null
}

private String handleAnnounce(List data) {
    // Device_annce: seq, NWK(LE 2), IEEE(LE 8), capability
    if (data.size() < 12) return "annce (short) data=${data}"
    String nwk = (data[2].toString() + data[1].toString()).toUpperCase()
    String ieee = ((3..10).collect { data[it].toString() }.reverse()).join('').toUpperCase()
    String cap = data[11].toString()
    String prev = state.nwk
    state.annceCount = ((state.annceCount ?: 0) as int) + 1
    String note
    if (prev == null) {
        note = 'first announce'
    } else if (prev != nwk) {
        note = "NWK CHANGED ${prev} -> ${nwk} (rejoin)"
        List ch = (state.nwkChanges ?: []) as List
        ch << "${stamp(now())} ${prev}->${nwk}".toString()
        while (ch.size() > 20) ch.remove(0)
        state.nwkChanges = ch
        logInfo "Announce with a NEW network address ${nwk} (was ${prev}): the device has rejoined."
    } else {
        note = 'same NWK (announce only)'
    }
    state.nwk = nwk
    device.updateDataValue('ieee', ieee)
    return "annce nwk=${nwk} ieee=${ieee} cap=${cap} ${note}"
}

// ------------------------------------------------------------------ attributes

private Map rawReadAttrFallback(String description) {
    // read attr - raw: <DNI 4><EP 2><CLUSTER 4><SIZE/CMD 2><ATTR 4><TYPE-or-STATUS 2><VALUE...>
    def mt = (description =~ /read attr - raw: *([0-9A-Fa-f]{4})([0-9A-Fa-f]{2})([0-9A-Fa-f]{4})([0-9A-Fa-f]{2})([0-9A-Fa-f]{4})([0-9A-Fa-f]{2})([0-9A-Fa-f]*)/)
    if (!mt) return [:]
    String enc = mt[0][6]
    String le = mt[0][7]
    StringBuilder be = new StringBuilder()
    for (int i = le.length() - 2; i >= 0; i -= 2) be.append(le.substring(i, i + 2))
    Map r = [cluster: mt[0][3], command: '', attrId: mt[0][5], encoding: enc, value: be.toString(), fallback: true]
    return r
}

private String handleAttributeMap(Map r, String kind) {
    if (!r) return 'empty map'
    List<String> parts = []
    parts << handleOneAttr(r.cluster?.toString(), r.attrId?.toString(), r.value?.toString(), r.encoding?.toString(), r.status?.toString(), kind)
    List extra = (r.additionalAttrs ?: []) as List
    extra.each { Map a ->
        parts << handleOneAttr(r.cluster?.toString(), a.attrId?.toString(), a.value?.toString(), a.encoding?.toString(), a.status?.toString(), kind)
    }
    return parts.join(' | ')
}

private String handleOneAttr(String clusterRaw, String attrRaw, String value, String enc, String status, String kind) {
    String cluster = hex4(clusterRaw)
    String attr = hex4(attrRaw)
    String what = "${cluster}/${attr} enc=${enc} val=${value}${status ? ' status=' + status : ''}"
    // ack of our own read (also credited for reports of the same attribute, R6)
    boolean isError = (status != null && status != '00' && status != '0') || (enc == '86')
    ackRead(cluster, attr, !isError)

    if (isError) return "${what} -> ERROR/UNSUPPORTED"

    try {
        if (cluster == '0402' && attr == '0000') {
            int raw = signed16(value)
            if (raw == -32768) return "${what} -> temperature INVALID (0x8000)"
            BigDecimal c = new BigDecimal(raw).divide(100G, 2, RoundingMode.HALF_UP)
            def tv = convertTemperatureIfNeeded(c, 'C', 2)
            sendEvent(name: 'temperature', value: tv, unit: "°${location.temperatureScale}", isStateChange: true,
                      descriptionText: "${device.displayName} temperature is ${tv}°${location.temperatureScale} (raw ${raw}, ${kind})")
            return "${what} -> ${c} C"
        }
        if (cluster == '0405' && attr == '0000') {
            int raw = Integer.parseInt(value, 16)
            if (raw == 0xFFFF) return "${what} -> humidity INVALID (0xFFFF)"
            BigDecimal h = new BigDecimal(raw).divide(100G, 2, RoundingMode.HALF_UP)
            sendEvent(name: 'humidity', value: h, unit: '%rh', isStateChange: true,
                      descriptionText: "${device.displayName} humidity is ${h}% (raw ${raw}, ${kind})")
            return "${what} -> ${h} %RH"
        }
        if (cluster == '0001' && attr == '0021') {
            int raw = Integer.parseInt(value, 16)
            if (raw == 0xFF) return "${what} -> battery INVALID (0xFF)"
            int pct = Math.max(0, Math.min(100, (int) Math.round(raw / 2.0d)))
            sendEvent(name: 'battery', value: pct, unit: '%', isStateChange: true,
                      descriptionText: "${device.displayName} battery is ${pct}% (raw ${raw}, ${kind})")
            return "${what} -> ${pct} %"
        }
        if (cluster == '0001' && attr == '0020') {
            int raw = Integer.parseInt(value, 16)
            BigDecimal v = new BigDecimal(raw).divide(10G, 1, RoundingMode.HALF_UP)
            sendEvent(name: 'batteryVoltage', value: v, unit: 'V',
                      descriptionText: "${device.displayName} battery voltage is ${v} V (raw ${raw})")
            return "${what} -> ${v} V"
        }
        if (cluster == '0000') {
            Map names = ['0000': 'zclVersion', '0001': 'appVersion', '0002': 'stackVersion', '0003': 'hwVersion',
                         '0004': 'mfrName', '0005': 'modelId', '0006': 'dateCode', '0007': 'powerSource', '4000': 'swBuildId']
            String key = names[attr]
            if (key) {
                String shown = value
                if (enc == '42' && value && value ==~ /^[0-9A-Fa-f]+$/ && value.length() % 2 == 0) {
                    String dec = hexToText(value)
                    if (dec) shown = dec
                }
                device.updateDataValue(key, shown)
                return "${what} -> data ${key}=${shown}"
            }
        }
    } catch (Exception e) {
        return "${what} -> DECODE ERROR ${e.class.simpleName}: ${e.message}"
    }
    return what
}

private int signed16(String hex) {
    int v = Integer.parseInt(hex, 16)
    return v > 32767 ? v - 65536 : v
}

private String hexToText(String hex) {
    StringBuilder sb = new StringBuilder()
    for (int i = 0; i + 1 < hex.length(); i += 2) {
        int c = Integer.parseInt(hex.substring(i, i + 2), 16)
        if (c < 32 || c > 126) return null
        sb.append((char) c)
    }
    return sb.toString()
}

private String hex4(def v) {
    String s = (v ?: '').toString().toUpperCase().replace('0X', '')
    while (s.length() < 4) s = '0' + s
    return s
}

// ------------------------------------------------------------------ queue and session

/** Items are specs; the command string is built at send time because the DNI can change after a rejoin. */
private void ensureInit() {
    if (state.initDone) return
    state.initDone = true
    state.queue = []
    state.nextId = 1
    state.ring = []
    buildJoinQueue()
}

private void buildJoinQueue() {
    state.queue = []
    // 1 binds so any default reports can reach the hub
    ['0402', '0405', '0001'].each { String c -> queueItem('bind', c, null) }
    // 2 identity
    [['0000', '0005'], ['0000', '0004'], ['0000', '4000'], ['0000', '0000'], ['0000', '0001']].each { queueItem('read', it[0], it[1]) }
    // 3 what reporting the device holds now (read only)
    [['0402', '0000'], ['0405', '0000'], ['0001', '0021']].each { queueItem('rcr', it[0], it[1]) }
    // 4 current values
    [['0001', '0021'], ['0001', '0020'], ['0402', '0000'], ['0405', '0000']].each { queueItem('read', it[0], it[1]) }
    // 5 remaining Basic attributes
    [['0000', '0007'], ['0000', '0006'], ['0000', '0003'], ['0000', '0002']].each { queueItem('read', it[0], it[1]) }
}

private void queueItem(String kind, String cluster, String attr) {
    List q = (state.queue ?: []) as List
    if (q.any { Map x -> x.status == 'queued' && x.kind == kind && x.cluster == cluster && x.attr == attr }) return
    int id = ((state.nextId ?: 1) as int)
    state.nextId = id + 1
    q << [id: id, kind: kind, cluster: cluster, attr: attr, status: 'queued', tries: 0, charged: 0, lastSession: 0, sentAt: 0L, ackedAt: 0L]
    state.queue = q
}

private void queueFront(String kind, String cluster, String attr, Map p) {
    List q = (state.queue ?: []) as List
    q.removeAll { Map x -> x.status == 'queued' && x.kind == kind && x.cluster == cluster && x.attr == attr }
    int id = ((state.nextId ?: 1) as int)
    state.nextId = id + 1
    q.add(0, [id: id, kind: kind, cluster: cluster, attr: attr, p: p, status: 'queued', tries: 0, charged: 0, lastSession: 0, sentAt: 0L, ackedAt: 0L])
    state.queue = q
}

private boolean hasPending() {
    return ((state.queue ?: []) as List).any { it.status == 'queued' && ((it.charged ?: 0) as int) < MAX_TRIES }
}

private List outstandingItems() {
    return ((state.queue ?: []) as List).findAll { it.status == 'sent' }
}

void startSession(String trigger) {
    ensureInit()
    if (state.session) {
        Map old = state.session
        if ((now() - ((old.lastRxAt ?: old.openedAt) as long)) > SESSION_MAX_MS && (now() - (old.openedAt as long)) > SESSION_MAX_MS) closeSession('stale')
        else return
    }
    if (state.replay) return
    if (!hasPending()) {
        logDebug "Session trigger ${trigger}: nothing pending."
        return
    }
    long t = now()
    int sid = ((state.sessionCount ?: 0) as int) + 1
    state.sessionCount = sid
    state.session = [replied: false, id: sid, trigger: trigger, openedAt: t, lastRxAt: t, lastSendAt: 0L, sent: 0, acked: 0, lastAckAt: 0L]
    addRing('SESSION', "S${sid} open trigger=${trigger} pending=${((state.queue ?: []) as List).count { it.status != 'acked' && it.status != 'failed' }}")
    logInfo "Session S${sid} opened (${trigger}); sending the pending items one at a time."
    pump()
    runInMillis(TICK_MS, 'sessionTick', [overwrite: true])
}

void sessionTick() {
    try {
        sessionTickBody()
    } catch (Exception e) {
        logWarn "sessionTick error ${e.class.simpleName}: ${e.message}"
        closeSession('error')
    }
    if (state.session) runInMillis(TICK_MS, 'sessionTick', [overwrite: true])
}

private void sessionTickBody() {
    Map s = state.session
    if (!s) return
    long t = now()
    // expire unanswered items
    ((state.queue ?: []) as List).each { Map it ->
        if (it.status == 'sent' && (t - (it.sentAt as long)) > ITEM_TIMEOUT_MS) {
            if (s.replied) it.charged = ((it.charged ?: 0) as int) + 1
            it.status = ((it.charged ?: 0) as int) >= MAX_TRIES ? 'failed' : 'queued'
            addRing('TIMEOUT', "item ${it.id} ${it.kind} ${it.cluster}/${it.attr ?: ''} tries=${it.tries} -> ${it.status}")
            logInfo "No answer to item ${it.id} (${it.kind} ${it.cluster}/${it.attr ?: ''}) after ${ITEM_TIMEOUT_MS / 1000} s; try ${it.tries} of ${MAX_TRIES}."
        }
    }
    pump()
    int out = outstandingItems().size()
    long age = t - (s.openedAt as long)
    boolean pendingLeft = hasPending()
    if (!pendingLeft && out == 0) {
        closeSession('complete')
    } else if (age > SESSION_MAX_MS) {
        closeSession('max-time')
    } else if (age > SEND_BUDGET_MS && out == 0 && (t - (s.lastRxAt as long)) > SESSION_GAP_MS) {
        closeSession('window-over')
    } else if (age > SEND_BUDGET_MS && out == 0 && pendingLeft) {
        // budget over, nothing outstanding: stay open a little for late frames, then close on the idle gap above
    }
}

private void pump() {
    Map s = state.session
    if (!s || state.replay) return
    long t = now()
    if ((t - (s.openedAt as long)) > SEND_BUDGET_MS) return
    int out = outstandingItems().size()
    if (out >= MAX_OUTSTANDING) return
    long sinceSend = t - ((s.lastSendAt ?: 0) as long)
    if (sinceSend < SETTLE_MS) return
    if (out > 0 && sinceSend < RELEASE_AFTER_MS) return
    boolean bindOut = outstandingItems().any { it.kind == 'bind' }
    Map next = ((state.queue ?: []) as List).find { Map it ->
        it.status == 'queued' && it.lastSession != s.id && ((it.charged ?: 0) as int) < MAX_TRIES && !(it.kind == 'bind' && bindOut)
    }
    if (!next) return
    sendItem(next, s)
}

private void sendItem(Map it, Map s) {
    long t = now()
    String cmd
    try {
        cmd = buildCmd(it)
    } catch (Exception e) {
        it.status = 'failed'
        addRing('BLOCK', "item ${it.id} ${e.message}")
        logWarn "Item ${it.id} not sent: ${e.message}"
        return
    }
    it.status = 'sent'
    it.sentAt = t
    it.tries = ((it.tries ?: 0) as int) + 1
    it.lastSession = s.id
    s.lastSendAt = t
    s.sent = ((s.sent ?: 0) as int) + 1
    String note = "TX item ${it.id} ${it.kind} ${it.cluster}/${it.attr ?: ''} try ${it.tries} S${s.id} +${fmtDt(t - (s.openedAt as long))}: ${cmd}"
    addRing('TX', note)
    logInfo note
    if (state.replay) {
        logInfo '(replay mode: command not sent)'
    } else {
        sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZIGBEE))
    }
    publishConfigStatus()
    if (it.noAck && !state.replay) { it.status = 'done'; addRing('TXDONE', "item ${it.id} ${it.kind} sent, no reply expected"); publishConfigStatus() }
}

private String buildCmd(Map it) {
    int cl = Integer.parseInt(it.cluster as String, 16)
    if (!device.zigbeeId) throw new IllegalStateException('device.zigbeeId not available')
    if (it.kind == 'bind') {
        // proven syntax (22 SUCCESS in the unit-A logs): the device's own IEEE in the first braces, empty second braces
        return "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x${it.cluster} {${device.zigbeeId}} {}".toString()
    }
    if (it.kind == 'mbind') {
        // ZDO Mgmt_Bind_req (0x0033): [seq, startIndex]; profile 0x0000, endpoints 0
        int seq = ((((state.zdoSeq ?: 0x40) as int) + 1) & 0xFF)
        state.zdoSeq = seq
        return String.format('he raw 0x%s 0 0 0x0033 {%02X 00} {0x0000}', device.deviceNetworkId, seq)
    }
    if (it.kind == 'disc') {
        // ZCL Discover Attributes (0x0C): start attribute 0x0000, up to 255 attributes. Same 'he raw' form as the proven reads.
        return "he raw 0x${device.deviceNetworkId} 1 0x01 0x${it.cluster} {10 00 0C 00 00 FF}".toString()
    }
    if (it.kind == 'ident') {
        // Identify cluster command 0x00, IdentifyTime LE; frame control 0x11 = cluster specific, default response disabled
        int secs = (((it.p as Map).seconds) as int)
        if (secs < 1 || secs > 30) throw new IllegalStateException("Identify seconds ${secs} out of range")
        return String.format('he raw 0x%s 1 0x01 0x0003 {11 00 00 %02X 00}', device.deviceNetworkId, secs)
    }
    int at = Integer.parseInt(it.attr as String, 16)
    if (it.kind == 'cr') {
        // allow-list: Configure Reporting only for Temperature / Humidity MeasuredValue and BatteryPercentageRemaining
        Map okType = ['0402/0000': 0x29, '0405/0000': 0x21, '0001/0021': 0x20]
        String key = "${it.cluster}/${it.attr}".toString()
        if (!okType.containsKey(key)) throw new IllegalStateException("CR not allowed for ${key}")
        Map pr = it.p as Map
        if ((pr.type as int) != (okType[key] as int)) throw new IllegalStateException("CR data type ${pr.type} does not match ${key}")
        List<String> crs = zigbee.configureReporting(cl, at, (pr.type as int), (pr.min as int), (pr.max as int), (pr.delta as int), [destEndpoint: 0x01], 0)
        String c = crs.find { it.toString().startsWith('he cr ') }
        if (!c) throw new IllegalStateException("no 'he cr' string in builder output: ${crs}")
        return c.toString()
    }
    List<String> cmds
    if (it.kind == 'read') {
        cmds = zigbee.readAttribute(cl, at, [destEndpoint: 0x01], 0)          // delay 0 = no 'delay' strings (Zigbee API docs)
    } else {
        cmds = zigbee.reportingConfiguration(cl, at, [destEndpoint: 0x01], 0) // read-only: Read Reporting Configuration
    }
    List<String> real = cmds.findAll { !it.toString().startsWith('delay') }
    String out = real[0].toString()
    // observed on hardware: both builders emit 'he raw <dni> 1 <ep> <cluster> {<ZCL frame>}'; ZCL frame = 10 <seq> <cmd> ...
    boolean ok = out.startsWith('zdo bind ') ||
                 (it.kind == 'read' && out.startsWith('he raw ') && out.contains('{10 00 00 ')) ||
                 (it.kind == 'rcr' && out.startsWith('he raw ') && out.contains('{10 00 08 '))
    if (!ok || out.startsWith('he cr') || out.startsWith('he wattr')) throw new IllegalStateException("blocked unexpected command form: ${out}")
    return out
}

// ---- acknowledgements (content matched, any time, R6) ----

private void ackBind(String status) {
    Map it = outstandingItems().find { it.kind == 'bind' }
    if (!it) {
        // late or duplicate answer: credit the oldest bind that is not acked yet
        it = ((state.queue ?: []) as List).find { it.kind == 'bind' && it.status == 'queued' && ((it.tries ?: 0) as int) > 0 }
    }
    if (it) markAcked(it, "bind_rsp status ${status}", status == '00')
}

private void ackRead(String cluster, String attr, boolean ok = true) {
    Map it = ((state.queue ?: []) as List).find { it.kind == 'read' && it.cluster == cluster && it.attr == attr && (it.status == 'sent' || (it.status == 'queued' && ((it.tries ?: 0) as int) > 0)) }
    if (it) markAcked(it, ok ? 'read answer' : 'read answer (error/unsupported)', ok)
}

private void ackCr(String cluster, List data) {
    Map it = ((state.queue ?: []) as List).find { it.kind == 'cr' && it.cluster == cluster && (it.status == 'sent' || (it.status == 'queued' && ((it.tries ?: 0) as int) > 0)) }
    boolean ok = data && data.every { it.toString() == '00' }
    if (it) {
        markAcked(it, "cr_rsp ${data}", ok)
        if (ok && cluster != '0001' && it.p && !((it.p as Map).raw)) state.expectedMaxS = (((it.p as Map).max) as int)
    }
}

private void failOutstanding(String cluster, String cmdId, String why) {
    Map kindOf = ['06': 'cr', '08': 'rcr', '00': 'read', '0C': 'disc']
    String k = kindOf[cmdId?.toString()?.toUpperCase()]
    Map it = outstandingItems().find { it.cluster == cluster && (k == null || it.kind == k) }
    if (it) markAcked(it, why, false)
}

private void ackByCluster(String kind, String cluster, boolean ok = true) {
    Map it = ((state.queue ?: []) as List).find { it.kind == kind && it.cluster == cluster && (it.status == 'sent' || (it.status == 'queued' && ((it.tries ?: 0) as int) > 0)) }
    if (it) {
        markAcked(it, ok ? "${kind} answer" : "${kind} answer (error status)", ok)
    }
}

private void markAcked(Map it, String via, boolean ok) {
    long t = now()
    it.status = ok ? 'acked' : 'failed'
    it.ackedAt = t
    Map s = state.session
    if (s) {
        s.acked = ((s.acked ?: 0) as int) + 1
        s.lastAckAt = t
    }
    addRing('ACK', "item ${it.id} ${it.kind} ${it.cluster}/${it.attr ?: ''} via ${via} after ${fmtDt(t - ((it.sentAt ?: t) as long))}")
    publishConfigStatus()
    if (s) pump()
}

private void closeSession(String why) {
    Map s = state.session
    if (!s) return
    long t = now()
    String summary = "S${s.id} closed (${why}): sent ${s.sent}, acked ${s.acked}, last ack ${s.lastAckAt ? '+' + fmtDt((s.lastAckAt as long) - (s.openedAt as long)) : 'none'} after open, open ${fmtDt(t - (s.openedAt as long))}"
    addRing('SESSION', summary)
    logInfo summary
    List hist = (state.sessions ?: []) as List
    hist << [id: s.id, trigger: s.trigger, why: why, sent: s.sent, acked: s.acked,
             lastAckMs: s.lastAckAt ? ((s.lastAckAt as long) - (s.openedAt as long)) : null, openMs: t - (s.openedAt as long), at: stamp(t)]
    while (hist.size() > 30) hist.remove(0)
    state.sessions = hist
    state.session = null
    state.lastSessionEnd = t
    unschedule('sessionTick')
    publishConfigStatus()
}

// ------------------------------------------------------------------ status and housekeeping

private void publishConfigStatus() {
    List q = (state.queue ?: []) as List
    int total = q.size()
    int done = q.count { it.status == 'acked' || it.status == 'done' }
    int failed = q.count { it.status == 'failed' }
    String txt = (done + failed == total) ? "Probe queue complete: ${done} acked, ${failed} failed (of ${total})" : "Probe queue ${done}/${total} acked (${failed} failed)"
    if (device.currentValue('configStatus') != txt) sendEvent(name: 'configStatus', value: txt)
}

private void publishCheckin(long t) {
    long last = ((state.lastCheckinEvent ?: 0) as long)
    if (t - last >= CHECKIN_THROTTLE_MS) {
        state.lastCheckinEvent = t
        sendEvent(name: 'lastCheckin', value: new Date(t).format('yyyy-MM-dd HH:mm:ss', location.timeZone))
    }
}

void summary() {
    Map k = (state.kinds ?: [:]) as Map
    logInfo "SUMMARY v${DRIVER_VERSION}: rx=${state.rxCount ?: 0} first=${state.firstRxAt ? stamp(state.firstRxAt as long) : '-'} last=${state.lastRxAt ? stamp(state.lastRxAt as long) : '-'} kinds=${k} annces=${state.annceCount ?: 0} nwk=${state.nwk} changes=${state.nwkChanges ?: []}"
    List q = (state.queue ?: []) as List
    logInfo "SUMMARY queue: ${q.collect { "${it.id}:${it.kind}:${it.cluster}/${it.attr ?: ''}=${it.status}(${it.tries})" }.join(' ')}"
    logInfo "SUMMARY sessions: ${state.sessions ?: []}"
    logInfo "SUMMARY reports=${state.reportCount ?: 0} outOfRunFrames=${state.outOfRun ?: 0} expectedMax=${state.expectedMaxS ?: FACTORY_MAX_S}s runs=${state.runCount ?: 0}"
}

void runs() {
    List h = (state.runs ?: []) as List
    logInfo "RUNS (${h.size()} closed, ${state.run ? 'one open' : 'none open'}):"
    h.each { logInfo "RUN ${it}" }
    if (state.run) logInfo "RUN open ${state.run}"
}

void dumpRing(def n) {
    int cnt = Math.max(1, Math.min(RING_MAX, ((n ?: 40) as Number).intValue()))
    List ring = (state.ring ?: []) as List
    List tail = ring.size() > cnt ? ring.subList(ring.size() - cnt, ring.size()) : ring
    logInfo "RING (last ${tail.size()} of ${ring.size()}):"
    tail.each { logInfo "RING ${it}" }
}

void replayFrame(String frame) {
    // test hook: feed a recorded description string through parse() without sending anything
    state.replay = true
    try {
        parse(frame)
    } finally {
        state.replay = false
    }
}

// ------------------------------------------------------------------ rejoin runs, reports, quiet-time watch

private String decodeMgmtBind(List d) {
    // Mgmt_Bind_rsp: seq, status, total, startIndex, count, entries[srcIEEE 8, srcEP 1, cluster 2, mode 1, dst...]
    try {
        if (d.size() < 5) return "mgmt_bind_rsp (short) data=${d}"
        String st = d[1].toString()
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
        String hub = ''
        try { hub = location.hub?.zigbeeEui?.toString()?.toUpperCase() ?: '' } catch (Exception ignored) { hub = '' }
        String summary = "mgmt_bind_rsp status=${st} total=${total} listed=${cnt} entries=${out}"
        if (st == '00') summary += (total == 0 ? ' -> BINDING TABLE EMPTY' : (hub && out.any { it.contains(hub) } ? ' -> includes the hub' : ''))
        return summary.toString()
    } catch (Exception e) {
        return "mgmt_bind_rsp decode error ${e.message} data=${d}"
    }
}

private String decodeRcr(String cluster, List data) {
    // Read Reporting Configuration Response: [status, direction, attr LE 2, type, min LE 2, max LE 2, change...]
    try {
        if (data.size() < 10 || data[0].toString() != '00' || data[1].toString() != '00') return ''
        int mn = Integer.parseInt(data[6].toString() + data[5].toString(), 16)
        int mx = Integer.parseInt(data[8].toString() + data[7].toString(), 16)
        // remember what the device really holds (temperature / humidity) for the quiet-time watch
        if (cluster == '0402' || cluster == '0405') state.expectedMaxS = mx
        return " -> holds min ${mn} s, max ${mx} s, change raw 0x${data.drop(9).reverse().join('')}".toString()
    } catch (Exception e) {
        return ' (rcr decode failed)'
    }
}

private String decodeDiscover(String cluster, List data) {
    // Discover Attributes Response: [discoveryComplete, (attr LE 2, type)*]
    if (!data) return "disc_rsp cluster=${cluster} (empty)"
    List<String> found = []
    for (int i = 1; i + 2 < data.size(); i += 3) {
        found << "${(data[i + 1].toString() + data[i].toString()).toUpperCase()}:t${data[i + 2]}".toString()
    }
    return "disc_rsp cluster=${cluster} complete=${data[0]}${data[0].toString() == '00' ? ' INCOMPLETE (more attributes exist; list truncated)' : ''} attrs=${found}"
}

private void trackRun(long t, String kind, boolean replyToUs) {
    closeRunIfIdle(t)
    Map r = state.run
    if (kind == 'ANNCE') {
        if (!r) {
            int id = ((state.runCount ?: 0) as int) + 1
            state.runCount = id
            r = [id: id, startAt: t, lastAt: t, annces: 1, reports: 0, frames: 1, lastAnnceAt: t, gaps: []]
            logInfo "RUN R${id} started (first announce after quiet time): the sensor's radio is awake. Rejoin runs last about 4 minutes."
            addRing('RUN', "R${id} start")
            if (settings.autoRefreshOnRun != false) {
                queueItem('read', '0402', '0000')
                queueItem('read', '0405', '0000')
                queueItem('read', '0001', '0021')
            }
        } else {
            r.annces = ((r.annces ?: 0) as int) + 1
            long gap = t - ((r.lastAnnceAt ?: t) as long)
            List g = (r.gaps ?: []) as List
            g << (int) (gap / 100L) / 10.0
            r.gaps = g
            r.lastAnnceAt = t
            logInfo "RUN R${r.id}: announce #${r.annces}, ${fmtDt(gap)} after the previous announce."
            r.lastAt = t
            r.frames = ((r.frames ?: 0) as int) + 1
        }
        state.run = r
    } else if (r) {
        r.frames = ((r.frames ?: 0) as int) + 1
        r.lastAt = t
        if (kind == 'REPORT') r.reports = ((r.reports ?: 0) as int) + 1
        state.run = r
    } else {
        // a frame with no rejoin run around it: this is what we are hunting (spontaneous payloads)
        if (!replyToUs) state.outOfRun = ((state.outOfRun ?: 0) as int) + 1
        if (replyToUs) logInfo "late reply (${kind}) outside a run"
        else logWarn "OUT-OF-RUN frame #${state.outOfRun}: ${kind} arrived with no rejoin run active (no announce in the last ${(int) (RUN_GAP_MS / 1000)} s)."
        addRing('OUTRUN', "${kind} #${state.outOfRun}")
    }
}

private void closeRunIfIdle(long t) {
    Map r = state.run
    if (r && (t - (r.lastAt as long)) > RUN_GAP_MS) {
        long durS = (long) (((r.lastAt as long) - (r.startAt as long)) / 1000L)
        List h = (state.runs ?: []) as List
        h << [id: r.id, start: stamp(r.startAt as long), durationS: durS, annces: r.annces, reports: r.reports, frames: r.frames, gapsS: r.gaps]
        while (h.size() > RUNS_MAX) h.remove(0)
        state.runs = h
        logInfo "RUN R${r.id} ended: ${durS} s from first to last frame, ${r.annces} announces (gaps ${r.gaps ?: []} s), ${r.reports} reports, ${r.frames} frames."
        addRing('RUN', "R${r.id} end ${durS}s annces=${r.annces} reports=${r.reports}")
        state.run = null
    }
}

private void noteReport(long t) {
    state.lastReportAt = t
    state.reportCount = ((state.reportCount ?: 0) as int) + 1
    state.overdueWarned = false
    sendEvent(name: 'lastReport', value: new Date(t).format('yyyy-MM-dd HH:mm:ss', location.timeZone))
}

void watchTick() {
    try {
        watchBody()
    } catch (Exception e) {
        logWarn "watchTick error ${e.class.simpleName}: ${e.message}"
    }
}

private void watchBody() {
    long t = now()
    closeRunIfIdle(t)
    long lastRx = ((state.lastRxAt ?: 0) as long)
    if (!lastRx) return
    long lastRep = ((state.lastReportAt ?: 0) as long)
    long expMaxS = ((state.expectedMaxS ?: FACTORY_MAX_S) as long)
    int quietMin = (int) ((t - lastRx) / 60000L)
    int repMin = lastRep ? (int) ((t - lastRep) / 60000L) : -1
    boolean inRun = state.run != null
    long ref = Math.max(lastRep, lastRx)   // v0.4: measure from the last frame (report or not), not from the first frame ever
    boolean overdue = !inRun && (t - ref) > (long) (expMaxS * 1500L) + 60000L
    String txt = "${inRun ? 'rejoin run active' : 'no run'}; quiet ${quietMin - (quietMin % 5)}+ min; last report ${repMin < 0 ? 'never' : (repMin - (repMin % 5)) + '+ min ago'}; configured max ${expMaxS} s${overdue ? '; OVERDUE (no spontaneous report)' : ''}".toString()
    if (device.currentValue('watch') != txt) sendEvent(name: 'watch', value: txt)
    if (overdue && !state.overdueWarned) {
        state.overdueWarned = true
        logWarn "WATCH: no frame for ${(int) ((t - ref) / 60000L)} min although the device is configured to report at least every ${expMaxS} s."
    }
}

private void addRing(String kind, String text) {
    List ring = (state.ring ?: []) as List
    String ln = "${stamp(now())} ${kind} ${text}".toString()
    if (ln.length() > 200) ln = ln.substring(0, 200)
    ring << ln
    while (ring.size() > RING_MAX) ring.remove(0)
    state.ring = ring
}

private String stamp(long ms) {
    return new Date(ms).format('MM-dd HH:mm:ss.SSS', location.timeZone)
}

private String fmtDt(long ms) {
    return String.format('%.3fs', ms / 1000.0d)
}

private void logInfo(String msg)  { log.info  "${device.displayName}: ${msg}" }
private void logWarn(String msg)  { log.warn  "${device.displayName}: ${msg}" }
private void logDebug(String msg) { if (settings.logEnable) log.debug "${device.displayName}: ${msg}" }
