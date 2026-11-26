package com.interop.ridepowerble

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The battery-percentage logic, independent of Android so it can be tested on a simulated ride.
 *
 * The controller reports pack voltage only (no current). Voltage under load reads LOW (sag),
 * and after letting go it jumps most of the way back within a second, then keeps creeping up
 * for about a minute (relaxation). So the true resting voltage (which maps to %) is:
 *
 *   resting ≈ measured + sag (while on the throttle) + remaining relaxation (after letting go)
 *
 * Both are LEARNED from this bike:
 *  - Sag model: every time you let go, (voltage after recovery − voltage just before) is one
 *    sample of the sag at that throttle position and motor speed. A small least-squares fit
 *    learns sag ≈ a·throttle + b·rpm, so on long full-throttle stretches the % keeps updating
 *    instead of freezing.
 *  - Relaxation: when you stop for a minute after riding, how much the voltage kept rising
 *    beyond the 3-second value, as a fraction of the sag. That fraction (decaying over ~30 s)
 *    is added while coasting, so coasting doesn't read low.
 *
 * Voltage → %: the rider's e-bike chart (52 V: 58.8 V = 100%, 50.4 V = 50%, 42.0 V = 0%),
 * applied per cell so any pack size works.
 */
class BatteryCore(private val st: MutableMap<String, Double>) {

