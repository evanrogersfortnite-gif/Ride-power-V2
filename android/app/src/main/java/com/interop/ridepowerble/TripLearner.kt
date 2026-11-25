package com.interop.ridepowerble

import android.content.Context
import org.json.JSONObject
import java.util.Locale
import kotlin.math.min

/**
 * Learns from completed rides so ETAs and route choice fit YOUR e-bike:
 *  - Per destination: ratio of actual ride time to the router's estimate
 *    (BRouter assumes pedal-bike speeds; an e-bike is usually quicker).
 *  - Globally: the same ratio across all rides, used for places you've never been.
 *  - Your usual route length to each destination, so it can be marked "★ Your usual".
 * Uses a moving average that weights recent rides more once you've done ~10.
 */
class TripLearner(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("trips", Context.MODE_PRIVATE)

    private fun load(): JSONObject =
        try { JSONObject(prefs.getString("data", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun key(lat: Double, lon: Double): String = String.format(Locale.US, "%.3f,%.3f", lat, lon)

    /** Multiplier to apply to router time estimates for this destination. */
    fun factor(key: String): Double {
        val d = load()
        val e = d.optJSONObject("dests")?.optJSONObject(key)
        if (e != null && e.optInt("n") > 0) return e.optDouble("f", 1.0)
        val g = d.optJSONObject("global")
        return if (g != null && g.optInt("n") > 0) g.optDouble("f", 1.0) else 1.0
    }

    fun rides(key: String): Int = load().optJSONObject("dests")?.optJSONObject(key)?.optInt("n") ?: 0
    fun globalRides(): Int = load().optJSONObject("global")?.optInt("n") ?: 0

    fun usualMeters(key: String): Int? {
        val e = load().optJSONObject("dests")?.optJSONObject(key) ?: return null
        val m = e.optInt("m", 0)
        return if (m > 0) m else null
    }

    fun record(key: String, predictedSec: Int, actualSec: Int, meters: Int) {
        if (predictedSec <= 0 || actualSec <= 0) return
        val ratio = (actualSec.toDouble() / predictedSec).coerceIn(0.3, 3.0)
        val d = load()
        val dests = d.optJSONObject("dests") ?: JSONObject()
        val e = dests.optJSONObject(key) ?: JSONObject()
        bump(e, ratio); e.put("m", meters)
        dests.put(key, e); d.put("dests", dests)
        val g = d.optJSONObject("global") ?: JSONObject()
        bump(g, ratio); d.put("global", g)
        prefs.edit().putString("data", d.toString()).apply()
    }

    private fun bump(o: JSONObject, ratio: Double) {
        val n = o.optInt("n", 0) + 1
        val f = o.optDouble("f", 1.0)
        o.put("n", n)
        o.put("f", f + (ratio - f) / min(n, 10))
    }
}
