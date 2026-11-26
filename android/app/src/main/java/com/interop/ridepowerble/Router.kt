package com.interop.ridepowerble

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
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
        val r = photon(query, near)
        return if (r.isNotEmpty() || System.currentTimeMillis() > photonDownUntil) r else geocode(query, near, false)
    }

    /** Photon is fast but sometimes overloaded; after a failure skip it for a while. */
    @Volatile private var photonDownUntil = 0L
    private val searchPool = java.util.concurrent.Executors.newCachedThreadPool()

    private fun photon(query: String, near: Pt?): List<Place> {
        if (System.currentTimeMillis() < photonDownUntil) return emptyList()
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
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName
            photonDownUntil = System.currentTimeMillis() + 90_000
            emptyList()
        }
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

    /** Address + postcode for a point (Nominatim reverse). Returns (postcode, address line) or null. */
    fun reverse(lat: Double, lon: Double): Pair<String, String>? {
        return try {
            val o = JSONObject(httpGet("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&lat=$lat&lon=$lon", 5000, 8000))
            val a = o.optJSONObject("address") ?: return null
            val street = listOf(a.optString("house_number"), a.optString("road")).filter { it.isNotEmpty() }.joinToString(" ")
            val area = listOf("suburb", "neighbourhood", "village", "town", "city").map { a.optString(it) }.firstOrNull { it.isNotEmpty() } ?: ""
            val city = listOf("city", "town", "village").map { a.optString(it) }.firstOrNull { it.isNotEmpty() } ?: ""
            val line = listOf(street, area, if (city != area) city else "").filter { it.isNotEmpty() }.distinct().joinToString(", ")
            a.optString("postcode", "") to line.ifEmpty { o.optString("display_name", "") }
        } catch (e: Exception) { null }
    }

    /**
     * Street names at points along a route (OpenStreetMap via Overpass): for each point, the
     * nearest named road within ~20 m, or null. Used for "Turn right onto Dragon Road".
     */
    fun streetNames(points: List<Pt>): List<String?> {
        if (points.isEmpty()) return emptyList()
        return try {
            val q = "[out:json][timeout:20];(" + points.joinToString("") {
                "way(around:18,${"%.6f".format(Locale.US, it.lat)},${"%.6f".format(Locale.US, it.lon)})[highway][name];"
            } + ");out tags geom;"
            val els = JSONObject(overpass(q, 8000, 20000)).getJSONArray("elements")
            data class W(val name: String, val geom: List<Pt>)
            val ways = ArrayList<W>()
            for (i in 0 until els.length()) {
                val e = els.getJSONObject(i)
                val name = e.optJSONObject("tags")?.optString("name", "") ?: ""
                val g = e.optJSONArray("geometry") ?: continue
                if (name.isEmpty()) continue
                ways += W(name, (0 until g.length()).map { val o = g.getJSONObject(it); Pt(o.getDouble("lat"), o.getDouble("lon")) })
            }
            points.map { p ->
                var best: String? = null; var bestD = 22.0
                for (w in ways) for (k in 0 until w.geom.size - 1) {
                    val d = segDist(p, w.geom[k], w.geom[k + 1])
                    if (d < bestD) { bestD = d; best = w.name }
                }
                best
            }
        } catch (e: Exception) { points.map { null } }
    }

    private fun segDist(p: Pt, a: Pt, b: Pt): Double {
        val kx = 111320.0 * Math.cos(Math.toRadians(a.lat)); val ky = 110540.0
        val bx = (b.lon - a.lon) * kx; val by = (b.lat - a.lat) * ky
        val px = (p.lon - a.lon) * kx; val py = (p.lat - a.lat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 > 0) ((px * bx + py * by) / len2).coerceIn(0.0, 1.0) else 0.0
        return Math.hypot(px - bx * t, py - by * t)
    }

    /** "Dragon Road" -> "Dragon Rd" (as Google shows it). */
    fun shortStreet(n: String): String = n.replace(Regex("\\bRoad\\b"), "Rd").replace(Regex("\\bStreet\\b"), "St")
        .replace(Regex("\\bAvenue\\b"), "Ave").replace(Regex("\\bLane\\b"), "Ln").replace(Regex("\\bDrive\\b"), "Dr")
        .replace(Regex("\\bCrescent\\b"), "Cres").replace(Regex("\\bPlace\\b"), "Pl").replace(Regex("\\bTerrace\\b"), "Ter")

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
            val els = JSONObject(overpass(q, 6000, 15000))
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
    private val PLACE_TYPES = setOf("city", "town", "village", "hamlet", "suburb", "neighbourhood", "quarter", "locality", "borough")

    /** A town/village/suburb whose name matches [word] (typos allowed), near [near]. */
    private fun findLocality(word: String, near: Pt?, known: List<Place>): Place? {
        fun ok(p: Place) = p.cat in PLACE_TYPES && norm(p.title).split(" ").any { it == word || oneEdit(it, word) || (word.length >= 5 && it.startsWith(word)) }
        known.firstOrNull { ok(it) }?.let { return it }
        return (photon(word, near) + (if (System.currentTimeMillis() < photonDownUntil) geocode(word, near, false) else emptyList()))
            .filter { ok(it) || (it.cat in PLACE_TYPES && norm(it.title).contains(word.take(5))) }
            .minByOrNull { if (near == null) 0.0 else dist(near, it) }
    }

    fun smartSearch(query: String, near: Pt?, deep: Boolean, alive: () -> Boolean = { true },
                    extra: List<Place> = emptyList()): List<Place> {
        val pool = LinkedHashMap<String, Place>()
        @Synchronized fun add(list: List<Place>) { for (p in list) pool.putIfAbsent("${p.title.lowercase()}|${"%.3f".format(p.lat)}|${"%.3f".format(p.lon)}", p) }
        add(extra)
        if (!alive()) return emptyList()
        val al = aliased(query)
        val (street, nameWords) = parseQuery(al)
        val photonOk = System.currentTimeMillis() >= photonDownUntil
        // every source runs at the same time; whatever answers before the deadline is used
        val jobs = ArrayList<java.util.concurrent.Future<List<Place>>>()
        fun job(f: () -> List<Place>) { jobs += searchPool.submit(java.util.concurrent.Callable { try { f() } catch (e: Exception) { emptyList<Place>() } }) }
        job { photon(query, near) }
        if (al != query.lowercase()) job { photon(al, near) }
        if (deep || !photonOk) job { geocode(al, near, false) }
        if (deep && near != null && nameWords.isNotEmpty()) job { overpassNames(nameWords, near) }
        // "winterbourne coop": find the place "Winterbourne", then the shop near it
        val anchor = java.util.concurrent.atomic.AtomicReference<Place?>(null)
        if (deep && street == null && nameWords.size >= 2) {
            val known = extra
            for (w in nameWords.filter { it.length >= 5 }.take(2)) job {
                val loc = findLocality(w, near, known) ?: return@job emptyList()
                anchor.compareAndSet(null, loc)
                overpassNames(nameWords.filter { it != w }, Pt(loc.lat, loc.lon), 3000)
            }
        }
        val streetJob = if (deep && street != null) searchPool.submit(java.util.concurrent.Callable {
            try { photon(street, near).ifEmpty { geocode(street, near, false) } } catch (e: Exception) { emptyList<Place>() } }) else null
        val deadline = System.currentTimeMillis() + (if (deep) 9000 else 4500)
        for (j in jobs) {
            if (!alive()) { jobs.forEach { it.cancel(true) }; return emptyList() }
            try { add(j.get(maxOf(1L, deadline - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS)) }
            catch (e: Exception) { j.cancel(true) }
        }
        val streetPt = try {
            streetJob?.get(maxOf(1L, deadline - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS)
                ?.firstOrNull { it.cat in setOf("residential", "primary", "secondary", "tertiary", "unclassified", "living_street",
                    "trunk", "service", "pedestrian", "road", "street") || it.title.lowercase().contains(street!!.substringBefore(" ")) }
                ?.let { Pt(it.lat, it.lon) }
        } catch (e: Exception) { streetJob?.cancel(true); null }
        val loc = anchor.get()
        if (loc != null) {
            // rank by the shop words, close to that place; the place itself is still offered
            val locWord = norm(loc.title)
            val rest = nameWords.filter { w -> locWord.split(" ").none { it == w || oneEdit(it, w) } }.joinToString(" ")
            return rank(pool.values.toList(), rest.ifEmpty { al }, null, Pt(loc.lat, loc.lon), near)
        }
        return rank(pool.values.toList(), al, street, streetPt, near)
    }

    /** Lower-case letters/digits only, so "Co-op Food" and "coop" can be compared. */
    fun compact(t: String) = t.lowercase().filter { it.isLetterOrDigit() }

    /** The meaningful words of a query, brand aliases applied, compacted ("coop", "winterbourne"). */
    fun searchWords(q: String) = norm(aliased(q)).split(" ").filter { it.length >= 3 && it !in STOP }.map { compact(it) }
        .plus(aliased(q).split(" ").map { compact(it) }.filter { it.length >= 3 }).distinct()

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
                    w.length >= 4 && title.split(" ").any { oneEdit(it, w) } -> sc += 10
                    hay.contains(w) -> sc += 8
                    w.length >= 5 && hay.split(" ").any { oneEdit(it, w) } -> sc += 7     // small typo
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

    /** True if a and b differ by at most one inserted, deleted or changed letter. */
    internal fun oneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (abs(a.length - b.length) > 1 || a.length < 4) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when { a.length > b.length -> i++; a.length < b.length -> j++; else -> { i++; j++ } }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    private fun dist(a: Pt, p: Place): Double {
        val dLat = Math.toRadians(p.lat - a.lat); val dLon = Math.toRadians(p.lon - a.lon)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(p.lat)) * Math.sin(dLon / 2).let { it * it }
        return 2 * 6371000.0 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
    }

    /** Nearby shops/amenities whose name matches the words (Overpass, punctuation-tolerant). */
    private fun overpassNames(words: List<String>, around: Pt, radius: Int = 8000): List<Place> {
        return try {
            // a name matching ANY of the words ("winterbourne coop" still finds "Co-op Food"); ranking sorts it out
            val ws = words.filter { it.length >= 3 }.ifEmpty { words.filter { it.length >= 2 } }
            val pat = ws.joinToString("|") { looseRegex(it) }
            if (pat.isEmpty()) return emptyList()
            val q = "[out:json][timeout:10];nwr(around:$radius,${around.lat},${around.lon})[\"name\"~\"$pat\",i]" +
                    "[~\"^(shop|amenity|leisure|tourism|office|craft)$\"~\".\"];out center tags 60;"
            val els = JSONObject(overpass(q, 4000, 7000)).getJSONArray("elements")
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
            val body = overpass(q)
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

    /**
     * Accepts a TomTom key pasted on its own or inside a whole example link
     * ("...&key=XXXX") and returns just the key.
     */
    fun cleanApiKey(raw: String): String {
        val t = raw.trim()
        Regex("[?&]key=([A-Za-z0-9_-]+)").find(t)?.let { return it.groupValues[1] }
        return t.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    /** Overpass with fallback mirrors (the main server is often busy / rate-limited). */
    private val OVERPASS = listOf("https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter")
    @Volatile private var overpassFirst = 0
    private fun overpass(q: String, connectMs: Int = 8000, readMs: Int = 20000): String {
        var last: Exception? = null
        for (i in OVERPASS.indices) {
            val idx = (overpassFirst + i) % OVERPASS.size
            try {
                val body = httpGet(OVERPASS[idx] + "?data=" + URLEncoder.encode(q, "UTF-8"), connectMs, readMs)
                if (!body.trimStart().startsWith("{")) throw java.io.IOException("busy")
                overpassFirst = idx
                return body
            } catch (e: Exception) { last = e }
        }
        throw last ?: java.io.IOException("overpass unavailable")
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
