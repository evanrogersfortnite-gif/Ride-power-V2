package com.interop.ridepowerble

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val POI_STYLE = mapOf(
    "shop" to ("#1A73E8" to "🛒"), "food" to ("#E8710A" to "🍴"), "health" to ("#D93025" to "✚"),
    "fuel" to ("#0E7C86" to "⚡"), "bike" to ("#188038" to "🚲"), "service" to ("#5F6368" to "£"),
    "place" to ("#7B61FF" to "★"))
private val POI_RANK = mapOf("shop" to 1, "food" to 2, "bike" to 2, "fuel" to 3, "health" to 3, "service" to 4, "place" to 5)

/**
 * VoltRoute home screen — a Google-Maps-style e-bike navigator.
 *
 * Flow: BROWSE (map + search) → PLACE (place card + Directions) → ROUTES (route
 * preview + Start) → NAV (live turn-by-turn) → ARRIVED.
 */
class MainActivity : android.app.Activity(), BleManager.Listener, LocationListener {

    private enum class Mode { BROWSE, PLACE, ROUTES, NAV, ARRIVED }

    private fun styleUrl() = "https://tiles.openfreemap.org/styles/" + (prefs().getString("mapStyle", "liberty") ?: "liberty")
    private var appliedStyle = ""
    private var speedAlertLim = -1; private var speedAlertAt = 0L
    private var lowBattWarned = false
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val BLUE = 0xFF1A73E8.toInt(); private val INK = 0xFF202124.toInt(); private val MUTED = 0xFF5F6368.toInt()
    private val LINE = 0xFFE3E5E8.toInt(); private val FAB = 0xFF0E7C86.toInt(); private val RED = 0xFFD93025.toInt()
    private val GREEN = 0xFF188038.toInt(); private val AMBER = 0xFFE37400.toInt(); private val NAVGREEN = 0xFF0B6E4F.toInt()
    private val SOFTBLUE = 0xFFE8F0FE.toInt(); private val CHIPBG = 0xFFF1F3F4.toInt()

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var srcAlt: GeoJsonSource? = null
    private var srcSel: GeoJsonSource? = null
    private var srcMe: GeoJsonSource? = null
    private var srcDest: GeoJsonSource? = null

    private lateinit var estimator: BatteryEstimator
    private lateinit var learner: TripLearner
    private lateinit var range: RangeLearner
    private lateinit var rideLog: RideLogger
    private val exec = Executors.newSingleThreadExecutor()        // routing
    private val searchExec = Executors.newCachedThreadPool()      // type-ahead (parallel, stale ones abort)
    private val limitExec = Executors.newSingleThreadExecutor()   // speed limits
    private val poiExec = Executors.newSingleThreadExecutor()     // shops/places on the map
    private var srcPoi: GeoJsonSource? = null
    @Volatile private var pois: List<Router.Poi> = emptyList()
    private var poiBox: DoubleArray? = null                      // s,w,n,e already loaded
    private var poiLoading = false
    private var poiFailAt = 0L
    private val ui = Handler(Looper.getMainLooper())
    private var sbH = 0

    // ---- state ----
    private var mode = Mode.BROWSE
    private var lastLoc: Location? = null
    private var odoLoc: Location? = null
    private var follow = true
    private var lastGestureAt = 0L
    private var haveFix = false
    private var lastPct = -1.0
    private var lastVolts = 0.0
    private var trafficOn = false
    private var routeProfile = "trekking"
    private var dest: Router.Place? = null
    private var routes: List<Router.Route> = emptyList()
    private var routeMsg = ""
    private var sel = 0
    private var searchSeq = 0
    private var suppressWatcher = false
    private var limitMph: Int? = null
    private var limitQueryLoc: Location? = null
    private var limitQueryAt = 0L
    private var limitBusy = false
    // navigation
    private var nav: NavRoute? = null
    private var navSeg = 0
    private var navAlong = 0.0
    private var jumpCount = 0
    private var navBearing = 0.0
    private var navStart = 0L
    private var navLearnable = true
    private var offRouteCount = 0
    private var lastReroute = 0L
    private val manStage = HashMap<Int, Int>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var muted = false
    private var tripStartMiles = 0.0

    // ---- views ----
    private lateinit var topBox: LinearLayout
    private lateinit var searchBar: EditText
    private lateinit var clearBtn: TextView
    private lateinit var tvBatt: TextView
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var resultsCard: LinearLayout
    private lateinit var routeCard: LinearLayout
    private lateinit var tvRouteTo: TextView
    private lateinit var trafficBtn: TextView
    private lateinit var compass: TextView
    private lateinit var myLocBtn: TextView
    private lateinit var gearBtn: TextView
    private lateinit var speedCard: LinearLayout
    private lateinit var hud: LinearLayout
    private lateinit var statusRow: LinearLayout
    private var wasCruise = false; private var wasReverse = false; private var lastFault = 0
    private var steadyRef = 0.0; private var steadySecs = 0.0; private var cruiseTipShown = false
    private lateinit var battCard: LinearLayout
    private lateinit var battFill: View
    private lateinit var tvRange: TextView
    private lateinit var tvVolts: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvLimit: TextView
    private lateinit var sheet: LinearLayout
    private lateinit var turnBanner: LinearLayout
    private lateinit var tvTurnIcon: TextView
    private lateinit var tvTurnDist: TextView
    private lateinit var tvTurnText: TextView
    private lateinit var tvThen: TextView
    private lateinit var navBottom: LinearLayout
    private lateinit var tvEta: TextView
    private lateinit var tvEtaSub: TextView
    private lateinit var muteBtn: TextView
    private lateinit var recenterPill: TextView
    private lateinit var bubbleLayer: FrameLayout
    private val bubbles = ArrayList<Pair<TextView, LatLng>>()

    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        window.statusBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val rid = resources.getIdentifier("status_bar_height", "dimen", "android")
        sbH = if (rid > 0) resources.getDimensionPixelSize(rid) else dp(24)

