package com.interop.ridepowerble

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records rides to CSV on the phone (GPS + bike data, about once a second) so they can be
 * exported from Settings and shared, e.g. to tune the battery and route estimates.
 * Nothing is uploaded automatically.
 */
class RideLogger(ctx: Context) {
    private val dir = File(ctx.filesDir, "rides").apply { mkdirs() }
    private var file: File? = null
    private var writer: FileWriter? = null
    private var lastLineAt = 0L
    private var lines = 0

    fun log(lat: Double?, lon: Double?, mph: Double?, acc: Float?, volts: Double?, throttle: Double?, rpm: Int?,
            pct: Double, holding: Boolean, mode: String, along: Double, workState: Int? = null, motorMph: Double? = null,
            ctrlTemp: Int? = null, motorTemp: Int? = null) {
        val now = System.currentTimeMillis()
        if (now - lastLineAt < 900) return
        try {
            if (writer == null || now - lastLineAt > 10 * 60_000) startFile(now)
            lastLineAt = now
            val f = { d: Double?, p: Int -> if (d == null) "" else String.format(Locale.US, "%.${p}f", d) }
            writer!!.append(listOf(
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now)), f(lat, 6), f(lon, 6), f(mph, 1),
                acc?.let { "%.0f".format(Locale.US, it) } ?: "", f(volts, 2), f(throttle, 2), rpm?.toString() ?: "",
                if (pct >= 0) f(pct, 1) else "", if (holding) "1" else "0", mode, f(along, 0),
                workState?.toString() ?: "", f(motorMph, 1), ctrlTemp?.toString() ?: "", motorTemp?.toString() ?: ""
            ).joinToString(",")).append('\n')
            if (++lines % 20 == 0) writer!!.flush()
        } catch (_: Exception) {}
    }

    private fun startFile(now: Long) {
        try { writer?.close() } catch (_: Exception) {}
        val name = "ride-" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(now)) + ".csv"
        file = File(dir, name)
        writer = FileWriter(file!!, true).apply {
            append("time,lat,lon,gps_mph,gps_acc_m,battery_v,throttle_v,motor_raw,battery_pct,pct_held,mode,route_m,work_state,motor_mph,ctrl_temp_c,motor_temp_c\n")
        }
        // keep the newest 20 rides
        dir.listFiles()?.sortedByDescending { it.name }?.drop(20)?.forEach { it.delete() }
    }

    fun close() { try { writer?.flush(); writer?.close() } catch (_: Exception) {}; writer = null }
    fun flush() { try { writer?.flush() } catch (_: Exception) {} }

    companion object {
        fun rides(ctx: Context): List<File> =
            File(ctx.filesDir, "rides").listFiles()?.filter { it.length() > 200 }?.sortedByDescending { it.name } ?: emptyList()

        /** Lets the rider pick a ride, saves it to Downloads/Amped and opens the share sheet. */
        fun export(act: Activity) {
            val list = rides(act)
            if (list.isEmpty()) { Toast.makeText(act, "No rides recorded yet", Toast.LENGTH_SHORT).show(); return }
            val labels = list.map { f ->
                val n = f.name.removePrefix("ride-").removeSuffix(".csv").replace('_', ' ')
                "$n  ·  ${maxOf(1, (f.length() / 1024).toInt())} KB"
            }.toTypedArray()
            AlertDialog.Builder(act).setTitle("Export a ride")
                .setItems(labels) { _, i -> save(act, list[i]) }
                .setNegativeButton("Cancel", null).show()
        }

        private fun save(act: Activity, f: File) {
            try {
                if (Build.VERSION.SDK_INT < 29) {
                    Toast.makeText(act, "Exporting needs Android 10 or newer", Toast.LENGTH_LONG).show(); return
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "Amped-" + f.name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/Amped")
                }
                val uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("couldn't create the file")
                act.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                Toast.makeText(act, "Saved to Downloads/Amped", Toast.LENGTH_SHORT).show()
                act.startActivity(Intent.createChooser(share, "Share ride log"))
            } catch (e: Exception) {
                Toast.makeText(act, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
