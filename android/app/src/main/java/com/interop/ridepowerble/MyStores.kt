package com.interop.ridepowerble

import android.content.Context
import org.json.JSONObject

/**
 * Your Co-op stores (the same list as the Shift Tracker app). They come first in search, so
 * "gloucester road" offers both Gloucester Road stores with their area (Bishopston / Horfield)
 * instead of guessing. Locations are looked up once from OpenStreetMap and kept on the phone.
 */
object MyStores {
    val LIST = listOf(
        "Co-op Food - Lodge Road" to "Bristol",
        "Co-op Food - Straits Parade" to "Fishponds",
        "Co-op Food - Downend Road" to "Fishponds",
        "Co-op Food - Ashley Down Road" to "Ashley Down",
        "Co-op Food - Filton Road" to "Horfield",
        "Co-op Food - Gloucester Road" to "Bishopston",
        "Co-op Food - 327 Gloucester Road" to "Horfield",
        "Co-op Food - Clarence Place" to "Kingsdown",
        "Co-op Food - Redcliffe Street" to "Bristol",
        "Co-op Food - Clifton Village" to "Clifton",
        "Co-op Food - Coronation Road" to "Southville",
        "Co-op Food - Wells Road" to "Bristol",
        "Co-op Food - Wyatts View" to "St Anne's",
        "Co-op Food - Broomhill Road" to "Brislington",
        "Co-op Food - Holloway Road" to "Stockwood",
        "Co-op Food - Belland Drive" to "Whitchurch",
        "Co-op Food - Bishopsworth Road" to "Bristol",
        "Co-op Food - Two Mile Hill Road" to "Kingswood",
        "Co-op Food - Ingleside Road" to "Kingswood",
        "Co-op Food - High Street" to "Hanham",
        "Co-op Food - Memorial Road" to "Hanham",
        "Co-op Food - School Road" to "Barrs Court",
        "Co-op Food - Station Road" to "Henbury",
        "Co-op Food - Crow Lane" to "Henbury",
        "Co-op Food - Stoke Lane" to "Westbury-on-Trym",
        "Co-op Food - Canford Lane" to "Westbury-on-Trym",
        "Co-op Food - Henleaze Road" to "Henleaze",
        "Co-op Food - Shirehampton Road" to "Sea Mills",
        "Co-op Food - Ridingleaze" to "Lawrence Weston",
        "Co-op Food - Shirehampton High Street" to "Shirehampton",
        "Co-op Food - Avonmouth Road" to "Avonmouth",
        "Co-op Food - Long Down Avenue" to "Stoke Gifford",
        "Co-op Food - Dragon Road" to "Winterbourne",
        "Co-op Food - Keynsham Queens Road" to "Keynsham",
        "Co-op Food - Pill" to "Pill"
    )

    private val areas = LIST.map { it.second }.distinct()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mystores", Context.MODE_PRIVATE)
    private fun streetOf(title: String) = title.substringAfter(" - ").trim()

    /** Saved location of each store, or null until it has been looked up. */
    fun placeOf(ctx: Context, i: Int): Router.Place? {
        val (title, area) = LIST[i]
        val v = prefs(ctx).getString(title, null) ?: return null
        val (la, lo) = v.split(",").map { it.toDouble() }
        return Router.Place("$title, $area", la, lo, title, "$area · your store", "supermarket")
    }

    fun allResolved(ctx: Context) = LIST.indices.all { prefs(ctx).contains(LIST[it].first) }

    /**
     * Stores matching what you typed. Every word must start a word of the store's name or area
     * ("glouc rd" finds both Gloucester Road stores); "co-op"/"food" words are ignored, so "coop"
     * on its own lists all of them. Only stores with a known location are returned.
     */
    fun matches(ctx: Context, q: String): List<Router.Place> {
        val skip = setOf("coop", "co", "op", "food", "the", "store", "shop", "in", "on", "at")
        val words = q.lowercase().replace("co-op", "coop").replace("co op", "coop").split(Regex("[^a-z0-9']+"))
            .map { it.replace("'", "") }.filter { it.isNotEmpty() && it !in skip }
            .map { when (it) { "rd" -> "road"; "st" -> "street"; "ave" -> "avenue"; else -> it } }
        val coopOnly = words.isEmpty() && Regex("co.?op").containsMatchIn(q.lowercase())
        if (words.isEmpty() && !coopOnly) return emptyList()
        val out = ArrayList<Router.Place>()
        LIST.forEachIndexed { i, (title, area) ->
            val toks = "$title $area".lowercase().replace("'", "").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            if (coopOnly || words.all { w -> toks.any { it.startsWith(w) } }) placeOf(ctx, i)?.let { out += it }
        }
        return out
    }

    /** Looks up every store's location in one OpenStreetMap query (call off the UI thread). */
    fun resolve(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (allResolved(ctx)) return true
        if (System.currentTimeMillis() - p.getLong("triedAt", 0) < 10 * 60_000) return false
        p.edit().putLong("triedAt", System.currentTimeMillis()).apply()
        return try {
            val bb = "51.30,-2.85,51.62,-2.35"
            val q = "[out:json][timeout:25];(nwr[\"shop\"][\"name\"~\"co-?op\",i]($bb);" +
                    "node[\"place\"][\"name\"~\"^(${areas.filter { it != "Bristol" }.joinToString("|") { it.replace("'", ".") }})$\"]($bb););out center tags;"
            val els = JSONObject(Router.overpass(q, 8000, 30000)).getJSONArray("elements")
            data class Shop(val lat: Double, val lon: Double, val street: String, val num: String, val name: String)
            val shops = ArrayList<Shop>(); val places = HashMap<String, Router.Pt>()
            for (k in 0 until els.length()) {
                val e = els.getJSONObject(k); val t = e.optJSONObject("tags") ?: continue
                val la = if (e.has("lat")) e.getDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
                val lo = if (e.has("lon")) e.getDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
                if (t.has("place")) places.putIfAbsent(t.optString("name").lowercase(), Router.Pt(la, lo))
                else shops += Shop(la, lo, t.optString("addr:street").lowercase(), t.optString("addr:housenumber"), t.optString("name").lowercase())
            }
            places.putIfAbsent("bristol", Router.Pt(51.4545, -2.5879))   // "Bristol" stores: nearest the centre
            val ed = p.edit()
            for ((title, area) in LIST) {
                if (p.contains(title)) continue
                var street = streetOf(title).lowercase()
                if (street.startsWith(area.lowercase() + " ")) street = street.removePrefix(area.lowercase() + " ")
                val num = Regex("^(\\d+)\\s+").find(street)?.groupValues?.get(1); if (num != null) street = street.substringAfter(" ")
                val at = places[area.lowercase()]
                fun d(s: Shop) = if (at == null) 0.0 else Geo.dist(at.lat, at.lon, s.lat, s.lon)
                val onStreet = shops.filter { it.street == street || it.name.contains(street) }
                val pick = when {
                    num != null && onStreet.any { it.num == num } -> onStreet.first { it.num == num }
                    onStreet.isNotEmpty() && (at != null || onStreet.size == 1) -> onStreet.minByOrNull { d(it) }
                    at != null -> shops.minByOrNull { d(it) }?.takeIf { d(it) < 2500 }
                    else -> null
                } ?: continue
                ed.putString(title, "${pick.lat},${pick.lon}")
            }
            ed.apply()
            true
        } catch (e: Exception) { false }
    }
}
