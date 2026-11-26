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
                    setRequestProperty("User-Agent", "Amped-updater")
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
            .setMessage("Amped ${r.name} is ready (you have ${currentVersionName(act)}).\n\n" +
                    "Size: ${"%.0f".format(r.sizeBytes / 1_048_576.0)} MB")
            .setPositiveButton("Update") { _, _ -> install(act, r) }
            .setNegativeButton("Later", null)
            .show()
    }

    fun install(act: Activity, r: Release) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("Allow updates")
                .setMessage("To update itself, Amped needs permission to install apps.\n\n" +
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
        val dlg = AlertDialog.Builder(act).setTitle("Updating Amped").setView(box).setCancelable(false).show()
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        exec.execute {
            try {
                // 1) download to a local file, resuming if the connection drops
                val file = java.io.File(act.cacheDir, "update-${r.versionCode}.apk")
                var total = r.sizeBytes
                var attempt = 0
                while (true) {
                    val have = if (file.exists()) file.length() else 0L
                    if (total > 0 && have >= total) break
                    try {
                        val conn = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = true; connectTimeout = 10000; readTimeout = 20000
                            setRequestProperty("User-Agent", "Amped-updater")
                            if (have > 0) setRequestProperty("Range", "bytes=$have-")
                        }
                        val code = conn.responseCode
                        if (code == 404) throw java.io.IOException("This version was replaced by a newer one — tap Check for updates again")
                        val resumed = code == 206
                        if (!resumed && have > 0) file.delete()
                        if (total <= 0) total = conn.contentLengthLong.let { if (resumed) it + have else it }
                        java.io.FileOutputStream(file, resumed).use { out ->
                            conn.inputStream.use { inp ->
                                val buf = ByteArray(128 * 1024); var done = if (resumed) have else 0L; var lastPct = -1
                                val t0 = System.currentTimeMillis(); val start = done
                                while (true) {
                                    val n = inp.read(buf); if (n < 0) break
                                    out.write(buf, 0, n); done += n
                                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        val secs = (System.currentTimeMillis() - t0) / 1000.0
                                        val mbs = if (secs > 0.5) (done - start) / 1_048_576.0 / secs else 0.0
                                        val txt = "Downloading… $pct%  (${"%.1f".format(done / 1_048_576.0)} of ${"%.1f".format(total / 1_048_576.0)} MB" +
                                                (if (mbs > 0) ", ${"%.1f".format(mbs)} MB/s)" else ")")
                                        ui.post { bar.progress = pct; label.text = txt }
                                    }
                                }
                            }
                        }
                        if (total <= 0 || file.length() >= total) break
                        throw java.io.IOException("connection dropped")
                    } catch (e: java.io.IOException) {
                        if (e.message?.startsWith("This version") == true || ++attempt > 6) throw e
                        ui.post { label.text = "Connection dropped — resuming…" }
                        Thread.sleep(1500L * attempt)
                    }
                }

                // 2) hand it to Android's installer (quick: it's already on the phone)
                ui.post { label.text = "Installing…"; bar.isIndeterminate = true }
                val pi = act.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(act.packageName)
                params.setSize(file.length())
                // Android 12+: once Amped installed itself, later updates need no extra confirmation
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val sid = pi.createSession(params)
                pi.openSession(sid).use { s ->
                    s.openWrite("voltroute.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out, 256 * 1024) }
                        s.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(act, sid, Intent(act, UpdateReceiver::class.java), flags)
                    s.commit(pending.intentSender)
                }
                file.delete()
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
