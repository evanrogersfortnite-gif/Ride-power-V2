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

    val learned get() = samples > 0
    val totalMiles get() = odo

    fun addMiles(miles: Double) {
        if (miles <= 0 || miles > 0.2) return   // ignore GPS jumps
        odo += miles
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
        prefs.edit().putFloat("mpp", milesPerPct.toFloat()).putInt("n", samples).putFloat("odo", odo.toFloat())
            .putFloat("aPct", anchorPct.toFloat()).putFloat("aOdo", anchorOdo.toFloat()).apply()
    }
}
