package com.interop.ridepowerble

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.math.max
import kotlin.math.min

class SettingsActivity : android.app.Activity(), BleManager.Listener {

    private val BG = 0xFFEDF1F5.toInt(); private val CARD = 0xFFFFFFFF.toInt()
    private val TEAL = 0xFF14B8A6.toInt(); private val TEALD = 0xFF0E8C7E.toInt()
    private val INK = 0xFF1B2430.toInt(); private val MUTED = 0xFF707A88.toInt()
    private val LINE = 0xFFE3E8EE.toInt(); private val REDG = 0xFFE5484D.toInt(); private val OKG = 0xFF12A594.toInt()

    private data class Spec(
        val key: String, val label: String, val unit: String, val min: Int, val max: Int,
        val enum: List<String>? = null, val enumBase: Int = 0
    ) { val isEnum get() = enum != null }

    private val SPECS = listOf(
        Spec("limitCurrentVoltage", "Limit current voltage", "V", 0, 10),
        Spec("dcCurrent", "DC current", "%", 50, 100),
        Spec("limitMode", "Limit mode", "", 0, 3, listOf("No Limit", "Limited 1", "Limited 2", "Limited 3"), 0),
        Spec("accelerateStrength", "Accelerate strength", "", 1, 5, listOf("Level 1", "Level 2", "Level 3", "Level 4", "Level 5"), 1),
        Spec("pas1Speed", "PAS1 speed", "%", 0, 100),
        Spec("pas2Speed", "PAS2 speed", "%", 0, 100),
        Spec("pas3Speed", "PAS3 speed", "%", 0, 100),
        Spec("pas4Speed", "PAS4 speed", "%", 0, 100),
        Spec("pas5Speed", "PAS5 speed", "%", 0, 100),
        Spec("regenBraking", "Regen braking", "", 0, 3, listOf("Off", "Level 1", "Level 2", "Level 3"), 0),
        Spec("phaseCurrent", "Phase current", "%", 50, 100),
        Spec("overVoltage", "Over voltage", "V", 10, 150),
        Spec("underVoltage", "Under voltage", "V", 10, 150),
        Spec("boost", "Boost", "", 0, 1, listOf("Off", "On"), 0),
        Spec("stopOutputTemp", "Stop-output temp", "℃", 50, 200),
        Spec("recoverOutputTemp", "Recover-output temp", "℃", 50, 200),
        Spec("currentLimitingTemp", "Current-limiting temp", "℃", 50, 200),
        Spec("rotorPolesPair", "Rotor pole pairs", "", 1, 100)
    )

