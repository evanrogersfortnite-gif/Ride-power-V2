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
        // v4: sag model was learned with the speed field misread as rpm — relearn it
        if ((state["ver"] ?: 0.0) < 4.0) {
            val e = prefs.edit()
            for (k in listOf("sxx", "sxy", "syy", "sxs", "sys", "n")) { state.remove(k); e.remove(k) }
            state["ver"] = 4.0; e.putFloat("ver", 4f).apply()
        }
        core = BatteryCore(state)
        val app = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        core.fixedSeries = app.getInt("packSeries", 0)
        core.maxAmps = app.getInt("maxAmps", 45).toDouble()
        app.getInt("packAh10", 0).takeIf { it > 0 }?.let { core.capAh = it / 10.0 }
    }

    /** Last known estimate (persisted), or -1 if never measured. */
    val current: Double get() = core.soc
    val learnedSeries get() = core.series
    val fullVolts get() = if (core.series > 0) core.series * 4.2 else 0.0
    val holding get() = core.holding
    /** Estimated battery current (A) and power (W) right now. */
    val amps get() = core.amps
    val watts get() = core.watts
    /** Best estimate of the battery's resting voltage (what the % is based on). */
    val restingVolts get() = core.lastOcv

    /**
     * Current limit for the level you're in: controller max × "DC current %" × that level's %,
     * read from the controller's own settings (bytes 2 and 5–9 of the parameter frame).
     */
    private fun limitFor(gear: Int): Pair<Double, Boolean> {
        val p = BleManager.lastParams
        if (p == null || p.size < 20 || gear !in 1..5) return core.maxAmps to false
        val dcc = (p[2].toInt() and 0xff).coerceIn(10, 100)
        val lvl = (p[4 + gear].toInt() and 0xff).coerceIn(1, 100)
        return core.maxAmps * dcc / 100.0 * lvl / 100.0 to true
    }
    var lastLimitAmps = 0.0; private set

    fun update(batteryV: Double, throttleV: Double, mph: Double, cruise: Boolean, brake: Boolean, gear: Int = 0): Double {
        val (lim, known) = limitFor(gear)
        lastLimitAmps = lim
        val r = core.update(batteryV, throttleV, mph, cruise, brake, lim, known)
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
        val cc = "Pack resistance ${"%.3f".format(core.resistance)} Ω · counted-charge scale ×${"%.2f".format(core.coulombScale)}" +
            (if (core.coulombSamples > 0) " (learned from ${core.coulombSamples} stops)" else " (learns at stops)")
        return "$pack\nRecovery after letting go: $relax\nCurrent estimate: $cc"
    }
}
