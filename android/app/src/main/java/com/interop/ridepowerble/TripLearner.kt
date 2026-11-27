package com.interop.ridepowerble

import android.content.Context
import org.json.JSONArray
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

    fun record(key: String, predictedSec: Int, actualSec: Int, meters: Int,
               name: String = "", plannedSec: Int = predictedSec, plannedM: Int = meters, riddenM: Int = 0, battUsed: Double? = null) {
        if (predictedSec <= 0 || actualSec <= 0) return
        val ratio = (actualSec.toDouble() / predictedSec).coerceIn(0.3, 3.0)
        val d = load()
        val dests = d.optJSONObject("dests") ?: JSONObject()
        val e = dests.optJSONObject(key) ?: JSONObject()
        bump(e, ratio); e.put("m", meters)
        if (name.isNotEmpty()) e.put("name", name)
        // your door-to-door speed (stops included): ETAs become distance ÷ this
        val mph = ((if (riddenM > 0) riddenM else plannedM) / 1609.34 / (actualSec / 3600.0)).coerceIn(3.0, 40.0)
        bumpMph(e, mph)
        dests.put(key, e); d.put("dests", dests)
        val g = d.optJSONObject("global") ?: JSONObject()
        bump(g, ratio); bumpMph(g, mph); d.put("global", g)
        // every ride kept so the Trip learning screen can show what was learned from it
        val h = d.optJSONArray("history") ?: JSONArray()
        h.put(JSONObject().put("t", System.currentTimeMillis()).put("k", key).put("name", name)
            .put("plan", plannedSec).put("pred", predictedSec).put("act", actualSec).put("pm", plannedM).put("rm", riddenM)
            .put("ratio", ratio).put("mph", mph).apply { if (battUsed != null) put("batt", battUsed) })
        while (h.length() > 200) h.remove(0)
        d.put("history", h)
        prefs.edit().putString("data", d.toString()).apply()
    }

    data class Dest(val key: String, val name: String, val rides: Int, val factor: Double, val meters: Int, val lastAt: Long, val mph: Double?)
    data class Ride(val at: Long, val key: String, val name: String, val plannedSec: Int, val predictedSec: Int, val actualSec: Int,
                    val plannedM: Int, val riddenM: Int, val ratio: Double, val battUsed: Double?, val mph: Double?)

    fun globalFactor(): Double = load().optJSONObject("global")?.optDouble("f", 1.0) ?: 1.0

    fun history(key: String? = null): List<Ride> {
        val h = load().optJSONArray("history") ?: return emptyList()
        return (0 until h.length()).map { h.getJSONObject(it) }.filter { key == null || it.optString("k") == key }.map {
            Ride(it.optLong("t"), it.optString("k"), it.optString("name"), it.optInt("plan"), it.optInt("pred"), it.optInt("act"),
                it.optInt("pm"), it.optInt("rm"), it.optDouble("ratio", 1.0), if (it.has("batt")) it.optDouble("batt") else null,
                if (it.has("mph")) it.optDouble("mph") else null)
        }.sortedByDescending { it.at }
    }

    /** Every place you've ridden to, most recent first. */
    fun destinations(): List<Dest> {
        val ds = load().optJSONObject("dests") ?: return emptyList()
        val hist = history()
        return ds.keys().asSequence().map { k ->
            val e = ds.getJSONObject(k)
            Dest(k, e.optString("name"), e.optInt("n"), e.optDouble("f", 1.0), e.optInt("m"), hist.firstOrNull { it.key == k }?.at ?: 0L,
                if (e.optInt("sn") > 0) e.optDouble("s") else null)
        }.sortedByDescending { it.lastAt }.toList()
    }

    /** Forget one place (and recompute the overall figure from the rides that are left). */
    fun forget(key: String) {
        val d = load()
        d.optJSONObject("dests")?.remove(key)
        val h = d.optJSONArray("history") ?: JSONArray()
        val keep = JSONArray(); for (i in 0 until h.length()) if (h.getJSONObject(i).optString("k") != key) keep.put(h.getJSONObject(i))
        d.put("history", keep)
        val g = JSONObject()
        // rides with details, plus places learned before details were kept
        for (i in 0 until keep.length()) { val o = keep.getJSONObject(i); bump(g, o.optDouble("ratio", 1.0)); if (o.has("mph")) bumpMph(g, o.optDouble("mph")) }
        val withHist = (0 until keep.length()).map { keep.getJSONObject(it).optString("k") }.toSet()
        d.optJSONObject("dests")?.let { ds -> for (k in ds.keys()) if (k !in withHist) { val e = ds.getJSONObject(k); repeat(e.optInt("n")) { bump(g, e.optDouble("f", 1.0)) } } }
        if (g.optInt("n") > 0) d.put("global", g) else d.remove("global")
        prefs.edit().putString("data", d.toString()).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private fun bumpMph(o: JSONObject, mph: Double) {
        val n = o.optInt("sn", 0) + 1
        val v = o.optDouble("s", mph)
        o.put("sn", n); o.put("s", v + (mph - v) / min(n, 10))
    }

    /**
     * ETA for a route: its distance at your learned door-to-door speed (this place's own if you've ridden
     * here, else your overall). Before any speed is learned: the router's time × the old learned ratio.
     */
    fun etaSec(key: String, meters: Int, routerSec: Int): Int {
        val d = load()
        val mph = d.optJSONObject("dests")?.optJSONObject(key)?.takeIf { it.optInt("sn") > 0 }?.optDouble("s")
            ?: d.optJSONObject("global")?.takeIf { it.optInt("sn") > 0 }?.optDouble("s")
        return if (mph != null && mph > 0 && meters > 0) (meters / 1609.34 / mph * 3600).toInt() else (routerSec * factor(key)).toInt()
    }

    fun globalMph(): Double? = load().optJSONObject("global")?.takeIf { it.optInt("sn") > 0 }?.optDouble("s")

    private fun bump(o: JSONObject, ratio: Double) {
        val n = o.optInt("n", 0) + 1
        val f = o.optDouble("f", 1.0)
        o.put("n", n)
        o.put("f", f + (ratio - f) / min(n, 10))
    }
}
