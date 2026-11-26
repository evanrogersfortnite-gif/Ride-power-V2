package com.interop.ridepowerble

import android.content.Context

/**
 * Battery percentage for the app: wraps [BatteryCore] (the logic) and stores what it has
 * learned (sag model, relaxation, pack size, last %) on the phone.
 */
class BatteryEstimator(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("battery2", Context.MODE_PRIVATE)
    private val state = HashMap<String, Double>()
    private val core: BatteryCore
    private var lastSaveMs = 0L

    companion object {
        fun pctFromCell(v: Double) = BatteryCore.pctFromCell(v)
    }

    init {
        for ((k, v) in prefs.all) if (v is Float) state[k] = v.toDouble()
        // don't carry the % over from the old estimator (it was wrong); only the throttle's rest level
        if (!state.containsKey("thrIdle")) {
            val old = ctx.getSharedPreferences("battery", Context.MODE_PRIVATE)
            old.getFloat("thrIdle", -1f).takeIf { it in 0f..1f }?.let { state["thrIdle"] = it.toDouble() }
        }
        // one-time: drop a % copied from the old estimator by the previous update
        if ((state["ver"] ?: 0.0) < 3.0) { state.remove("soc"); state["ver"] = 3.0; prefs.edit().remove("soc").putFloat("ver", 3f).apply() }
        core = BatteryCore(state)
        core.fixedSeries = ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getInt("packSeries", 0)
    }

    /** Last known estimate (persisted), or -1 if never measured. */
    val current: Double get() = core.soc
    val learnedSeries get() = core.series
    val fullVolts get() = if (core.series > 0) core.series * 4.2 else 0.0
    val holding get() = core.holding
    /** Best estimate of the battery's resting voltage (what the % is based on). */
    val restingVolts get() = core.lastOcv

    fun update(batteryV: Double, throttleV: Double, speedRpm: Int): Double {
        val r = core.update(batteryV, throttleV, speedRpm)
        maybeSave()
        return r
    }

    private fun maybeSave() {
        val now = System.currentTimeMillis()
        if (now - lastSaveMs < 20000) return
        lastSaveMs = now
        val e = prefs.edit()
        for ((k, v) in state) e.putFloat(k, v.toFloat())
        e.apply()
    }

    fun save() { lastSaveMs = 0; maybeSave() }

    /** Plain-English description of what it has learned. */
    fun summary(): String {
        val s = core.series
        val pack = if (s > 0) "${s}S pack (full ${"%.1f".format(s * 4.2)} V, empty ${"%.1f".format(s * 3.0)} V)" else "pack size: learning…"
        val sag = core.predictSag(1.0, 0.0)?.let { "learned from ${core.sagSamples} throttle releases (≈${"%.1f".format(it)} V at full throttle)" }
            ?: "learning (${core.sagSamples}/5 throttle releases)"
        val relax = if (core.relaxSamples > 0) "learned from ${core.relaxSamples} stops (${"%.0f".format(core.relaxRatio * 100)}% of sag)" else "typical value until you stop for a minute after riding"
        return "$pack\nVoltage sag: $sag\nRecovery after letting go: $relax"
    }
}
