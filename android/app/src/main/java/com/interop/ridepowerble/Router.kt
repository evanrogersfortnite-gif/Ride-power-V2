package com.interop.ridepowerble

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Keyless map services (all blocking — call from a background thread):
 *  - Place search: Nominatim (OpenStreetMap), biased to the area around you.
 *  - E-bike routing: BRouter. "trekking" prefers quiet roads/cycleways, "fastbike"
 *    prefers faster, more direct roads. alternativeidx 0..3 gives alternatives.
 *  - Speed limits: Overpass API (OpenStreetMap maxspeed tags), converted to mph.
 */
object Router {

    private const val UA = "RidePowerBLE/1.0 (personal e-bike interoperability app)"

    data class Pt(val lat: Double, val lon: Double)

    data class Place(
        val name: String, val lat: Double, val lon: Double,
        val label: String = "", val sub: String = "", val cat: String = ""
    ) {
        val title: String get() = label.ifEmpty { name.substringBefore(",").trim() }
        val subtitle: String get() = sub.ifEmpty { name.substringAfter(",", "").trim() }
    }

    /**
     * Search-as-you-type suggestions (Photon, built for autocomplete and partial names),
     * biased toward where you are. Falls back to Nominatim if Photon is unavailable.
     */
    fun suggest(query: String, near: Pt?): List<Place> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            var url = "https://photon.komoot.io/api/?q=$q&limit=8&lang=en"
            if (near != null) url += "&lat=${near.lat}&lon=${near.lon}&zoom=12&location_bias_scale=0.5"
            val feats = JSONObject(httpGet(url, 5000, 6000)).getJSONArray("features")
            val out = ArrayList<Place>()
            for (i in 0 until feats.length()) {
                val f = feats.getJSONObject(i)
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                val p = f.getJSONObject("properties")
                fun s(k: String) = p.optString(k, "").trim()
                val streetLine = listOf(s("housenumber"), s("street")).filter { it.isNotEmpty() }.joinToString(" ")
                val town = s("city").ifEmpty { s("town") }.ifEmpty { s("village") }.ifEmpty { s("county") }
                val title = s("name").ifEmpty { streetLine }.ifEmpty { town }
                if (title.isEmpty()) continue
                val sub = listOf(if (s("name").isNotEmpty()) streetLine else "", s("district"), town, s("postcode"))
                    .filter { it.isNotEmpty() && it != title }.distinct().joinToString(", ")
                val lat = c.getDouble(1); val lon = c.getDouble(0)
                if (out.any { it.title == title && abs(it.lat - lat) < 0.0005 && abs(it.lon - lon) < 0.0005 }) continue
                out.add(Place(if (sub.isEmpty()) title else "$title, $sub", lat, lon, title, sub, s("osm_value")))
            }
            out
        } catch (e: Exception) { lastError = e.javaClass.simpleName; geocode(query, near, false) }
    }

    @Volatile var lastError: String? = null

    data class Route(val points: List<Pt>, val seconds: Int, val meters: Int, val ascentM: Int = 0)

    /** nearby=true restricts to a few km around [near] (category searches like "cafe"). */
    fun geocode(query: String, near: Pt?, nearby: Boolean): List<Place> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            var url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&q=$q"
            if (near != null) {
                val d = if (nearby) 0.06 else 0.6
                url += "&viewbox=${near.lon - d},${near.lat + d},${near.lon + d},${near.lat - d}" +
                        "&bounded=${if (nearby) 1 else 0}"
            }
            val arr = JSONArray(httpGet(url, 5000, 7000))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Place(
                    o.getString("display_name"),
                    o.getString("lat").toDouble(), o.getString("lon").toDouble(),
                    o.optString("name", ""), "", o.optString("type", "")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // ===================== places on the map =====================
    /** A named place to draw on the map. group: shop/food/health/fuel/bike/service/place */
    data class Poi(val name: String, val lat: Double, val lon: Double, val cat: String, val group: String, val sub: String)

    fun poiGroup(key: String, v: String): String = when {
        key == "shop" && v == "bicycle" -> "bike"
        key == "shop" -> "shop"
        v in setOf("restaurant", "fast_food", "cafe", "pub", "bar", "ice_cream", "food_court") -> "food"
        v in setOf("pharmacy", "hospital", "doctors", "dentist", "clinic") -> "health"
        v in setOf("fuel", "charging_station") -> "fuel"
        v in setOf("bicycle_parking", "bicycle_rental", "bicycle_repair_station") -> "bike"
        v in setOf("bank", "atm", "post_office") -> "service"
        else -> "place"
    }

    /** Named shops/food/amenities inside a bounding box (OpenStreetMap via Overpass). */
    fun fetchPois(s: Double, w: Double, n: Double, e: Double): List<Poi> {
        return try {
            val bb = "$s,$w,$n,$e"
            val q = "[out:json][timeout:12];(" +
                    "nwr[\"name\"][\"shop\"]($bb);" +
                    "nwr[\"name\"][\"amenity\"~\"^(restaurant|fast_food|cafe|pub|bar|ice_cream|pharmacy|hospital|doctors|dentist|" +
                    "fuel|charging_station|bank|atm|post_office|bicycle_rental|library|cinema|theatre|marketplace)$\"]($bb);" +
                    ");out center 400;"
            val els = JSONObject(httpGet("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"), 6000, 15000))
                .getJSONArray("elements")
            val out = ArrayList<Poi>()
            for (i in 0 until els.length()) {
                val el = els.getJSONObject(i)
                val t = el.optJSONObject("tags") ?: continue
                val name = t.optString("name", ""); if (name.isEmpty()) continue
                val lat = if (el.has("lat")) el.getDouble("lat") else el.optJSONObject("center")?.optDouble("lat") ?: continue
                val lon = if (el.has("lon")) el.getDouble("lon") else el.optJSONObject("center")?.optDouble("lon") ?: continue
                val key = if (t.has("shop")) "shop" else "amenity"
                val v = t.optString(key)
                val street = listOf(t.optString("addr:housenumber"), t.optString("addr:street")).filter { it.isNotEmpty() }.joinToString(" ")
                val sub = listOf(street, t.optString("addr:city"), t.optString("addr:postcode")).filter { it.isNotEmpty() }.joinToString(", ")
                out.add(Poi(name, lat, lon, v, poiGroup(key, v), sub))
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    // ===================== smart search =====================
    private val ALIASES = mapOf(
        "coop" to "co-op", "co op" to "co-op", "tescos" to "tesco", "sainsburys" to "sainsbury's",
        "sainsbury" to "sainsbury's", "mcdonalds" to "mcdonald's", "maccies" to "mcdonald's", "maccas" to "mcdonald's",
        "mcds" to "mcdonald's", "macdonalds" to "mcdonald's", "asdas" to "asda", "aldis" to "aldi", "lidls" to "lidl",
        "morrison" to "morrisons", "waitroses" to "waitrose", "greggs" to "greggs", "costas" to "costa",
        "starbuck" to "starbucks", "boots" to "boots", "kfcs" to "kfc", "nandos" to "nando's", "subways" to "subway",
        "spoons" to "wetherspoon", "wetherspoons" to "wetherspoon", "m&s" to "marks & spencer", "m and s" to "marks & spencer",
        "b&q" to "b&q", "halfords" to "halfords"
    )
    private val STREET_WORDS = setOf("road", "rd", "street", "st", "lane", "ln", "avenue", "ave", "way", "close", "drive",
        "dr", "hill", "crescent", "cres", "place", "pl", "terrace", "grove", "gardens", "court", "parade", "walk", "row", "square", "park")
    private val STOP = setOf("the", "and", "near", "on", "in", "at", "of", "a", "by", "shop", "store")

    private fun norm(t: String) = t.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
    private fun expandStreetWord(w: String) = when (w) { "rd" -> "road"; "st" -> "street"; "ln" -> "lane"; "ave" -> "avenue"
        "dr" -> "drive"; "cres" -> "crescent"; "pl" -> "place"; else -> w }

    /** Splits "lodge road coop" into street "lodge road" and name words ["coop"]. */
    fun parseQuery(q: String): Pair<String?, List<String>> {
        val words = norm(q).split(" ").filter { it.isNotEmpty() }.map { expandStreetWord(it) }
        val si = words.indexOfFirst { it in STREET_WORDS || expandStreetWord(it) in STREET_WORDS }
        if (si > 0 && words.size > si + 0) {
            val street = words.subList(maxOf(0, si - 1), si + 1).joinToString(" ")
            val rest = words.filterIndexed { i, _ -> i < si - 1 || i > si }.filter { it !in STOP }
            if (rest.isNotEmpty()) return street to rest
        }
        return null to words.filter { it !in STOP }
    }

    private fun aliased(q: String): String {
        var t = " " + q.lowercase() + " "
        for ((k, v) in ALIASES) t = t.replace(" $k ", " $v ")
        return t.trim()
    }

    /** Punctuation-tolerant regex: "coop" also matches "Co-op", "Co op", "CO-OP". */
    private fun looseRegex(word: String) = word.filter { it.isLetterOrDigit() }.toCharArray().joinToString("[-'. &]?") { Regex.escape(it.toString()) }

    /**
     * Search that understands real-world queries like "lodge road coop":
     * type-ahead results + brand aliases + nearby OSM shop names + street matching, ranked.
     */
    fun smartSearch(query: String, near: Pt?, deep: Boolean, alive: () -> Boolean = { true }): List<Place> {
        val pool = LinkedHashMap<String, Place>()
        fun add(list: List<Place>) { for (p in list) pool.putIfAbsent("${p.title.lowercase()}|${"%.4f".format(p.lat)}|${"%.4f".format(p.lon)}", p) }
        if (!alive()) return emptyList()
        add(suggest(query, near))
        val al = aliased(query)
        if (al != query.lowercase() && alive()) add(suggest(al, near))
        val (street, nameWords) = parseQuery(al)
        var streetPt: Pt? = null
        if (deep && near != null && alive()) {
            if (street != null) {
                streetPt = suggest(street, near).firstOrNull { it.cat in setOf("residential", "primary", "secondary", "tertiary",
                    "unclassified", "living_street", "trunk", "service", "pedestrian", "road", "street") || it.title.lowercase().contains(street.substringBefore(" ")) }
                    ?.let { Pt(it.lat, it.lon) }
            }
            if (nameWords.isNotEmpty() && alive()) add(overpassNames(nameWords, streetPt ?: near))
        }
        return rank(pool.values.toList(), al, street, streetPt, near)
    }

    internal fun rank(places: List<Place>, q: String, street: String?, streetPt: Pt?, near: Pt?): List<Place> {
        val qWords = norm(q).split(" ").filter { it.isNotEmpty() && it !in STOP }.map { expandStreetWord(it) }
        fun score(p: Place): Double {
            val title = norm(p.title); val hay = norm(p.title + " " + p.subtitle).replace(" rd ", " road ")
            val titleJoined = title.replace(" ", "")
            var sc = 0.0
            for (w in qWords) {
                val wj = w.replace(" ", "")
                when {
                    title.split(" ").any { it == w } || titleJoined.contains(wj) -> sc += 12
                    hay.contains(w) -> sc += 8
                }
            }
            if (street != null && norm(p.subtitle).contains(street)) sc += 14
            if (streetPt != null) sc -= minOf(20.0, dist(streetPt, p) / 150.0)        // near the named street
            if (near != null) { val mi = dist(near, p) / 1609.0
                sc -= if (mi < 30) mi * 1.2 else 36 + minOf(30.0, (mi - 30) / 10) }  // local first, far away sinks
            return sc
        }
        return places.sortedByDescending { score(it) }
    }

    private fun dist(a: Pt, p: Place): Double {
        val dLat = Math.toRadians(p.lat - a.lat); val dLon = Math.toRadians(p.lon - a.lon)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(p.lat)) * Math.sin(dLon / 2).let { it * it }
        return 2 * 6371000.0 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
    }

    /** Nearby shops/amenities whose name matches the words (Overpass, punctuation-tolerant). */
    private fun overpassNames(words: List<String>, around: Pt): List<Place> {
        return try {
            val pat = words.filter { it.length >= 2 }.joinToString(".*") { looseRegex(it) }
            if (pat.isEmpty()) return emptyList()
            val q = "[out:json][timeout:10];nwr(around:8000,${around.lat},${around.lon})[\"name\"~\"$pat\",i]" +
                    "[~\"^(shop|amenity|leisure|tourism|office|craft)$\"~\".\"];out center tags 40;"
            val els = JSONObject(httpGet("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"), 5000, 9000)).getJSONArray("elements")
            val out = ArrayList<Place>()
            for (i in 0 until els.length()) {
                val e = els.getJSONObject(i)
                val tags = e.optJSONObject("tags") ?: continue
                val name = tags.optString("name", ""); if (name.isEmpty()) continue
                val lat = if (e.has("lat")) e.getDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
                val lon = if (e.has("lon")) e.getDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
                val streetLine = listOf(tags.optString("addr:housenumber"), tags.optString("addr:street")).filter { it.isNotEmpty() }.joinToString(" ")
                val sub = listOf(streetLine, tags.optString("addr:suburb"), tags.optString("addr:city"), tags.optString("addr:postcode"))
                    .filter { it.isNotEmpty() }.distinct().joinToString(", ")
                val cat = listOf("shop", "amenity", "leisure", "tourism", "office").map { tags.optString(it) }.firstOrNull { it.isNotEmpty() } ?: ""
                out.add(Place(if (sub.isEmpty()) name else "$name, $sub", lat, lon, name, sub, cat))
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    /** idx 0 = best route, 1..3 = alternatives. Returns null if none. */
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, idx: Int, profile: String): Route? {
        return try {
            val url = "https://brouter.de/brouter?lonlats=$fromLon,$fromLat|$toLon,$toLat" +
                    "&profile=$profile&alternativeidx=$idx&format=geojson"
            val body = httpGet(url)
            if (!body.trimStart().startsWith("{")) return null
            val feat = JSONObject(body).getJSONArray("features").getJSONObject(0)
            val props = feat.getJSONObject("properties")
            val meters = props.optString("track-length", "0").toDoubleOrNull()?.toInt() ?: 0
            val seconds = props.optString("total-time", "0").toDoubleOrNull()?.toInt() ?: 0
            val ascent = props.optString("filtered ascend", "0").toDoubleOrNull()?.toInt() ?: 0
            val coords = feat.getJSONObject("geometry").getJSONArray("coordinates")
            val pts = ArrayList<Pt>(coords.length())
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)               // [lon, lat, (ele)]
                pts.add(Pt(c.getDouble(1), c.getDouble(0)))
            }
            if (pts.size < 2) null else Route(pts, seconds, meters, ascent)
        } catch (e: Exception) { null }
    }

    /** Posted speed limit (mph) of the road you're on, or null if unknown. */
    fun speedLimitMph(lat: Double, lon: Double): Int? {
        return try {
            val q = "[out:json][timeout:8];way(around:20,$lat,$lon)[highway][maxspeed];out tags 3;"
            val body = httpGet("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"))
            val els = JSONObject(body).getJSONArray("elements")
            for (i in 0 until els.length()) {
                val tags = els.getJSONObject(i).optJSONObject("tags") ?: continue
                val v = parseMaxspeed(tags.optString("maxspeed", ""))
                if (v != null) return v
            }
            null
        } catch (e: Exception) { null }
    }

    private fun parseMaxspeed(raw: String): Int? {
        val s = raw.lowercase().trim()
        if (s.isEmpty()) return null
        val digits = s.filter { it.isDigit() }.toIntOrNull()
        return when {
            s.contains("mph") -> digits
            s.contains("nsl_dual") || s.contains("motorway") -> 70
            s.contains("nsl_single") || s.contains("nsl_restricted") -> if (s.contains("restricted")) 30 else 60
            s.contains("zone") -> digits
            digits != null && s.all { it.isDigit() } -> (digits * 0.621371).roundToInt()  // plain number = km/h
            else -> null
        }
    }

    private fun httpGet(urlStr: String, connectMs: Int = 12000, readMs: Int = 20000): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            connectTimeout = connectMs
            readTimeout = readMs
        }
        try {
            conn.inputStream.bufferedReader().use { return it.readText() }
        } finally { conn.disconnect() }
    }
}
