package com.interop.ridepowerble

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure geometry for navigation (no Android dependencies, unit-testable on the JVM). */
object Geo {
    private const val R = 6371000.0

    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing a -> b in degrees, 0..360 (0 = north, 90 = east). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Signed smallest difference to - from, in (-180, 180]. Positive = clockwise (right). */
    fun angleDiff(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180) d -= 360.0
        if (d <= -180) d += 360.0
        return d
    }

    fun cumulative(pts: List<Router.Pt>): DoubleArray {
        val out = DoubleArray(pts.size)
        for (i in 1 until pts.size) out[i] = out[i - 1] + dist(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        return out
    }

    /** Point at a given distance along the polyline. */
    fun pointAt(pts: List<Router.Pt>, cum: DoubleArray, along: Double): Router.Pt {
        if (along <= 0) return pts.first()
        if (along >= cum.last()) return pts.last()
        var lo = 0; var hi = cum.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (cum[mid] <= along) lo = mid else hi = mid }
        val seg = cum[hi] - cum[lo]
        val t = if (seg > 0) (along - cum[lo]) / seg else 0.0
        return Router.Pt(pts[lo].lat + (pts[hi].lat - pts[lo].lat) * t, pts[lo].lon + (pts[hi].lon - pts[lo].lon) * t)
    }
}

data class Snap(
    val seg: Int,            // segment index (pts[seg] -> pts[seg+1])
    val lat: Double, val lon: Double,
    val offRoute: Double,    // metres from the route
    val along: Double,       // metres travelled along the route
    val bearing: Double      // direction of the route at this point
)

data class Maneuver(val along: Double, val type: Type, val idx: Int) {
    enum class Type { SLIGHT_LEFT, LEFT, SHARP_LEFT, UTURN, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, ARRIVE }
}

/** A route prepared for live navigation. */
class NavRoute(val route: Router.Route) {
    val pts = route.points
    val cum = Geo.cumulative(pts)
    val total = cum.last()
    val maneuvers: List<Maneuver> = detectManeuvers()
    /** Street you turn onto at each manoeuvre (filled in shortly after the route starts), or null. */
    @Volatile var streets: List<String?> = emptyList()

    /** Points just after each turn, used to look up the street name you turn onto. */
    fun streetSamplePoints(): List<Router.Pt> = maneuvers.map { Geo.pointAt(pts, cum, it.along + 25.0) }

    /** Snap a GPS fix onto the route, searching near the previous segment first. */
    fun snap(lat: Double, lon: Double, hintSeg: Int): Snap {
        var best = project(lat, lon, 0); var bestD = best.offRoute
        val from = (hintSeg - 5).coerceAtLeast(0)
        val to = (hintSeg + 80).coerceAtMost(pts.size - 2)
        for (i in from..to) { val s = project(lat, lon, i); if (s.offRoute < bestD) { best = s; bestD = s.offRoute } }
        if (bestD > 40) {   // lost: search the whole route
            for (i in 0 until pts.size - 1) { val s = project(lat, lon, i); if (s.offRoute < bestD) { best = s; bestD = s.offRoute } }
        }
        return best
    }

    /**
     * Snap only to the part of the route near where you already are: from a little behind
     * [along] to [maxAhead] metres ahead. Stops a bad GPS fix (or a route that passes near
     * itself) from jumping you to a different part of the route.
     */
    fun snapNear(lat: Double, lon: Double, along: Double, maxAhead: Double): Snap {
        var best: Snap? = null
        for (i in 0 until pts.size - 1) {
            if (cum[i + 1] < along - 40) continue
            if (cum[i] > along + maxAhead) break
            val s = project(lat, lon, i)
            if (best == null || s.offRoute < best.offRoute) best = s
        }
        return best ?: project(lat, lon, 0)
    }

    private fun project(lat: Double, lon: Double, i: Int): Snap {
        val a = pts[i]; val b = pts[i + 1]
        // local flat projection (metres) around a
        val kx = 111320.0 * cos(Math.toRadians(a.lat)); val ky = 110540.0
        val bx = (b.lon - a.lon) * kx; val by = (b.lat - a.lat) * ky
        val px = (lon - a.lon) * kx; val py = (lat - a.lat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 > 0) ((px * bx + py * by) / len2).coerceIn(0.0, 1.0) else 0.0
        val sx = bx * t; val sy = by * t
        val off = sqrt((px - sx).pow(2) + (py - sy).pow(2))
        val sLat = a.lat + (b.lat - a.lat) * t; val sLon = a.lon + (b.lon - a.lon) * t
        val segLen = cum[i + 1] - cum[i]
        return Snap(i, sLat, sLon, off, cum[i] + segLen * t, Geo.bearing(a.lat, a.lon, b.lat, b.lon))
    }