        BleManager.init(this)
        estimator = BatteryEstimator(this)
        learner = TripLearner(this)
        range = RangeLearner(this)
        rideLog = RideLogger(this)
        trafficOn = prefs().getBoolean("traffic", false) && trafficKey().isNotEmpty()
        muted = prefs().getBoolean("muted", false)
        routeProfile = prefs().getString("defProfile", "trekking") ?: "trekking"
        tts = TextToSpeech(this) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.UK
        }

        val frame = FrameLayout(this)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        frame.addView(mapView, FrameLayout.LayoutParams(MATCH, MATCH))
        bubbleLayer = FrameLayout(this)
        frame.addView(bubbleLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        mapView.getMapAsync { m -> map = m; setupMap(m) }

        buildTop(frame); buildRouteCard(frame); buildRight(frame); buildSpeed(frame)
        buildSheet(frame); buildNavUi(frame)
        setContentView(frame)

        val known = estimator.current
        if (known >= 0) { lastPct = known }
        showBatt(lastPct, false)
        applyMode()
        requestPerms()
        // check for a newer version a few seconds after opening
        ui.postDelayed({ Updater.check(this) { r, _ -> if (r != null && !isFinishing && mode != Mode.NAV) Updater.prompt(this, r) } }, 2500)
    }

    // ============================ map ============================
    private fun setupMap(m: MapLibreMap) {
        m.uiSettings.isCompassEnabled = false
        m.uiSettings.isLogoEnabled = false
        m.uiSettings.setAttributionMargins(dp(190), 0, 0, dp(8))
        val p = getSharedPreferences("map", Context.MODE_PRIVATE)
        m.cameraPosition = CameraPosition.Builder()
            .target(LatLng(p.getFloat("lat", 51.4545f).toDouble(), p.getFloat("lon", -2.5879f).toDouble()))
            .zoom(15.5).build()
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                follow = false; lastGestureAt = System.currentTimeMillis()
                if (mode == Mode.NAV) recenterPill.visibility = View.VISIBLE
            }
        }
        m.addOnCameraMoveListener { updateCompass(); positionBubbles() }
        m.addOnCameraIdleListener { positionBubbles(); loadPois() }
        m.addOnMapClickListener { ll ->
            if (mode == Mode.BROWSE || mode == Mode.PLACE) {
                hideResults(); hideKeyboard()
                val hit = poiAt(ll) ?: basemapPoiAt(ll)
                if (hit != null) {
                    selectPlace(Router.Place(hit.name, hit.lat, hit.lon, hit.name, hit.sub, hit.cat))
                    if (hit.sub.isEmpty()) fillAddress(hit.lat, hit.lon, false)
                    return@addOnMapClickListener true
                }
            }
            false
        }
        m.addOnMapLongClickListener { ll ->
            if (mode == Mode.BROWSE || mode == Mode.PLACE) { dropPin(ll); true } else false
        }
        appliedStyle = styleUrl()
        m.setStyle(Style.Builder().fromUri(appliedStyle)) { st -> addLayers(st) }
    }

    private fun addLayers(st: Style) {
        try { polishBasemap(st) } catch (_: Exception) {}
        val empty = fc(emptyList())
        srcAlt = GeoJsonSource("route-alt").also { it.setGeoJson(empty); st.addSource(it) }
        srcSel = GeoJsonSource("route-sel").also { it.setGeoJson(empty); st.addSource(it) }
        srcDest = GeoJsonSource("dest").also { it.setGeoJson(empty); st.addSource(it) }
        srcMe = GeoJsonSource("me").also { it.setGeoJson(empty); st.addSource(it) }
        st.addImage("nav-arrow", makeArrow())
        for ((g, v) in POI_STYLE) st.addImage("poi-$g", makePoiIcon(v.first, v.second))
        srcPoi = GeoJsonSource("pois").also { it.setGeoJson(poiJson(pois)); st.addSource(it) }
        val rnd = arrayOf(PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
        st.addLayer(LineLayer("route-alt-casing", "route-alt").withProperties(PropertyFactory.lineColor("#8FA3BC"), PropertyFactory.lineWidth(10f), *rnd))
        st.addLayer(LineLayer("route-alt-l", "route-alt").withProperties(PropertyFactory.lineColor("#BCCBDF"), PropertyFactory.lineWidth(7f), *rnd))
        st.addLayer(LineLayer("route-sel-casing", "route-sel").withProperties(PropertyFactory.lineColor("#1A47C9"), PropertyFactory.lineWidth(11f), *rnd))
        st.addLayer(LineLayer("route-sel-l", "route-sel").withProperties(PropertyFactory.lineColor("#4285F4"), PropertyFactory.lineWidth(7.5f), *rnd))
        st.addLayer(CircleLayer("dest-l", "dest").withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#EA4335"),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3.5f)))
        st.addLayer(CircleLayer("me-halo", "me").withProperties(
            PropertyFactory.circleRadius(22f), PropertyFactory.circleColor("#1A73E8"), PropertyFactory.circleOpacity(0.16f)))
        st.addLayer(CircleLayer("me-dot", "me").withProperties(
            PropertyFactory.circleRadius(8.5f), PropertyFactory.circleColor("#1A73E8"),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f)))
        st.addLayer(SymbolLayer("me-arrow", "me").withProperties(
            PropertyFactory.iconImage("nav-arrow"), PropertyFactory.iconRotate(0f),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconSize(1.2f), PropertyFactory.visibility(Property.NONE)))
        st.addLayerBelow(SymbolLayer("poi-l", "pois").withProperties(
            PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconSize(0.5f),
            PropertyFactory.textField(Expression.get("name")), PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(12.5f), PropertyFactory.textColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.textHaloColor(if (appliedStyle.endsWith("dark")) "#1F1F1F" else "#FFFFFF"), PropertyFactory.textHaloWidth(1.6f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT), PropertyFactory.textOffset(arrayOf(1.2f, 0f)),
            PropertyFactory.textMaxWidth(9f), PropertyFactory.textOptional(true),
            PropertyFactory.symbolSortKey(Expression.get("rank")),
            PropertyFactory.visibility(if (prefs().getBoolean("showPois", true)) Property.VISIBLE else Property.NONE)).also { it.setMinZoom(15.5f) }, "route-alt-casing")
        if (trafficOn) addTrafficLayer(st)
        redrawRoutes()
        lastLoc?.let { srcMe?.setGeoJson(pointJson(it.latitude, it.longitude)) }
        dest?.let { srcDest?.setGeoJson(pointJson(it.lat, it.lon)) }
        setNavLayers(mode == Mode.NAV)
    }

    /**
     * Restyles the base map to feel cleaner, closer to Google Maps: wider, clearer streets,
     * soft light buildings, slightly bigger street names.
     */
    private fun polishBasemap(st: Style) {
        val dark = appliedStyle.endsWith("dark")
        fun z(vararg st: Pair<Float, Float>) = Expression.interpolate(Expression.exponential(1.6f), Expression.zoom(),
            *st.map { Expression.stop(it.first, it.second) }.toTypedArray())
        for (l in st.layers) {
            val id = l.id
            when (l) {
                is LineLayer -> {
                    if (l.sourceLayer != "transportation") continue
                    if (id.contains("rail") || id.contains("path") || id.contains("pedestrian") || id.contains("track")
                        || id.contains("ferry") || id.contains("aerialway") || id.contains("area")) continue
                    val casing = id.contains("casing")
                    val w = when {
                        id.contains("motorway") || id.contains("trunk") || id.contains("primary") ->
                            z(10f to 1.5f, 13f to 3f, 15f to 7f, 17f to 18f, 19f to 44f)
                        id.contains("secondary") || id.contains("tertiary") ->
                            z(11f to 1f, 13f to 2.2f, 15f to 6f, 17f to 15f, 19f to 38f)
                        id.contains("minor") || id.contains("street") || id.contains("link") ->
                            z(13f to 0.8f, 14f to 2f, 15f to 4.5f, 17f to 12f, 19f to 30f)
                        id.contains("service") -> z(14f to 0.8f, 16f to 3f, 18f to 9f, 19f to 14f)
                        else -> null
                    } ?: continue
                    if (casing) {
                        // casing = road width plus a thin edge
                        val c = when {
                            id.contains("motorway") || id.contains("trunk") || id.contains("primary") ->
                                z(10f to 2.5f, 13f to 4.5f, 15f to 9.5f, 17f to 22f, 19f to 50f)
                            id.contains("secondary") || id.contains("tertiary") ->
                                z(11f to 2f, 13f to 3.5f, 15f to 8f, 17f to 18.5f, 19f to 43f)
                            id.contains("service") -> z(14f to 1.5f, 16f to 4.5f, 18f to 11f, 19f to 17f)
                            else -> z(13f to 1.6f, 14f to 3f, 15f to 6f, 17f to 14.5f, 19f to 34f)
                        }
                        l.setProperties(PropertyFactory.lineWidth(c))
                        if (!dark) l.setProperties(PropertyFactory.lineColor("#D3D6DB"))
                    } else {
                        l.setProperties(PropertyFactory.lineWidth(w))
                        if (!dark && (id.contains("minor") || id.contains("street") || id.contains("service")
                                    || id.contains("secondary") || id.contains("tertiary")))
                            l.setProperties(PropertyFactory.lineColor("#FFFFFF"))
                    }
                }
                is FillExtrusionLayer -> if (l.sourceLayer == "building") {
                    l.setProperties(
                        PropertyFactory.fillExtrusionColor(if (dark) "#3A3F47" else "#E1E3E7"),
                        PropertyFactory.fillExtrusionOpacity(if (dark) 0.9f else 0.92f))
                }
                is FillLayer -> if (l.sourceLayer == "building") {
                    l.setProperties(PropertyFactory.fillColor(if (dark) "#33373E" else "#E8E9EC"), PropertyFactory.fillOutlineColor(if (dark) "#2A2D33" else "#DCDEE2"))
                }
                is SymbolLayer -> if (l.sourceLayer == "transportation_name") {
                    l.setProperties(PropertyFactory.textSize(z(13f to 11f, 16f to 13.5f, 18f to 16f)),
                        PropertyFactory.textHaloWidth(1.8f))
                }
            }
        }
        // softer, Google-like land colour on the standard style
        if (!dark) st.getLayer("background")?.setProperties(PropertyFactory.backgroundColor("#F5F5F3"))
    }

    /** Google-style navigation chevron. */
    private fun makeArrow(): Bitmap {
        val s = 140
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val path = Path().apply {
            moveTo(70f, 12f); lineTo(118f, 122f); lineTo(70f, 96f); lineTo(22f, 122f); close()
        }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000; style = Paint.Style.FILL }
        c.save(); c.translate(0f, 5f); c.drawPath(path, shadow); c.restore()
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 14f; strokeJoin = Paint.Join.ROUND
        })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE; style = Paint.Style.FILL })
        return bmp
    }

    private fun setNavLayers(on: Boolean) {
        val st = map?.style ?: return
        st.getLayer("me-dot")?.setProperties(PropertyFactory.visibility(if (on) Property.NONE else Property.VISIBLE))
        st.getLayer("me-halo")?.setProperties(PropertyFactory.visibility(if (on) Property.NONE else Property.VISIBLE))
        st.getLayer("me-arrow")?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE))
        st.getLayer("route-sel-casing")?.setProperties(PropertyFactory.lineWidth(if (on) 17f else 11f))
        st.getLayer("route-sel-l")?.setProperties(PropertyFactory.lineWidth(if (on) 12f else 7.5f))
    }

    // ============================ UI build ============================
    private fun buildTop(frame: FrameLayout) {
        topBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pill = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(8), 0)
            background = shape(Color.WHITE, dp(30)); elevation = dp(6).toFloat()
        }
        pill.addView(TextView(this).apply { text = "📍"; textSize = 20f })
        searchBar = EditText(this).apply {
            hint = "Search here"; setHintTextColor(MUTED); setTextColor(INK); textSize = 18f
            background = null; setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(12), dp(14), dp(4), dp(14))
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) { val q = text.toString().trim(); if (q.isNotEmpty()) runSuggest(q, true); true } else false
            }
            setOnFocusChangeListener { _, has -> if (has && text.isEmpty() && mode == Mode.BROWSE) showRecent() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) { if (!suppressWatcher) onQueryChanged(e?.toString() ?: "") }
            })
        }
        pill.addView(searchBar, LinearLayout.LayoutParams(0, WRAP, 1f))
        clearBtn = TextView(this).apply {
            text = "✕"; textSize = 17f; setTextColor(MUTED); setPadding(dp(10), dp(8), dp(10), dp(8)); visibility = View.GONE
            setOnClickListener { clearSearch() }
        }
        pill.addView(clearBtn)
        topBox.addView(pill, LinearLayout.LayoutParams(MATCH, dp(60)))

        chipsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
        val chips = LinearLayout(this).apply { setPadding(dp(2), dp(10), dp(2), dp(8)) }
        chips.addView(chip("🏠  Home") { goHome() })
        val cats = listOf("🛒  Groceries" to "supermarket", "☕  Cafés" to "cafe", "🍽  Restaurants" to "restaurant",
            "🔧  Bike shops" to "bicycle shop", "🅿  Bike parking" to "bicycle parking", "⛽  Fuel" to "fuel")
        for ((label, q) in cats) chips.addView(chip(label) { categorySearch(label.substringAfter("  "), q) })
        chipsScroll.addView(chips)
        topBox.addView(chipsScroll)

        resultsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(Color.WHITE, dp(18))
            elevation = dp(6).toFloat(); visibility = View.GONE
        }
        topBox.addView(resultsCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        frame.addView(topBox, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP; setMargins(dp(12), sbH + dp(8), dp(12), 0)
        })
    }

    private fun buildRouteCard(frame: FrameLayout) {
        routeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = shape(Color.WHITE, dp(20)); elevation = dp(6).toFloat()
        }
        val fromRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fromRow.addView(TextView(this).apply {
            text = "←"; textSize = 22f; setTextColor(INK); setPadding(dp(2), 0, dp(14), 0)
            setOnClickListener { setMode(Mode.PLACE) }
        })
        fromRow.addView(TextView(this).apply { text = "◉"; textSize = 15f; setTextColor(BLUE); setPadding(0, 0, dp(12), 0) })
        fromRow.addView(TextView(this).apply { text = "Your location"; textSize = 17f; setTextColor(BLUE) })
        routeCard.addView(fromRow)
        routeCard.addView(View(this).apply { setBackgroundColor(LINE) },
            LinearLayout.LayoutParams(MATCH, dp(1)).apply { setMargins(dp(64), dp(10), 0, dp(10)) })
        val toRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(38), 0, 0, 0) }
        toRow.addView(TextView(this).apply { text = "📍"; textSize = 15f; setPadding(0, 0, dp(11), 0) })
        tvRouteTo = TextView(this).apply { textSize = 17f; setTextColor(INK); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        toRow.addView(tvRouteTo, LinearLayout.LayoutParams(0, WRAP, 1f))
        routeCard.addView(toRow)
        frame.addView(routeCard, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP; setMargins(dp(12), sbH + dp(8), dp(12), 0)
        })
    }

    private fun buildRight(frame: FrameLayout) {
        trafficBtn = TextView(this).apply {
            text = "🚦"; textSize = 20f; gravity = Gravity.CENTER
            background = shape(if (trafficOn) SOFTBLUE else Color.WHITE, dp(24)); elevation = dp(4).toFloat()
            setOnClickListener { toggleTraffic() }
        }
        frame.addView(trafficBtn, FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.TOP or Gravity.END; setMargins(0, sbH + dp(136), dp(15), 0)
        })
        compass = TextView(this).apply {
            text = "▲"; textSize = 18f; setTextColor(RED); gravity = Gravity.CENTER
            background = shape(Color.WHITE, dp(24)); elevation = dp(4).toFloat(); visibility = View.GONE
            setOnClickListener { map?.animateCamera(CameraUpdateFactory.bearingTo(0.0), 400) }
        }
        frame.addView(compass, FrameLayout.LayoutParams(dp(46), dp(46)).apply {
            gravity = Gravity.TOP or Gravity.END; setMargins(0, sbH + dp(194), dp(16), 0)
        })
        myLocBtn = TextView(this).apply {
            text = "◉"; textSize = 26f; setTextColor(BLUE); gravity = Gravity.CENTER
            background = shape(Color.WHITE, dp(18)); elevation = dp(4).toFloat()
            setOnClickListener { recenter() }
        }
        frame.addView(myLocBtn, FrameLayout.LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.BOTTOM or Gravity.END; setMargins(0, 0, dp(16), dp(96))
        })
        gearBtn = TextView(this).apply {
            text = "⚙"; textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = shape(FAB, dp(18)); elevation = dp(6).toFloat()
            setOnClickListener { startActivity(Intent(this@MainActivity, AppSettingsActivity::class.java)) }
        }
        frame.addView(gearBtn, FrameLayout.LayoutParams(dp(60), dp(60)).apply {
            gravity = Gravity.BOTTOM or Gravity.END; setMargins(0, 0, dp(14), dp(22))
        })
    }

    private fun buildSpeed(frame: FrameLayout) {
        speedCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(14), dp(8))
            background = shape(Color.WHITE, dp(20)); elevation = dp(5).toFloat()
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        tvSpeed = TextView(this).apply { text = "0"; textSize = 34f; setTextColor(INK); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        col.addView(tvSpeed)
        col.addView(TextView(this).apply { text = "mph"; textSize = 12f; setTextColor(MUTED); gravity = Gravity.CENTER })
        speedCard.addView(col)
        tvLimit = TextView(this).apply {
            textSize = 17f; setTextColor(INK); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(4), RED) }
            visibility = View.GONE
        }
        speedCard.addView(tvLimit, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(12) })
        // battery block, floating next to the speed
        battCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = shape(Color.WHITE, dp(20)); elevation = dp(5).toFloat()
            setOnClickListener { showBatteryDetails() }
        }
        tvBatt = TextView(this).apply { text = "–%"; textSize = 24f; setTextColor(INK); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        battCard.addView(tvBatt)
        val track = FrameLayout(this).apply { background = shape(0xFFE8EAED.toInt(), dp(3)) }
        battFill = View(this).apply { background = shape(MUTED, dp(3)) }
        track.addView(battFill, FrameLayout.LayoutParams(0, MATCH))
        battCard.addView(track, LinearLayout.LayoutParams(dp(64), dp(6)).apply { topMargin = dp(3); bottomMargin = dp(4) })
        tvRange = TextView(this).apply { text = "– mi"; textSize = 12f; setTextColor(MUTED); gravity = Gravity.CENTER }
        battCard.addView(tvRange)
        tvVolts = TextView(this).apply { text = ""; textSize = 12f; setTextColor(MUTED); gravity = Gravity.CENTER; visibility = View.GONE }
        battCard.addView(tvVolts)
        val hudRow = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        hudRow.addView(speedCard)
        hudRow.addView(battCard, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) })
        // bike status chips (cruise / reverse / brake / anti-theft / cruise countdown) above the cards
        statusRow = LinearLayout(this).apply { visibility = View.GONE }
        hud = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hud.addView(statusRow, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(8) })
        hud.addView(hudRow)
        frame.addView(hud, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.START; setMargins(dp(14), 0, 0, dp(22))
        })
    }

    private fun buildSheet(frame: FrameLayout) {
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); val r = dp(26).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            elevation = dp(14).toFloat(); isClickable = true
        }
        frame.addView(sheet, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.BOTTOM })
    }

    private fun buildNavUi(frame: FrameLayout) {
        // Turn banner (top)
        turnBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(NAVGREEN, dp(22)); elevation = dp(10).toFloat()
        }
        val main = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(18), dp(14)) }
        tvTurnIcon = TextView(this).apply { textSize = 46f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(0, 0, dp(14), 0) }
        main.addView(tvTurnIcon)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvTurnDist = TextView(this).apply { textSize = 30f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) }
        tvTurnText = TextView(this).apply { textSize = 18f; setTextColor(0xFFE6F4EA.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        col.addView(tvTurnDist); col.addView(tvTurnText)
        main.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        turnBanner.addView(main)
        tvThen = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
            setPadding(dp(18), dp(8), dp(18), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF07563D.toInt()); val r = dp(22).toFloat(); cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
        }
        turnBanner.addView(tvThen, LinearLayout.LayoutParams(MATCH, WRAP))
        frame.addView(turnBanner, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP; setMargins(dp(10), sbH + dp(6), dp(10), 0)
        })

        // Bottom bar
        navBottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); val r = dp(24).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            elevation = dp(14).toFloat(); isClickable = true
        }
        navBottom.addView(roundIcon("✕") { stopNav() })
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        tvEta = TextView(this).apply { textSize = 24f; setTextColor(GREEN); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        tvEtaSub = TextView(this).apply { textSize = 15f; setTextColor(MUTED); gravity = Gravity.CENTER }
        mid.addView(tvEta); mid.addView(tvEtaSub)
        navBottom.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        muteBtn = roundIcon(if (muted) "🔇" else "🔊") {
            muted = !muted; prefs().edit().putBoolean("muted", muted).apply()
            muteBtn.text = if (muted) "🔇" else "🔊"
            if (muted) tts?.stop()
        }
        navBottom.addView(muteBtn)
        frame.addView(navBottom, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.BOTTOM })

        recenterPill = TextView(this).apply {
            text = "⌖  Re-centre"; textSize = 15f; setTextColor(BLUE); setTypeface(null, Typeface.BOLD)
            setPadding(dp(18), dp(12), dp(18), dp(12)); background = shape(Color.WHITE, dp(24)); elevation = dp(6).toFloat()
            setOnClickListener { follow = true; recenterPill.visibility = View.GONE; lastLoc?.let { onNavFix(it) } }
        }
        frame.addView(recenterPill, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.END; setMargins(0, 0, dp(14), dp(120))
        })
    }

    // ============================ modes ============================
    private fun setMode(m: Mode) {
        val prev = mode
        mode = m
        if (prev == Mode.NAV && m != Mode.NAV) leaveNavCamera()
        applyMode()
        when (m) {
            Mode.PLACE -> { fillPlaceSheet(); dest?.let { flyTo(it) } }
            Mode.ROUTES -> { fillRoutesSheet(); fitRoute() }
            else -> {}
        }
    }

    private fun applyMode() {
        fun vis(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }
        vis(topBox, mode == Mode.BROWSE || mode == Mode.PLACE)
        vis(chipsScroll, mode == Mode.BROWSE && resultsCard.visibility != View.VISIBLE)
        vis(routeCard, mode == Mode.ROUTES)
        vis(sheet, mode == Mode.PLACE || mode == Mode.ROUTES || mode == Mode.ARRIVED)
        vis(hud, mode == Mode.BROWSE || mode == Mode.NAV)
        vis(myLocBtn, mode == Mode.BROWSE)
        vis(gearBtn, mode == Mode.BROWSE)
        vis(trafficBtn, mode != Mode.ARRIVED)
        vis(turnBanner, mode == Mode.NAV)
        vis(navBottom, mode == Mode.NAV)
        vis(recenterPill, mode == Mode.NAV && !follow)
        if (mode != Mode.BROWSE && mode != Mode.PLACE) resultsCard.visibility = View.GONE
        (hud.layoutParams as FrameLayout.LayoutParams).bottomMargin = if (mode == Mode.NAV) dp(124) else dp(22)
        hud.requestLayout()
        setNavLayers(mode == Mode.NAV)
        val st = map?.style
        st?.getLayer("route-alt-l")?.setProperties(PropertyFactory.visibility(if (mode == Mode.ROUTES) Property.VISIBLE else Property.NONE))
        st?.getLayer("route-alt-casing")?.setProperties(PropertyFactory.visibility(if (mode == Mode.ROUTES) Property.VISIBLE else Property.NONE))
        if (mode != Mode.ROUTES) { bubbleLayer.removeAllViews(); bubbles.clear() } else buildBubbles()
        if (mode == Mode.NAV || prefs().getBoolean("keepOn", false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun closeAll() {
        dest = null; routes = emptyList(); nav = null
        redrawRoutes(); srcDest?.setGeoJson(fc(emptyList()))
        setSearchText(""); hideResults()
        setMode(Mode.BROWSE)
    }

    // ============================ search ============================
    private fun onQueryChanged(q: String) {
        clearBtn.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        ui.removeCallbacksAndMessages("search")
        val t = q.trim()
        if (t.isEmpty()) { searchSeq++; showRecent(); return }
        if (t.length < 2) return
        ui.postAtTime({ runSuggest(t, false) }, "search", android.os.SystemClock.uptimeMillis() + 220)
    }

    /**
     * Two-phase search: instant type-ahead first, then (once you pause, or press search)
     * the deep search that understands "street + shop" and finds nearby shops by name.
     */
    private fun runSuggest(q: String, enter: Boolean) {
        val seq = ++searchSeq
        val near = nearPt()
        if (resultsCard.visibility != View.VISIBLE || enter) showResultsMessage("Searching…")
        val wantDeep = enter || q.trim().contains(' ') || q.trim().length >= 4
        val alive = { seq == searchSeq }
        // watchdog: never sit on "Searching…" forever
        ui.postDelayed({
            if (seq == searchSeq && resultsCard.childCount == 1 && (resultsCard.getChildAt(0) as? TextView)?.text == "Searching…")
                showResultsMessage("Search is slow right now — check your signal and try again")
        }, 15000)
        // instant: shops already loaded / drawn on the map around you
        val local = localMatches(q)
        if (local.isNotEmpty() && !enter) showPlaces(Router.rank(local, q, null, null, near), near, q, null, false)
        searchExec.execute {
            if (!enter) {
                val quick = Router.smartSearch(q, near, false, alive, local)
                if (!alive()) return@execute
                runOnUiThread { if (seq == searchSeq) showPlaces(quick, near, q, null, !wantDeep) }
                if (!wantDeep) return@execute
                try { Thread.sleep(450) } catch (_: InterruptedException) { return@execute }
                if (seq != searchSeq) return@execute
            }
            if (!alive()) return@execute
            val deep = Router.smartSearch(q, near, true, alive, local)
            runOnUiThread {
                if (seq != searchSeq) return@runOnUiThread
                val best = localFirst(deep, near)
                if (enter && best.isNotEmpty()) selectPlace(best[0])      // search key: straight to the top match
                else showPlaces(deep, near, q, null, true)
            }
        }
    }

    /** Places matching the query among what's already on the map (no network needed). */
    private fun localMatches(q: String): List<Router.Place> {
        val words = Router.searchWords(q)
        if (words.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Router.Place>()
        fun hit(name: String): Boolean {
            val n = Router.compact(name)
            val parts = name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            return words.any { w -> n.contains(w) || (w.length >= 5 && parts.any { Router.oneEdit(it, w) }) }
        }
        for (p in pois) if (hit(p.name)) out.putIfAbsent(p.name + p.lat, Router.Place(p.name, p.lat, p.lon, p.name, p.sub, p.cat))
        // places you've searched before
        try {
            val arr = JSONArray(prefs().getString("recent", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pl = Router.Place(o.optString("n"), o.optDouble("lat"), o.optDouble("lon"), o.optString("l"), o.optString("s"), o.optString("c"))
                if (hit(pl.title) || hit(pl.subtitle)) out.putIfAbsent(pl.title + pl.lat, pl)
            }
        } catch (_: Exception) {}
        try {
            val m = map; val st = m?.style
            if (m != null && st != null) {
                val ids = st.layers.filter { it.id != "poi-l" && ((it as? SymbolLayer)?.sourceLayer == "poi" || it.id.startsWith("poi")) }
                    .map { it.id }.toTypedArray()
                if (ids.isNotEmpty()) for (f in m.queryRenderedFeatures(android.graphics.RectF(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat()), *ids)) {
                    val name = (if (f.hasProperty("name")) f.getStringProperty("name") else null) ?: continue
                    val g = f.geometry() as? org.maplibre.geojson.Point ?: continue
                    if (!hit(name)) continue
                    val cls = if (f.hasProperty("subclass")) f.getStringProperty("subclass") ?: "" else ""
                    out.putIfAbsent(name + g.latitude(), Router.Place(name, g.latitude(), g.longitude(), name, "", cls))
                }
                // towns / villages / suburbs on screen ("Winterbourne" in "winterborne coop")
                val placeIds = st.layers.filter { (it as? SymbolLayer)?.sourceLayer == "place" }.map { it.id }.toTypedArray()
                if (placeIds.isNotEmpty()) for (f in m.queryRenderedFeatures(android.graphics.RectF(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat()), *placeIds)) {
                    val name = (if (f.hasProperty("name")) f.getStringProperty("name") else null) ?: continue
                    val g = f.geometry() as? org.maplibre.geojson.Point ?: continue
                    if (!hit(name)) continue
                    val cls = if (f.hasProperty("class")) f.getStringProperty("class") ?: "village" else "village"
                    out.putIfAbsent(name + g.latitude(), Router.Place(name, g.latitude(), g.longitude(), name, "", cls))
                }
            }
        } catch (_: Exception) {}
        return out.values.toList()
    }

    private fun categorySearch(label: String, q: String) {
        setSearchText(label); hideKeyboard()
        val seq = ++searchSeq
        val near = nearPt()
        showResultsMessage("Finding $label near you…")
        searchExec.execute {
            val r = Router.geocode(q, near, true)
            runOnUiThread { if (seq == searchSeq) showPlaces(r, near, "", "$label nearby", true) }
        }
    }

    private fun nearPt(): Router.Pt? = lastLoc?.let { Router.Pt(it.latitude, it.longitude) }
        ?: map?.cameraPosition?.target?.let { Router.Pt(it.latitude, it.longitude) }

    private fun showPlaces(places: List<Router.Place>, near: Router.Pt?, query: String, header: String?, final: Boolean = true) {
        if (places.isEmpty() && final && resultsCard.childCount > 1) return   // keep earlier results
        if (places.isEmpty()) {
            if (final) showResultsMessage("No matches for “$query” — try the shop name or street") else showResultsMessage("Searching…")
            return
        }
        resultsCard.removeAllViews()
        if (header != null) resultsCard.addView(sectionLabel(header))
        val sorted = if (near != null && query.isEmpty()) places.sortedBy { Geo.dist(near.lat, near.lon, it.lat, it.lon) } else places
        val list = localFirst(sorted, near)
        list.take(7).forEachIndexed { i, p ->
            if (i > 0) resultsCard.addView(divider())
            resultsCard.addView(placeRow(p, near, query, catEmoji(p.cat)))
        }
        resultsCard.visibility = View.VISIBLE; chipsScroll.visibility = View.GONE
    }

    private fun placeRow(p: Router.Place, near: Router.Pt?, query: String, icon: String): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(11), dp(16), dp(11))
            setOnClickListener { selectPlace(p) }
        }
        row.addView(TextView(this).apply {
            text = icon; textSize = 17f; gravity = Gravity.CENTER; background = shape(CHIPBG, dp(18))
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = highlight(p.title, query); textSize = 16f; setTextColor(INK); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (p.subtitle.isNotEmpty()) col.addView(TextView(this).apply {
            text = p.subtitle; textSize = 13f; setTextColor(MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (near != null) row.addView(TextView(this).apply {
            text = fmtMiles(Geo.dist(near.lat, near.lon, p.lat, p.lon)); textSize = 13f; setTextColor(MUTED); setPadding(dp(8), 0, 0, 0)
        })
        return row
    }

    /** Bold the part of the title that matches what you typed, like Google. */
    private fun highlight(title: String, query: String): CharSequence {
        if (query.isEmpty()) return SpannableString(title).apply { setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val sp = SpannableString(title)
        val low = title.lowercase()
        for (w in query.lowercase().split(" ").filter { it.length >= 2 }) {
            var i = low.indexOf(w)
            while (i >= 0) { sp.setSpan(StyleSpan(Typeface.BOLD), i, i + w.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); i = low.indexOf(w, i + w.length) }
        }
        return sp
    }

    /** If anything is within ~25 miles, drop results more than ~60 miles away (other cities' branches). */
    private fun localFirst(places: List<Router.Place>, near: Router.Pt?): List<Router.Place> {
        if (near == null) return places
        val d = places.map { Geo.dist(near.lat, near.lon, it.lat, it.lon) / 1609.34 }
        return if (d.any { it < 25 }) places.filterIndexed { i, _ -> d[i] < 60 } else places
    }

    private fun showResultsMessage(msg: String) {
        resultsCard.removeAllViews()
        resultsCard.addView(TextView(this).apply { text = msg; textSize = 15f; setTextColor(MUTED); setPadding(dp(18), dp(16), dp(18), dp(16)) })
        resultsCard.visibility = View.VISIBLE; chipsScroll.visibility = View.GONE
    }

    private fun hideResults() {
        resultsCard.visibility = View.GONE
        if (mode == Mode.BROWSE) chipsScroll.visibility = View.VISIBLE
    }

    private fun clearSearch() {
        setSearchText(""); searchSeq++
        if (mode == Mode.PLACE) closeAll() else showRecent()
        searchBar.requestFocus(); showKeyboard()
    }

    private fun setSearchText(t: String) {
        suppressWatcher = true; searchBar.setText(t); searchBar.setSelection(t.length); suppressWatcher = false
        clearBtn.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    // ============================ shops & places on the map ============================
    private fun loadPois() {
        val m = map ?: return
        if (m.cameraPosition.zoom < 14.5) return
        val t = m.cameraPosition.target ?: return
        loadPoisAround(t.latitude, t.longitude)
    }

    /** Loads places in a ~1.5 km box around a point (works while tilted/navigating too). */
    private fun loadPoisAround(lat: Double, lon: Double) {
        if (poiLoading || !prefs().getBoolean("showPois", true)) return
        val have = poiBox
        if (have != null) {   // still well inside what's loaded?
            val iLat = (have[2] - have[0]) * 0.25; val iLon = (have[3] - have[1]) * 0.25
            if (lat > have[0] + iLat && lat < have[2] - iLat && lon > have[1] + iLon && lon < have[3] - iLon) return
        }
        val now = System.currentTimeMillis()
        if (now - poiFailAt < 15000) return            // back off briefly after a failure
        val box = doubleArrayOf(lat - 0.0135, lon - 0.021, lat + 0.0135, lon + 0.021)
        poiLoading = true
        poiExec.execute {
            val got = Router.fetchPois(box[0], box[1], box[2], box[3])
            runOnUiThread {
                poiLoading = false
                if (got.isEmpty()) { poiFailAt = System.currentTimeMillis(); return@runOnUiThread }
                pois = got; poiBox = box
                srcPoi?.setGeoJson(poiJson(got))
            }
        }
    }

    private fun poiJson(list: List<Router.Poi>): String {
        val fs = JSONArray()
        for (p in list) {
            val st = POI_STYLE[p.group] ?: POI_STYLE.getValue("place")
            fs.put(JSONObject().put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat)))
                .put("properties", JSONObject().put("name", p.name).put("icon", "poi-${p.group}")
                    .put("color", st.first).put("rank", POI_RANK[p.group] ?: 5)))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", fs).toString()
    }

    /** Nearest drawn place within a finger's width of the tap. */
    private fun poiAt(ll: LatLng): Router.Poi? {
        val m = map ?: return null
        if (m.cameraPosition.zoom < 15.5 || !prefs().getBoolean("showPois", true)) return null
        val tap = m.projection.toScreenLocation(ll)
        var best: Router.Poi? = null; var bestD = dp(28).toFloat()
        for (p in pois) {
            val pt = m.projection.toScreenLocation(LatLng(p.lat, p.lon))
            val d = Math.hypot((pt.x - tap.x).toDouble(), (pt.y - tap.y).toDouble()).toFloat()
            if (d < bestD) { bestD = d; best = p }
        }
        return best
    }

    /** Shops/places drawn by the base map itself (its own icons), found under the finger. */
    private fun basemapPoiAt(ll: LatLng): Router.Poi? {
        val m = map ?: return null
        val st = m.style ?: return null
        val pt = m.projection.toScreenLocation(ll)
        val r = dp(22).toFloat()
        val ids = st.layers.filter { it.id != "poi-l" && ((it as? SymbolLayer)?.sourceLayer == "poi" || it.id.startsWith("poi")) }
            .map { it.id }.toTypedArray()
        if (ids.isEmpty()) return null
        val feats = m.queryRenderedFeatures(android.graphics.RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r), *ids)
        var best: Router.Poi? = null; var bestD = Float.MAX_VALUE
        for (f in feats) {
            val name = (if (f.hasProperty("name")) f.getStringProperty("name") else null) ?: continue
            val g = f.geometry() as? org.maplibre.geojson.Point ?: continue
            val sp = m.projection.toScreenLocation(LatLng(g.latitude(), g.longitude()))
            val d = Math.hypot((sp.x - pt.x).toDouble(), (sp.y - pt.y).toDouble()).toFloat()
            if (d < bestD) {
                bestD = d
                val cls = if (f.hasProperty("subclass")) f.getStringProperty("subclass") else if (f.hasProperty("class")) f.getStringProperty("class") else ""
                best = Router.Poi(name, g.latitude(), g.longitude(), cls ?: "", "place", "")
            }
        }
        return best
    }

    /** Long-press: drop a pin, show its postcode and address, with Directions. */
    private fun dropPin(ll: LatLng) {
        val pin = Router.Place("Dropped pin", ll.latitude, ll.longitude, "Dropped pin",
            String.format(Locale.US, "%.5f, %.5f", ll.latitude, ll.longitude), "")
        hideResults(); hideKeyboard()
        dest = pin; routes = emptyList(); nav = null; sel = 0
        setSearchText(pin.title)
        srcDest?.setGeoJson(pointJson(pin.lat, pin.lon))
        redrawRoutes()
        setMode(Mode.PLACE)
        fillAddress(ll.latitude, ll.longitude, true)
    }

    /** Looks up the postcode/address and updates the open place card. */
    private fun fillAddress(lat: Double, lon: Double, isPin: Boolean) {
        searchExec.execute {
            val r = Router.reverse(lat, lon) ?: return@execute
            runOnUiThread {
                val d = dest ?: return@runOnUiThread
                if (d.lat != lat || d.lon != lon || mode != Mode.PLACE) return@runOnUiThread
                val (pc, line) = r
                dest = if (isPin) d.copy(name = pc.ifEmpty { line.substringBefore(",") }.ifEmpty { "Dropped pin" },
                        label = pc.ifEmpty { "Dropped pin" }, sub = line + "\n" + String.format(Locale.US, "%.5f, %.5f", lat, lon))
                    else d.copy(sub = listOf(line, pc).filter { it.isNotEmpty() }.joinToString(", "))
                setSearchText(dest!!.title)
                fillPlaceSheet()
            }
        }
    }

    private fun makePoiIcon(color: String, glyph: String): Bitmap {
        val sz = 72
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE; c.drawCircle(sz / 2f, sz / 2f, sz / 2f, paint)
        paint.color = Color.parseColor(color); c.drawCircle(sz / 2f, sz / 2f, sz / 2f - 5f, paint)
        paint.color = Color.WHITE; paint.textSize = 36f; paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        val y = sz / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(glyph, sz / 2f, y, paint)
        return bmp
    }

    // ============================ place ============================
    private fun selectPlace(p: Router.Place) {
        hideKeyboard(); hideResults()
        dest = p; routes = emptyList(); nav = null; sel = 0
        saveRecent(p)
        setSearchText(p.title)
        srcDest?.setGeoJson(pointJson(p.lat, p.lon))
        redrawRoutes()
        setMode(Mode.PLACE)
    }

    private fun fillPlaceSheet() {
        val d = dest ?: return
        sheet.removeAllViews()
        sheet.addView(handle())
        val head = LinearLayout(this).apply { gravity = Gravity.TOP }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = d.title; textSize = 24f; setTextColor(INK); setTypeface(null, Typeface.BOLD) })
        val near = nearPt()
        val meta = listOfNotNull(prettyCat(d.cat), near?.let { fmtMiles(Geo.dist(it.lat, it.lon, d.lat, d.lon)) + " away" })
        if (meta.isNotEmpty()) col.addView(TextView(this).apply { text = meta.joinToString("  ·  "); textSize = 15f; setTextColor(MUTED); setPadding(0, dp(4), 0, 0) })
        if (d.subtitle.isNotEmpty()) col.addView(TextView(this).apply { text = d.subtitle; textSize = 14f; setTextColor(MUTED); setPadding(0, dp(2), 0, 0) })
        head.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(roundIcon("✕") { closeAll() })
        sheet.addView(head)
        val btns = LinearLayout(this).apply { setPadding(0, dp(18), 0, 0) }
        btns.addView(pillButton("➤  Directions", BLUE, Color.WHITE) { startDirections() }, LinearLayout.LayoutParams(0, dp(52), 1.3f).apply { rightMargin = dp(10) })
        btns.addView(pillButton("🏠  Save as Home", SOFTBLUE, BLUE) { saveHome(d) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        sheet.addView(btns)
    }

    private fun startDirections() {
        val d = dest ?: return
        tvRouteTo.text = d.title
        mode = Mode.ROUTES; applyMode()
        computeRoutes(d)
    }

    // ============================ routes ============================
    private fun computeRoutes(d: Router.Place) {
        val from = lastLoc ?: run { routes = emptyList(); routeMsg = "Waiting for your GPS location…"; fillRoutesSheet(); return }
        routes = emptyList(); routeMsg = "Finding the best e-bike routes…"; redrawRoutes(); fillRoutesSheet()
        val profile = routeProfile
        exec.execute {
            val found = ArrayList<Router.Route>()
            for (idx in 0..3) {
                val r = Router.route(from.latitude, from.longitude, d.lat, d.lon, idx, profile) ?: continue
                if (found.none { abs(it.meters - r.meters) < 60 && abs(it.seconds - r.seconds) < 30 }) found.add(r)
                if (found.size >= 3) break
            }
            found.sortBy { it.seconds }
            runOnUiThread {
                if (dest !== d || routeProfile != profile || mode != Mode.ROUTES) return@runOnUiThread
                if (found.isEmpty()) { routeMsg = "Couldn't find a route — check your connection and try again"; fillRoutesSheet(); return@runOnUiThread }
                routes = found
                val usual = learner.usualMeters(learner.key(d.lat, d.lon))
                sel = if (usual != null) found.indexOfFirst { abs(it.meters - usual) < max(80, usual / 20) }.let { if (it < 0) 0 else it } else 0
                redrawRoutes(); fillRoutesSheet(); fitRoute(); buildBubbles()
            }
        }
    }

    private fun redrawRoutes() {
        srcAlt?.setGeoJson(fc(routes.filterIndexed { i, _ -> i != sel }.map { lineJson(it.points) }))
        srcSel?.setGeoJson(fc(routes.getOrNull(sel)?.let { listOf(lineJson(it.points)) } ?: emptyList()))
        if (mode == Mode.ROUTES) buildBubbles()
    }

    private fun eta(r: Router.Route): Int {
        val d = dest ?: return r.seconds
        return (r.seconds * learner.factor(learner.key(d.lat, d.lon))).roundToInt()
    }

    private fun fillRoutesSheet() {
        val d = dest ?: return
        sheet.removeAllViews()
        sheet.addView(handle())
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = "🚲  E-bike"; textSize = 22f; setTextColor(INK); setTypeface(null, Typeface.BOLD) },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(roundIcon("✕") { closeAll() })
        sheet.addView(head)

        val prof = LinearLayout(this).apply { setPadding(0, dp(12), 0, dp(4)) }
        prof.addView(toggleChip("🌳  Quiet roads", routeProfile == "trekking") {
            if (routeProfile != "trekking") { routeProfile = "trekking"; computeRoutes(d) } })
        prof.addView(toggleChip("🛣  Faster roads", routeProfile == "fastbike") {
            if (routeProfile != "fastbike") { routeProfile = "fastbike"; computeRoutes(d) } })
        sheet.addView(prof)

        if (routes.isEmpty()) {
            sheet.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(18), 0, dp(20))
                if (!routeMsg.startsWith("Couldn't")) addView(ProgressBar(context), LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(12) })
                addView(TextView(context).apply { text = routeMsg; textSize = 16f; setTextColor(MUTED) })
            })
            return
        }

        val r = routes[sel]
        val mins = max(1, (eta(r) / 60.0).roundToInt())
        sheet.addView(TextView(this).apply {
            val big = "${fmtDuration(mins)} "
            val sp = SpannableString(big + "(${fmtMiles(r.meters.toDouble())})")
            sp.setSpan(StyleSpan(Typeface.BOLD), 0, big.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sp; textSize = 26f; setTextColor(INK); setPadding(0, dp(12), 0, dp(2))
        })
        sheet.addView(TextView(this).apply { text = "${hillLabel(r)}  ·  Arrive ${clock(eta(r))}"; textSize = 15f; setTextColor(MUTED) })
        if (lastPct >= 0) {
            val need = range.pctNeeded(r.meters / 1609.34)
            val left = lastPct - need
            sheet.addView(TextView(this).apply {
                text = if (left < 8) "⚠ May not have enough battery (needs ~${need.roundToInt()}%, you have ${lastPct.roundToInt()}%)"
                       else "🔋 Arrive with ~${left.roundToInt()}% battery"
                textSize = 14f; setTextColor(if (left < 8) RED else if (left < 25) AMBER else GREEN)
                setTypeface(null, Typeface.BOLD); setPadding(0, dp(6), 0, 0)
            })
        }

        if (routes.size > 1) {
            val rs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(this).apply { setPadding(0, dp(14), 0, dp(4)) }
            val fastest = routes.minOf { eta(it) }
            val usual = learner.usualMeters(learner.key(d.lat, d.lon))
            routes.forEachIndexed { i, rr ->
                val isSel = i == sel
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = GradientDrawable().apply {
                        setColor(if (isSel) SOFTBLUE else Color.WHITE); cornerRadius = dp(14).toFloat()
                        setStroke(dp(if (isSel) 2 else 1), if (isSel) BLUE else LINE)
                    }
                    setOnClickListener { selectRoute(i) }
                }
                card.addView(TextView(this).apply {
                    text = fmtDuration(max(1, (eta(rr) / 60.0).roundToInt())); textSize = 18f
                    setTypeface(null, Typeface.BOLD); setTextColor(if (eta(rr) == fastest) GREEN else INK)
                })
                card.addView(TextView(this).apply { text = fmtMiles(rr.meters.toDouble()); textSize = 13f; setTextColor(MUTED) })
                val tag = when {
                    usual != null && abs(rr.meters - usual) < max(80, usual / 20) -> "★ Your usual"
                    eta(rr) == fastest -> "Fastest"
                    else -> "+${((eta(rr) - fastest) / 60.0).roundToInt()} min"
                }
                card.addView(TextView(this).apply { text = tag; textSize = 12f; setTextColor(MUTED); setTypeface(null, Typeface.BOLD) })
                row.addView(card, LinearLayout.LayoutParams(dp(116), WRAP).apply { rightMargin = dp(10) })
            }
            rs.addView(row); sheet.addView(rs)
        }

        val n = learner.rides(learner.key(d.lat, d.lon)); val g = learner.globalRides()
        sheet.addView(TextView(this).apply {
            text = when {
                n > 0 -> "Times learned from $n ride${if (n == 1) "" else "s"} here"
                g > 0 -> "Times adjusted to your riding ($g ride${if (g == 1) "" else "s"})"
                else -> "Times adapt to your e-bike after a few trips"
            }
            textSize = 12f; setTextColor(MUTED); setPadding(0, dp(10), 0, dp(14))
        })
        sheet.addView(pillButton("▲  Start", BLUE, Color.WHITE) { startNav() }, LinearLayout.LayoutParams(MATCH, dp(56)))
    }

    private fun selectRoute(i: Int) { sel = i; redrawRoutes(); fillRoutesSheet(); fitRoute() }

    private fun hillLabel(r: Router.Route): String {
        val perKm = if (r.meters > 0) r.ascentM / (r.meters / 1000.0) else 0.0
        val word = when { perKm < 5 -> "Mostly flat"; perKm < 12 -> "Gentle hills"; perKm < 22 -> "Moderate hills"; else -> "Steep hills" }
        return "$word (↗ ${(r.ascentM * 3.28084).roundToInt()} ft)"
    }

    /** Time bubbles on each route line, like Google Maps. */
    private fun buildBubbles() {
        bubbleLayer.removeAllViews(); bubbles.clear()
        if (routes.isEmpty() || mode != Mode.ROUTES) return
        val selPts = routes[sel].points
        val fastest = routes.minOf { eta(it) }
        routes.forEachIndexed { i, r ->
            val a = if (i == sel) r.points[r.points.size * 55 / 100] else farthestFrom(r.points, selPts)
            val mins = max(1, (eta(r) / 60.0).roundToInt())
            val tag = if (eta(r) == fastest) "Best route" else "+${((eta(r) - fastest) / 60.0).roundToInt()} min"
            val tv = TextView(this).apply {
                text = "${fmtDuration(mins)}\n$tag"; textSize = if (i == sel) 15f else 13f
                setTypeface(null, Typeface.BOLD); setTextColor(if (i == sel) Color.WHITE else INK)
                setPadding(dp(10), dp(5), dp(10), dp(6))
                background = shape(if (i == sel) 0xFF1A47C9.toInt() else Color.WHITE, dp(10)); elevation = dp(4).toFloat()
                setOnClickListener { selectRoute(i) }
            }
            bubbleLayer.addView(tv, FrameLayout.LayoutParams(WRAP, WRAP))
            bubbles.add(tv to LatLng(a.lat, a.lon))
        }
        bubbleLayer.post { positionBubbles() }
    }

    private fun farthestFrom(pts: List<Router.Pt>, other: List<Router.Pt>): Router.Pt {
        var best = pts[pts.size / 2]; var bestD = -1.0
        val step = max(1, pts.size / 40); val ostep = max(1, other.size / 60)
        var i = pts.size / 6
        while (i < pts.size * 5 / 6) {
            var dMin = Double.MAX_VALUE; var j = 0
            while (j < other.size) { val dd = Geo.dist(pts[i].lat, pts[i].lon, other[j].lat, other[j].lon); if (dd < dMin) dMin = dd; j += ostep }
            if (dMin > bestD) { bestD = dMin; best = pts[i] }
            i += step
        }
        return best
    }

    private fun positionBubbles() {
        val m = map ?: return
        for ((tv, ll) in bubbles) {
            val pt = m.projection.toScreenLocation(ll)
            tv.x = pt.x - tv.width / 2f; tv.y = pt.y - tv.height - dp(6)
        }
    }

    private fun fitRoute() {
        val m = map ?: return
        val r = routes.getOrNull(sel) ?: return
        follow = false
        val b = LatLngBounds.Builder().includes(r.points.map { LatLng(it.lat, it.lon) }).build()
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, dp(50), sbH + dp(150), dp(50), dp(420)), 700)
    }

    private fun flyTo(p: Router.Place) {
        val m = map ?: return
        follow = false
        m.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(p.lat, p.lon)).zoom(16.0).tilt(0.0).bearing(0.0)
                .padding(0.0, 0.0, 0.0, (mapView.height * 0.35)).build()), 800)
    }

    // ============================ navigation ============================
    private fun startNav() {
        val r = routes.getOrNull(sel) ?: return
        val d = dest ?: return
        nav = NavRoute(r); navSeg = 0; navAlong = 0.0; jumpCount = 0; manStage.clear()
        navStart = System.currentTimeMillis(); navLearnable = true; offRouteCount = 0
        tripStartMiles = range.totalMiles
        follow = true
        navBearing = lastLoc?.let { nav!!.snap(it.latitude, it.longitude, 0).bearing }
            ?: Geo.bearing(r.points[0].lat, r.points[0].lon, r.points[1].lat, r.points[1].lon)
        mode = Mode.NAV; applyMode()
        val first = nav!!.maneuvers.first()
        speak("Starting route to ${d.title}. " + if (first.type == Maneuver.Type.ARRIVE) "Head straight to your destination."
              else "In ${spokenDist(first.along)}, ${NavRoute.text(first.type).lowercase()}.")
        lastLoc?.let { onNavFix(it) }
    }

    private fun stopNav() {
        tts?.stop()
        nav = null
        srcSel?.setGeoJson(fc(routes.getOrNull(sel)?.let { listOf(lineJson(it.points)) } ?: emptyList()))
        lastLoc?.let { srcMe?.setGeoJson(pointJson(it.latitude, it.longitude)) }
        setMode(Mode.ROUTES)
    }

    private fun leaveNavCamera() {
        val m = map ?: return
        m.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder(m.cameraPosition).tilt(0.0).bearing(0.0).zoom(15.5).padding(0.0, 0.0, 0.0, 0.0).build()), 600)
    }

    private fun onNavFix(loc: Location) {
        loadPoisAround(loc.latitude, loc.longitude)
        val nr = nav ?: return
        val d = dest ?: return
        // ignore very poor GPS fixes (they cause the arrow to jump onto other roads)
        if (loc.hasAccuracy() && loc.accuracy > 45f && navAlong > 0) return
        // where are we on the route? Only look near our last position...
        val maxAhead = 80.0 + loc.speed * 8.0
        var s = nr.snapNear(loc.latitude, loc.longitude, navAlong, maxAhead)
        if (s.offRoute > 35) {
            // ...unless it's consistently somewhere else on the route (e.g. GPS caught up, shortcut)
            val g = nr.snap(loc.latitude, loc.longitude, navSeg)
            if (g.offRoute < 25 && abs(g.along - navAlong) > 40) {
                if (++jumpCount >= 3) { s = g; jumpCount = 0 }
            } else jumpCount = 0
        } else jumpCount = 0
        navSeg = s.seg
        navAlong = s.along
        val onRoute = s.offRoute < 35
        val lat = if (onRoute) s.lat else loc.latitude
        val lon = if (onRoute) s.lon else loc.longitude
        val target = when {
            onRoute -> s.bearing
            loc.hasBearing() && loc.speed > 1.5f -> loc.bearing.toDouble()
            else -> navBearing
        }
        navBearing = if (onRoute && loc.speed < 1.0f) target
                     else (navBearing + Geo.angleDiff(navBearing, target) * 0.6 + 360.0) % 360.0

        // blue line only ahead of you; arrow on the road
        srcMe?.setGeoJson(pointJson(lat, lon))
        map?.style?.getLayer("me-arrow")?.setProperties(PropertyFactory.iconRotate(navBearing.toFloat()))
        val ahead = ArrayList<Router.Pt>(nr.pts.size - s.seg)
        ahead.add(Router.Pt(lat, lon)); ahead.addAll(nr.pts.subList(s.seg + 1, nr.pts.size))
        srcSel?.setGeoJson(fc(listOf(lineJson(ahead))))

        if (!follow && System.currentTimeMillis() - lastGestureAt > 12000) { follow = true; recenterPill.visibility = View.GONE }
        if (follow) navCamera(lat, lon, navBearing, loc.speed.toDouble())

        // next manoeuvre
        val mi = nr.maneuvers.indexOfFirst { it.along > s.along + 3 }.let { if (it < 0) nr.maneuvers.size - 1 else it }
        val m = nr.maneuvers[mi]
        val dm = max(0.0, m.along - s.along)
        tvTurnIcon.text = NavRoute.glyph(m.type)
        tvTurnDist.text = fmtTurnDist(dm)
        tvTurnText.text = if (m.type == Maneuver.Type.ARRIVE) "Arrive at ${d.title}" else NavRoute.text(m.type)
        val next = nr.maneuvers.getOrNull(mi + 1)
        if (next != null && next.along - m.along < 250) {
            tvThen.text = "Then  ${NavRoute.glyph(next.type)}"; tvThen.visibility = View.VISIBLE
        } else tvThen.visibility = View.GONE
        announce(mi, m, dm)

        // ETA
        val remM = (nr.total - s.along) + if (onRoute) 0.0 else s.offRoute
        val remS = remM / max(1.0, nr.total) * eta(nr.route)
        tvEta.text = fmtDuration(max(1, (remS / 60).roundToInt()))
        tvEtaSub.text = "${fmtMiles(remM)}  ·  ${clock(remS.roundToInt())}"

        if ((m.type == Maneuver.Type.ARRIVE && dm < 25) || Geo.dist(loc.latitude, loc.longitude, d.lat, d.lon) < 25) { arrive(); return }

        if (s.offRoute > 45) offRouteCount++ else offRouteCount = 0
        if (offRouteCount >= 4 && System.currentTimeMillis() - lastReroute > 25000) {
            lastReroute = System.currentTimeMillis(); offRouteCount = 0; reroute(d)
        }
    }

    private fun navCamera(lat: Double, lon: Double, bearing: Double, speedMs: Double) {
        val m = map ?: return
        val zoom = when { speedMs > 9 -> 16.6; speedMs > 5 -> 17.2; else -> 17.7 }
        val cp = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).bearing(bearing).tilt(if (prefs().getBoolean("navTilt", true)) 55.0 else 0.0)
            .padding(0.0, mapView.height * 0.45, 0.0, 0.0).build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(cp), 950)
    }

    private fun announce(mi: Int, m: Maneuver, dm: Double) {
        val stage = manStage[mi] ?: 0
        val arrive = m.type == Maneuver.Type.ARRIVE
        val what = if (arrive) "your destination will be ahead" else NavRoute.text(m.type).lowercase()
        when {
            !arrive && dm <= 35 && stage < 3 -> { speak(NavRoute.text(m.type)); manStage[mi] = 3 }
            dm <= 160 && dm > 35 && stage < 2 -> { speak("In ${spokenDist(dm)}, $what"); manStage[mi] = 2 }
            dm <= 820 && dm > 400 && stage < 1 -> { speak("In ${spokenDist(dm)}, $what"); manStage[mi] = 1 }
        }
    }

    private fun reroute(d: Router.Place) {
        val from = lastLoc ?: return
        val profile = routeProfile
        speak("Rerouting")
        exec.execute {
            val r = Router.route(from.latitude, from.longitude, d.lat, d.lon, 0, profile)
            runOnUiThread {
                if (r != null && mode == Mode.NAV && dest === d) {
                    routes = listOf(r); sel = 0; nav = NavRoute(r); navSeg = 0; navAlong = 0.0; jumpCount = 0; manStage.clear(); navLearnable = false
                    lastLoc?.let { onNavFix(it) }
                }
            }
        }
    }

    private fun arrive() {
        val r = nav?.route; val d = dest ?: return
        val secs = ((System.currentTimeMillis() - navStart) / 1000).toInt()
        val miles = range.totalMiles - tripStartMiles
        if (r != null && navLearnable && secs > 30) learner.record(learner.key(d.lat, d.lon), r.seconds, secs, r.meters)
        speak("You have arrived at ${d.title}")
        nav = null
        srcSel?.setGeoJson(fc(emptyList())); srcAlt?.setGeoJson(fc(emptyList()))
        mode = Mode.ARRIVED; applyMode(); leaveNavCamera()
        sheet.removeAllViews(); sheet.addView(handle())
        sheet.addView(TextView(this).apply { text = "🏁  You've arrived"; textSize = 24f; setTextColor(INK); setTypeface(null, Typeface.BOLD) })
        sheet.addView(TextView(this).apply { text = d.title; textSize = 16f; setTextColor(MUTED); setPadding(0, dp(4), 0, dp(12)) })
        val stats = LinearLayout(this)
        stats.addView(stat(fmtDuration(max(1, secs / 60)), "ride time"), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (miles > 0.05) stats.addView(stat(String.format(Locale.US, "%.1f mi", miles), "distance"), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (lastPct >= 0) stats.addView(stat("${lastPct.roundToInt()}%", "battery"), LinearLayout.LayoutParams(0, WRAP, 1f))
        sheet.addView(stats)
        if (navLearnable && secs > 30) sheet.addView(TextView(this).apply {
            text = "Saved — next time your ETA here will use this ride"; textSize = 13f; setTextColor(GREEN); setPadding(0, dp(12), 0, 0)
        })
        sheet.addView(pillButton("Done", BLUE, Color.WHITE) { closeAll() }, LinearLayout.LayoutParams(MATCH, dp(54)).apply { topMargin = dp(16) })
    }

    private fun stat(v: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { text = v; textSize = 20f; setTextColor(INK); setTypeface(null, Typeface.BOLD) })
        addView(TextView(context).apply { text = label; textSize = 13f; setTextColor(MUTED) })
    }

    private fun speak(text: String) {
        if (muted || !ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voltroute")
    }

    private fun spokenDist(m: Double): String {
        val ft = m * 3.28084
        if (ft < 1000) return "${max(50, ((ft / 50).roundToInt() * 50))} feet"
        val mi = m / 1609.34
        return when {
            mi < 0.35 -> "a quarter of a mile"
            mi < 0.6 -> "half a mile"
            mi < 0.9 -> "three quarters of a mile"
            mi < 1.5 -> "1 mile"
            else -> "${mi.roundToInt()} miles"
        }
    }

    private fun fmtTurnDist(m: Double): String {
        val ft = m * 3.28084
        return when {
            ft < 100 -> "${max(10, ((ft / 10).roundToInt() * 10))} ft"
            ft < 1000 -> "${(ft / 50).roundToInt() * 50} ft"
            else -> String.format(Locale.US, "%.1f mi", m / 1609.34)
        }
    }

    // ============================ location ============================
    override fun onLocationChanged(loc: Location) {
        val prevOdo = odoLoc
        if (BleManager.connected && loc.hasSpeed() && loc.speed > 1.2f && loc.accuracy < 30f) {
            if (prevOdo != null) range.addMiles(prevOdo.distanceTo(loc) / 1609.34, (loc.time - prevOdo.time) / 1000.0)
            odoLoc = loc
        } else odoLoc = null
        lastLoc = loc
        if (!BleManager.connected && mode == Mode.NAV)   // GPS-only ride log while navigating without the bike
            rideLog.log(loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed * 2.2369363 else null, loc.accuracy,
                null, null, null, lastPct, false, mode.name, navAlong)

        val mph = if (loc.hasSpeed()) (loc.speed * 2.2369363).roundToInt() else 0
        tvSpeed.text = mph.toString()
        val lim = limitMph
        tvSpeed.setTextColor(if (lim != null && mph > lim + 1) RED else INK)
        if (lim != null && mph > lim + 2 && prefs().getBoolean("speedAlert", true)) {
            val now = System.currentTimeMillis()
            if (lim != speedAlertLim || now - speedAlertAt > 90_000) {
                speedAlertLim = lim; speedAlertAt = now; speak("Speed limit $lim")
            }
        }
        maybeQuerySpeedLimit(loc)

        if (mode == Mode.NAV) { onNavFix(loc); return }
        srcMe?.setGeoJson(pointJson(loc.latitude, loc.longitude))
        val ll = LatLng(loc.latitude, loc.longitude)
        if (!haveFix) {
            haveFix = true
            if (mode == Mode.BROWSE) moveTo(ll, 16.5)
            if (mode == Mode.ROUTES && routes.isEmpty()) dest?.let { computeRoutes(it) }
        } else if (follow && mode == Mode.BROWSE) moveTo(ll, null, 900)
    }
    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    private fun maybeQuerySpeedLimit(loc: Location) {
        if (limitBusy) return
        val now = System.currentTimeMillis()
        val prev = limitQueryLoc
        if (prev != null && (loc.distanceTo(prev) < 60f || now - limitQueryAt < 8000)) return
        limitBusy = true; limitQueryLoc = loc; limitQueryAt = now
        limitExec.execute {
            val v = Router.speedLimitMph(loc.latitude, loc.longitude)
            runOnUiThread {
                limitBusy = false; limitMph = v
                if (v != null) tvLimit.text = v.toString()
                tvLimit.visibility = if (v != null && prefs().getBoolean("showLimit", true)) View.VISIBLE else View.GONE
            }
        }
    }

    private fun recenter() {
        follow = true
        val l = lastLoc ?: run { toast("Waiting for GPS…"); return }
        moveTo(LatLng(l.latitude, l.longitude), max(map?.cameraPosition?.zoom ?: 16.0, 16.0))
    }

    private fun updateCompass() {
        val b = map?.cameraPosition?.bearing ?: 0.0
        compass.rotation = (-b).toFloat()
        compass.visibility = if (mode != Mode.NAV && b > 2 && b < 358) View.VISIBLE else View.GONE
    }

    // ============================ battery ============================
    override fun onRealtime(d: BleManager.Realtime) = runOnUiThread {
        val pct = estimator.update(d.batteryV, d.throttleV, d.speedRpm)
        val l = lastLoc
        rideLog.log(l?.latitude, l?.longitude, l?.let { if (it.hasSpeed()) it.speed * 2.2369363 else null }, l?.accuracy,
            d.batteryV, d.throttleV, d.speedRpm, pct, estimator.holding, mode.name, navAlong)
        if (d.batteryV > 5) { tvVolts.text = "%.1f V".format(d.batteryV); tvVolts.visibility = View.VISIBLE }
        showBikeStatus(d)
        lastVolts = d.batteryV
        if (pct >= 0) {
            lastPct = pct; range.onSoc(pct)
            val p = pct.roundToInt()
            showBatt(pct, true)
            val warnAt = prefs().getInt("lowBatt", 15)
            if (warnAt > 0 && p <= warnAt && !lowBattWarned) {
                lowBattWarned = true
                val msg = "Battery low, $p percent, about ${range.rangeMiles(pct).roundToInt()} miles left"
                speak(msg); toast(msg)
            } else if (p > warnAt + 5) lowBattWarned = false
        }
    }

    /** Status chips, cruise countdown and fault alerts from the controller's live data. */
    private fun showBikeStatus(d: BleManager.Realtime) {
        // cruise countdown: how long the throttle has been held steady
        if (d.throttleV > 1.3 && abs(d.throttleV - steadyRef) < 0.12) steadySecs += 0.7
        else { steadyRef = d.throttleV; steadySecs = 0.0 }
        val hold = prefs().getFloat("cruiseHold", 8f).toDouble()
        if (d.cruise && !wasCruise) {
            if (steadySecs in 2.0..25.0) prefs().edit().putFloat("cruiseHold", ((hold + steadySecs) / 2).toFloat()).apply()
            speak("Cruise on"); cruiseTipShown = true
        }
        if (!d.cruise && wasCruise) speak("Cruise off")
        if (d.reverse && !wasReverse) speak("Reverse")
        if (!d.cruise && steadySecs > hold + 6 && !cruiseTipShown) {
            cruiseTipShown = true
            toast("Cruise didn't switch on — your controller may need cruise enabled (often a wire or button)")
        }
        wasCruise = d.cruise; wasReverse = d.reverse
        // faults: announce each new one
        val newBits = d.fault and lastFault.inv()
        if (newBits != 0) {
            val names = BleManager.FAULTS.filterIndexed { i, _ -> newBits and (1 shl i) != 0 }
            val msg = "Bike warning: " + names.joinToString(", ")
            speak(msg); toast(msg)
        }
        lastFault = d.fault

        statusRow.removeAllViews()
        fun chip(t: String, bg: Int) = statusRow.addView(TextView(this).apply {
            text = t; textSize = 13f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6)); background = shape(bg, dp(14)); elevation = dp(4).toFloat()
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        if (d.cruise) chip("⏵ CRUISE", 0xFF188038.toInt())
        else if (steadySecs >= 1.4) {
            val left = kotlin.math.ceil(hold - steadySecs).toInt()
            if (left > 0) chip("Hold steady… cruise in ${left}s", BLUE)
        }
        if (d.reverse) chip("◀ REVERSE", 0xFFE37400.toInt())
        if (d.brake) chip("BRAKE", RED)
        if (d.antiTheft) chip("🔒 ANTI-THEFT", 0xFF5F6368.toInt())
        if (d.fault != 0) chip("⚠ " + d.faults.first(), RED)
        statusRow.visibility = if (statusRow.childCount > 0) View.VISIBLE else View.GONE
    }

    override fun onStatus(status: String, connected: Boolean) = runOnUiThread {
        if (!connected) { statusRow.removeAllViews(); statusRow.visibility = View.GONE }
        if (!connected) tvVolts.visibility = View.GONE
        if (!connected) showBatt(lastPct, false)
    }

    private fun rangeMph() = prefs().getInt("rangeMph", 15)

    /** Battery block: big %, red→green live bar, and miles left at your chosen average speed. */
    private fun showBatt(pct: Double, live: Boolean) {
        if (pct < 0) {
            tvBatt.text = "–%"; tvBatt.setTextColor(MUTED); tvRange.text = "no bike"
            (battFill.layoutParams as FrameLayout.LayoutParams).width = 0; battFill.requestLayout(); return
        }
        val p = pct.coerceIn(0.0, 100.0)
        val hue = (p / 100.0 * 120.0).toFloat()                  // 0 = red, 60 = amber, 120 = green
        val col = Color.HSVToColor(floatArrayOf(hue, 0.85f, if (live) 0.80f else 0.55f))
        tvBatt.text = "${p.roundToInt()}%"
        tvBatt.setTextColor(if (live) INK else MUTED)
        battFill.background = shape(col, dp(3))
        (battFill.layoutParams as FrameLayout.LayoutParams).width = (dp(64) * p / 100.0).roundToInt().coerceAtLeast(dp(3))
        battFill.requestLayout()
        val mi = range.rangeAt(p, rangeMph().toDouble())
        tvRange.text = "≈${mi.roundToInt()} mi @${rangeMph()}"
    }

    private fun showBatteryDetails() {
        val pct = if (lastPct >= 0) "${lastPct.roundToInt()}%" else "unknown"
        val v = if (lastVolts > 0) String.format(Locale.US, "%.1f V", lastVolts) else "–"
        AlertDialog.Builder(this).setTitle("Battery")
            .setMessage("Charge: $pct\nVoltage: $v\n" +
                    "Range left: ~${if (lastPct >= 0) range.rangeMiles(lastPct).roundToInt() else 0} mi " +
                    "(full charge ≈ ${range.fullRangeMiles().roundToInt()} mi, ${if (range.learned) "learned" else "estimate"})\n" +
                    "Ridden while connected: ${String.format(Locale.US, "%.1f", range.totalMiles)} mi\n" +
                    "Bike: ${BleManager.status}\n\nLearned: ${estimator.summary()}\n\n" +
                    "The % holds steady while you accelerate (voltage sag) and re-calibrates when you coast or stop. " +
                    "Range learns from how far you actually ride per % of battery.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Reconnect bike") { _, _ -> if (BleManager.isBluetoothOn()) BleManager.connect() else toast("Turn on Bluetooth first") }
            .show()
    }

    // ============================ home / recent ============================
    private fun goHome() {
        val p = getSharedPreferences("places", Context.MODE_PRIVATE)
        if (!p.contains("homeLat")) {
            toast("Search your home address, then tap “Save as Home”")
            searchBar.requestFocus(); showKeyboard(); return
        }
        val home = Router.Place(p.getString("homeName", "Home") ?: "Home",
            p.getFloat("homeLat", 0f).toDouble(), p.getFloat("homeLon", 0f).toDouble(), "Home")
        dest = home; setSearchText("Home"); srcDest?.setGeoJson(pointJson(home.lat, home.lon))
        startDirections()
    }

    private fun saveHome(d: Router.Place) {
        getSharedPreferences("places", Context.MODE_PRIVATE).edit()
            .putString("homeName", d.name).putFloat("homeLat", d.lat.toFloat()).putFloat("homeLon", d.lon.toFloat()).apply()
        toast("Saved as Home")
    }

    private fun saveRecent(p: Router.Place) {
        if (p.label == "Home") return
        val arr = try { JSONArray(prefs().getString("recent", "[]")) } catch (e: Exception) { JSONArray() }
        val out = JSONArray()
        out.put(JSONObject().put("n", p.name).put("l", p.label).put("s", p.sub).put("c", p.cat).put("lat", p.lat).put("lon", p.lon))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (abs(o.optDouble("lat") - p.lat) < 0.0005 && abs(o.optDouble("lon") - p.lon) < 0.0005) continue
            if (out.length() < 8) out.put(o)
        }
        prefs().edit().putString("recent", out.toString()).apply()
    }

    private fun showRecent() {
        if (mode != Mode.BROWSE) return
        val arr = try { JSONArray(prefs().getString("recent", "[]")) } catch (e: Exception) { JSONArray() }
        if (arr.length() == 0) { hideResults(); return }
        val near = nearPt()
        resultsCard.removeAllViews()
        resultsCard.addView(sectionLabel("Recent"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = Router.Place(o.optString("n"), o.optDouble("lat"), o.optDouble("lon"), o.optString("l"), o.optString("s"), o.optString("c"))
            if (i > 0) resultsCard.addView(divider())
            resultsCard.addView(placeRow(p, near, "", "🕘"))
        }
        resultsCard.visibility = View.VISIBLE; chipsScroll.visibility = View.GONE
    }

    // ============================ traffic ============================
    private fun trafficKey() = prefs().getString("tomtom_key", "") ?: ""

    private fun addTrafficLayer(st: Style) {
        if (st.getLayer("traffic-l") != null) return
        val url = "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?key=${trafficKey()}&tileSize=256"
        st.addSource(RasterSource("traffic", TileSet("2.1.0", url), 256))
        st.addLayerBelow(RasterLayer("traffic-l", "traffic").withProperties(PropertyFactory.rasterOpacity(0.9f)), "route-alt-casing")
    }

    private fun removeTrafficLayer(st: Style) {
        st.getLayer("traffic-l")?.let { st.removeLayer(it) }
        st.getSource("traffic")?.let { st.removeSource(it) }
    }

    private fun toggleTraffic() {
        if (trafficKey().isEmpty()) { askTrafficKey(); return }
        trafficOn = !trafficOn
        prefs().edit().putBoolean("traffic", trafficOn).apply()
        trafficBtn.background = shape(if (trafficOn) SOFTBLUE else Color.WHITE, dp(24))
        map?.getStyle { st -> if (trafficOn) addTrafficLayer(st) else removeTrafficLayer(st) }
        toast(if (trafficOn) "Live traffic on" else "Live traffic off")
    }

    private fun askTrafficKey() {
        val input = EditText(this).apply { hint = "Paste your TomTom API key"; setSingleLine(true) }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this).setTitle("Live traffic")
            .setMessage("Live traffic needs a free TomTom key (one rider is well within the free daily limit):\n\n" +
                    "1. Go to developer.tomtom.com and sign up (free, no card)\n" +
                    "2. Open your Dashboard → copy the API key\n3. Paste it below")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val k = input.text.toString().trim()
                if (k.isNotEmpty()) { prefs().edit().putString("tomtom_key", k).apply(); toggleTraffic() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ============================ permissions ============================
    private fun requestPerms() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need.add(Manifest.permission.BLUETOOTH_SCAN); need.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) onPermsReady() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r); onPermsReady()
    }

    private fun onPermsReady() {
        startLocation()
        if (BleManager.isBluetoothOn()) BleManager.connect() else toast("Turn on Bluetooth to connect to the bike")
    }

    private fun startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, this)
        } catch (_: Exception) {}
    }

    // ============================ lifecycle ============================
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume(); BleManager.addListener(this); applySettings() }

    /** Picks up anything changed on the Settings screen. */
    private fun applySettings() {
        val p = prefs()
        if (p.getBoolean("resetLearners", false)) {
            p.edit().remove("resetLearners").apply()
            estimator = BatteryEstimator(this); range = RangeLearner(this); learner = TripLearner(this)
            lastPct = -1.0
        }
        muted = p.getBoolean("muted", false)
        showBatt(lastPct, BleManager.connected)
        if (::muteBtn.isInitialized) muteBtn.text = if (muted) "🔇" else "🔊"
        if (mode != Mode.ROUTES && mode != Mode.NAV) routeProfile = p.getString("defProfile", "trekking") ?: "trekking"
        if (limitMph != null) tvLimit.visibility = if (p.getBoolean("showLimit", true)) View.VISIBLE else View.GONE
        val wantTraffic = p.getBoolean("traffic", false) && trafficKey().isNotEmpty()
        if (wantTraffic != trafficOn) {
            trafficOn = wantTraffic
            trafficBtn.background = shape(if (trafficOn) SOFTBLUE else Color.WHITE, dp(24))
            map?.getStyle { st -> if (trafficOn) addTrafficLayer(st) else removeTrafficLayer(st) }
        }
        val m = map ?: return
        if (appliedStyle.isNotEmpty() && styleUrl() != appliedStyle) {
            appliedStyle = styleUrl()
            m.setStyle(Style.Builder().fromUri(appliedStyle)) { st -> addLayers(st) }
        } else m.style?.getLayer("poi-l")?.setProperties(
            PropertyFactory.visibility(if (p.getBoolean("showPois", true)) Property.VISIBLE else Property.NONE))
        applyMode()
    }
    override fun onPause() {
        super.onPause(); mapView.onPause(); BleManager.removeListener(this); estimator.save(); range.save(); rideLog.flush()
        lastLoc?.let {
            getSharedPreferences("map", Context.MODE_PRIVATE).edit()
                .putFloat("lat", it.latitude.toFloat()).putFloat("lon", it.longitude.toFloat()).apply()
        }
    }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onDestroy() {
        super.onDestroy()
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (_: Exception) {}
        tts?.shutdown()
        rideLog.close()
        mapView.onDestroy(); exec.shutdownNow(); searchExec.shutdownNow(); limitExec.shutdownNow()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            resultsCard.visibility == View.VISIBLE -> { hideResults(); hideKeyboard() }
            mode == Mode.NAV -> stopNav()
            mode == Mode.ROUTES -> setMode(Mode.PLACE)
            mode == Mode.PLACE || mode == Mode.ARRIVED -> closeAll()
            else -> super.onBackPressed()
        }
    }

    // ============================ helpers ============================
    private fun moveTo(ll: LatLng, zoom: Double?, ms: Int = 700) {
        val m = map ?: return
        val b = CameraPosition.Builder(m.cameraPosition).target(ll)
        if (zoom != null) b.zoom(zoom)
        m.animateCamera(CameraUpdateFactory.newCameraPosition(b.build()), ms)
    }

    private fun lineJson(pts: List<Router.Pt>): String {
        val sb = StringBuilder("{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
        pts.forEachIndexed { i, p -> if (i > 0) sb.append(','); sb.append('[').append(p.lon).append(',').append(p.lat).append(']') }
        return sb.append("]}}").toString()
    }
    private fun pointJson(lat: Double, lon: Double) =
        "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Point\",\"coordinates\":[$lon,$lat]}}"
    private fun fc(features: List<String>) = "{\"type\":\"FeatureCollection\",\"features\":[" + features.joinToString(",") + "]}"

    private fun fmtMiles(m: Double): String {
        val mi = m / 1609.34
        return if (mi < 0.1) "${(m * 3.28084).roundToInt()} ft" else String.format(Locale.US, "%.1f mi", mi)
    }
    private fun fmtDuration(mins: Int) = if (mins >= 60) "${mins / 60} hr ${mins % 60} min" else "$mins min"
    private fun clock(secFromNow: Int) =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + secFromNow * 1000L))

    private fun catEmoji(cat: String): String = when (cat) {
        "supermarket", "convenience", "grocery", "greengrocer" -> "🛒"
        "cafe", "coffee" -> "☕"
        "restaurant", "food_court" -> "🍽"
        "fast_food" -> "🍔"
        "pub", "bar", "biergarten" -> "🍺"
        "fuel", "charging_station" -> "⛽"
        "bicycle", "bicycle_parking", "bicycle_rental" -> "🚲"
        "school", "university", "college", "kindergarten" -> "🎓"
        "hospital", "clinic", "doctors" -> "🏥"
        "pharmacy", "chemist" -> "💊"
        "bank", "atm" -> "💷"
        "park", "garden", "nature_reserve" -> "🌳"
        "station", "bus_stop", "halt", "tram_stop" -> "🚉"
        "city", "town", "village", "suburb", "hamlet", "neighbourhood" -> "🏙"
        "house", "residential", "street", "road", "tertiary", "secondary", "primary", "unclassified" -> "🏠"
        "parking" -> "🅿"
        "hotel", "guest_house" -> "🏨"
        else -> "📍"
    }
    private fun prettyCat(cat: String): String? {
        if (cat.isEmpty() || cat == "yes") return null
        return when (cat) { "convenience" -> "Convenience store"; "fast_food" -> "Fast food"; "fuel" -> "Petrol station"
            else -> cat.replace('_', ' ').replaceFirstChar { it.uppercase() } }
    }

    private fun handle() = View(this).apply { background = shape(0xFFDADCE0.toInt(), dp(3)) }.also {
        it.layoutParams = LinearLayout.LayoutParams(dp(38), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) }
    }
    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(MUTED); setTypeface(null, Typeface.BOLD); setPadding(dp(18), dp(12), dp(18), dp(4))
    }
    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; setTextColor(INK); setPadding(dp(16), dp(9), dp(16), dp(9))
        background = shape(Color.WHITE, dp(20)); elevation = dp(3).toFloat()
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
    }
    private fun toggleChip(label: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(if (on) BLUE else MUTED)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
            setColor(if (on) SOFTBLUE else Color.WHITE); cornerRadius = dp(18).toFloat(); setStroke(dp(1), if (on) BLUE else LINE)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
    }
    private fun pillButton(t: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 17f; setTextColor(fg); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        background = shape(bg, dp(28)); setOnClickListener { onClick() }
    }
    private fun roundIcon(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 18f; setTextColor(INK); gravity = Gravity.CENTER
        background = shape(CHIPBG, dp(22)); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }
    private fun divider() = View(this).apply {
        setBackgroundColor(LINE); layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { leftMargin = dp(64) }
    }
    private fun shape(color: Int, radiusPx: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radiusPx.toFloat() }
    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchBar.windowToken, 0)
        searchBar.clearFocus()
    }
    private fun showKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchBar, 0)
    }
    private fun prefs() = getSharedPreferences("app", Context.MODE_PRIVATE)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