    private val steppers = HashMap<String, EditText>()
    private val spinners = HashMap<String, Spinner>()
    private lateinit var statusChip: TextView
    private lateinit var logText: TextView
    private lateinit var tvTemps: TextView
    private lateinit var diagBox: LinearLayout
    private lateinit var readBtn: Button
    private lateinit var writeBtn: Button
    private lateinit var learnBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BleManager.init(this)
        window.statusBarColor = TEAL

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })

        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(TEAL); setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(context).apply {
                text = "←  Bike settings"; setTextColor(Color.WHITE); textSize = 18f; setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { finish() }
            })
            statusChip = TextView(context).apply {
                text = BleManager.status; setTextColor(TEAL); textSize = 12f; setTypeface(null, Typeface.BOLD)
                setPadding(dp(12), dp(6), dp(12), dp(6)); background = rounded(Color.WHITE, dp(999).toFloat())
            }
            addView(statusChip)
        })

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(28)) }
        root.addView(body)

        val sc = cardColumn()
        sc.addView(TextView(this).apply {
            text = "Read first — the controls then show your controller's current values. Ranges match the " +
                    "official app, so unsafe values can't be entered. Reading never changes anything; Download confirms each change."
            textSize = 12f; setTextColor(REDG); setPadding(0, 0, 0, dp(12))
        })
        SPECS.forEachIndexed { i, s -> sc.addView(buildRow(s)); if (i < SPECS.size - 1) sc.addView(divider()) }
        sc.addView(LinearLayout(this).apply {
            setPadding(0, dp(12), 0, 0)
            readBtn = ghostBtn("Read") { BleManager.read() }
            writeBtn = primaryBtn("Download") { writeParams() }
            addView(readBtn, lpw(0, 1f).apply { rightMargin = dp(8) })
            addView(writeBtn, lpw(0, 1f))
        })
        body.addView(sc)

        body.addView(sectionTitle("Self-learning"))
        val lc = cardColumn()
        lc.addView(TextView(this).apply {
            text = "Spins the motor to learn hall/phase. Lift the rear wheel off the ground first."
            textSize = 12f; setTextColor(MUTED); setPadding(0, 0, 0, dp(10))
        })
        learnBtn = warnBtn("Start self-learning") { confirmLearn() }
        lc.addView(learnBtn, lpw(ViewGroup.LayoutParams.MATCH_PARENT, 0f))
        body.addView(lc)

        body.addView(ghostBtn("Show diagnostics ▾") { toggleDiag() }, lpw(ViewGroup.LayoutParams.MATCH_PARENT, 0f).apply { topMargin = dp(14) })
        diagBox = cardColumn().also { it.visibility = View.GONE }
        tvTemps = TextView(this).apply { textSize = 13f; setTextColor(MUTED); text = "Controller temp: –   Motor temp: –" }
        diagBox.addView(tvTemps); diagBox.addView(divider())
        logText = TextView(this).apply {
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(INK)
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = rounded(0xFFF2F4F7.toInt(), dp(10).toFloat())
        }
        diagBox.addView(logText)
        diagBox.addView(LinearLayout(this).apply {
            setPadding(0, dp(10), 0, 0)
            addView(ghostBtn("Copy log") { copyLog() }, lpw(0, 1f).apply { rightMargin = dp(8) })
            addView(ghostBtn("Clear") { logText.text = "" }, lpw(0, 1f))
        })
        body.addView(diagBox)
        body.addView(ghostBtn("Check for updates") {
            toast("Checking for updates…")
            Updater.check(this) { r, err ->
                when {
                    r != null -> Updater.prompt(this, r)
                    err != null -> toast(err)
                    else -> toast("You're on the latest version")
                }
            }
        }, lpw(ViewGroup.LayoutParams.MATCH_PARENT, 0f).apply { topMargin = dp(18) })
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        body.addView(TextView(this).apply {
            text = "VoltRoute $ver"; textSize = 12f; setTextColor(MUTED); gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }, lpw(ViewGroup.LayoutParams.MATCH_PARENT, 0f))

        setControlsEnabled(BleManager.connected)
    }

    override fun onResume() { super.onResume(); BleManager.pollMs = 700; BleManager.addListener(this) }
    override fun onPause() { super.onPause(); BleManager.removeListener(this) }

    // ---- BleManager.Listener ----
    override fun onStatus(status: String, connected: Boolean) = runOnUiThread {
        statusChip.text = status
        statusChip.setTextColor(if (connected) OKG else MUTED)
        readBtn.isEnabled = connected; writeBtn.isEnabled = connected; learnBtn.isEnabled = connected
        if (!connected) setControlsEnabled(false) else setControlsEnabled(true)
    }
    override fun onParams(params: ByteArray) = runOnUiThread {
        SPECS.forEachIndexed { i, s ->
            val v = params[i + 1].toInt() and 0xff
            if (s.isEnum) spinners[s.key]?.setSelection(clamp(v - s.enumBase, 0, s.enum!!.size - 1))
            else steppers[s.key]?.setText(clamp(v, s.min, s.max).toString())
        }
        setControlsEnabled(true)
    }
    override fun onRealtime(d: BleManager.Realtime) = runOnUiThread {
        tvTemps.text = "Controller temp: ${d.ctrlTemp} ℃    Motor temp: ${d.motorTemp} ℃"
    }
    override fun onLog(line: String) = runOnUiThread {
        val lines = (logText.text.toString() + line + "\n").split("\n")
        logText.text = lines.takeLast(140).joinToString("\n")
    }

    // ---- actions ----
    private fun writeParams() {
        val base = BleManager.lastParams ?: run {
            toast("Loading current settings… tap Download again in a moment"); BleManager.read(); return
        }
        val payload = IntArray(18); val changes = ArrayList<String>()
        SPECS.forEachIndexed { i, s ->
            val old = base[i + 1].toInt() and 0xff
            val nv = currentValue(s); payload[i] = nv
            if (nv != old) {
                val fmt: (Int) -> String = { x -> if (s.isEnum) (s.enum!!.getOrNull(x - s.enumBase) ?: x.toString()) else "$x${if (s.unit.isNotEmpty()) " ${s.unit}" else ""}" }
                changes.add("${s.label}: ${fmt(old)} → ${fmt(nv)}")
            }
        }
        if (changes.isEmpty()) { toast("No changes to download"); return }
        AlertDialog.Builder(this).setTitle("Download these changes to the bike?")
            .setMessage(changes.joinToString("\n") + "\n\nEverything else stays exactly as read from the bike.")
            .setPositiveButton("Download") { _, _ -> BleManager.writeParams(payload) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmLearn() {
        AlertDialog.Builder(this).setTitle("Start self-learning?")
            .setMessage("The motor will spin. Lift the rear wheel off the ground and keep clear.")
            .setPositiveButton("Start") { _, _ -> BleManager.selfLearn() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun currentValue(s: Spec): Int =
        if (s.isEnum) s.enumBase + (spinners[s.key]?.selectedItemPosition ?: 0)
        else clamp(cur(steppers[s.key]!!), s.min, s.max)

    // ---- UI ----
    private fun buildRow(s: Spec): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val lbl = if (s.isEnum) s.label else "${s.label}${if (s.unit.isNotEmpty()) " (${s.unit})" else ""}  ·  ${s.min}–${s.max}"
        row.addView(TextView(this).apply {
            text = lbl; textSize = 14f; setTextColor(INK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (s.isEnum) {
            val sp = Spinner(this).apply {
                adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, s.enum!!)
                isEnabled = false
            }
            spinners[s.key] = sp; row.addView(sp)
        } else {
            val et = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER; setText(""); hint = "—"; isEnabled = false
                gravity = Gravity.CENTER; textSize = 15f; setTextColor(INK)
                background = rounded(CARD, dp(9).toFloat(), dp(1), LINE)
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(42))
            }
            val minus = stepBtn("−") { et.setText(clamp(cur(et) - 1, s.min, s.max).toString()) }
            val plus = stepBtn("+") { et.setText(clamp(cur(et) + 1, s.min, s.max).toString()) }
            steppers[s.key] = et
            row.addView(minus, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(6) })
            row.addView(et)
            row.addView(plus, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(6) })
        }
        return row
    }

    private fun setControlsEnabled(on: Boolean) {
        for (et in steppers.values) et.isEnabled = on
        for (sp in spinners.values) sp.isEnabled = on
    }
    private fun toggleDiag() { diagBox.visibility = if (diagBox.visibility == View.GONE) View.VISIBLE else View.GONE }
    private fun copyLog() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("RidePower log", logText.text))
        toast("Log copied to clipboard")
    }

    private fun cur(et: EditText) = et.text?.toString()?.trim()?.toIntOrNull() ?: 0
    private fun clamp(v: Int, lo: Int, hi: Int) = max(lo, min(hi, v))
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable {
        val g = GradientDrawable(); g.setColor(color); g.cornerRadius = radius
        if (stroke > 0) g.setStroke(stroke, strokeColor); return g
    }
    private fun lpw(w: Int, weight: Float): LinearLayout.LayoutParams {
        val width = if (w == ViewGroup.LayoutParams.MATCH_PARENT) ViewGroup.LayoutParams.MATCH_PARENT else 0
        return LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    }
    private fun sectionTitle(s: String) = TextView(this).apply {
        text = s; textSize = 13f; setTextColor(MUTED); setTypeface(null, Typeface.BOLD); setPadding(dp(4), dp(18), 0, dp(8))
    }
    private fun cardColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(CARD, dp(16).toFloat(), dp(1), LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }
    private fun divider() = View(this).apply {
        setBackgroundColor(LINE); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }
    private fun primaryBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); setAllCaps(false); textSize = 15f; setTypeface(null, Typeface.BOLD)
        background = rounded(TEAL, dp(12).toFloat()); setOnClickListener { onClick() }
    }
    private fun ghostBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setTextColor(TEALD); setAllCaps(false); textSize = 15f
        background = rounded(CARD, dp(12).toFloat(), dp(1), TEAL); setOnClickListener { onClick() }
    }
    private fun warnBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); setAllCaps(false); textSize = 15f; setTypeface(null, Typeface.BOLD)
        background = rounded(0xFFF59E0B.toInt(), dp(12).toFloat()); setOnClickListener { onClick() }
    }
    private fun stepBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 18f; setAllCaps(false); setTypeface(null, Typeface.BOLD)
        background = rounded(TEAL, dp(10).toFloat()); setOnClickListener { onClick() }
    }
}
