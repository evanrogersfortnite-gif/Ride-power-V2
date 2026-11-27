package com.interop.ridepowerble

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What Amped has learned from your navigated trips: overall (are you quicker than the standard
 * estimate?) and per place, with each ride's planned vs actual time. Tap a place for its rides.
 */
class TripLearningActivity : android.app.Activity() {

    private val BG = 0xFFF1F3F4.toInt(); private val INK = 0xFF202124.toInt(); private val MUTED = 0xFF5F6368.toInt()
    private val BLUE = 0xFF1A73E8.toInt(); private val GREEN = 0xFF188038.toInt(); private val ORANGE = 0xFFE37400.toInt()
    private val LINE = 0xFFE8EAED.toInt(); private val SOFT = 0xFFE8F0FE.toInt()
    private lateinit var learner: TripLearner
    private lateinit var body: LinearLayout
    private var inDetail = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        learner = TripLearner(this)
        showList()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (inDetail) showList() else super.onBackPressed() }

    private fun screen(title: String, onBack: () -> Unit) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(10), dp(16), dp(10)); elevation = dp(2).toFloat()
            addView(ImageView(context).apply {
                setImageDrawable(AppIcons.drawable(this@TripLearningActivity, "arrow_back", INK, 24)); scaleType = ImageView.ScaleType.CENTER
                setOnClickListener { onBack() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(context).apply { text = title; textSize = 21f; setTextColor(INK); setTypeface(null, Typeface.BOLD); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END })
        })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(32)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    // ============================ overview + places ============================
    private fun showList() {
        inDetail = false
        screen("Trip learning") { finish() }
        val dests = learner.destinations()
        val total = learner.globalRides()
        val f = learner.globalFactor()

        val top = card()
        top.addView(iconLine("trending", "What Amped has learned", BLUE))
        if (total == 0) {
            top.addView(text("Nothing yet.\n\nStart navigation to a place and ride until it says “You have arrived”. Amped compares how long " +
                "you took with its estimate and adjusts future ETAs to your e-bike.", 15f, INK, top = 10))
        } else {
            val mph = learner.globalMph()
            if (mph != null) {
                top.addView(text("You average ${"%.1f".format(mph)} mph door to door", 21f, INK, bold = true, top = 10))
                top.addView(text("Learned from $total ride${s(total)}, stops and traffic lights included.", 14f, MUTED, top = 6))
                top.addView(text("ETAs are now simply distance ÷ your speed — e.g. 5 miles ≈ ${fmt((5 / mph * 3600).roundToInt())}. " +
                        "Places you've ridden to use their own speed.", 14f, INK, top = 10))
            } else {
                top.addView(text(speedSentence(f), 20f, INK, bold = true, top = 10))
                top.addView(text("Learned from $total ride${s(total)}. Your next arrival switches this to your own average speed.", 14f, MUTED, top = 6))
                top.addView(meter(f))
            }
        }
        body.addView(top)

        if (dests.isNotEmpty()) {
            body.addView(section("Places you've ridden to"))
            val dayFmt = SimpleDateFormat("EEE d MMM", Locale.UK)
            for (d in dests) {
                val c = card().apply { isClickable = true; setOnClickListener { showPlace(d) } }
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(View(this).apply { background = iconBg("place", BLUE) }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(14) })
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(text(d.name.ifEmpty { "A place you rode to" }, 16f, INK, bold = true))
                val last = learner.history(d.key).firstOrNull()
                col.addView(text(
                    if (last != null) "Usually ${fmt(last.actualSec)}" + (d.mph?.let { " · ${"%.1f".format(it)} mph average" } ?: "")
                    else "Saved before ride details were kept", 14f, INK, top = 3))
                col.addView(text("${d.rides} ride${s(d.rides)}" + (if (d.lastAt > 0) " · last ${dayFmt.format(Date(d.lastAt))}" else "") +
                        (if (d.mph == null) " · " + shortPct(d.factor) else ""), 13f, MUTED, top = 3))
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(ImageView(this).apply { setImageDrawable(AppIcons.drawable(this@TripLearningActivity, "arrow_back", MUTED, 18)); rotation = 180f },
                    LinearLayout.LayoutParams(dp(20), dp(20)))
                c.addView(row)
                body.addView(c)
            }
        }

        if (total > 0) body.addView(TextView(this).apply {
            text = "Reset all trip learning"; textSize = 15f; setTextColor(0xFFD93025.toInt()); setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(0, dp(22), 0, dp(8))
            setOnClickListener {
                AlertDialog.Builder(this@TripLearningActivity).setTitle("Reset trip learning?")
                    .setMessage("Amped forgets how long your trips take. ETAs go back to the standard estimate.")
                    .setPositiveButton("Reset") { _, _ -> learner.clear(); flagReload(); showList() }
                    .setNegativeButton("Cancel", null).show()
            }
        })
    }

    // ============================ one place ============================
    private fun showPlace(d: TripLearner.Dest) {
        inDetail = true
        screen(d.name.ifEmpty { "A place you rode to" }) { showList() }
        val rides = learner.history(d.key)

        val sum = card()
        sum.addView(iconLine("trending", "What it learned here", BLUE))
        val last = rides.firstOrNull()
        if (d.mph != null) {
            sum.addView(text("You average ${"%.1f".format(d.mph)} mph to here", 20f, INK, bold = true, top = 10))
            if (d.meters > 0) sum.addView(text("Next time: ${miles(d.meters)} at ${"%.1f".format(d.mph)} mph ≈ " +
                    "${fmt((d.meters / 1609.34 / d.mph * 3600).roundToInt())}", 15f, INK, top = 8))
            if (last != null && last.plannedSec > 0) sum.addView(text("A standard bike estimate would say ${fmt(last.plannedSec)}.", 13f, MUTED, top = 6))
        } else {
            sum.addView(text(speedSentence(d.factor, here = true), 19f, INK, bold = true, top = 10))
            if (last != null) sum.addView(text("Next time the ETA here will be about ${fmt((last.plannedSec * d.factor).roundToInt())} " +
                    "instead of the standard ${fmt(last.plannedSec)}.", 15f, INK, top = 8))
            if (d.meters > 0) sum.addView(text("Your usual route: ${miles(d.meters)}", 14f, MUTED, top = 6))
            sum.addView(meter(d.factor))
        }
        body.addView(sum)

        if (rides.isEmpty()) {
            body.addView(card().apply {
                addView(text("This ride was saved before Amped kept the details of each ride, so only the result above is known. " +
                        "Your next ride here will show up in full.", 14f, MUTED))
            })
        } else {
            body.addView(section("Your rides here"))
            val fmtDay = SimpleDateFormat("EEE d MMM · HH:mm", Locale.UK)
            for (r in rides) {
                val c = card()
                c.addView(text(fmtDay.format(Date(r.at)), 15f, INK, bold = true))
                c.addView(statRow(listOf(fmt(r.actualSec) to "you took", (r.mph?.let { "%.1f".format(it) } ?: "–") to "mph door to door",
                    fmt(r.plannedSec) to "bike estimate"), listOf(INK, BLUE, MUTED)))
                val bits = ArrayList<String>()
                if (r.riddenM > 0) {
                    bits += "Rode ${miles(r.riddenM)}" + (if (abs(r.riddenM - r.plannedM) > r.plannedM * 0.05) " (planned ${miles(r.plannedM)})" else "")
                } else bits += "Planned ${miles(r.plannedM)}"
                r.battUsed?.let { bits += "${it.roundToInt()}% battery" }
                c.addView(text(bits.joinToString("  ·  "), 13f, MUTED, top = 8))
                body.addView(c)
            }
        }

        body.addView(TextView(this).apply {
            text = "Forget this place"; textSize = 15f; setTextColor(0xFFD93025.toInt()); setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(0, dp(22), 0, dp(8))
            setOnClickListener {
                AlertDialog.Builder(this@TripLearningActivity).setTitle("Forget ${d.name.ifEmpty { "this place" }}?")
                    .setMessage("Its rides are removed and the ETA here goes back to your overall figure.")
                    .setPositiveButton("Forget") { _, _ -> learner.forget(d.key); flagReload(); showList() }
                    .setNegativeButton("Cancel", null).show()
            }
        })
    }

    // ============================ wording ============================
    /** f = your time ÷ the standard estimate. */
    private fun speedSentence(f: Double, here: Boolean = false): String {
        val pct = ((1 - f) * 100).roundToInt()
        val where = if (here) " here" else ""
        return when {
            pct >= 3 -> "You get there $pct% quicker than the standard estimate$where"
            pct <= -3 -> "You take ${-pct}% longer than the standard estimate$where (stops, traffic, hills)"
            else -> "Your times match the standard estimate$where"
        }
    }
    private fun shortPct(f: Double): String { val p = ((1 - f) * 100).roundToInt()
        return when { p >= 1 -> "$p% quicker"; p <= -1 -> "${-p}% slower"; else -> "on time" } }
    private fun colorFor(f: Double) = if (f <= 0.99) GREEN else if (f >= 1.01) ORANGE else MUTED

    /** A simple bar: standard estimate vs you. */
    private fun meter(f: Double) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, 0)
        val maxF = maxOf(1.0, f)
        fun bar(label: String, frac: Double, color: Int) = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3))
            addView(TextView(context).apply { text = label; textSize = 12f; setTextColor(MUTED) }, LinearLayout.LayoutParams(dp(78), -2))
            val track = FrameLayout(context)
            track.addView(View(context).apply { background = GradientDrawable().apply { setColor(color); cornerRadius = dp(5).toFloat() } },
                FrameLayout.LayoutParams(0, dp(10)))
            addView(track, LinearLayout.LayoutParams(0, dp(10), 1f))
            track.post { (track.getChildAt(0).layoutParams).width = (track.width * frac / maxF).toInt(); track.getChildAt(0).requestLayout() }
        }
        addView(bar("Estimate", 1.0, 0xFFBDC1C6.toInt()))
        addView(bar("You", f, colorFor(f).takeIf { it != MUTED } ?: BLUE))
    }

    // ============================ helpers ============================
    private fun fmt(sec: Int): String { val m = (sec / 60.0).roundToInt().coerceAtLeast(1)
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min" }
    private fun miles(m: Int) = "%.1f mi".format(m / 1609.34)
    private fun s(n: Int) = if (n == 1) "" else "s"
    private fun flagReload() = getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("reloadTrips", true).apply()

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }
    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(BLUE); setTypeface(null, Typeface.BOLD); setPadding(dp(6), dp(20), 0, 0)
    }
    private fun text(t: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(0, dp(top), 0, 0)
    }
    private fun iconLine(icon: String, t: String, color: Int) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(color); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL
        setCompoundDrawablesRelative(AppIcons.drawable(this@TripLearningActivity, icon, color, 18), null, null, null); compoundDrawablePadding = dp(6)
    }
    private fun iconBg(icon: String, color: Int): android.graphics.drawable.Drawable {
        val ld = android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply { setColor(SOFT); cornerRadius = dp(21).toFloat() },
            AppIcons.drawable(this, icon, color, 22)))
        ld.setLayerInset(1, dp(10), dp(10), dp(10), dp(10)); return ld
    }
    private fun statRow(items: List<Pair<String, String>>, colors: List<Int>) = LinearLayout(this).apply {
        setPadding(0, dp(10), 0, 0)
        items.forEachIndexed { i, (v, label) -> addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = v; textSize = 19f; setTextColor(colors[i]); setTypeface(null, Typeface.BOLD) })
            addView(TextView(context).apply { text = label; textSize = 12f; setTextColor(MUTED) })
        }, LinearLayout.LayoutParams(0, -2, 1f)) }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