    /**
     * Turns detected from the route shape: compare the heading over the ~20 m before and
     * after each vertex (and over ~45 m to ignore gentle road curves). Nearby candidates
     * are merged, keeping the sharpest.
     */
    private fun detectManeuvers(): List<Maneuver> {
        val out = ArrayList<Maneuver>()
        if (pts.size < 3) { out.add(Maneuver(total, Maneuver.Type.ARRIVE, pts.size - 1)); return out }
        data class Cand(val idx: Int, val along: Double, val d: Double)
        val cands = ArrayList<Cand>()
        for (i in 1 until pts.size - 1) {
            val at = cum[i]
            if (at < 15 || at > total - 10) continue
            val dShort = turnAt(at, 20.0)
            val dLong = turnAt(at, 45.0)
            if (abs(dShort) >= 35 && abs(dLong) >= 30 && Math.signum(dShort) == Math.signum(dLong)) cands.add(Cand(i, at, dLong))
        }
        var k = 0
        while (k < cands.size) {
            var j = k; var best = cands[k]
            while (j + 1 < cands.size && cands[j + 1].along - cands[j].along < 30) { j++; if (abs(cands[j].d) > abs(best.d)) best = cands[j] }
            out.add(Maneuver(best.along, classify(best.d), best.idx))
            k = j + 1
        }
        out.add(Maneuver(total, Maneuver.Type.ARRIVE, pts.size - 1))
        return out
    }

    private fun turnAt(at: Double, window: Double): Double {
        val p0 = Geo.pointAt(pts, cum, at - window)
        val p1 = Geo.pointAt(pts, cum, at)
        val p2 = Geo.pointAt(pts, cum, at + window)
        if (Geo.dist(p0.lat, p0.lon, p1.lat, p1.lon) < 3 || Geo.dist(p1.lat, p1.lon, p2.lat, p2.lon) < 3) return 0.0
        return Geo.angleDiff(Geo.bearing(p0.lat, p0.lon, p1.lat, p1.lon), Geo.bearing(p1.lat, p1.lon, p2.lat, p2.lon))
    }

    private fun classify(d: Double): Maneuver.Type {
        val a = abs(d)
        return when {
            a >= 160 -> Maneuver.Type.UTURN
            d > 0 -> if (a < 55) Maneuver.Type.SLIGHT_RIGHT else if (a < 130) Maneuver.Type.RIGHT else Maneuver.Type.SHARP_RIGHT
            else -> if (a < 55) Maneuver.Type.SLIGHT_LEFT else if (a < 130) Maneuver.Type.LEFT else Maneuver.Type.SHARP_LEFT
        }
    }

    companion object {
        /** Material turn icon for a manoeuvre (see IconPaths). */
        fun iconName(t: Maneuver.Type): String = when (t) {
            Maneuver.Type.SLIGHT_LEFT -> "turn_slight_left"; Maneuver.Type.LEFT -> "turn_left"; Maneuver.Type.SHARP_LEFT -> "turn_sharp_left"
            Maneuver.Type.SLIGHT_RIGHT -> "turn_slight_right"; Maneuver.Type.RIGHT -> "turn_right"; Maneuver.Type.SHARP_RIGHT -> "turn_sharp_right"
            Maneuver.Type.UTURN -> "u_turn"; Maneuver.Type.ARRIVE -> "flag"
        }
        fun text(t: Maneuver.Type) = when (t) {
            Maneuver.Type.SLIGHT_LEFT -> "Bear left"; Maneuver.Type.LEFT -> "Turn left"; Maneuver.Type.SHARP_LEFT -> "Sharp left"
            Maneuver.Type.SLIGHT_RIGHT -> "Bear right"; Maneuver.Type.RIGHT -> "Turn right"; Maneuver.Type.SHARP_RIGHT -> "Sharp right"
            Maneuver.Type.UTURN -> "Make a U-turn"; Maneuver.Type.ARRIVE -> "Arrive"
        }
    }
}
