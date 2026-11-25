package com.interop.ridepowerble

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Adaptive state-of-charge (0–100%) estimator for the bike battery.
 *
 * The controller reports pack VOLTAGE only (no live current), so raw voltage swings
 * wildly under acceleration (sag). This estimator produces a CLEAN, stable percentage:
 *
 *  - Detects "under load" (throttle applied / accelerating) and HOLDS the percentage
 *    there, so a 58.8V→52V sag during a hard pull barely moves the number.
 *  - Only corrects the percentage from near-rest voltage (coasting / stopped), where
 *    measured voltage ≈ open-circuit voltage, using a per-cell Li-ion OCV→SoC curve.
 *  - LEARNS the pack: series-cell count and the real full/empty voltages seen over
 *    rides, and persists the running estimate so it survives app restarts. The more
 *    you ride (especially to full and down low), the better the endpoints get.
 *
 * It is not lab-accurate (that needs a current shunt the controller doesn't expose),
 * but it is far steadier and more trustworthy than the raw voltage reading.
 */
class BatteryEstimator(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("battery", Context.MODE_PRIVATE)

    // per-cell open-circuit voltage -> SoC% (typical Li-ion NMC discharge curve)
    private val ocv = arrayOf(
        4.20 to 100.0, 4.15 to 95.0, 4.11 to 90.0, 4.08 to 85.0, 4.02 to 80.0,
        3.98 to 75.0, 3.95 to 70.0, 3.91 to 65.0, 3.87 to 60.0, 3.85 to 55.0,
        3.84 to 50.0, 3.82 to 45.0, 3.80 to 40.0, 3.79 to 35.0, 3.77 to 30.0,
        3.75 to 25.0, 3.73 to 20.0, 3.71 to 16.0, 3.69 to 13.0, 3.61 to 10.0,
        3.50 to 6.0, 3.40 to 3.0, 3.00 to 0.0
    )

    // pack voltage set by the rider in Settings (0 = auto-detect from voltage)
    private val fixedSeries = ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getInt("packSeries", 0)
    private var series = if (fixedSeries > 0) fixedSeries else prefs.getInt("series", 0)   // 0 = not learned yet
    private var maxRestV = prefs.getFloat("maxRestV", 0f).toDouble()
    private var minRestV = prefs.getFloat("minRestV", 999f).toDouble()
    private var soc = prefs.getFloat("soc", -1f).toDouble()        // -1 = uninitialised
    private var lastSpeed = 0
    private var lastSaveMs = 0L

    val learnedSeries get() = series
    /** Last known estimate (persisted), or -1 if never measured. */
    val current: Double get() = soc
    val fullVolts get() = if (series > 0) series * 4.2 else 0.0

    /** Feed one realtime sample; returns the clean SoC percentage (0–100), or -1 if unknown. */
    fun update(batteryV: Double, throttleV: Double, speedRpm: Int): Double {
        if (batteryV < 5.0) return if (soc >= 0) soc else -1.0   // ignore junk / disconnected

        val accel = speedRpm - lastSpeed
        lastSpeed = speedRpm
        // "under load": throttle applied, or clearly accelerating -> voltage is sagging
        val underLoad = throttleV > 0.20 || accel > 30

        // learn series-cell count from the highest near-rest voltage ever seen
        if (!underLoad && batteryV > maxRestV) { maxRestV = batteryV }
        if (!underLoad && batteryV < minRestV && batteryV > 10.0) { minRestV = batteryV }
        if (fixedSeries > 0) series = fixedSeries
        else if (series == 0) {
            val basis = if (maxRestV > 0) maxRestV else batteryV
            series = (basis / 4.2).roundToInt().coerceIn(1, 30)
        }
        // if a higher rest voltage appears, re-derive series (handles wrong first guess)
        if (fixedSeries == 0 && maxRestV > 0) {
            val s = (maxRestV / 4.2).roundToInt().coerceIn(1, 30)
            if (s > series) series = s
        }

        val perCell = batteryV / series
        val target = ocvToSoc(perCell)

        soc = when {
            soc < 0 -> target                                   // first ever sample
            underLoad -> (soc - 0.015).coerceAtLeast(0.0)       // hold (tiny drain) during sag
            else -> soc + (target - soc) * 0.03                 // slow correction at rest/coast
        }.coerceIn(0.0, 100.0)

        maybeSave()
        return soc
    }

    private fun ocvToSoc(perCell: Double): Double {
        if (perCell >= ocv.first().first) return 100.0
        if (perCell <= ocv.last().first) return 0.0
        for (i in 0 until ocv.size - 1) {
            val (vHi, sHi) = ocv[i]
            val (vLo, sLo) = ocv[i + 1]
            if (perCell <= vHi && perCell >= vLo) {
                val t = (perCell - vLo) / (vHi - vLo)
                return sLo + t * (sHi - sLo)
            }
        }
        return 50.0
    }

    private fun maybeSave() {
        val now = System.currentTimeMillis()
        if (now - lastSaveMs < 20000) return
        lastSaveMs = now
        prefs.edit()
            .putInt("series", series)
            .putFloat("maxRestV", maxRestV.toFloat())
            .putFloat("minRestV", if (minRestV < 900) minRestV.toFloat() else 999f)
            .putFloat("soc", soc.toFloat())
            .apply()
    }

    fun save() { lastSaveMs = 0; maybeSave() }

    /** One-line description of what it has learned, for diagnostics. */
    fun summary(): String {
        val full = if (series > 0) "%.1fV".format(series * 4.2) else "?"
        val seenHi = if (maxRestV > 0) "%.1fV".format(maxRestV) else "–"
        val seenLo = if (minRestV < 900) "%.1fV".format(minRestV) else "–"
        return "pack ${if (series>0) "${series}S (full≈$full)" else "learning…"}, rest seen $seenLo–$seenHi"
    }
}
