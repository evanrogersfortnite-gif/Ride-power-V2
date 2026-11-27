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
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
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
    "shop" to ("#1A73E8" to "cart"), "food" to ("#E8710A" to "restaurant"), "health" to ("#D93025" to "pharmacy"),
    "fuel" to ("#0E7C86" to "charging"), "bike" to ("#188038" to "bike"), "service" to ("#5F6368" to "bank"),
    "place" to ("#7B61FF" to "place"))
private val POI_RANK = mapOf("shop" to 1, "food" to 2, "bike" to 2, "fuel" to 3, "health" to 3, "service" to 4, "place" to 5)

/**
 * Amped home screen — a Google-Maps-style e-bike navigator.
 *
 * Flow: BROWSE (map + search) → PLACE (place card + Directions) → ROUTES (route
 * preview + Start) → NAV (live turn-by-turn) → ARRIVED.
 */
class MainActivity : android.app.Activity(), BleManager.Listener, LocationListener {

    private enum class Mode { BROWSE, PLACE, ROUTES, NAV, ARRIVED }

    private fun styleUrl() = "https://tiles.openfreemap.org/styles/" + (prefs().getString("mapStyle", "liberty") ?: "liberty")
    private var appliedStyle = ""
    private var applied3d = false
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
    // "cinema" etc: every result as a pin, and "Search this area" after you move the map
    private var srcFound: GeoJsonSource? = null
    private var foundCat: Router.Category? = null
    private var foundPlaces: List<Router.Place> = emptyList()
    private var foundCam: LatLng? = null; private var foundZoom = 0.0; private var foundFitting = false; private var areaBusy = false
    private lateinit var searchAreaBtn: TextView

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
    private lateinit var battRing: RingView
    private val NAVY_TOP = 0xFF1E4277.toInt(); private val NAVY_BOTTOM = 0xFF0A1D3A.toInt(); private val BAR_LIGHT = 0xFFAFC3E0.toInt()
    private lateinit var tvRange: TextView
    private lateinit var tvVolts: TextView
    private lateinit var wattCard: TextView
    private lateinit var tempText: TextView
    private lateinit var topLeft: LinearLayout
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
        searchExec.execute { MyStores.resolve(this) }    // your Co-op stores' locations, looked up once
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
        BleManager.motorK = prefs().getFloat("motorK", 3820f).toDouble()
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
                if (foundCat != null && mode == Mode.BROWSE && resultsCard.visibility == View.VISIBLE) hideResults()   // see the pins
                if (mode == Mode.NAV) recenterPill.visibility = View.VISIBLE
            }
        }
        m.addOnCameraMoveListener { updateCompass(); positionBubbles() }
        m.addOnCameraIdleListener { positionBubbles(); loadPois(); checkSearchArea() }
        m.addOnMapClickListener { ll ->
            if (mode == Mode.BROWSE || mode == Mode.PLACE) {
                hideResults(); hideKeyboard()
                foundAt(ll)?.let { selectPlace(it); return@addOnMapClickListener true }
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
        srcPoi = GeoJsonSource("pois").also { it.setGeoJson(poiJson(pois + majorPois)); st.addSource(it) }
        val rnd = arrayOf(PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
        st.addLayer(LineLayer("route-alt-casing", "route-alt").withProperties(PropertyFactory.lineColor("#8FA3BC"), PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(12f, 5.5f), Expression.stop(15f, 8.5f), Expression.stop(17f, 12.5f), Expression.stop(19f, 21f))), *rnd))
        st.addLayer(LineLayer("route-alt-l", "route-alt").withProperties(PropertyFactory.lineColor("#BCCBDF"), PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(12f, 3.5f), Expression.stop(15f, 6f), Expression.stop(17f, 9f), Expression.stop(19f, 16f))), *rnd))
        st.addLayer(LineLayer("route-sel-casing", "route-sel").withProperties(PropertyFactory.lineColor("#1A47C9"), PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(12f, 6f), Expression.stop(15f, 9.5f), Expression.stop(17f, 14f), Expression.stop(19f, 24f))), *rnd))
        st.addLayer(LineLayer("route-sel-l", "route-sel").withProperties(PropertyFactory.lineColor("#4285F4"), PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(12f, 4f), Expression.stop(15f, 6.5f), Expression.stop(17f, 10f), Expression.stop(19f, 18f))), *rnd))
        srcFound = GeoJsonSource("found").also { it.setGeoJson(empty); st.addSource(it) }
        st.addImage("dest-pin", makeDestPin())
        st.addLayer(SymbolLayer("dest-l", "dest").withProperties(
            PropertyFactory.iconImage("dest-pin"), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        st.addLayerBelow(SymbolLayer("found-l", "found").withProperties(
            PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.textField(Expression.get("name")), PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(12.5f), PropertyFactory.textColor("#B3261E"),
            PropertyFactory.textHaloColor(if (appliedStyle.endsWith("dark")) "#1F1F1F" else "#FFFFFF"), PropertyFactory.textHaloWidth(1.6f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT), PropertyFactory.textOffset(arrayOf(1.0f, -1.0f)),
            PropertyFactory.textMaxWidth(8f), PropertyFactory.textOptional(true)), "dest-l")
        foundCat?.let { c -> st.addImage("found-${c.icon}", makeBadge(0xFFEA4335.toInt(), c.icon, 26f, true)); srcFound?.setGeoJson(foundJson()) }
        // Google-style faint blue cone showing which way the phone is facing
        st.addImage("me-beam-img", makeBeam())
        st.addLayer(SymbolLayer("me-beam", "me").withProperties(
            PropertyFactory.iconImage("me-beam-img"), PropertyFactory.iconRotate(headingDeg),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM), PropertyFactory.iconSize(0.9f),
            PropertyFactory.visibility(Property.NONE)))
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
        // two layers: important places from further out, everything once zoomed in
        fun poiLayer(id: String) = SymbolLayer(id, "pois").withProperties(
            PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconSize(0.5f),
            PropertyFactory.textField(Expression.get("name")), PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(12.5f), PropertyFactory.textColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.textHaloColor(if (appliedStyle.endsWith("dark")) "#1F1F1F" else "#FFFFFF"), PropertyFactory.textHaloWidth(1.6f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT), PropertyFactory.textOffset(arrayOf(1.2f, 0f)),
            PropertyFactory.textMaxWidth(9f), PropertyFactory.textOptional(true),
            PropertyFactory.symbolSortKey(Expression.get("rank")),
            PropertyFactory.visibility(if (prefs().getBoolean("showPois", true)) Property.VISIBLE else Property.NONE))
        try { addPlaceLayers(st) } catch (_: Exception) {}
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
        fun zc(vararg s: Pair<Float, String>) = Expression.interpolate(Expression.linear(), Expression.zoom(),
            *s.map { Expression.stop(it.first, Expression.color(Color.parseColor(it.second))) }.toTypedArray())
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
                        if (!dark) l.setProperties(PropertyFactory.lineColor(
                            if (id.contains("motorway") || id.contains("trunk") || id.contains("primary")) zc(12f to "#7F97B7", 16f to "#7F97B7")
                            else zc(13f to "#BCC3CC", 16f to "#B9C0C9")))
                    } else {
                        l.setProperties(PropertyFactory.lineWidth(w))
                        if (!dark && (id.contains("minor") || id.contains("street") || id.contains("service")
                                    || id.contains("secondary") || id.contains("tertiary")))
                            l.setProperties(PropertyFactory.lineColor(zc(12f to "#C9CFD6", 14.5f to "#D9DEE4", 16f to "#FFFFFF")))
                        // main roads: Google's blue-grey instead of yellow/orange
                        if (!dark && (id.contains("trunk") || id.contains("primary")) && !id.contains("motorway"))
                            l.setProperties(PropertyFactory.lineColor("#9DB4D3"))
                    }
                }
                is FillExtrusionLayer -> if (l.sourceLayer == "building") {
                    l.setMinZoom(15.5f)       // like Google: no building clutter until you're zoomed in
                    // Google-like: pale, slightly see-through, and lower so streets stay visible when tilted
                    fun scaled(key: String) = Expression.product(Expression.literal(0.6f),
                        Expression.coalesce(Expression.toNumber(Expression.get(key)), Expression.literal(0f)))
                    val threeD = prefs().getBoolean("buildings3d", false)
                    l.setProperties(
                        PropertyFactory.fillExtrusionColor(if (dark) "#3A3F47" else if (threeD) "#EDEEF1" else "#E9EAED"),
                        PropertyFactory.fillExtrusionOpacity(if (dark) 0.85f else if (threeD) 0.8f else 1f))
                    // flat (Google-style) unless 3D buildings are switched on in Settings. Flat = hide the 3D layer and
                    // use the plain 2D building layer instead: a zero-height 3D layer still covers labels when tilted.
                    if (!threeD) l.setProperties(PropertyFactory.visibility(Property.NONE))
                    else l.setProperties(PropertyFactory.visibility(Property.VISIBLE),
                        PropertyFactory.fillExtrusionHeight(Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(14.5f, 0f), Expression.stop(16f, scaled("render_height")))),
                        PropertyFactory.fillExtrusionBase(Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(14.5f, 0f), Expression.stop(16f, scaled("render_min_height")))))
                }
                is FillLayer -> when {
                    l.sourceLayer == "building" -> {
                        l.setMinZoom(15.5f); l.setMaxZoom(24f)      // the style stops it at 14 (3D takes over); keep it for flat mode
                        l.setProperties(PropertyFactory.visibility(if (prefs().getBoolean("buildings3d", false)) Property.NONE else Property.VISIBLE))
                        l.setProperties(PropertyFactory.fillColor(if (dark) "#33373E" else "#ECEDF0"), PropertyFactory.fillOutlineColor(if (dark) "#2A2D33" else "#DEE0E4")) }
                    dark -> {}
                    l.sourceLayer == "water" -> l.setProperties(PropertyFactory.fillColor("#9CC0F9"))
                    l.sourceLayer == "park" -> l.setProperties(PropertyFactory.fillColor("#B4E2C0"), PropertyFactory.fillOpacity(1f))
                    (l.sourceLayer == "landcover" || l.sourceLayer == "landuse") &&
                        (id.contains("wood") || id.contains("forest") || id.contains("grass") || id.contains("park")
                            || id.contains("cemetery") || id.contains("pitch") || id.contains("meadow") || id.contains("garden")) ->
                        l.setProperties(PropertyFactory.fillColor(if (id.contains("wood") || id.contains("forest")) "#A6D8B3" else "#C1E8CB"),
                            PropertyFactory.fillOpacity(1f))
                    // schools, hospitals, shops, industry: Google keeps these very subtle; housing areas plain
                    l.sourceLayer == "landuse" && id.contains("residential") -> l.setProperties(PropertyFactory.fillOpacity(0f))
                    l.sourceLayer == "landuse" && (id.contains("school") || id.contains("university") || id.contains("college")) ->
                        l.setProperties(PropertyFactory.fillColor("#EFEBE2"), PropertyFactory.fillOpacity(0.8f))
                    l.sourceLayer == "landuse" && (id.contains("hospital")) -> l.setProperties(PropertyFactory.fillColor("#F6E3E5"), PropertyFactory.fillOpacity(0.8f))
                    l.sourceLayer == "landuse" && (id.contains("retail") || id.contains("commercial") || id.contains("industrial")) ->
                        l.setProperties(PropertyFactory.fillColor("#F1EEF4"), PropertyFactory.fillOpacity(0.7f))
                }
                is SymbolLayer -> {
                    if (id == "poi-l" || id == "poi-major" || id == "me-arrow" || id == "me-beam") continue
                    // the map's italic font renders badly on some phones (garbled names) — use the clean regular one
                    val bold = try { (l.textFont.value as? Array<*>)?.any { it.toString().contains("Bold") } == true } catch (_: Exception) { false }
                    l.setProperties(PropertyFactory.textFont(arrayOf(if (bold) "Noto Sans Bold" else "Noto Sans Regular")))
                    when {
                        l.sourceLayer == "transportation_name" -> {
                            // street names only: road-number shields have their own fixed-size box, leave them alone
                            if (!l.iconImage.isNull || id.contains("shield") || id.contains("ref")) continue
                            l.setProperties(PropertyFactory.textSize(z(13f to 11f, 16f to 13f, 18f to 15f)),
                                PropertyFactory.textHaloWidth(1.6f), PropertyFactory.textColor(if (dark) "#C8CDD3" else "#5F6368"))
                        }
                        // suburbs / neighbourhoods: Google-style grey CAPITALS with spacing
                        l.sourceLayer == "place" && !id.contains("city") && !id.contains("town") && !id.contains("country")
                                && !id.contains("state") && !id.contains("continent") ->
                            l.setProperties(PropertyFactory.textTransform(Property.TEXT_TRANSFORM_UPPERCASE),
                                PropertyFactory.textLetterSpacing(0.12f), PropertyFactory.textSize(z(11f to 11f, 14f to 13f, 16f to 15f)),
                                PropertyFactory.textColor(if (dark) "#AEB4BB" else "#5F6368"),
                                PropertyFactory.textHaloColor(if (dark) "#1F1F1F" else "#FFFFFF"), PropertyFactory.textHaloWidth(1.5f))
                        // the map's own place icons are replaced by Google-style ones (addPlaceLayers)
                        l.sourceLayer == "poi" && !id.startsWith("g-") -> l.setProperties(PropertyFactory.visibility(Property.NONE))
                    }
                }
            }
        }
        // softer, Google-like land colour on the standard style
        if (!dark) st.getLayer("background")?.setProperties(PropertyFactory.backgroundColor("#F8F9FA"))
    }

    /** Faint blue wedge fading outward, pointing up (rotated to the phone's heading). */
    private fun makeBeam(): Bitmap {
        val w = 160; val h = 150
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.RadialGradient(w / 2f, h.toFloat(), h.toFloat(),
            intArrayOf(0x701A73E8, 0x401A73E8, 0x001A73E8), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        val path = Path().apply { moveTo(w / 2f, h.toFloat()); lineTo(0f, 0f); lineTo(w.toFloat(), 0f); close() }
        c.drawPath(path, p)
        return bmp
    }

    // ---- phone heading (for the direction cone) ----
    private var headingDeg = 0f
    private var headingShownAt = 0L
    private var haveHeading = false
    private val headingListener = object : android.hardware.SensorEventListener {
        private val rot = FloatArray(9); private val ori = FloatArray(3)
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            android.hardware.SensorManager.getRotationMatrixFromVector(rot, e.values)
            android.hardware.SensorManager.getOrientation(rot, ori)
            val az = ((Math.toDegrees(ori[0].toDouble()) + 360) % 360).toFloat()
            // smooth (handles the 359→0 wrap) and redraw at most ~8 times a second
            val diff = ((az - headingDeg + 540) % 360) - 180
            headingDeg = (headingDeg + diff * 0.25f + 360) % 360
            haveHeading = true
            val now = System.currentTimeMillis()
            if (now - headingShownAt > 120 && abs(diff) > 1.5f) { headingShownAt = now; showHeading() }
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }
    private fun startHeading() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)?.let {
            sm.registerListener(headingListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
    }
    private fun stopHeading() {
        try { (getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager).unregisterListener(headingListener) } catch (_: Exception) {}
    }
    private fun showHeading() {
        val st = map?.style ?: return
        val on = haveHeading && mode != Mode.NAV && lastLoc != null
        st.getLayer("me-beam")?.setProperties(PropertyFactory.iconRotate(headingDeg),
            PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE))
    }

    /** Google-style navigation chevron. */
    private fun makeArrow(): Bitmap {
        val s = 140
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // white disc with a soft shadow behind the chevron (Google-style)
        c.drawCircle(70f, 74f, 64f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 })
        c.drawCircle(70f, 70f, 62f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val path = Path().apply {
            moveTo(70f, 22f); lineTo(106f, 110f); lineTo(70f, 90f); lineTo(34f, 110f); close()
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE; style = Paint.Style.FILL })
        return bmp
    }

    private fun setNavLayers(on: Boolean) {
        val st = map?.style ?: return
        st.getLayer("me-dot")?.setProperties(PropertyFactory.visibility(if (on) Property.NONE else Property.VISIBLE))
        st.getLayer("me-halo")?.setProperties(PropertyFactory.visibility(if (on) Property.NONE else Property.VISIBLE))
        st.getLayer("me-arrow")?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE))
        if (on) st.getLayer("me-beam")?.setProperties(PropertyFactory.visibility(Property.NONE)) else showHeading()
        fun zw(vararg s: Pair<Float, Float>) = Expression.interpolate(Expression.linear(), Expression.zoom(), *s.map { Expression.stop(it.first, it.second) }.toTypedArray())
        st.getLayer("route-sel-casing")?.setProperties(PropertyFactory.lineWidth(
            if (on) zw(14f to 10f, 16f to 15f, 18f to 22f, 20f to 34f) else zw(12f to 6f, 15f to 9.5f, 17f to 14f, 19f to 24f)))
        st.getLayer("route-sel-l")?.setProperties(PropertyFactory.lineWidth(
            if (on) zw(14f to 7f, 16f to 11f, 18f to 16f, 20f to 26f) else zw(12f to 4f, 15f to 6.5f, 17f to 10f, 19f to 18f)))
    }

    // ============================ UI build ============================
    private fun buildTop(frame: FrameLayout) {
        topBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pill = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(8), 0)
            background = shape(Color.WHITE, dp(30)); elevation = dp(6).toFloat()
        }
        pill.addView(iconView("place", 0xFFEA4335.toInt(), 24))
        searchBar = EditText(this).apply {
            hint = "Search here"; setHintTextColor(MUTED); setTextColor(INK); textSize = 18f
            background = null; setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(12), dp(14), dp(4), dp(14))
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) {
                    val q = text.toString().trim()
                    val c = Router.categoryFor(q)
                    if (c != null) areaSearch(c, true) else if (q.isNotEmpty()) runSuggest(q, true)
                    true
                } else false
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
            background = iconBg(Color.TRANSPARENT, 18, "close", MUTED, 36, 20); visibility = View.GONE
            setOnClickListener { clearSearch() }
        }
        pill.addView(clearBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        topBox.addView(pill, LinearLayout.LayoutParams(MATCH, dp(60)))

        chipsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
        val chips = LinearLayout(this).apply { setPadding(dp(2), dp(10), dp(2), dp(8)) }
        chips.addView(chip("@home  Home") { goHome() })
        val cats = listOf("@cart  Groceries" to "supermarket", "@cafe  Cafés" to "cafe", "@restaurant  Restaurants" to "restaurant",
            "@wrench  Bike shops" to "bicycle shop", "@parking  Bike parking" to "bicycle parking", "@charging  Charging" to "charging station")
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
        searchAreaBtn = TextView(this).apply {
            iconText("@search  Search this area", BLUE, 18); gravity = Gravity.CENTER_VERTICAL
            textSize = 15f; setTextColor(BLUE); setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(10), dp(18), dp(10)); background = shape(Color.WHITE, dp(22)); elevation = dp(6).toFloat()
            visibility = View.GONE
            setOnClickListener { foundCat?.let { areaSearch(it, it.let { _ -> foundPlaces.isEmpty() && foundCam == null }) } }
        }
        frame.addView(searchAreaBtn, FrameLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL })
    }

    private fun buildRouteCard(frame: FrameLayout) {
        routeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = shape(Color.WHITE, dp(20)); elevation = dp(6).toFloat()
        }
        val fromRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fromRow.addView(iconView("arrow_back", INK, 24).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14); setOnClickListener { setMode(Mode.PLACE) }
        })
        fromRow.addView(iconView("my_location", BLUE, 18).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12) })
        fromRow.addView(TextView(this).apply { text = "Your location"; textSize = 17f; setTextColor(BLUE) })
        routeCard.addView(fromRow)
        routeCard.addView(View(this).apply { setBackgroundColor(LINE) },
            LinearLayout.LayoutParams(MATCH, dp(1)).apply { setMargins(dp(64), dp(10), 0, dp(10)) })
        val toRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(38), 0, 0, 0) }
        toRow.addView(iconView("place", 0xFFEA4335.toInt(), 18).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12) })
        tvRouteTo = TextView(this).apply { textSize = 17f; setTextColor(INK); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        toRow.addView(tvRouteTo, LinearLayout.LayoutParams(0, WRAP, 1f))
        routeCard.addView(toRow)
        frame.addView(routeCard, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP; setMargins(dp(12), sbH + dp(8), dp(12), 0)
        })
    }

    private fun buildRight(frame: FrameLayout) {
        trafficBtn = TextView(this).apply {
            background = iconBg(if (trafficOn) SOFTBLUE else Color.WHITE, 24, "traffic", if (trafficOn) BLUE else 0xFF3C4043.toInt(), 48, 24)
            elevation = dp(4).toFloat()
            setOnClickListener { toggleTraffic() }
        }
        frame.addView(trafficBtn, FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.TOP or Gravity.END; setMargins(0, sbH + dp(136), dp(15), 0)
        })
        compass = TextView(this).apply {
            background = iconBg(Color.WHITE, 23, "navigation", RED, 46, 24); elevation = dp(4).toFloat(); visibility = View.GONE
            setOnClickListener { map?.animateCamera(CameraUpdateFactory.bearingTo(0.0), 400) }
        }
        frame.addView(compass, FrameLayout.LayoutParams(dp(46), dp(46)).apply {
            gravity = Gravity.TOP or Gravity.END; setMargins(0, sbH + dp(194), dp(16), 0)
        })
        myLocBtn = TextView(this).apply {
            background = iconBg(Color.WHITE, 18, "my_location", BLUE, 56, 26); elevation = dp(4).toFloat()
            setOnClickListener { recenter() }
        }
        frame.addView(myLocBtn, FrameLayout.LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.BOTTOM or Gravity.END; setMargins(0, 0, dp(16), dp(96))
        })
        gearBtn = TextView(this).apply {
            background = iconBg(FAB, 18, "settings", Color.WHITE, 60, 28); elevation = dp(6).toFloat()
            setOnClickListener { startActivity(Intent(this@MainActivity, AppSettingsActivity::class.java)) }
        }
        frame.addView(gearBtn, FrameLayout.LayoutParams(dp(60), dp(60)).apply {
            gravity = Gravity.BOTTOM or Gravity.END; setMargins(0, 0, dp(14), dp(22))
        })
    }

    private fun buildSpeed(frame: FrameLayout) {
        // one navy bar, bottom-left: speed | charge ring | voltage + range (matches the app icon)
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(6), dp(18), dp(6))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(NAVY_TOP, NAVY_BOTTOM)).apply { cornerRadius = dp(30).toFloat() }
            isClickable = true   // no elevation: its shadow drew a grey smear under the pill
            setOnClickListener { showBatteryDetails() }
        }
        speedCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        tvSpeed = TextView(this).apply { text = "0"; textSize = 26f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; includeFontPadding = false }
        speedCard.addView(tvSpeed)
        speedCard.addView(TextView(this).apply { text = "mph"; textSize = 11f; setTextColor(BAR_LIGHT); gravity = Gravity.CENTER })
        bar.addView(speedCard, LinearLayout.LayoutParams(dp(50), WRAP))
        bar.addView(View(this).apply { setBackgroundColor(0x2EFFFFFF) }, LinearLayout.LayoutParams(dp(1), dp(34)).apply { leftMargin = dp(10); rightMargin = dp(12) })
        // charge ring with the % inside
        val ringBox = FrameLayout(this)
        battRing = RingView(this)
        ringBox.addView(battRing, FrameLayout.LayoutParams(MATCH, MATCH))
        tvBatt = TextView(this).apply { text = "–"; textSize = 13f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        ringBox.addView(tvBatt, FrameLayout.LayoutParams(MATCH, MATCH))
        bar.addView(ringBox, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(10) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvVolts = TextView(this).apply { text = "No bike"; textSize = 16f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) }
        tvRange = TextView(this).apply { text = ""; textSize = 11.5f; setTextColor(BAR_LIGHT) }
        info.addView(tvVolts); info.addView(tvRange)
        bar.addView(info)
        battCard = bar
        // road speed-limit sign sits on the bar's top-left corner, over the speed
        tvLimit = TextView(this).apply {
            textSize = 13f; setTextColor(INK); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(3), RED) }
            elevation = dp(8).toFloat(); visibility = View.GONE
            translationX = -dp(8).toFloat(); translationY = -dp(12).toFloat()
        }
        val barBox = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        barBox.addView(bar, FrameLayout.LayoutParams(WRAP, dp(60)))   // same height as the gear button
        barBox.addView(tvLimit, FrameLayout.LayoutParams(dp(34), dp(34)).apply { gravity = Gravity.TOP or Gravity.START })

        val hudRow = LinearLayout(this).apply { gravity = Gravity.BOTTOM; clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(14), dp(12), dp(8)) }   // bar bottom = 14 + 8 = 22dp, level with the gear
        hudRow.addView(barBox)

        // live power block, top-left (under the search bar / turn banner)
        // live power + temperatures, top-left (under the search bar / turn banner)
        topLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(7), dp(14), dp(8))
            background = shape(Color.WHITE, dp(18)); elevation = dp(4).toFloat(); visibility = View.GONE
        }
        wattCard = TextView(this).apply {
            text = "– W"; textSize = 18f; setTextColor(INK); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelative(AppIcons.drawable(this@MainActivity, "bolt", 0xFFF57C00.toInt(), 22), null, null, null)
            compoundDrawablePadding = dp(4)
        }
        tempText = TextView(this).apply { text = "Motor –° · Ctrl –°"; textSize = 12f; setTextColor(MUTED); setPadding(dp(4), dp(2), 0, 0) }
        topLeft.addView(wattCard)      // temperatures not shown: this controller doesn't report them
        frame.addView(topLeft, FrameLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.TOP or Gravity.START; leftMargin = dp(14) })
        // bike status chips (cruise / reverse / brake / anti-theft / cruise countdown) above the cards
        hud = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        hud.addView(hudRow)
        frame.addView(hud, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.START; setMargins(dp(6), 0, 0, dp(14))   // + 8dp row padding = bar 14dp in; room for the limit sign
        })
        // status chips float independently just above the cards (so they can never disturb the cards)
        statusRow = LinearLayout(this).apply { visibility = View.GONE; isClickable = false }
        frame.addView(statusRow, FrameLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.BOTTOM or Gravity.START; leftMargin = dp(14) })
        hud.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            val lp = statusRow.layoutParams as FrameLayout.LayoutParams
            val want = (v.parent as View).height - top + dp(4)
            if (lp.bottomMargin != want && bottom > top) { lp.bottomMargin = want; statusRow.layoutParams = lp }
        }
    }
    private val BAR_W = 58
    private val HUD = 96          // speed & battery blocks: same size

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
        tvTurnIcon = TextView(this)
        main.addView(tvTurnIcon, LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(16) })
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
        navBottom.addView(roundIcon("close") { stopNav() })
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        tvEta = TextView(this).apply { textSize = 24f; setTextColor(GREEN); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        tvEtaSub = TextView(this).apply { textSize = 15f; setTextColor(MUTED); gravity = Gravity.CENTER }
        mid.addView(tvEta); mid.addView(tvEtaSub)
        navBottom.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        muteBtn = roundIcon(if (muted) "volume_off" else "volume_up") {
            muted = !muted; prefs().edit().putBoolean("muted", muted).apply()
            setRoundIcon(muteBtn, if (muted) "volume_off" else "volume_up")
            if (muted) tts?.stop()
        }
        navBottom.addView(muteBtn)
        frame.addView(navBottom, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.BOTTOM })

        recenterPill = TextView(this).apply {
            iconText("@navigation  Re-centre", BLUE, 18); gravity = Gravity.CENTER_VERTICAL
            textSize = 15f; setTextColor(BLUE); setTypeface(null, Typeface.BOLD)
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
        if (m == Mode.NAV) clearFound()
        applyMode()
        when (m) {
            Mode.PLACE -> { fillPlaceSheet(); dest?.let { flyTo(it) } }
            Mode.ROUTES -> { fillRoutesSheet(); fitRoute() }
            else -> {}
        }
    }

    private fun applyMode() {
        if (mode != Mode.ROUTES && (sheetCollapsed || sheet.translationY != 0f)) { sheetCollapsed = false; sheet.animate().cancel(); sheet.translationY = 0f }
        fun vis(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }
        vis(topBox, mode == Mode.BROWSE || mode == Mode.PLACE)
        vis(chipsScroll, mode == Mode.BROWSE && resultsCard.visibility != View.VISIBLE)
        vis(routeCard, mode == Mode.ROUTES)
        vis(sheet, mode == Mode.PLACE || mode == Mode.ROUTES || mode == Mode.ARRIVED)
        vis(hud, mode == Mode.BROWSE || mode == Mode.NAV)
        if (hud.visibility != View.VISIBLE) statusRow.visibility = View.GONE
        hud.post { positionWattCard() }
        vis(myLocBtn, mode == Mode.BROWSE)
        vis(gearBtn, mode == Mode.BROWSE)
        vis(trafficBtn, mode != Mode.ARRIVED)
        if (mode != Mode.BROWSE) searchAreaBtn.visibility = View.GONE
        map?.style?.getLayer("found-l")?.setProperties(PropertyFactory.visibility(
            if (mode == Mode.BROWSE || mode == Mode.PLACE) Property.VISIBLE else Property.NONE))
        vis(turnBanner, mode == Mode.NAV)
        vis(navBottom, mode == Mode.NAV)
        vis(recenterPill, mode == Mode.NAV && !follow)
        if (mode != Mode.BROWSE && mode != Mode.PLACE) { resultsCard.visibility = View.GONE; trafficBtn.visibility = View.VISIBLE }
        (hud.layoutParams as FrameLayout.LayoutParams).bottomMargin = if (mode == Mode.NAV) dp(116) else dp(14)
        hud.requestLayout()
        setNavLayers(mode == Mode.NAV)
        val st = map?.style
        st?.getLayer("route-alt-l")?.setProperties(PropertyFactory.visibility(if (mode == Mode.ROUTES) Property.VISIBLE else Property.NONE))
        st?.getLayer("route-alt-casing")?.setProperties(PropertyFactory.visibility(if (mode == Mode.ROUTES) Property.VISIBLE else Property.NONE))
        st?.getLayer("traffic-top")?.setProperties(PropertyFactory.visibility(if (mode == Mode.ROUTES || mode == Mode.NAV) Property.VISIBLE else Property.NONE))
        if (mode != Mode.ROUTES) { bubbleLayer.removeAllViews(); bubbles.clear() } else buildBubbles()
        tuneGps()
        if (mode == Mode.NAV || prefs().getBoolean("keepOn", false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Motor and controller temperatures. Colour warns as they approach the controller's own
     * "current-limiting temp" setting (parameter byte 17; it cuts power to protect itself above it).
     */
    private fun showTemps(d: BleManager.Realtime) {
        // this controller sends 0 for both when it has no sensor / doesn't report them: then hide the line
        if (d.motorTemp == 0 && d.ctrlTemp == 0) { tempText.visibility = View.GONE; return }
        tempText.visibility = View.VISIBLE
        val limit = BleManager.lastParams?.let { if (it.size >= 20) (it[17].toInt() and 0xff).takeIf { v -> v in 50..200 } else null } ?: 90
        val hottest = max(d.motorTemp, d.ctrlTemp)
        fun t(v: Int) = if (v > 0) "$v°" else "–°"
        tempText.text = "Motor ${t(d.motorTemp)} · Ctrl ${t(d.ctrlTemp)}"
        tempText.setTextColor(when { hottest >= limit -> RED; hottest >= limit - 10 -> AMBER; else -> MUTED })
    }

    /** "51.8 V · 1240 W" on the bar: voltage and live power, the dot dimmed. */
    private fun showVoltsWatts(v: Double, w: Int) {
        val vs = "%.1f V".format(v); val sep = "  ·  "
        val sp = android.text.SpannableString(vs + sep + "$w W")
        sp.setSpan(android.text.style.ForegroundColorSpan(BAR_LIGHT), vs.length, vs.length + sep.length, 0)
        sp.setSpan(android.text.style.StyleSpan(Typeface.NORMAL), vs.length, vs.length + sep.length, 0)
        tvVolts.text = sp
    }

    /** The old top-left power block: watts now live in the navy bar, so it stays hidden. */
    private fun positionWattCard() {
        topLeft.visibility = View.GONE
        if (true) return
        val show = (mode == Mode.BROWSE || mode == Mode.NAV) && resultsCard.visibility != View.VISIBLE  // "– W" until the bike sends data
        topLeft.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val anchor: View = if (mode == Mode.NAV) turnBanner else topBox
        val want = anchor.bottom + dp(10)
        val lp = topLeft.layoutParams as FrameLayout.LayoutParams
        if (anchor.bottom > 0 && lp.topMargin != want) { lp.topMargin = want; topLeft.layoutParams = lp }
    }

    private fun closeAll() {
        dest = null; routes = emptyList(); nav = null
        redrawRoutes(); srcDest?.setGeoJson(fc(emptyList()))
        setSearchText(""); hideResults()
        setMode(Mode.BROWSE)
        // came from a "cinema" search: back to the list of them, like Google
        foundCat?.let { c -> setSearchText(c.label); areaPill(null, false) }     // back to the pins
    }

    // ============================ search ============================
    private fun onQueryChanged(q: String) {
        clearBtn.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        if (foundCat != null) clearFound()      // typing something new replaces the pins
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
        // "restaurants" (even misspelt): pins on the map, no list — once you pause typing
        Router.categoryFor(q)?.let { c ->
            if (enter) { areaSearch(c, true); return }
            val s0 = ++searchSeq
            ui.postDelayed({ if (s0 == searchSeq && searchBar.text.toString().trim() == q) areaSearch(c, true) }, 650)
            return
        }
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
        val mineNow = MyStores.matches(this, q)
        if ((local.isNotEmpty() || mineNow.isNotEmpty()) && !enter) showPlaces(withMine(mineNow, Router.rank(local, q, null, null, near)), near, q, null, false)
        searchExec.execute {
            // your stores first ("gloucester road" → both Gloucester Road stores, with their area)
            if (!MyStores.allResolved(this)) MyStores.resolve(this)
            val mine = MyStores.matches(this, q)
            if (!enter) {
                val quick = withMine(mine, Router.smartSearch(q, near, false, alive, local))
                if (!alive()) return@execute
                runOnUiThread { if (seq == searchSeq) showPlaces(quick, near, q, null, !wantDeep) }
                if (!wantDeep) return@execute
                try { Thread.sleep(450) } catch (_: InterruptedException) { return@execute }
                if (seq != searchSeq) return@execute
            }
            if (!alive()) return@execute
            val deep = withMine(mine, Router.smartSearch(q, near, true, alive, local))
            runOnUiThread {
                if (seq != searchSeq) return@runOnUiThread
                val best = localFirst(deep, near)
                if (enter && best.isNotEmpty()) selectPlace(best[0])      // search key: straight to the top match
                else showPlaces(deep, near, q, null, true)
            }
        }
    }

    // ============================ "cinema": all of them in this area ============================
    private fun areaSearch(c: Router.Category, fit: Boolean) {
        val m = map ?: return
        hideKeyboard(); setSearchText(c.label); hideResults()
        val seq = ++searchSeq
        foundCat = c; areaBusy = true
        areaPill("Finding ${c.label.lowercase()} near you…", false)
        val b = m.projection.visibleRegion.latLngBounds
        val cLat = (b.latitudeNorth + b.latitudeSouth) / 2; val cLon = (b.longitudeEast + b.longitudeWest) / 2
        // never more than ~15 km across (zoomed far out would ask for thousands)
        val hLat = minOf((b.latitudeNorth - b.latitudeSouth) / 2, 0.07); val hLon = minOf((b.longitudeEast - b.longitudeWest) / 2, 0.11)
        searchExec.execute {
            var list = Router.searchArea(c, cLat - hLat, cLon - hLon, cLat + hLat, cLon + hLon)
            if (list != null && list.isEmpty() && fit)      // none on screen: look a bit wider
                list = Router.searchArea(c, cLat - hLat * 3, cLon - hLon * 3, cLat + hLat * 3, cLon + hLon * 3)
            runOnUiThread {
                if (seq != searchSeq) return@runOnUiThread
                areaBusy = false
                when {
                    list == null -> areaPill("Search is busy — tap to try again", true)
                    list.isEmpty() -> showFound(c, emptyList(), false)
                    else -> showFound(c, list, fit)
                }
            }
        }
    }

    private fun showFound(c: Router.Category, list: List<Router.Place>, fit: Boolean) {
        val m = map ?: return
        foundCat = c; foundPlaces = list
        m.style?.addImage("found-${c.icon}", makeBadge(0xFFEA4335.toInt(), c.icon, 26f, true))
        srcFound?.setGeoJson(foundJson())
        hideResults()
        areaPill(null, false)
        if (list.isEmpty()) { foundCam = m.cameraPosition.target; foundZoom = m.cameraPosition.zoom; return }
        if (fit) {
            // frame the nearest ones (the rest are still pinned)
            val near = nearPt()
            val pick = if (near != null) list.sortedBy { Geo.dist(near.lat, near.lon, it.lat, it.lon) }.take(12) else list.take(12)
            if (pick.size == 1) m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pick[0].lat, pick[0].lon), 15.0))
            else {
                val bb = LatLngBounds.Builder(); pick.forEach { bb.include(LatLng(it.lat, it.lon) ) }
                near?.let { bb.include(LatLng(it.lat, it.lon)) }
                try { m.animateCamera(CameraUpdateFactory.newLatLngBounds(bb.build(), dp(50), topBox.bottom + dp(40), dp(50), dp(120))) } catch (_: Exception) {}
            }
            foundFitting = true
        } else { foundCam = m.cameraPosition.target; foundZoom = m.cameraPosition.zoom }
    }

    private fun foundJson(): String {
        val c = foundCat ?: return fc(emptyList())
        val fs = JSONArray()
        for (p in foundPlaces) fs.put(JSONObject().put("type", "Feature")
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat)))
            .put("properties", JSONObject().put("name", if (p.title == c.one) "" else p.title).put("icon", "found-${c.icon}")))
        return JSONObject().put("type", "FeatureCollection").put("features", fs).toString()
    }

    private fun clearFound() {
        foundCat = null; foundPlaces = emptyList(); foundCam = null; areaBusy = false
        srcFound?.setGeoJson(fc(emptyList()))
        if (::searchAreaBtn.isInitialized) searchAreaBtn.visibility = View.GONE
    }

    /** Red pin under the finger, if any. */
    private fun foundAt(ll: LatLng): Router.Place? {
        val m = map ?: return null
        if (foundPlaces.isEmpty()) return null
        val tap = m.projection.toScreenLocation(ll)
        var best: Router.Place? = null; var bestD = dp(30).toFloat()
        for (p in foundPlaces) {
            val pt = m.projection.toScreenLocation(LatLng(p.lat, p.lon))
            val d = Math.hypot((pt.x - tap.x).toDouble(), (pt.y - tap.y - dp(14)).toDouble()).toFloat()   // pin's head sits above the point
            if (d < bestD) { bestD = d; best = p }
        }
        return best
    }

    /** After you pan/zoom away from the results, offer "Search this area". */
    private fun checkSearchArea() {
        val m = map ?: return
        if (foundCat == null || !::searchAreaBtn.isInitialized || areaBusy) return
        val cp = m.cameraPosition; val t = cp.target ?: return
        if (foundFitting) { foundFitting = false; foundCam = t; foundZoom = cp.zoom; return }
        val f = foundCam ?: run { foundCam = t; foundZoom = cp.zoom; return }
        val b = m.projection.visibleRegion.latLngBounds
        val moved = abs(t.latitude - f.latitude) > (b.latitudeNorth - b.latitudeSouth) * 0.25 ||
                abs(t.longitude - f.longitude) > (b.longitudeEast - b.longitudeWest) * 0.25 || abs(cp.zoom - foundZoom) > 0.8
        if (moved) areaPill("Search this area", true) else areaPill(null, false)
    }

    /**
     * The small pill under the search bar: "12 restaurants nearby" (info) or "Search this area" (button).
     * [text] null = the result count.
     */
    private fun areaPill(text: String?, action: Boolean) {
        val c = foundCat
        if (c == null || mode != Mode.BROWSE || !::searchAreaBtn.isInitialized) { if (::searchAreaBtn.isInitialized) searchAreaBtn.visibility = View.GONE; return }
        val n = foundPlaces.size
        val t = text ?: when (n) { 0 -> "No ${c.label.lowercase()} here — move the map to look elsewhere"; 1 -> "1 ${c.one.lowercase()} nearby · tap the pin"
            else -> "$n ${c.label.lowercase()} nearby · tap a pin" }
        searchAreaBtn.iconText(if (action) "@search  $t" else "@${c.icon}  $t", if (action) BLUE else 0xFFEA4335.toInt(), 18)
        searchAreaBtn.setTextColor(if (action) BLUE else INK)
        searchAreaBtn.setTypeface(null, if (action) Typeface.BOLD else Typeface.NORMAL)
        searchAreaBtn.isClickable = action
        val lp = searchAreaBtn.layoutParams as FrameLayout.LayoutParams
        topBox.post {
            val want = (topBox.layoutParams as FrameLayout.LayoutParams).topMargin + topBox.height + dp(8)
            if (lp.topMargin != want) { lp.topMargin = want; searchAreaBtn.layoutParams = lp }
        }
        searchAreaBtn.visibility = View.VISIBLE
    }

    /** Your stores on top, without the same shop appearing again lower down. */
    private fun withMine(mine: List<Router.Place>, rest: List<Router.Place>): List<Router.Place> =
        mine + rest.filter { r -> mine.none { Geo.dist(it.lat, it.lon, r.lat, r.lon) < 80 } }

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
        for (p in pois + majorPois) if (hit(p.name)) out.putIfAbsent(p.name + p.lat, Router.Place(p.name, p.lat, p.lon, p.name, p.sub, p.cat))
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
                val ids = st.layers.filter { it.id != "poi-l" && it.id != "poi-major" && ((it as? SymbolLayer)?.sourceLayer == "poi" || it.id.startsWith("poi")) }
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
        Router.categoryFor(q)?.let { areaSearch(it, true); return }
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
        // typed a kind of place ("cinema"): offer all of them on the map, like Google
        val cat = if (header == null && query.isNotEmpty()) Router.categoryFor(query) else null
        if (cat != null) {
            resultsCard.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(11), dp(16), dp(11))
                setOnClickListener { areaSearch(cat, true) }
                addView(View(this@MainActivity).apply { background = iconBg(SOFTBLUE, 19, "search", BLUE, 38, 20) },
                    LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) })
                addView(TextView(this@MainActivity).apply {
                    text = "${cat.label} near you"; textSize = 16f; setTextColor(BLUE); setTypeface(null, Typeface.BOLD) })
            })
            resultsCard.addView(divider())
        }
        val sorted = if (near != null && query.isEmpty()) places.sortedBy { Geo.dist(near.lat, near.lon, it.lat, it.lon) } else places
        val list = localFirst(sorted, near)
        list.take(7).forEachIndexed { i, p ->
            if (i > 0) resultsCard.addView(divider())
            resultsCard.addView(placeRow(p, near, query, IconPaths.forCategory(p.cat)))
        }
        resultsCard.visibility = View.VISIBLE; chipsScroll.visibility = View.GONE; resultsOverlayCheck()
    }

    private fun placeRow(p: Router.Place, near: Router.Pt?, query: String, icon: String): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(11), dp(16), dp(11))
            setOnClickListener { selectPlace(p) }
        }
        row.addView(View(this).apply { background = iconBg(CHIPBG, 19, icon, 0xFF5F6368.toInt(), 38, 20) },
            LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) })
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
        return if (d.any { it < 25 }) places.filterIndexed { i, _ -> d[i] < 60 } else places.filterIndexed { i, _ -> d[i] < 250 }
    }

    private fun showResultsMessage(msg: String) {
        resultsCard.removeAllViews()
        resultsCard.addView(TextView(this).apply { text = msg; textSize = 15f; setTextColor(MUTED); setPadding(dp(18), dp(16), dp(18), dp(16)) })
        resultsCard.visibility = View.VISIBLE; chipsScroll.visibility = View.GONE; resultsOverlayCheck()
    }

    private fun hideResults() {
        resultsCard.visibility = View.GONE
        if (mode == Mode.BROWSE) chipsScroll.visibility = View.VISIBLE
        resultsOverlayCheck()
    }

    /** While the search results list is open, the power block and traffic button step aside. */
    private fun resultsOverlayCheck() {
        val open = resultsCard.visibility == View.VISIBLE
        trafficBtn.visibility = if (open) View.GONE else View.VISIBLE
        positionWattCard()
    }

    private fun clearSearch() {
        clearFound()
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
        val zoom = m.cameraPosition.zoom
        val t = m.cameraPosition.target ?: return
        if (zoom >= 14.5) loadPoisAround(t.latitude, t.longitude)
        else if (zoom >= 12.0) loadMajorPois(m)
    }

    // zoomed out: important places only, over the whole visible area
    private var majorPois: List<Router.Poi> = emptyList()
    private var majorBox: DoubleArray? = null
    private var majorLoading = false
    private fun loadMajorPois(m: MapLibreMap) {
        if (majorLoading || !prefs().getBoolean("showPois", true)) return
        val b = m.projection.visibleRegion.latLngBounds
        val have = majorBox
        if (have != null && b.latitudeSouth >= have[0] && b.longitudeWest >= have[1] && b.latitudeNorth <= have[2] && b.longitudeEast <= have[3]) return
        val cLat = (b.latitudeNorth + b.latitudeSouth) / 2; val cLon = (b.longitudeEast + b.longitudeWest) / 2
        val hLat = minOf(0.06, (b.latitudeNorth - b.latitudeSouth) * 0.75); val hLon = minOf(0.1, (b.longitudeEast - b.longitudeWest) * 0.75)
        val box = doubleArrayOf(cLat - hLat, cLon - hLon, cLat + hLat, cLon + hLon)
        majorLoading = true
        poiExec.execute {
            val got = Router.fetchPois(box[0], box[1], box[2], box[3], major = true)
            runOnUiThread {
                majorLoading = false
                if (got.isEmpty()) return@runOnUiThread
                majorPois = got; majorBox = box
                refreshPoiSource()
            }
        }
    }

    /** Detailed places near you + important places further out, without duplicates. */
    private fun refreshPoiSource() {
        val seen = HashSet<String>()
        val all = (pois + majorPois).filter { seen.add(it.name + "%.4f".format(it.lat) + "%.4f".format(it.lon)) }
        srcPoi?.setGeoJson(poiJson(all, majorPois.map { it.name + it.lat }.toHashSet()))
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
                refreshPoiSource()
            }
        }
    }

    private fun poiJson(list: List<Router.Poi>, major: Set<String> = emptySet()): String {
        val fs = JSONArray()
        for (p in list) {
            val st = POI_STYLE[p.group] ?: POI_STYLE.getValue("place")
            val important = p.cat in setOf("supermarket", "department_store", "mall", "charging_station", "hospital") || (p.name + p.lat) in major
            fs.put(JSONObject().put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat)))
                .put("properties", JSONObject().put("name", p.name).put("icon", "poi-${p.group}")
                    .put("color", st.first).put("rank", (POI_RANK[p.group] ?: 5) - (if (important) 10 else 0))
                    .put("major", if (important) 1 else 0)))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", fs).toString()
    }

    /** Nearest drawn place within a finger's width of the tap. */
    private fun poiAt(ll: LatLng): Router.Poi? {
        val m = map ?: return null
        if (m.cameraPosition.zoom < 12.5 || !prefs().getBoolean("showPois", true)) return null
        val tap = m.projection.toScreenLocation(ll)
        var best: Router.Poi? = null; var bestD = dp(28).toFloat()
        for (p in pois + majorPois) {
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
        val ids = st.layers.filter { it.id != "poi-l" && it.id != "poi-major" && ((it as? SymbolLayer)?.sourceLayer == "poi" || it.id.startsWith("poi")) }
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

    /** Google-style place badge: coloured circle, white ring, white Material icon. */
    /** Google-style red teardrop pin with a dark centre, pointing at the place. */
    private fun makeDestPin(): Bitmap {
        val d = resources.displayMetrics.density
        val w = (30 * d).toInt(); val h = (42 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = w / 2f - d
        val cx = w / 2f; val cy = r + d
        val pin = android.graphics.Path().apply {
            addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
            moveTo(cx - r * 0.82f, cy + r * 0.58f); lineTo(cx, h - d); lineTo(cx + r * 0.82f, cy + r * 0.58f); close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFEA4335.toInt(); p.setShadowLayer(2.5f * d, 0f, d, 0x55000000); c.drawPath(pin, p)
        p.clearShadowLayer(); p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = 0xFFB31412.toInt(); c.drawPath(pin, p)
        p.style = Paint.Style.FILL; p.color = 0xFFB31412.toInt(); c.drawCircle(cx, cy, r * 0.36f, p)
        return bmp
    }

    /** Google-style place badge: coloured round marker with a small point, white glyph. */
    private fun makeBadge(color: Int, icon: String, sizeDp: Float, pointed: Boolean, square: Boolean = false): Bitmap {
        val d = resources.displayMetrics.density
        val s = (sizeDp * d).toInt(); val tail = if (pointed) (4 * d).toInt() else 0
        val bmp = Bitmap.createBitmap(s + (2 * d).toInt(), s + tail + (2 * d).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = bmp.width / 2f; val r = s / 2f; val cy = r + d * 0.5f
        val shape = android.graphics.Path().apply {
            if (square) addRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), r * 0.35f, r * 0.35f, android.graphics.Path.Direction.CW)
            else addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
            if (pointed) { moveTo(cx - r * 0.45f, cy + r * 0.85f); lineTo(cx, cy + r + tail); lineTo(cx + r * 0.45f, cy + r * 0.85f); close() }
        }
        p.color = Color.WHITE; p.setShadowLayer(1.5f * d, 0f, 0.6f * d, 0x40000000); c.drawPath(shape, p); p.clearShadowLayer()
        val inset = 1.6f * d
        p.color = color
        if (square) c.drawRoundRect(RectF(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset), r * 0.3f, r * 0.3f, p)
        else c.drawCircle(cx, cy, r - inset, p)
        val g = s * 0.56f
        AppIcons.draw(c, icon, Color.WHITE, cx - g / 2, cy - g / 2, g)
        return bmp
    }

    /**
     * Shops, food, bus stops and parking drawn from the map's own data (works offline and never
     * waits for a server), styled like Google: coloured badges with names in the same colour.
     */
    private fun addPlaceLayers(st: Style) {
        val dark = appliedStyle.endsWith("dark")
        fun many(keys: List<String>, out: Any) = keys.map { Expression.stop(it, out) }.toTypedArray()
        val srcId = st.layers.mapNotNull { (it as? SymbolLayer)?.takeIf { l -> l.sourceLayer == "poi" }?.sourceId }.firstOrNull() ?: "openmaptiles"
        // group -> colour, glyph, label colour
        val groups = linkedMapOf(
            "grocery" to Triple(0xFF1A73E8.toInt(), "cart", "#1967D2"),
            "shop" to Triple(0xFF1A73E8.toInt(), "bag", "#1967D2"),
            "food" to Triple(0xFFF57C00.toInt(), "restaurant", "#C26401"),
            "cafe" to Triple(0xFFF57C00.toInt(), "cafe", "#C26401"),
            "bar" to Triple(0xFFF57C00.toInt(), "bar", "#C26401"),
            "health" to Triple(0xFFE8453C.toInt(), "pharmacy", "#C5221F"),
            "bike" to Triple(0xFF188038.toInt(), "bike", "#137333"),
            "fuel" to Triple(0xFF12B5CB.toInt(), "charging", "#098591"),
            "hotel" to Triple(0xFFE52592.toInt(), "hotel", "#B80672"),
            "park" to Triple(0xFF34A853.toInt(), "park", "#137333"),
            "school" to Triple(0xFF7B8794.toInt(), "school", "#5F6368"),
            "other" to Triple(0xFF7B8794.toInt(), "place", "#5F6368"))
        for ((g, v) in groups) st.addImage("g-$g", makeBadge(v.first, v.second, 22f, true))
        st.addImage("g-bus", makeBadge(0xFF1A73E8.toInt(), "bus", 17f, false, square = true))
        st.addImage("g-parking", makeBadge(0xFF4F7CC7.toInt(), "parking", 17f, false, square = true))
        st.addImage("g-train", makeBadge(0xFF5E35B1.toInt(), "train", 19f, false, square = true))
        val cls = Expression.get("class")
        val group = Expression.match(cls, Expression.literal("other"),
            Expression.stop("grocery", "grocery"), Expression.stop("supermarket", "grocery"),
            *many(listOf("shop", "clothing_store", "hardware", "furniture", "jewelry", "alcohol_shop", "bakery",
                "butcher", "laundry", "music", "car", "mobile_phone", "art_gallery", "gift", "books", "shoes", "florist", "beauty", "hairdresser",
                "pet", "toys", "optician", "stationery", "electronics", "department_store", "convenience", "variety_store", "garden_centre", "computer"), "shop"),
            *many(listOf("restaurant", "fast_food", "ice_cream", "food_court"), "food"),
            *many(listOf("cafe"), "cafe"),
            *many(listOf("bar", "beer", "pub", "biergarten"), "bar"),
            *many(listOf("pharmacy", "hospital", "doctors", "dentist", "veterinary"), "health"),
            Expression.stop("bicycle", "bike"),
            *many(listOf("fuel", "charging_station"), "fuel"),
            *many(listOf("lodging", "hotel"), "hotel"),
            *many(listOf("park", "garden", "playground", "pitch", "sports_centre", "golf"), "park"),
            *many(listOf("school", "college", "university", "kindergarten"), "school"))
        val textCol = Expression.match(group, Expression.literal(groups.getValue("other").third),
            *groups.entries.filter { it.key != "other" }.map { Expression.stop(it.key, it.value.third) }.toTypedArray())
        // Google order of importance: supermarkets & shops first, then food, then the rest
        val rank = Expression.match(group, Expression.literal(6),
            Expression.stop("grocery", 0), Expression.stop("shop", 1), Expression.stop("food", 2), Expression.stop("cafe", 2),
            Expression.stop("bar", 2), Expression.stop("health", 3), Expression.stop("bike", 2), Expression.stop("fuel", 3))
        val vis = PropertyFactory.visibility(if (prefs().getBoolean("showPois", true)) Property.VISIBLE else Property.NONE)
        val notTransit = Expression.all(Expression.has("name"),
            Expression.match(cls, Expression.literal(true), *many(listOf("bus", "railway", "parking",
                "bicycle_parking", "toilets", "information", "entrance", "atm", "post_box", "drinking_water", "bench", "waste_basket",
                "recycling", "attraction", "tram", "ferry_terminal", "aerialway", "bicycle_rental", "town_hall"), false)))
        fun labelled(id: String) = SymbolLayer(id, srcId).withProperties(
            PropertyFactory.iconImage(Expression.concat(Expression.literal("g-"), group)), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.textField(Expression.get("name")), PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(14f, 11.5f), Expression.stop(17f, 13f))),
            PropertyFactory.textColor(textCol), PropertyFactory.textHaloColor(if (dark) "#1F1F1F" else "#FFFFFF"), PropertyFactory.textHaloWidth(1.6f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT), PropertyFactory.textOffset(arrayOf(0.95f, -0.85f)),
            PropertyFactory.textMaxWidth(8f), PropertyFactory.textOptional(true), PropertyFactory.symbolSortKey(rank), vis)
            .also { it.sourceLayer = "poi" }
        // supermarkets and big shops from a little further out, everything once zoomed in
        st.addLayerBelow(labelled("g-poi-major").also {
            it.setFilter(Expression.all(notTransit, Expression.eq(group, Expression.literal("grocery")))); it.setMinZoom(13.5f); it.setMaxZoom(15f)
        }, "route-alt-casing")
        st.addLayerBelow(labelled("g-poi").also { it.setFilter(notTransit); it.setMinZoom(15f) }, "route-alt-casing")
        // bus stops, stations and parking: small neat squares, only when zoomed right in
        val tIcon = Expression.match(cls, Expression.literal("g-bus"),
            *many(listOf("parking", "bicycle_parking"), "g-parking"), Expression.stop("railway", "g-train"))
        st.addLayerBelow(SymbolLayer("g-transit", srcId).withProperties(
            PropertyFactory.iconImage(tIcon),
            PropertyFactory.textField(Expression.match(cls, Expression.get("name"), *many(listOf("parking", "bicycle_parking"), ""))),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")), PropertyFactory.textSize(11f),
            PropertyFactory.textColor(if (dark) "#9FB4D6" else "#3C6CB4"), PropertyFactory.textHaloColor(if (dark) "#1F1F1F" else "#FFFFFF"),
            PropertyFactory.textHaloWidth(1.5f), PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT), PropertyFactory.textOffset(arrayOf(1.0f, 0f)),
            PropertyFactory.textMaxWidth(7f), PropertyFactory.textOptional(true), PropertyFactory.symbolSortKey(8f), vis)
            .also {
                it.sourceLayer = "poi"; it.setMinZoom(16f)
                it.setFilter(Expression.match(cls, Expression.literal(false),
                    *many(listOf("bus", "railway", "parking", "bicycle_parking"), true)))
            }, "route-alt-casing")
    }

    private fun makePoiIcon(color: String, icon: String): Bitmap {
        val sz = 72
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0x33000000; c.drawCircle(sz / 2f, sz / 2f + 2f, sz / 2f - 2f, paint)      // soft shadow
        paint.color = Color.WHITE; c.drawCircle(sz / 2f, sz / 2f, sz / 2f - 2f, paint)
        paint.color = Color.parseColor(color); c.drawCircle(sz / 2f, sz / 2f, sz / 2f - 7f, paint)
        AppIcons.draw(c, icon, Color.WHITE, 18f, 18f, 36f)
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
        head.addView(roundIcon("close") { closeAll() })
        sheet.addView(head)
        val btns = LinearLayout(this).apply { setPadding(0, dp(18), 0, 0) }
        btns.addView(pillButton("@directions  Directions", BLUE, Color.WHITE) { startDirections() }, LinearLayout.LayoutParams(0, dp(52), 1.3f).apply { rightMargin = dp(10) })
        btns.addView(pillButton("@home  Save as Home", SOFTBLUE, BLUE) { saveHome(d) }, LinearLayout.LayoutParams(0, dp(52), 1f))
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
        head.addView(TextView(this).apply { iconText("@bike  E-bike", INK, 24); gravity = Gravity.CENTER_VERTICAL; textSize = 22f; setTextColor(INK); setTypeface(null, Typeface.BOLD) },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(roundIcon("close") { closeAll() })
        head.setOnTouchListener(sheetDrag)
        sheet.addView(head)
        // keep a pulled-down sheet pulled down when its content refreshes
        sheet.post { if (mode == Mode.ROUTES && sheetCollapsed) sheet.translationY = sheetMaxDrop() }

        val prof = LinearLayout(this).apply { setPadding(0, dp(12), 0, dp(4)) }
        prof.addView(toggleChip("@leaf  Quiet roads", routeProfile == "trekking") {
            if (routeProfile != "trekking") { routeProfile = "trekking"; computeRoutes(d) } })
        prof.addView(toggleChip("@bolt  Faster roads", routeProfile == "fastbike") {
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
            val need = (r.meters / 1609.34) / max(0.01, range.rangeAt(1.0, rangeMph()))   // at your average speed
            val left = lastPct - need
            sheet.addView(TextView(this).apply {
                val col = if (left < 8) RED else if (left < 25) AMBER else GREEN
                iconText(if (left < 8) "@warning May not have enough battery (needs ~${need.roundToInt()}%, you have ${lastPct.roundToInt()}%)"
                         else "@battery Arrive with ~${left.roundToInt()}% battery", col, 16)
                gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = dp(6)
                textSize = 14f; setTextColor(col)
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
                    usual != null && abs(rr.meters - usual) < max(80, usual / 20) -> "Your usual"
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
        sheet.addView(pillButton("@navigation  Start", BLUE, Color.WHITE) { startNav() }, LinearLayout.LayoutParams(MATCH, dp(56)))
    }

    private fun selectRoute(i: Int) { sel = i; redrawRoutes(); fillRoutesSheet(); fitRoute() }

    private fun hillLabel(r: Router.Route): String {
        val perKm = if (r.meters > 0) r.ascentM / (r.meters / 1000.0) else 0.0
        val word = when { perKm < 5 -> "Mostly flat"; perKm < 12 -> "Gentle hills"; perKm < 22 -> "Moderate hills"; else -> "Steep hills" }
        return "$word (${(r.ascentM * 3.28084).roundToInt()} ft climb)"
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
        val bottom = if (sheet.visibility == View.VISIBLE && sheet.height > 0) (sheet.height - sheet.translationY).toInt() + dp(30) else dp(420)
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, dp(50), sbH + dp(150), dp(50), bottom), 700)
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
        loadStreetNames(nav!!)
        val first = nav!!.maneuvers.first()
        speak("Starting route to ${d.title}. " + if (first.type == Maneuver.Type.ARRIVE) "Head straight to your destination."
              else "In ${spokenDist(first.along)}, ${NavRoute.text(first.type).lowercase()}.")
        lastLoc?.let { onNavFix(it) }
        connectForTrip()
    }

    // ---- start of a journey: connect to the bike and check the battery ----
    private var tripCheckPending = false
    private var tripConnectTries = 0
    private var firstLiveAt = 0L

    private fun connectForTrip() {
        tripCheckPending = true; firstLiveAt = 0L
        if (BleManager.connected) return                     // check runs on the next live reading
        if (!BleManager.isBluetoothOn()) {
            AlertDialog.Builder(this).setTitle("Connect your bike?")
                .setMessage("Bluetooth is off, so Amped can't read your battery for this trip.")
                .setPositiveButton("Turn on Bluetooth") { _, _ ->
                    try { startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
                    ui.postDelayed({ if (mode == Mode.NAV) { tripConnectTries = 0; tryTripConnect() } }, 4000)
                }
                .setNegativeButton("Not now") { _, _ -> tripCheckPending = false }.show()
            return
        }
        tripConnectTries = 0
        toast("Connecting to your bike…")
        tryTripConnect()
    }

    /** The bike scan gives up after 15 s; try up to three times while navigating. */
    private fun tryTripConnect() {
        if (BleManager.connected || mode != Mode.NAV || !tripCheckPending) return
        if (tripConnectTries >= 3) { toast("Couldn't find your bike — is it switched on?"); tripCheckPending = false; return }
        tripConnectTries++
        BleManager.connect()
        ui.postDelayed({ tryTripConnect() }, 16000)
    }

    /** Called with live data: once the voltage has settled, say whether the battery covers the trip. */
    private fun tripBatteryCheck(d: BleManager.Realtime, pct: Double) {
        if (!tripCheckPending || mode != Mode.NAV) return
        val now = System.currentTimeMillis()
        if (firstLiveAt == 0L) { firstLiveAt = now; return }
        if (now - firstLiveAt < 3000 || pct < 0) return          // give the reading a few seconds to settle
        tripCheckPending = false
        val nr = nav ?: return
        val leftMi = max(0.0, nr.total - navAlong) / 1609.34
        val milesPerPct = max(0.01, range.rangeAt(1.0, rangeMph()))
        val need = leftMi / milesPerPct
        val arrive = pct - need
        val p = pct.roundToInt()
        val volts = "%.1f".format(d.batteryV)
        val msg = when {
            arrive >= 10 -> "Bike connected. Battery $p percent, $volts volts. Plenty for this trip, you'll arrive with about ${arrive.roundToInt()} percent."
            arrive >= 0 -> "Bike connected. Battery $p percent, $volts volts. Enough for this trip, but you'll arrive with only about ${max(0, arrive.roundToInt())} percent."
            else -> "Warning: battery $p percent, $volts volts. This trip needs about ${need.roundToInt()} percent, so you may not make it without charging."
        }
        speak(msg, queue = true)          // after the route announcement, not over it
        toast(msg.replace(" percent", "%").replace(" volts", " V"))
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
        tvTurnIcon.text = ""
        tvTurnIcon.background = AppIcons.drawable(this, NavRoute.iconName(m.type), Color.WHITE, 52)
        tvTurnDist.text = fmtTurnDist(dm)
        tvTurnText.text = if (m.type == Maneuver.Type.ARRIVE) "Arrive at ${d.title}"
            else streetAt(mi)?.let { Router.shortStreet(it) } ?: NavRoute.text(m.type)
        tvTurnText.textSize = if (streetAt(mi) != null && m.type != Maneuver.Type.ARRIVE) 26f else 18f
        val next = nr.maneuvers.getOrNull(mi + 1)
        if (next != null && next.along - m.along < 250) {
            tvThen.text = "Then"
            tvThen.setCompoundDrawablesRelative(null, null, AppIcons.drawable(this, NavRoute.iconName(next.type), Color.WHITE, 22), null)
            tvThen.compoundDrawablePadding = dp(8); tvThen.visibility = View.VISIBLE
        } else tvThen.visibility = View.GONE
        announce(mi, m, dm)

        // ETA
        val remM = (nr.total - s.along) + if (onRoute) 0.0 else s.offRoute
        val remS = remM / max(1.0, nr.total) * eta(nr.route)
        tvEta.text = fmtDuration(max(1, (remS / 60).roundToInt()))
        val arrivePct = if (lastPct >= 0 && BleManager.connected) lastPct - (remM / 1609.34) / max(0.01, range.rangeAt(1.0, rangeMph())) else null
        tvEtaSub.text = "${fmtMiles(remM)}  ·  ${clock(remS.roundToInt())}" +
            (arrivePct?.let { "  ·  ${max(0, it.roundToInt())}% battery left" } ?: "")
        tvEtaSub.setTextColor(if (arrivePct != null && arrivePct < 8) RED else MUTED)

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

    private fun loadStreetNames(nr: NavRoute) {
        val pts = nr.streetSamplePoints()
        searchExec.execute {
            val names = Router.streetNames(pts)
            runOnUiThread { if (nav === nr) nr.streets = names }
        }
    }

    private fun streetAt(mi: Int): String? = nav?.streets?.getOrNull(mi)

    private fun announce(mi: Int, m: Maneuver, dm: Double) {
        val stage = manStage[mi] ?: 0
        val arrive = m.type == Maneuver.Type.ARRIVE
        val onto = streetAt(mi)?.let { " onto $it" } ?: ""
        val what = if (arrive) "your destination will be ahead" else NavRoute.text(m.type).lowercase() + onto
        when {
            !arrive && dm <= 35 && stage < 3 -> { speak(NavRoute.text(m.type) + onto); manStage[mi] = 3 }
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
                    loadStreetNames(nav!!)
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
        sheet.addView(TextView(this).apply { iconText("@flag  You've arrived", GREEN, 26); gravity = Gravity.CENTER_VERTICAL; textSize = 24f; setTextColor(INK); setTypeface(null, Typeface.BOLD) })
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

    private fun speak(text: String, queue: Boolean = false) {
        if (muted || !ttsReady) return
        tts?.speak(text, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, "voltroute")
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
        tvSpeed.setTextColor(if (lim != null && mph > lim + 1) 0xFFFF6E6E.toInt() else Color.WHITE)
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
        } else if (follow && mode == Mode.BROWSE) {
            val c = map?.cameraPosition?.target
            if (c == null || Geo.dist(c.latitude, c.longitude, ll.latitude, ll.longitude) > 6) moveTo(ll, null, 900)
        }
        // calibrate the controller's speed field against GPS (wheel size): mph × raw ≈ constant
        BleManager.lastRealtime?.let { rt ->
            if (BleManager.connected && loc.hasSpeed() && loc.speed > 3.6f && loc.accuracy < 15f && rt.speedRpm in 80..2000) {
                val k = loc.speed * 2.2369363 * rt.speedRpm
                if (k in 1000.0..12000.0) {
                    BleManager.motorK = BleManager.motorK * 0.98 + k * 0.02
                    if (++motorKSamples % 30 == 0) prefs().edit().putFloat("motorK", BleManager.motorK.toFloat()).apply()
                }
            }
        }
        // average moving speed this ride (for range estimates)
        if (loc.hasSpeed() && loc.speed > 1.5f && loc.accuracy < 30f) {
            val now = System.currentTimeMillis()
            if (lastMoveAt > 0 && now - lastMoveAt < 5000) { rideMovingSecs += (now - lastMoveAt) / 1000.0; rideMiles += loc.speed * (now - lastMoveAt) / 1000.0 / 1609.34 }
            lastMoveAt = now
            // live: the ride average (one decimal) ticks as you go
            if (rangeAuto() && BleManager.connected && lastPct >= 0) showBatt(lastPct, true)
        } else lastMoveAt = 0L
    }
    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    private fun maybeQuerySpeedLimit(loc: Location) {
        if (limitBusy) return
        val now = System.currentTimeMillis()
        val prev = limitQueryLoc
        if (prev != null && (loc.distanceTo(prev) < 120f || now - limitQueryAt < 15000)) return
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
        val pct = estimator.update(d.batteryV, d.throttleV, d.motorMph, d.cruise, d.brake, d.gear)
        val l = lastLoc
        rideLog.log(l?.latitude, l?.longitude, l?.let { if (it.hasSpeed()) it.speed * 2.2369363 else null }, l?.accuracy,
            d.batteryV, d.throttleV, d.speedRpm, pct, estimator.holding, mode.name, navAlong, d.workState, d.motorMph, d.ctrlTemp, d.motorTemp, estimator.watts)
        if (d.batteryV > 5) {
            if (lastPct < 0) showBatt(-1.0, true)
            val w = estimator.watts
            showVoltsWatts(d.batteryV, if (w >= 10) (w / 10).roundToInt() * 10 else 0)
        }
        showBikeStatus(d)
        tripBatteryCheck(d, pct)
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
        wasCruise = d.cruise; wasReverse = d.reverse
        // faults: announce each new one
        val newBits = d.fault and lastFault.inv()
        if (newBits != 0) {
            val names = BleManager.FAULTS.filterIndexed { i, _ -> newBits and (1 shl i) != 0 }
            val msg = "Bike warning: " + names.joinToString(", ")
            speak(msg); toast(msg)
        }
        lastFault = d.fault

        // build the wanted chips; only touch the views when something actually changed (no flicker)
        val want = ArrayList<Pair<String, Int>>()
        if (d.cruise) want += "CRUISE" to 0xFF188038.toInt()
        if (d.reverse) want += "REVERSE" to 0xFFE37400.toInt()
        if (d.brake) want += "BRAKE" to RED
        if (d.antiTheft) want += "ANTI-THEFT" to 0xFF5F6368.toInt()
        if (d.fault != 0) want += ("FAULT: " + d.faults.first()) to RED
        if (want != shownChips) {
            shownChips = want
            statusRow.removeAllViews()
            for ((t, bg) in want) statusRow.addView(TextView(this).apply {
                text = t; textSize = 13f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
                setPadding(dp(12), dp(6), dp(12), dp(6)); background = shape(bg, dp(14)); elevation = dp(4).toFloat()
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        }
        statusRow.visibility = if (want.isNotEmpty() && hud.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }
    private var shownChips: List<Pair<String, Int>> = emptyList()

    override fun onStatus(status: String, connected: Boolean) = runOnUiThread {
        if (!backgrounded) tuneGps()
        if (!connected) { statusRow.removeAllViews(); shownChips = emptyList(); statusRow.visibility = View.GONE }
        if (!connected) { wattCard.text = "– W"; showBatt(lastPct, false) }
        if (!connected) showBatt(lastPct, false)
    }

    private var rideMovingSecs = 0.0; private var rideMiles = 0.0; private var lastMoveAt = 0L

    /** Average speed for range: your average this ride (after 1 min of riding), else your learned average, else 15. */
    private fun autoMph(): Double = when {
        rideMovingSecs > 60 -> rideMiles / (rideMovingSecs / 3600.0)
        else -> range.learnedAvgMph ?: 15.0
    }.coerceIn(6.0, 35.0)
    private fun rangeAuto() = prefs().getInt("rangeMph", 0) == 0
    private fun rangeMph(): Double = if (rangeAuto()) autoMph() else prefs().getInt("rangeMph", 15).toDouble()

    /** Battery block: big %, red→green live bar, and miles left at your chosen average speed. */
    private fun showBatt(pct: Double, live: Boolean) {
        val connected = BleManager.connected
        if (connected && pct < 0) {            // connected, waiting for the first settled reading
            battRing.setPct(null); tvBatt.text = "–"; tvRange.text = "Reading battery…"; return
        }
        if (!connected || pct < 0) {
            battRing.setPct(null)
            tvBatt.text = "–"
            tvVolts.text = "No bike"
            tvRange.text = if (pct >= 0) "Last seen ${pct.roundToInt()}%" else "Connect to see battery"
            return
        }
        val p = pct.coerceIn(0.0, 100.0)
        battRing.setPct((p / 100.0).toFloat())
        tvBatt.text = "${p.roundToInt()}"
        val mi = range.rangeAt(p, rangeMph())
        tvRange.text = (if (rangeAuto()) "Avg %.1f mph".format(rangeMph()) else "At ${rangeMph().roundToInt()} mph") + " = ${mi.roundToInt()} mi left"
    }

    /** The battery charge ring: faint track + yellow-to-orange arc filling clockwise from the top. */
    inner class RingView(ctx: Context) : View(ctx) {
        private var frac: Float? = null
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x33FFFFFF; strokeCap = Paint.Cap.ROUND }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        fun setPct(f: Float?) { if (f != frac) { frac = f; invalidate() } }
        override fun onDraw(c: Canvas) {
            val w = dp(5).toFloat(); track.strokeWidth = w; arc.strokeWidth = w
            val r = RectF(w / 2 + 1, w / 2 + 1, width - w / 2 - 1, height - w / 2 - 1)
            c.drawOval(r, track)
            val f = frac ?: return
            arc.shader = LinearGradient(r.left, r.top, r.right, r.bottom, 0xFFFFD54F.toInt(), 0xFFF4511E.toInt(), Shader.TileMode.CLAMP)
            c.drawArc(r, -90f, 360f * f.coerceIn(0.005f, 1f), false, arc)
        }
    }

    private fun showBatteryDetails() {
        val pct = if (lastPct >= 0) "${lastPct.roundToInt()}%" else "unknown"
        val v = if (lastVolts > 0) String.format(Locale.US, "%.1f V", lastVolts) else "–"
        AlertDialog.Builder(this).setTitle("Battery")
            .setMessage("Charge: $pct\nVoltage: $v\n" +
                    "Range left: ~${if (lastPct >= 0) range.rangeAt(lastPct, rangeMph()).roundToInt() else 0} mi at ${rangeMph().roundToInt()} mph " +
                    "(full charge ≈ ${range.fullRangeMiles().roundToInt()} mi, ${if (range.learned) "learned" else "estimate"})\n" +
                    "Ridden while connected: ${String.format(Locale.US, "%.1f", range.totalMiles)} mi\n" +
                    "Bike: ${BleManager.status}\n\n" + estimator.summary() + "\n" +
                    "Route timing: ${learner.globalRides().let { if (it == 0) "learns from each navigation you finish" else "learned from $it ride${if (it == 1) "" else "s"}" }}\n\n" +
                    "All of this learns automatically every ride and stays on your phone.")
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
            resultsCard.addView(placeRow(p, near, "", "history"))
        }
        resultsCard.visibility = View.VISIBLE; chipsScroll.visibility = View.GONE; resultsOverlayCheck()
    }

    // ============================ traffic ============================
    private fun trafficKey() = prefs().getString("tomtom_key", "") ?: ""

    private fun addTrafficLayer(st: Style) {
        if (st.getLayer("traffic-l") != null) return
        val url = "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?key=${trafficKey()}&tileSize=256"
        st.addSource(RasterSource("traffic", TileSet("2.1.0", url), 256))
        st.addLayerBelow(RasterLayer("traffic-l", "traffic").withProperties(PropertyFactory.rasterOpacity(0.9f)), "route-alt-casing")
        // slowdowns only (no green), drawn ON TOP of the route line so congestion shows on your route like Google
        val delayUrl = "https://api.tomtom.com/traffic/map/4/tile/flow/relative-delay/{z}/{x}/{y}.png?key=${trafficKey()}&tileSize=256"
        st.addSource(RasterSource("traffic-delay", TileSet("2.1.0", delayUrl), 256))
        st.addLayerAbove(RasterLayer("traffic-top", "traffic-delay").withProperties(PropertyFactory.rasterOpacity(1f),
            PropertyFactory.visibility(if (mode == Mode.ROUTES || mode == Mode.NAV) Property.VISIBLE else Property.NONE)), "route-sel-l")
    }

    private fun removeTrafficLayer(st: Style) {
        st.getLayer("traffic-l")?.let { st.removeLayer(it) }
        st.getSource("traffic")?.let { st.removeSource(it) }
        st.getLayer("traffic-top")?.let { st.removeLayer(it) }
        st.getSource("traffic-delay")?.let { st.removeSource(it) }
    }

    private fun toggleTraffic() {
        if (trafficKey().isEmpty()) { askTrafficKey(); return }
        trafficOn = !trafficOn
        prefs().edit().putBoolean("traffic", trafficOn).apply()
        trafficBtn.background = iconBg(if (trafficOn) SOFTBLUE else Color.WHITE, 24, "traffic", if (trafficOn) BLUE else 0xFF3C4043.toInt(), 48, 24)
        map?.getStyle { st -> if (trafficOn) addTrafficLayer(st) else removeTrafficLayer(st) }
        toast(if (trafficOn) "Live traffic on" else "Live traffic off")
    }

    private fun askTrafficKey() {
        val input = EditText(this).apply { hint = "Paste your TomTom API key"; setSingleLine(true) }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this).setTitle("Live traffic")
            .setMessage("Live traffic needs a free TomTom key (one rider is well within the free daily limit):\n\n" +
                    "1. Go to my.tomtom.com and sign up (free, no card)\n" +
                    "2. Find your API key there and copy it\n3. Paste it below")
            .setView(box)
            .setNeutralButton("Open my.tomtom.com") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://my.tomtom.com/"))) }
            .setPositiveButton("Save") { _, _ ->
                val k = Router.cleanApiKey(input.text.toString())
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
        // 1 s fixes while navigating or riding; slower and only on movement while just browsing
        val fast = mode == Mode.NAV || BleManager.connected
        try {
            lm.removeUpdates(this)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, if (fast) 1000L else 2500L, if (fast) 0f else 4f, this)
            if (!haveFix) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this)
        } catch (_: Exception) {}
        gpsFast = fast
    }
    private var gpsFast = false
    private var motorKSamples = 0

    /** Switch GPS rate when navigation starts/stops or the bike connects. */
    private fun tuneGps() { if ((mode == Mode.NAV || BleManager.connected) != gpsFast) startLocation() }

    // ============================ lifecycle ============================
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() {
        super.onResume(); mapView.onResume(); BleManager.addListener(this); applySettings()
        BleManager.pollMs = 700
        if (backgrounded) { backgrounded = false; startLocation() }
        startHeading()
    }
    private var backgrounded = false

    /** Map refresh rate from Settings: 0 = the screen's maximum (smoothest). */
    private fun applyFps() {
        val want = prefs().getInt("mapFps", 0)
        val screen = (if (Build.VERSION.SDK_INT >= 30) display?.refreshRate else @Suppress("DEPRECATION") windowManager.defaultDisplay.refreshRate)?.roundToInt() ?: 60
        mapView.setMaximumFps(if (want <= 0) maxOf(screen, 60) else want)
    }

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
        if (::muteBtn.isInitialized) setRoundIcon(muteBtn, if (muted) "volume_off" else "volume_up")
        if (mode != Mode.ROUTES && mode != Mode.NAV) routeProfile = p.getString("defProfile", "trekking") ?: "trekking"
        if (limitMph != null) tvLimit.visibility = if (p.getBoolean("showLimit", true)) View.VISIBLE else View.GONE
        val wantTraffic = p.getBoolean("traffic", false) && trafficKey().isNotEmpty()
        if (wantTraffic != trafficOn) {
            trafficOn = wantTraffic
            trafficBtn.background = iconBg(if (trafficOn) SOFTBLUE else Color.WHITE, 24, "traffic", if (trafficOn) BLUE else 0xFF3C4043.toInt(), 48, 24)
            map?.getStyle { st -> if (trafficOn) addTrafficLayer(st) else removeTrafficLayer(st) }
        }
        applyFps()
        val m = map ?: return
        if (appliedStyle.isNotEmpty() && styleUrl() != appliedStyle) {
            appliedStyle = styleUrl()
            m.setStyle(Style.Builder().fromUri(appliedStyle)) { st -> addLayers(st) }
        } else if (p.getBoolean("buildings3d", false) != applied3d) {
            applied3d = p.getBoolean("buildings3d", false)
            m.setStyle(Style.Builder().fromUri(appliedStyle)) { st -> addLayers(st) }
        } else for (lid in listOf("g-poi", "g-poi-major", "g-transit")) m.style?.getLayer(lid)?.setProperties(
            PropertyFactory.visibility(if (p.getBoolean("showPois", true)) Property.VISIBLE else Property.NONE))
        applyMode()
    }
    override fun onPause() {
        super.onPause(); mapView.onPause(); BleManager.removeListener(this); estimator.save(); range.save(); rideLog.flush()
        stopHeading()
        // in the background and not navigating: stop GPS and slow the bike polling to save phone battery
        if (mode != Mode.NAV && !isChangingConfigurations) {
            backgrounded = true
            try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (_: Exception) {}
            BleManager.pollMs = 3000
        }
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

    private fun prettyCat(cat: String): String? {
        if (cat.isEmpty() || cat == "yes") return null
        return when (cat) { "convenience" -> "Convenience store"; "fast_food" -> "Fast food"; "fuel" -> "Petrol station"
            else -> cat.replace('_', ' ').replaceFirstChar { it.uppercase() } }
    }

    /** Sheet grab handle: a wide invisible touch strip with the small grey bar in the middle (drag or tap). */
    private fun handle() = FrameLayout(this).apply {
        addView(View(this@MainActivity).apply { background = shape(0xFFDADCE0.toInt(), dp(3)) },
            FrameLayout.LayoutParams(dp(38), dp(5)).apply { gravity = Gravity.CENTER })
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(22)).apply { bottomMargin = dp(4) }
        setOnTouchListener(sheetDrag)
    }

    // ---- route sheet can be pulled down to see more map (like Google) ----
    private var sheetCollapsed = false
    private var dragStartY = 0f; private var dragStartT = 0f; private var dragMoved = false
    private fun sheetMaxDrop(): Float {
        val peekChild = sheet.getChildAt(3)                     // handle, header, route-type chips, main time line
        val peek = if (peekChild != null) peekChild.bottom + dp(14) else dp(160)
        return max(0, sheet.height - peek).toFloat()
    }
    private val sheetDrag = View.OnTouchListener { _, e ->
        if (mode != Mode.ROUTES) return@OnTouchListener false
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { dragStartY = e.rawY; dragStartT = sheet.translationY; dragMoved = false; true }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - dragStartY
                if (abs(dy) > dp(6)) dragMoved = true
                sheet.translationY = (dragStartT + dy).coerceIn(0f, sheetMaxDrop()); true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                val collapse = if (!dragMoved) !sheetCollapsed else sheet.translationY > sheetMaxDrop() / 2
                snapSheet(collapse); true
            }
            else -> false
        }
    }
    private fun snapSheet(collapse: Boolean) {
        sheetCollapsed = collapse
        sheet.animate().translationY(if (collapse) sheetMaxDrop() else 0f).setDuration(220)
            .withEndAction { if (mode == Mode.ROUTES) fitRoute() }.start()
    }
    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(MUTED); setTypeface(null, Typeface.BOLD); setPadding(dp(18), dp(12), dp(18), dp(4))
    }
    // ---- icons instead of emojis: "@name  Label" puts the Material icon before the text ----
    private fun TextView.iconText(spec: String, color: Int, sizeDp: Int) {
        if (spec.startsWith("@")) {
            val name = spec.substring(1).substringBefore(' ')
            text = spec.substringAfter(' ', "").trimStart()
            setCompoundDrawablesRelative(AppIcons.drawable(this@MainActivity, name, color, sizeDp), null, null, null)
            compoundDrawablePadding = dp(8)
        } else { text = spec; setCompoundDrawablesRelative(null, null, null, null) }
    }
    /** Background = rounded shape with the icon centred on it (for square/round buttons). */
    private fun iconBg(bg: Int, radiusDp: Int, icon: String, color: Int, boxDp: Int, iconDp: Int): android.graphics.drawable.Drawable {
        val ld = android.graphics.drawable.LayerDrawable(arrayOf(shape(bg, dp(radiusDp)), AppIcons.drawable(this, icon, color, iconDp)))
        val inset = dp((boxDp - iconDp) / 2)
        ld.setLayerInset(1, inset, inset, inset, inset)
        return ld
    }
    private fun iconView(name: String, color: Int, sizeDp: Int) = ImageView(this).apply {
        setImageDrawable(AppIcons.drawable(this@MainActivity, name, color, sizeDp)); layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        iconText(label, 0xFF3C4043.toInt(), 18); textSize = 15f; setTextColor(INK); setPadding(dp(14), dp(9), dp(16), dp(9))
        gravity = Gravity.CENTER_VERTICAL
        background = shape(Color.WHITE, dp(20)); elevation = dp(3).toFloat()
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
    }
    private fun toggleChip(label: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        iconText(label, if (on) BLUE else MUTED, 16); gravity = Gravity.CENTER_VERTICAL
        textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(if (on) BLUE else MUTED)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
            setColor(if (on) SOFTBLUE else Color.WHITE); cornerRadius = dp(18).toFloat(); setStroke(dp(1), if (on) BLUE else LINE)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
    }
    private fun pillButton(t: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
        iconText(t, fg, 20); textSize = 17f; setTextColor(fg); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        if (t.startsWith("@")) setPadding(dp(18), 0, dp(22), 0)
        background = shape(bg, dp(28)); setOnClickListener { onClick() }
    }
    private fun roundIcon(icon: String, onClick: () -> Unit) = TextView(this).apply {
        background = iconBg(CHIPBG, 22, icon, 0xFF3C4043.toInt(), 44, 22); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }
    private fun setRoundIcon(v: View, icon: String) { v.background = iconBg(CHIPBG, 22, icon, 0xFF3C4043.toInt(), 44, 22) }
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
