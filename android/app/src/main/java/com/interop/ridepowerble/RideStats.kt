package com.interop.ridepowerble

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/** Summary of one recorded ride, worked out from its CSV log. */
data class RideSummary(
    val file: File,
    val start: Date,                 // when the ride started
    val durationSec: Int,            // first to last line
    val movingSec: Int,              // time actually moving
    val miles: Double,
    val avgMph: Double,              // average while moving
    val topMph: Double,
    val battStart: Double?,          // % at the start / end (null if the bike wasn't connected)
    val battEnd: Double?,
    val voltsStart: Double?,
    val voltsEnd: Double?,
    val wh: Double?,                 // energy used (exact from logged power, else estimated from %)
    val whExact: Boolean,
    val peakW: Double?,
    val route: List<Router.Pt>,
)

object RideStats {
    private val nameFmt = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US)

    /** Parses a ride log; [capAh] and [nominalV] are used to estimate energy for older logs without power. */
    fun summarize(f: File, capAh: Double = 30.0, nominalV: Double = 51.8): RideSummary? {
        val lines = try { f.readLines() } catch (e: Exception) { return null }
        if (lines.size < 3) return null
        val h = lines[0].split(","); fun ix(n: String) = h.indexOf(n)
        val iT = ix("time"); val iLat = ix("lat"); val iLon = ix("lon"); val iMph = ix("gps_mph"); val iAcc = ix("gps_acc_m")
        val iV = ix("battery_v"); val iPct = ix("battery_pct"); val iW = ix("power_w")
        val day = try { nameFmt.parse(f.name.removePrefix("ride-").removeSuffix(".csv")) } catch (e: Exception) { null } ?: Date(f.lastModified())
        val dayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(day)

        var firstT = -1; var lastT = -1; var prevT = -1
        var moving = 0; var miles = 0.0; var top = 0.0
        var prevLat = Double.NaN; var prevLon = Double.NaN
        var bS: Double? = null; var bE: Double? = null; var vS: Double? = null; var vE: Double? = null
        var whSum = 0.0; var haveW = false; var peak = 0.0; var connected = 0
        val route = ArrayList<Router.Pt>()
        for (l in lines.drop(1)) {
            val c = l.split(","); if (c.size < h.size - 2) continue
            fun d(i: Int) = if (i >= 0 && i < c.size) c[i].toDoubleOrNull() else null
            val tp = c[iT].split(":"); if (tp.size != 3) continue
            val t = tp[0].toInt() * 3600 + tp[1].toInt() * 60 + tp[2].toInt()
            if (firstT < 0) firstT = t
            val dt = if (prevT < 0) 0 else (t - prevT).coerceIn(0, 10)
            prevT = t; lastT = t
            val mph = d(iMph); val acc = d(iAcc); val lat = d(iLat); val lon = d(iLon)
            if (mph != null && mph > 2.0) moving += dt
            if (mph != null && mph < 60 && (acc ?: 99.0) < 25) top = max(top, mph)
            if (lat != null && lon != null && (acc ?: 99.0) < 30) {
                if (!prevLat.isNaN()) {
                    val m = Geo.dist(prevLat, prevLon, lat, lon)
                    if (m < 250) miles += m / 1609.34          // skip GPS jumps
                }
                prevLat = lat; prevLon = lon
                if (route.isEmpty() || Geo.dist(route.last().lat, route.last().lon, lat, lon) > 8) route += Router.Pt(lat, lon)
            }
            // battery: only from lines with live bike data, skipping the first few (the % shown before
            // connecting can be stale until the first settled reading)
            val v = d(iV)
            if (v != null && v > 20) {
                connected++
                if (vS == null) vS = v; vE = v
                d(iPct)?.let { if (it >= 0 && connected > 5) { if (bS == null) bS = it; bE = it } }
            }
            d(iW)?.let { haveW = true; whSum += it * dt / 3600.0; peak = max(peak, it) }
        }
        if (firstT < 0) return null
        val start = try { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse("$dayStr ${lines[1].split(",")[iT]}") } catch (e: Exception) { null } ?: day
        val dur = (if (lastT >= firstT) lastT - firstT else lastT + 86400 - firstT)
        val used = if (bS != null && bE != null) max(0.0, bS!! - bE!!) else null
        val wh = if (haveW) whSum else used?.let { it / 100.0 * capAh * nominalV }
        return RideSummary(f, start, dur, moving, miles, if (moving > 0) miles / (moving / 3600.0) else 0.0, top,
            bS, bE, vS, vE, wh, haveW, if (haveW) peak else null, route)
    }

    fun fmtDuration(sec: Int): String = if (sec >= 3600) "${sec / 3600} h ${(sec % 3600) / 60} min" else "${max(1, sec / 60)} min"
}
