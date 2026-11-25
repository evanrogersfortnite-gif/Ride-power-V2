package com.interop.ridepowerble

import android.content.Context

/**
 * Learns how far YOUR bike goes per 1% of battery, from real riding:
 * GPS distance ridden while connected vs. the (sag-filtered) battery % it cost.
 * Gives a range estimate and "battery left on arrival" for routes.
 * Starts from a conservative 30-miles-per-full-charge guess until it has data.
 */
class RangeLearner(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("range", Context.MODE_PRIVATE)

    private var milesPerPct = prefs.getFloat("mpp", 0.30f).toDouble()
    private var samples = prefs.getInt("n", 0)
    private var odo = prefs.getFloat("odo", 0f).toDouble()          // total miles ridden while connected
    private var anchorPct = prefs.getFloat("aPct", -1f).toDouble()
    private var anchorOdo = prefs.getFloat("aOdo", 0f).toDouble()
    private var movingSecs = prefs.getFloat("secs", 0f).toDouble()   // time spent moving while connected

    val learned get() = samples > 0
    val totalMiles get() = odo

    fun addMiles(miles: Double, secs: Double = 0.0) {
        if (miles <= 0 || miles > 0.2) return   // ignore GPS jumps
        odo += miles
        if (secs in 0.1..30.0) movingSecs += secs
    }

    /** Your real average riding speed (mph) while the learning was done, once there's enough data. */
    val learnedAvgMph: Double? get() = if (movingSecs > 600 && odo > 1) odo / (movingSecs / 3600.0) else null

    /** Rough energy per mile at a steady speed: rolling losses + air drag (grows with speed²). */
    private fun cost(mph: Double) = 10.0 + 0.06 * mph * mph

    /**
     * Range if you average [mph] from here. The learned miles-per-% was measured at your real
     * average speed, so it's scaled for the difference (going faster costs more per mile).
     */
    fun rangeAt(pct: Double, mph: Double): Double {
        val base = pct * milesPerPct
        val learnedAt = (if (learned) learnedAvgMph else null) ?: 15.0
        return base * cost(learnedAt) / cost(mph.coerceIn(5.0, 40.0))
    }

    /** Feed the clean battery %; learns miles-per-% each time it drops by 3+ points. */
    fun onSoc(pct: Double) {
        if (pct < 0) return
        if (anchorPct < 0 || pct > anchorPct + 5) { anchorPct = pct; anchorOdo = odo; save(); return } // first / charged
        val drop = anchorPct - pct
        if (drop >= 3.0) {
            val miles = odo - anchorOdo
            val mpp = miles / drop
            if (mpp in 0.03..3.0 && miles > 0.3) {
                samples++
                val w = 1.0 / kotlin.math.min(samples, 8)
                milesPerPct += (mpp - milesPerPct) * w
            }
            anchorPct = pct; anchorOdo = odo
            save()
        }
    }

    fun rangeMiles(pct: Double) = pct * milesPerPct
    fun pctNeeded(miles: Double) = miles / milesPerPct
    fun fullRangeMiles() = 100 * milesPerPct

    fun save() {
        prefs.edit().putFloat("mpp", milesPerPct.toFloat()).putInt("n", samples).putFloat("odo", odo.toFloat()).putFloat("secs", movingSecs.toFloat())
            .putFloat("aPct", anchorPct.toFloat()).putFloat("aOdo", anchorOdo.toFloat()).apply()
    }
}
