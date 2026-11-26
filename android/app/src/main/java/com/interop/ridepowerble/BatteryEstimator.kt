package com.interop.ridepowerble

import android.content.Context
import kotlin.math.roundToInt

/**
 * Battery percentage (0–100%) from the pack voltage the controller reports.
 *
 * The controller sends voltage only (no current), and voltage sags hard while you're on the
 * throttle, then recovers within a second or two when you let go. So:
 *
 *  - The % comes from the standard e-bike Li-ion chart (per cell: 4.20 V = 100%,
 *    3.76 V = 50%, 3.50 V = 20%, 3.00 V = 0%; for a 52 V/14S pack 58.8 / 52.6 / 49.0 / 42 V).
 *  - It only reads the % from voltages taken after ~3 s OFF the throttle (recovered).
 *    While you're on the throttle it simply HOLDS the last good value — no guessing.
 *  - Coasting, it follows drops smoothly and is slow to rise (ignores regen bumps);
 *    parked, it settles on the true resting value within seconds.
 *  - The first good reading after connecting sets the % directly.
 */
class BatteryEstimator(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("battery", Context.MODE_PRIVATE)

    companion object {
        /** Per-cell voltage → % (standard e-bike chart, light load). */
        val CURVE = arrayOf(
            4.20 to 100.0, 4.10 to 90.0, 4.01 to 80.0, 3.93 to 70.0, 3.84 to 60.0, 3.76 to 50.0,
            3.67 to 40.0, 3.59 to 30.0, 3.50 to 20.0, 3.40 to 10.0, 3.25 to 5.0, 3.00 to 0.0
        )
        fun pctFromCell(v: Double): Double {
            if (v >= CURVE.first().first) return 100.0
            if (v <= CURVE.last().first) return 0.0
            for (i in 0 until CURVE.size - 1) {
                val (hi, ph) = CURVE[i]; val (lo, pl) = CURVE[i + 1]
                if (v <= hi && v >= lo) return pl + (v - lo) / (hi - lo) * (ph - pl)
            }
            return 50.0
        }
    }

    // pack voltage set by the rider in Settings (0 = auto-detect from voltage)
    private val fixedSeries = ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getInt("packSeries", 0)
    private var series = if (fixedSeries > 0) fixedSeries else prefs.getInt("series", 0)
    private var maxRestV = prefs.getFloat("maxRestV", 0f).toDouble()
    private var minRestV = prefs.getFloat("minRestV", 999f).toDouble()
    private var soc = prefs.getFloat("soc", -1f).toDouble()
    // Hall throttles rest around 0.8 V, not 0 V: "on the throttle" is measured against this.
    private var idleThrottle = prefs.getFloat("thrIdle", -1f).toDouble()
    private var lastSpeed = 0
    private var offThrottle = 0          // consecutive samples off the throttle
    private var parked = 0               // consecutive samples stopped and off the throttle
    private var hadGoodReading = false   // this session
    private var lastSaveMs = 0L
    private val recent = ArrayDeque<Double>()   // last few recovered (off-throttle) voltages

    /** Last known estimate (persisted), or -1 if never measured. */
    val current: Double get() = soc
    val learnedSeries get() = series
    val fullVolts get() = if (series > 0) series * 4.2 else 0.0
    /** True while the shown % is a held value (on the throttle, voltage sagging). */
    var holding = false; private set

    /** Feed one realtime sample (~every 0.7 s); returns the % (0–100), or -1 if unknown yet. */
    fun update(batteryV: Double, throttleV: Double, speedRpm: Int): Double {
        if (batteryV < 5.0) return soc                          // junk / disconnected
        if (idleThrottle < 0 || throttleV < idleThrottle) idleThrottle = throttleV
        val onThrottle = throttleV > idleThrottle + 0.15
        val accelerating = speedRpm - lastSpeed > 30
        lastSpeed = speedRpm
        offThrottle = if (onThrottle || accelerating) 0 else offThrottle + 1
        parked = if (speedRpm == 0 && !onThrottle) parked + 1 else 0

        val recovered = offThrottle >= 4                        // ~3 s off: sag has recovered
        holding = !recovered
        if (!recovered) { recent.clear(); return soc }          // on the throttle: hold, no guessing
        recent.addLast(batteryV); if (recent.size > 5) recent.removeFirst()
        val v = recent.sorted()[recent.size / 2]                // median: one noisy reading can't move the %

        // pack size: from Settings, else learned from the highest resting voltage
        if (v > maxRestV) maxRestV = v
        if (v < minRestV && v > 10.0) minRestV = v
        if (fixedSeries > 0) series = fixedSeries
        else {
            val s = (maxRestV / 4.2).roundToInt().coerceIn(1, 30)
            if (series == 0 || s > series) series = s
        }

        val target = pctFromCell(v / series)
        soc = when {
            soc < 0 || !hadGoodReading -> target                  // first good reading: take it
            parked >= 12 -> soc + (target - soc) * 0.25          // parked ~8 s+: true resting value
            target < soc -> soc + (target - soc) * 0.10          // coasting: follow the drop
            else -> soc + (target - soc) * 0.02                  // coasting, higher (regen/recovery): rise slowly
        }.coerceIn(0.0, 100.0)
        hadGoodReading = true
        maybeSave()
        return soc
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
            .putFloat("thrIdle", idleThrottle.toFloat())
            .apply()
    }

    fun save() { lastSaveMs = 0; maybeSave() }

    /** One-line description of what it has learned, for diagnostics. */
    fun summary(): String {
        val full = if (series > 0) "%.1fV".format(series * 4.2) else "?"
        val seenHi = if (maxRestV > 0) "%.1fV".format(maxRestV) else "–"
        val seenLo = if (minRestV < 900) "%.1fV".format(minRestV) else "–"
        return "pack ${if (series > 0) "${series}S (full≈$full)" else "learning…"}, resting voltage seen $seenLo–$seenHi"
    }
}
