package com.interop.ridepowerble

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * The main Settings screen: Bike, Map & appearance, Navigation, Traffic, Privacy & data, About.
 * Everything is stored in the "app" preferences and picked up by the map screen when you return.
 */
class AppSettingsActivity : android.app.Activity(), BleManager.Listener {

    private val BG = 0xFFF1F3F4.toInt(); private val CARD = Color.WHITE
    private val BLUE = 0xFF1A73E8.toInt(); private val INK = 0xFF202124.toInt()
    private val MUTED = 0xFF5F6368.toInt(); private val LINE = 0xFFE8EAED.toInt(); private val RED = 0xFFD93025.toInt()

    private lateinit var body: LinearLayout
    private var bikeStatus: TextView? = null

    private fun prefs() = getSharedPreferences("app", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BleManager.init(this)
        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(10), dp(16), dp(10))
            elevation = dp(2).toFloat()
            addView(ImageView(context).apply {
                setImageDrawable(AppIcons.drawable(this@AppSettingsActivity, "arrow_back", INK, 24))
                scaleType = ImageView.ScaleType.CENTER; setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(context).apply { text = "Settings"; textSize = 21f; setTextColor(INK); setTypeface(null, Typeface.BOLD) })
        })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(32)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        build()
    }

    override fun onResume() { super.onResume(); BleManager.pollMs = 700; BleManager.addListener(this); build() }
    override fun onPause() { super.onPause(); BleManager.removeListener(this) }
    override fun onStatus(status: String, connected: Boolean) { runOnUiThread { bikeStatus?.text = statusLine() } }

    private fun statusLine() = if (BleManager.connected) "Connected · ${BleManager.status}" else "Not connected · ${BleManager.status}"

    private fun build() {
        body.removeAllViews()
        val p = prefs()

        // ---------------- Rides ----------------
        section("Rides")
        card {
            row("history", "Ride history", "Your rides with start time, distance, speed, battery and route") {
                startActivity(Intent(this, RideHistoryActivity::class.java))
            }
        }

        // ---------------- Bike ----------------
        section("Bike")
        card {
            row("bike", "Bike settings", "Controller: PAS, current, regen, limits, self-learning") {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            line()
            val st = row("bluetooth", if (BleManager.connected) "Disconnect bike" else "Connect bike", statusLine()) {
                if (BleManager.connected) BleManager.disconnect()
                else if (BleManager.isBluetoothOn()) BleManager.connect()
                else toast("Turn on Bluetooth first")
                body.postDelayed({ build() }, 600)
            }
            bikeStatus = st
            line()
            val est = BatteryEstimator(this@AppSettingsActivity)
            val rng = RangeLearner(this@AppSettingsActivity)
            choice("bolt", "Battery voltage", "packSeries", listOf("Auto-detect" to 0, "36 V (10S, 42 V full)" to 10,
                "48 V (13S, 54.6 V full)" to 13, "52 V (14S, 58.8 V full)" to 14, "60 V (16S, 67.2 V full)" to 16,
                "72 V (20S, 84 V full)" to 20), 0) {
                getSharedPreferences("battery2", Context.MODE_PRIVATE).edit().remove("soc").remove("series").apply()
                prefs().edit().putBoolean("resetLearners", true).apply() }
            line()
            val rtV = BleManager.lastRealtime?.batteryV
            row("tune", "Voltage calibration", (if (rtV != null) "Reading ${"%.1f".format(rtV)} V" else "Connect the bike to calibrate") +
                    (if (BleManager.voltCal != 1.0) " · corrected ×${"%.3f".format(BleManager.voltCal)}" else "")) { calibrateVolts() }
            line()
            choice("bolt", "Controller current limit", "maxAmps", listOf(20, 25, 30, 35, 40, 45, 50, 60, 70, 80).map { "$it A" to it }, 45) {
                prefs().edit().putBoolean("resetLearners", true).apply() }
            line()
            choice("battery", "Battery capacity", "packAh10", listOf("Not set" to 0) +
                listOf(10, 12, 13, 15, 17, 17.5, 20, 21, 24, 25, 28, 30, 35, 40, 50).map {
                    val n = it.toDouble(); (if (n % 1.0 == 0.0) "${n.toInt()} Ah" else "$n Ah") to (n * 10).toInt() }, 0) {
                prefs().edit().putBoolean("resetLearners", true).apply() }
            line()
            row("battery", "Battery & range learning",
                "Learned full range ≈ ${"%.0f".format(rng.fullRangeMiles())} mi\n${est.summary()}") { confirmResetBattery() }
            line()
            choice("trending", "Range estimate speed", "rangeMph",
                listOf("Automatic (your average speed)" to 0) + listOf(10, 12, 15, 18, 20, 22, 25, 28).map { "$it mph average" to it }, 0)
            line()
            choice("warning", "Low battery warning", "lowBatt", listOf("Off" to 0, "10%" to 10, "15%" to 15, "20%" to 20, "25%" to 25), 15)
        }

        // ---------------- Map & appearance ----------------
        section("Map & appearance")
        card {
            choiceStr("layers", "Map style", "mapStyle",
                listOf("Standard" to "liberty", "Bright" to "bright", "Light" to "positron", "Dark" to "dark"), "liberty")
            line()
            val hz = (if (android.os.Build.VERSION.SDK_INT >= 30) display?.refreshRate else @Suppress("DEPRECATION") windowManager.defaultDisplay.refreshRate)?.let { Math.round(it) } ?: 60
            choice("tune", "Map smoothness", "mapFps", listOf("Maximum — ${hz} Hz (smoothest)" to 0) +
                listOf(120, 90, 60).filter { it < hz }.map { "$it Hz" to it } + listOf("30 Hz (saves battery)" to 30), 0)
            line()
            row("download", "Offline maps", "Download the map around you for riding with no signal") { OfflineMaps.open(this@AppSettingsActivity) }
            line()
            toggle("store", "Shops & places on the map", "Names and icons when zoomed in", "showPois", true)
            line()
            toggle("city", "3D buildings", "Off = flat buildings like Google Maps (clearer streets)", "buildings3d", false)
            line()
            toggle("explore", "3D view while navigating", "Tilted map that looks ahead", "navTilt", true)
            line()
            toggle("sun", "Keep screen on", "Stop the screen sleeping while the map is open (always on while navigating)", "keepOn", false)
        }

        // ---------------- Navigation ----------------
        section("Navigation")
        card {
            val voice = !p.getBoolean("muted", false)
            switchRow("volume_up", "Voice guidance", "Spoken turn instructions", voice) { on -> p.edit().putBoolean("muted", !on).apply() }
            line()
            choiceStr("directions", "Preferred routes", "defProfile",
                listOf("Quiet roads & cycle paths" to "trekking", "Faster roads" to "fastbike"), "trekking")
            line()
            toggle("eye", "Show speed limit", "Road limit next to your speed", "showLimit", true)
            line()
            toggle("bell", "Speed limit alert", "Speaks when you go over the road's limit", "speedAlert", true)
            line()
            row("history", "Trip learning", tripSummary()) { confirm("Reset trip learning?",
                "Amped forgets how long your usual trips take and which routes you ride. ETAs go back to the default.") {
                getSharedPreferences("trips", Context.MODE_PRIVATE).edit().clear().apply(); p.edit().putBoolean("resetLearners", true).apply(); build()
            } }
        }

        // ---------------- Traffic ----------------
        section("Traffic")
        card {
            val key = p.getString("tomtom_key", "") ?: ""
            toggle("traffic", "Show traffic", if (key.isEmpty()) "Add a TomTom key first" else "Live traffic colours on roads", "traffic", false)
            line()
            row("key", "TomTom API key", if (key.isEmpty()) "Not set — tap to add" else "Set (…${key.takeLast(4)}) — tap to change or remove") { editKey() }
        }

        // ---------------- Privacy & data ----------------
        section("Privacy & data")
        card {
            row("history", "Clear search history", "Removes your recent searches") {
                confirm("Clear search history?", "Your recent searches will be removed.", "Clear") {
                    p.edit().remove("recent").apply(); toast("Search history cleared")
                }
            }
            line()
            row("share", "Export a ride log", "${RideLogger.rides(this@AppSettingsActivity).size} recorded — share one to help tune battery & ETA") {
                RideLogger.export(this@AppSettingsActivity)
            }
            line()
            row("lock", "What Amped stores", "Everything stays on this phone") {
                AlertDialog.Builder(this).setTitle("Your data")
                    .setMessage("Amped has no account and no tracking. Searches, saved places, learned battery/ETA data and your TomTom key are stored only on this phone.\n\n" +
                            "To show maps, search and routes, the app asks free public services (OpenFreeMap, OpenStreetMap/Photon/Nominatim/Overpass, BRouter, and TomTom if traffic is on) about the area you're looking at.")
                    .setPositiveButton("OK", null).show()
            }
        }

        // ---------------- About ----------------
        section("About")
        card {
            row("download", "Check for updates", "You have Amped ${Updater.currentVersionName(this@AppSettingsActivity)}") {
                toast("Checking…")
                Updater.check(this@AppSettingsActivity) { r, err ->
                    if (r != null) Updater.prompt(this@AppSettingsActivity, r) else toast(err ?: "You're on the latest version")
                }
            }
            line()
            row("code", "Source code", "github.com/evanrogersfortnite-gif/Ride-power-V2") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/evanrogersfortnite-gif/Ride-power-V2")))
            }
            line()
            row("info", "Map data", "© OpenStreetMap contributors · OpenFreeMap · BRouter") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright")))
            }
        }
    }

    private fun tripSummary(): String {
        val n = TripLearner(this).globalRides()
        return if (n == 0) "No rides learned yet — tap to reset" else "Learned from $n ride${if (n == 1) "" else "s"} — tap to reset"
    }

    private fun confirmResetBattery() = confirm("Reset battery learning?",
        "Amped forgets your pack size and range per %, and learns them again over your next rides. Use this after changing battery.") {
        getSharedPreferences("battery2", Context.MODE_PRIVATE).edit().clear().apply(); getSharedPreferences("battery", Context.MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("range", Context.MODE_PRIVATE).edit().clear().apply()
        prefs().edit().putBoolean("resetLearners", true).apply()
        toast("Battery learning reset"); build()
    }

    /** Match the app's voltage to the bike display / a meter. */
    /**
     * One-tap calibration against a known voltage: a fully charged pack sits at exactly
     * cells × 4.2 V (58.8 V for 52 V / 14S), because the charger stops there. The app measures
     * the resting voltage for ~5 s and scales its readings so that reads as full.
     */
    private fun calibrateVolts() {
        if (BleManager.lastRealtime == null || !BleManager.connected) { toast("Connect the bike first"); return }
        val series = prefs().getInt("packSeries", 0).takeIf { it > 0 }
            ?: BatteryEstimator(this).learnedSeries.takeIf { it > 0 } ?: 14
        val full = series * 4.2
        AlertDialog.Builder(this).setTitle("Calibrate voltage")
            .setMessage("For an exact calibration:\n\n1. Charge the battery to full and unplug the charger\n" +
                    "2. Keep the bike still, hands off the throttle\n3. Tap Calibrate\n\n" +
                    "Amped measures for 5 seconds and sets itself so a full battery reads ${"%.1f".format(full)} V.")
            .setPositiveButton("Calibrate") { _, _ -> measureAndCalibrate(full) }
            .setNeutralButton("Other options") { _, _ -> calibrateMenu() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun measureAndCalibrate(full: Double) {
        val label = TextView(this).apply { text = "Measuring… keep the bike still"; textSize = 16f; setPadding(dp(24), dp(20), dp(24), dp(8)) }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(24), dp(12)); addView(label); addView(bar) }
        val dlg = AlertDialog.Builder(this).setTitle("Calibrating").setView(box).setCancelable(false).show()
        val samples = ArrayList<Double>()
        var lastSeen: BleManager.Realtime? = null
        var moved = false
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val start = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val rt = BleManager.lastRealtime
                if (rt != null && rt !== lastSeen) {
                    lastSeen = rt
                    val still = (rt.speedRpm == 0 || rt.speedRpm >= 20000) && rt.throttleV < 1.05
                    if (!still) moved = true else samples += rt.batteryV / BleManager.voltCal   // raw (uncorrected) volts
                }
                val el = System.currentTimeMillis() - start
                bar.progress = (el / 50).toInt().coerceAtMost(100)
                if (el < 5000) { h.postDelayed(this, 200); return }
                dlg.dismiss()
                if (moved || samples.size < 4) { toast("The bike moved or the throttle was touched — try again with it still"); return }
                val raw = samples.sorted()[samples.size / 2]
                val f = full / raw
                if (f < 0.94 || f > 1.06) {
                    AlertDialog.Builder(this@AppSettingsActivity).setTitle("Not fully charged?")
                        .setMessage("The battery reads ${"%.1f".format(raw * BleManager.voltCal)} V — too far from full (${"%.1f".format(full)} V) to be a fully charged pack. " +
                                "Charge to full first, or use “Other options” to enter a meter reading.")
                        .setPositiveButton("OK", null).show()
                    return
                }
                BleManager.voltCal = f
                prefs().edit().putFloat("voltCal", f.toFloat()).putBoolean("resetLearners", true).apply()
                getSharedPreferences("battery2", Context.MODE_PRIVATE).edit().remove("soc").apply()
                toast("Calibrated: full battery now reads ${"%.1f".format(full)} V (×${"%.3f".format(f)})"); build()
            }
        }
        h.post(tick)
    }

    /** Fallbacks: type in a meter/display reading, or reset. */
    private fun calibrateMenu() {
        val rt = BleManager.lastRealtime ?: return
        val raw = rt.batteryV / BleManager.voltCal
        val input = EditText(this).apply {
            hint = "e.g. 54.2"; setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this).setTitle("Enter a meter reading")
            .setMessage("Amped reads ${"%.1f".format(rt.batteryV)} V now. With the bike still, enter what a multimeter shows:")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (v == null || raw <= 0) { toast("Enter a number like 54.2"); return@setPositiveButton }
                val f = v / raw
                if (f < 0.85 || f > 1.15) { toast("That's more than 15% off — check the number"); return@setPositiveButton }
                BleManager.voltCal = f
                prefs().edit().putFloat("voltCal", f.toFloat()).putBoolean("resetLearners", true).apply()
                getSharedPreferences("battery2", Context.MODE_PRIVATE).edit().remove("soc").apply()
                toast("Calibrated"); build()
            }
            .setNeutralButton("Reset to default") { _, _ ->
                BleManager.voltCal = 1.0; prefs().edit().remove("voltCal").apply(); toast("Calibration reset"); build() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun editKey() {
        val input = EditText(this).apply {
            hint = "Paste your TomTom API key"; setSingleLine(true); setText(prefs().getString("tomtom_key", ""))
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this).setTitle("TomTom API key")
            .setMessage("Free from my.tomtom.com (TomTom closed the old developer site). Sign up, find your API key, copy it here.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val k = Router.cleanApiKey(input.text.toString())
                prefs().edit().putString("tomtom_key", k).apply { if (k.isEmpty()) putBoolean("traffic", false) }.apply(); build()
            }
            .setNeutralButton("Remove") { _, _ -> prefs().edit().remove("tomtom_key").putBoolean("traffic", false).apply(); build() }
            .setNegativeButton("Cancel", null).show()
    }

    // ============================ building blocks ============================
    private var cur: LinearLayout? = null

    private fun section(t: String) {
        body.addView(TextView(this).apply {
            text = t; textSize = 14f; setTextColor(BLUE); setTypeface(null, Typeface.BOLD); setPadding(dp(8), dp(20), 0, dp(8))
        })
    }

    private fun card(fill: () -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(16).toFloat() }
        }
        cur = c; fill(); cur = null
        body.addView(c)
    }

    private fun line() { cur?.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(60) }) }

    private fun baseRow(icon: String, title: String, sub: String?, right: View?, onClick: (() -> Unit)?): TextView? {
        var subView: TextView? = null
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            minimumHeight = dp(60)
            if (onClick != null) {
                isClickable = true
                val tv = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { onClick() }
            }
        }
        r.addView(ImageView(this).apply { setImageDrawable(AppIcons.drawable(this@AppSettingsActivity, icon, 0xFF5F6368.toInt(), 24)) },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(3); rightMargin = dp(17) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(INK) })
        if (!sub.isNullOrEmpty()) {
            subView = TextView(this).apply { text = sub; textSize = 13f; setTextColor(MUTED); setPadding(0, dp(2), 0, 0) }
            col.addView(subView)
        }
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (right != null) r.addView(right)
        cur?.addView(r)
        return subView
    }

    private fun row(icon: String, title: String, sub: String?, onClick: () -> Unit): TextView? =
        baseRow(icon, title, sub, TextView(this).apply { text = "›"; textSize = 22f; setTextColor(MUTED); setPadding(dp(8), 0, 0, 0) }, onClick)

    private fun switchRow(icon: String, title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
        val sw = Switch(this).apply { isChecked = value; setOnCheckedChangeListener { _, on -> onChange(on) } }
        baseRow(icon, title, sub, sw) { sw.isChecked = !sw.isChecked }
    }

    private fun toggle(icon: String, title: String, sub: String?, key: String, def: Boolean) =
        switchRow(icon, title, sub, prefs().getBoolean(key, def)) { on ->
            if (key == "traffic" && on && (prefs().getString("tomtom_key", "") ?: "").isEmpty()) { editKey(); return@switchRow }
            prefs().edit().putBoolean(key, on).apply()
        }

    private fun choice(icon: String, title: String, key: String, opts: List<Pair<String, Int>>, def: Int, after: (() -> Unit)? = null) {
        val v = prefs().getInt(key, def)
        val label = opts.firstOrNull { it.second == v }?.first ?: opts[0].first
        row(icon, title, label) {
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(opts.map { it.first }.toTypedArray(), opts.indexOfFirst { it.second == v }) { d, i ->
                    prefs().edit().putInt(key, opts[i].second).apply(); after?.invoke(); d.dismiss(); build()
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun choiceStr(icon: String, title: String, key: String, opts: List<Pair<String, String>>, def: String) {
        val v = prefs().getString(key, def) ?: def
        val label = opts.firstOrNull { it.second == v }?.first ?: opts[0].first
        row(icon, title, label) {
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(opts.map { it.first }.toTypedArray(), opts.indexOfFirst { it.second == v }) { d, i ->
                    prefs().edit().putString(key, opts[i].second).apply(); d.dismiss(); build()
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun confirm(title: String, msg: String, btn: String = "Reset", ok: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton(btn) { _, _ -> ok() }.setNegativeButton("Cancel", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