    companion object {
        val CURVE = arrayOf(   // the rider's e-bike chart, 52 V (14S) column, per cell
            58.8 / 14 to 100.0, 58.0 / 14 to 95.0, 57.1 / 14 to 90.0, 56.3 / 14 to 85.0,
            55.4 / 14 to 80.0, 54.6 / 14 to 75.0, 53.8 / 14 to 70.0, 52.9 / 14 to 65.0,
            52.1 / 14 to 60.0, 51.2 / 14 to 55.0, 50.4 / 14 to 50.0, 49.6 / 14 to 45.0,
            48.7 / 14 to 40.0, 47.9 / 14 to 35.0, 47.0 / 14 to 30.0, 46.2 / 14 to 25.0,
            45.4 / 14 to 20.0, 44.5 / 14 to 15.0, 43.7 / 14 to 10.0, 42.8 / 14 to 5.0,
            42.0 / 14 to 0.0
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
        const val DT = 0.7   // seconds between controller samples
    }

    var fixedSeries = 0
    private fun g(k: String, d: Double) = st[k] ?: d
    private fun s(k: String, v: Double) { st[k] = v }

    // ---- live state (not persisted) ----
    private var lastMph = 0.0
    private var onSamples = 0
    private var offSamples = 0
    private var parkedSamples = 0
    private var hadGood = false
    private val loadV = ArrayDeque<Double>()          // last few on-throttle voltages
    private var relLoadV = 0.0; private var relThr = 0.0; private var relRpm = 0.0  // at the moment of release (relRpm = speed, mph)
    private val speeds = ArrayDeque<Double>()          // last few motor speeds (mph), to spot powered cruising
    private var relV3 = 0.0                            // recovered voltage ~3 s after release
    private var relSag = 0.0                           // sag seen at that release
    private var sinceRelease = 1e9                     // seconds since the last release
    private val recent = ArrayDeque<Double>()

    var holding = false; private set
    var lastOcv = 0.0; private set                     // best estimate of the resting voltage

    val soc get() = g("soc", -1.0)
    val series get() = g("series", 0.0).toInt()
    val sagSamples get() = g("n", 0.0).toInt()
    /** Fraction of the sag still to recover after ~3 s. Starts from a typical Li-ion value, then learned. */
    val relaxRatio get() = g("relax", 0.12)
    val relaxSamples get() = g("relaxN", 0.0).toInt()

    /** Predicted sag (V) at a throttle fraction and motor speed (mph), or null until learned. */
    fun predictSag(thrFrac: Double, rpm: Double): Double? {
        if (g("n", 0.0) < 5) return null
        // solve the 2x2 least-squares system  [Sxx Sxy; Sxy Syy] [a b] = [Sxs Sys]
        val sxx = g("sxx", 0.0); val sxy = g("sxy", 0.0); val syy = g("syy", 0.0)
        val sxs = g("sxs", 0.0); val sys = g("sys", 0.0)
        val det = sxx * syy - sxy * sxy
        val (a, b) = if (det > 1e-6) ((sxs * syy - sys * sxy) / det) to ((sys * sxx - sxs * sxy) / det)
                     else (if (sxx > 0) sxs / sxx else 0.0) to 0.0
        return (a.coerceIn(0.0, 25.0) * thrFrac + b.coerceIn(0.0, 10.0) * rpm / 20.0).coerceIn(0.0, 15.0)
    }

    private fun learnSag(thrFrac: Double, rpm: Double, sag: Double) {
        val x = thrFrac; val y = rpm / 20.0; val k = 0.97     // older samples fade (pack ages, temperature)
        s("sxx", g("sxx", 0.0) * k + x * x); s("sxy", g("sxy", 0.0) * k + x * y); s("syy", g("syy", 0.0) * k + y * y)
        s("sxs", g("sxs", 0.0) * k + x * sag); s("sys", g("sys", 0.0) * k + y * sag)
        s("n", g("n", 0.0) + 1)
    }

    /**
     * [mph] = motor/wheel speed from the controller (0 when stopped). [cruise]/[brake] = controller flags.
     * Cruise control powers the motor with the throttle at rest, so it counts as load.
     */
    fun update(v: Double, throttleV: Double, mph: Double, cruise: Boolean = false, brake: Boolean = false): Double {
        val rpm = mph
        if (v < 5.0) return soc
        var idle = g("thrIdle", -1.0)
        // throttle resting level: hall throttles sit ~0.8 V. Never learn it from a reading above 1 V,
        // or connecting while already on the throttle would make "full throttle" look like "off".
        if (idle < 0 || throttleV < idle) { idle = minOf(throttleV, 1.0); s("thrIdle", idle) }
        if (throttleV > g("thrMax", 0.0)) s("thrMax", throttleV)
        val span = (g("thrMax", 0.0) - idle).coerceAtLeast(0.5)
        val thrFrac = ((throttleV - idle) / span).coerceIn(0.0, 1.0)
        // powered with the throttle at rest = cruise (flag), or speed held steady above 8 mph for ~3 s
        // (coasting always slows down); braking = regen pushes voltage up, so don't read it either
        speeds.addLast(mph); if (speeds.size > 5) speeds.removeFirst()
        val holdingSpeed = speeds.size == 5 && mph > 8 && mph >= speeds.first() - 0.4
        val on = throttleV > idle + 0.15 || cruise || brake || holdingSpeed || mph - lastMph > 1.0
        lastMph = mph
        sinceRelease += DT

        if (on) {
            onSamples++; offSamples = 0; parkedSamples = 0; recent.clear()
            loadV.addLast(v); if (loadV.size > 3) loadV.removeFirst()
            relThr = thrFrac; relRpm = rpm
            holding = true
            // long stretch on the throttle: use the learned sag to keep the % honest (only ever downward)
            val sag = predictSag(thrFrac, rpm)
            if (sag != null && onSamples >= 4 && hadGood && series > 0) {
                lastOcv = v + sag
                val target = pctFromCell(lastOcv / series)
                if (target < soc) s("soc", soc + (target - soc) * 0.01)
            }
            return soc
        }

        // ---- off the throttle ----
        if (onSamples > 0) {                    // just let go: remember the loaded state
            relLoadV = if (loadV.isEmpty()) v else loadV.average()
            sinceRelease = 0.0
            relV3 = 0.0
        }
        onSamples = 0; loadV.clear()
        offSamples++
        parkedSamples = if (mph < 0.5) parkedSamples + 1 else 0
        if (offSamples < 4) { holding = true; return soc }     // ~3 s for the sag to recover
        holding = false

        recent.addLast(v); if (recent.size > 5) recent.removeFirst()
        val vm = recent.sorted()[recent.size / 2]

        // learn the sag from this release (once, ~3 s after letting go)
        if (relV3 == 0.0 && sinceRelease < 6 && relLoadV > 0) {
            relV3 = vm
            relSag = vm - relLoadV
            if (relSag > 0.15 && relThr > 0.05) learnSag(relThr, relRpm, relSag)
        }
        // learn the slow relaxation: stopped ~60 s after a release
        if (parkedSamples * DT >= 55 && relV3 > 0 && relSag > 0.5 && sinceRelease in 55.0..90.0) {
            val ratio = ((vm - relV3) / relSag).coerceIn(0.0, 0.6)
            val k = g("relaxN", 0.0).coerceAtMost(10.0) + 1.0          // running average (prior counts as one)
            s("relax", (relaxRatio * k + ratio) / (k + 1)); s("relaxN", g("relaxN", 0.0) + 1)
            relV3 = -1.0                                        // learned from this release
        }

        // pack size
        if (vm > g("maxRestV", 0.0)) s("maxRestV", vm)
        if (vm > 10 && vm < g("minRestV", 999.0)) s("minRestV", vm)
        if (fixedSeries > 0) s("series", fixedSeries.toDouble())
        else { val ns = (g("maxRestV", 0.0) / 4.2).roundToInt().coerceIn(1, 30); if (series == 0 || ns > series) s("series", ns.toDouble()) }

        // resting voltage = measured + the part of the relaxation still to come
        val remaining = if (relSag > 0) relaxRatio * relSag * exp(-sinceRelease / 30.0) else 0.0
        lastOcv = vm + remaining
        val target = pctFromCell(lastOcv / series)
        val cur = soc
        s("soc", when {
            cur < 0 || !hadGood -> target
            parkedSamples >= 12 -> cur + (target - cur) * 0.25      // stopped: settle on the true value
            // coasting: follow drops (gently in the first ~30 s after letting go, the voltage is still recovering)
            target < cur -> cur + (target - cur) * (if (sinceRelease > 30) 0.10 else 0.03)
            else -> cur + (target - cur) * 0.02                     // coasting, higher (regen): rise slowly
        }.coerceIn(0.0, 100.0))
        hadGood = true
        return soc
    }
}
