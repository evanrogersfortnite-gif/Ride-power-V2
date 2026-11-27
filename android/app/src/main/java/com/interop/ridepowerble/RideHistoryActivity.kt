package com.interop.ridepowerble

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** Clean list of past rides (from the ride logs) with totals, and a detail page with the route on a map. */
class RideHistoryActivity : android.app.Activity() {

    private val BG = 0xFFF1F3F4.toInt(); private val INK = 0xFF202124.toInt(); private val MUTED = 0xFF5F6368.toInt()
    private val BLUE = 0xFF1A73E8.toInt(); private val GREEN = 0xFF188038.toInt(); private val LINE = 0xFFE8EAED.toInt()
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private var mapView: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        showList()
    }

    private fun header(title: String, onBack: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(10), dp(16), dp(10)); elevation = dp(2).toFloat()
        addView(ImageView(context).apply {
            setImageDrawable(AppIcons.drawable(this@RideHistoryActivity, "arrow_back", INK, 24)); scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(context).apply { text = title; textSize = 21f; setTextColor(INK); setTypeface(null, Typeface.BOLD) })
    }

    // ============================ list ============================
    private fun showList() {
        destroyMap()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(header("Ride history") { finish() })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(32)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        body.addView(TextView(this).apply { text = "Loading rides…"; textSize = 15f; setTextColor(MUTED); setPadding(dp(8), dp(24), 0, 0) })
        val capAh = getSharedPreferences("app", Context.MODE_PRIVATE).getInt("packAh10", 300) / 10.0
        exec.execute {
            val rides = RideLogger.rides(this).mapNotNull { RideStats.summarize(it, if (capAh > 0) capAh else 30.0) }
                .filter { it.miles >= 0.2 }.sortedByDescending { it.start }
            runOnUiThread { fillList(rides) }
        }
    }

    private fun fillList(rides: List<RideSummary>) {
        body.removeAllViews()
        if (rides.isEmpty()) {
            body.addView(TextView(this).apply {
                text = "No rides yet.\n\nRides are recorded automatically while the bike is connected or while navigating."
                textSize = 15f; setTextColor(MUTED); setPadding(dp(8), dp(24), dp(8), 0)
            }); return
        }
        // totals
        val cal = Calendar.getInstance(); val month = cal.get(Calendar.MONTH); val year = cal.get(Calendar.YEAR)
        val thisMonth = rides.filter { val c = Calendar.getInstance().apply { time = it.start }; c.get(Calendar.MONTH) == month && c.get(Calendar.YEAR) == year }
        val totals = card()
        totals.addView(TextView(this).apply { text = "This month"; textSize = 13f; setTextColor(BLUE); setTypeface(null, Typeface.BOLD) })
        totals.addView(statRow(listOf(
            "${thisMonth.size}" to "rides", "%.1f".format(thisMonth.sumOf { it.miles }) to "miles",
            RideStats.fmtDuration(thisMonth.sumOf { it.movingSec }) to "riding")))
        totals.addView(TextView(this).apply {
            text = "All recorded: ${rides.size} rides · ${"%.1f".format(rides.sumOf { it.miles })} mi · top ${"%.0f".format(rides.maxOf { it.topMph })} mph"
            textSize = 13f; setTextColor(MUTED); setPadding(0, dp(10), 0, 0)
        })
        body.addView(totals)

        val dayFmt = SimpleDateFormat("EEE d MMM", Locale.UK); val timeFmt = SimpleDateFormat("HH:mm", Locale.UK)
        for (r in rides) {
            val c = card().apply { isClickable = true; setOnClickListener { showDetail(r) } }
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(RouteThumb(this, r.route), LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(14) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = "${dayFmt.format(r.start)}  ·  started ${timeFmt.format(r.start)}"; textSize = 16f; setTextColor(INK); setTypeface(null, Typeface.BOLD)
            })
            col.addView(TextView(this).apply {
                text = "${"%.1f".format(r.miles)} mi  ·  ${RideStats.fmtDuration(r.durationSec)}  ·  ${"%.1f".format(r.avgMph)} mph avg"
                textSize = 14f; setTextColor(INK); setPadding(0, dp(3), 0, 0)
            })
            val bits = ArrayList<String>()
            if (r.battStart != null && r.battEnd != null) bits += "Battery ${r.battStart.roundToInt()}% → ${r.battEnd.roundToInt()}%"
            if (r.wh != null && r.miles > 0.3) bits += "${(r.wh / r.miles).roundToInt()} Wh/mi"
            bits += "top ${"%.0f".format(r.topMph)} mph"
            col.addView(TextView(this).apply { text = bits.joinToString("  ·  "); textSize = 13f; setTextColor(MUTED); setPadding(0, dp(3), 0, 0) })
            top.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            c.addView(top)
            body.addView(c)
        }
    }

    // ============================ detail ============================
    private fun showDetail(r: RideSummary) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val dayFmt = SimpleDateFormat("EEEE d MMMM, HH:mm", Locale.UK)
        root.addView(header(SimpleDateFormat("EEE d MMM", Locale.UK).format(r.start)) { showList() })
        // map with the route
        val mv = MapView(this); mapView = mv
        mv.onCreate(null); mv.onStart(); mv.onResume()
        root.addView(mv, LinearLayout.LayoutParams(-1, dp(260)))
        val style = getSharedPreferences("app", Context.MODE_PRIVATE).getString("mapStyle", "liberty") ?: "liberty"
        mv.getMapAsync { m ->
            m.uiSettings.isLogoEnabled = false; m.uiSettings.isCompassEnabled = false
            m.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/$style")) { st ->
                if (r.route.size < 2) return@setStyle
                val coords = JSONArray(); r.route.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
                val line = JSONObject().put("type", "Feature").put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
                st.addSource(GeoJsonSource("ride", line.toString()))
                st.addLayer(LineLayer("ride-casing", "ride").withProperties(PropertyFactory.lineColor("#1A47C9"), PropertyFactory.lineWidth(8f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
                st.addLayer(LineLayer("ride-l", "ride").withProperties(PropertyFactory.lineColor("#4285F4"), PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
                fun pt(p: Router.Pt) = JSONObject().put("type", "Feature").put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat)))
                st.addSource(GeoJsonSource("ends", JSONObject().put("type", "FeatureCollection").put("features",
                    JSONArray().put(pt(r.route.first()).put("properties", JSONObject().put("c", "#188038")))
                        .put(pt(r.route.last()).put("properties", JSONObject().put("c", "#D93025")))).toString()))
                st.addLayer(CircleLayer("ends-l", "ends").withProperties(PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(org.maplibre.android.style.expressions.Expression.toColor(org.maplibre.android.style.expressions.Expression.get("c"))),
                    PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f)))
                val b = LatLngBounds.Builder().includes(r.route.map { LatLng(it.lat, it.lon) }).build()
                m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, dp(40)))
            }
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(32)) }
        val c = card()
        c.addView(TextView(this).apply { text = "Started ${dayFmt.format(r.start)}"; textSize = 15f; setTextColor(INK); setTypeface(null, Typeface.BOLD) })
        c.addView(statRow(listOf("%.1f".format(r.miles) to "miles", RideStats.fmtDuration(r.durationSec) to "total time",
            RideStats.fmtDuration(r.movingSec) to "moving")))
        c.addView(statRow(listOf("%.1f".format(r.avgMph) to "avg mph", "%.0f".format(r.topMph) to "top mph",
            (r.peakW?.let { "${(it / 10).roundToInt() * 10}" } ?: "–") to "peak W")))
        val wh = r.wh
        c.addView(statRow(listOf(
            (if (r.battStart != null && r.battEnd != null) "${r.battStart.roundToInt()}→${r.battEnd.roundToInt()}%" else "–") to "battery",
            (wh?.let { "${it.roundToInt()}" } ?: "–") to (if (r.whExact) "Wh used" else "Wh (est.)"),
            (if (wh != null && r.miles > 0.3) "${(wh / r.miles).roundToInt()}" else "–") to "Wh/mile")))
        if (r.voltsStart != null && r.voltsEnd != null)
            c.addView(TextView(this).apply { text = "Voltage ${"%.1f".format(r.voltsStart)} V → ${"%.1f".format(r.voltsEnd)} V"; textSize = 13f; setTextColor(MUTED); setPadding(0, dp(10), 0, 0) })
        info.addView(c)
        val btns = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0) }
        btns.addView(pill("share", "Share log", BLUE) { RideLogger.shareFile(this, r.file) }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(10) })
        btns.addView(pill("close", "Delete", 0xFFD93025.toInt()) {
            AlertDialog.Builder(this).setTitle("Delete this ride?").setMessage("The ride and its log are removed from this phone.")
                .setPositiveButton("Delete") { _, _ -> r.file.delete(); showList() }.setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        info.addView(btns)
        root.addView(ScrollView(this).apply { addView(info) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (mapView != null) showList() else super.onBackPressed() }
    override fun onDestroy() { super.onDestroy(); destroyMap(); exec.shutdownNow() }
    private fun destroyMap() { mapView?.let { it.onPause(); it.onStop(); it.onDestroy() }; mapView = null }

    // ============================ bits ============================
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }
    private fun statRow(items: List<Pair<String, String>>) = LinearLayout(this).apply {
        setPadding(0, dp(10), 0, 0)
        for ((v, label) in items) addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = v; textSize = 20f; setTextColor(INK); setTypeface(null, Typeface.BOLD) })
            addView(TextView(context).apply { text = label; textSize = 12f; setTextColor(MUTED) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    private fun pill(icon: String, t: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(color); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        setCompoundDrawablesRelative(AppIcons.drawable(this@RideHistoryActivity, icon, color, 18), null, null, null)
        compoundDrawablePadding = dp(6); setPadding(dp(16), 0, dp(16), 0)
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(25).toFloat(); setStroke(dp(1), LINE) }
        setOnClickListener { onClick() }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Tiny drawing of the route shape, for the list. */
    private class RouteThumb(ctx: Context, val pts: List<Router.Pt>) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4285F4.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8F0FE.toInt() }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.drawRoundRect(0f, 0f, w, h, 18f, 18f, bg)
            if (pts.size < 2) return
            val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }; val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
            val kx = Math.cos(Math.toRadians((minLat + maxLat) / 2))
            val spanX = max(1e-6, (maxLon - minLon) * kx); val spanY = max(1e-6, maxLat - minLat)
            val pad = 8f; val s = minOf((w - 2 * pad) / spanX, (h - 2 * pad) / spanY)
            val ox = (w - spanX * s) / 2; val oy = (h - spanY * s) / 2
            val p = Path()
            pts.forEachIndexed { i, q ->
                val x = (ox + (q.lon - minLon) * kx * s).toFloat(); val y = (h - oy - (q.lat - minLat) * s).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            c.drawPath(p, paint)
        }
    }
}
