package com.interop.ridepowerble

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * In-app updates from the GitHub "latest release":
 * check -> "Update available" dialog -> download -> Android's install confirmation.
 * Uses the platform PackageInstaller API (no FileProvider / extra libraries).
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/evanrogersfortnite-gif/Ride-power-V2/releases/latest"
    private val exec = Executors.newSingleThreadExecutor()

    data class Release(val versionCode: Int, val name: String, val apkUrl: String, val sizeBytes: Long)

    fun currentVersionCode(ctx: Context): Long =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode } catch (e: Exception) { 0L }

    fun currentVersionName(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    /** Checks GitHub; [onResult] gets the newer release, or null if up to date / unreachable. */
    fun check(ctx: Context, onResult: (Release?, String?) -> Unit) {
        val app = ctx.applicationContext
        exec.execute {
            var err: String? = null
            val rel = try {
                val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "VoltRoute-updater")
                    connectTimeout = 8000; readTimeout = 10000
                }
                if (conn.responseCode != 200) { err = "Update server returned ${conn.responseCode}"; null }
                else {
                    val o = JSONObject(conn.inputStream.bufferedReader().readText())
                    val tag = o.optString("tag_name")                      // e.g. v1.0.17
                    val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
                    val assets = o.getJSONArray("assets")
                    var url = ""; var size = 0L
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); size = a.optLong("size") }
                    }
                    if (url.isEmpty()) null else Release(code, tag.removePrefix("v"), url, size)
                }
            } catch (e: Exception) { err = "Couldn't reach the update server"; null }
            val newer = rel?.takeIf { it.versionCode > currentVersionCode(app) }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(newer, err) }
        }
    }

    /** Shows "Update available" and runs the whole update if accepted. */
    fun prompt(act: Activity, r: Release) {
        AlertDialog.Builder(act)
            .setTitle("Update available")
            .setMessage("VoltRoute ${r.name} is ready (you have ${currentVersionName(act)}).\n\n" +
                    "Size: ${"%.0f".format(r.sizeBytes / 1_048_576.0)} MB")
            .setPositiveButton("Update") { _, _ -> install(act, r) }
            .setNegativeButton("Later", null)
            .show()
    }

    fun install(act: Activity, r: Release) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("Allow updates")
                .setMessage("To update itself, VoltRoute needs permission to install apps.\n\n" +
                        "On the next screen turn on “Allow from this source”, then come back and tap Update again.")
                .setPositiveButton("Open settings") { _, _ ->
                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(act).apply { text = "Downloading…"; setPadding(0, 0, 0, 16) }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(60, 30, 60, 10); addView(label); addView(bar)
        }
        val dlg = AlertDialog.Builder(act).setTitle("Updating VoltRoute").setView(box).setCancelable(false).show()
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        exec.execute {
            try {
                val pi = act.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(act.packageName)
                val sid = pi.createSession(params)
                pi.openSession(sid).use { s ->
                    val conn = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true; connectTimeout = 10000; readTimeout = 30000
                        setRequestProperty("User-Agent", "VoltRoute-updater")
                    }
                    val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else r.sizeBytes
                    conn.inputStream.use { inp ->
                        s.openWrite("voltroute.apk", 0, total).use { out ->
                            val buf = ByteArray(64 * 1024); var done = 0L; var lastPct = -1
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                if (pct != lastPct) { lastPct = pct; ui.post { bar.progress = pct; label.text = "Downloading… $pct%" } }
                            }
                            s.fsync(out)
                        }
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(act, sid, Intent(act, UpdateReceiver::class.java), flags)
                    ui.post { label.text = "Installing…" }
                    s.commit(pending.intentSender)
                }
                ui.post { dlg.dismiss() }
            } catch (e: Exception) {
                ui.post { dlg.dismiss(); Toast.makeText(act, "Update failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}

/** Receives installer status; launches Android's install confirmation when needed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(ctx, "Update not installed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
