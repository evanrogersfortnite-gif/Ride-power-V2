package com.interop.ridepowerble

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.*
import kotlin.math.cos

/**
 * Offline maps: download the map (streets, labels, icons) for an area around you so it still
 * shows with no signal. Map vector tiles stop at zoom 14 and are drawn sharper beyond that,
 * so zoom 6–14 covers everything. Routing and search still need a connection.
 */
object OfflineMaps {
    private val ui = Handler(Looper.getMainLooper())

    private fun mgr(ctx: Context): OfflineManager { MapLibre.getInstance(ctx); return OfflineManager.getInstance(ctx) }
    private fun nameOf(r: OfflineRegion) = try { JSONObject(String(r.metadata)).optString("name", "Area") } catch (e: Exception) { "Area" }

    /** Main dialog: downloaded areas (tap to delete) + download a new one. */
    fun open(act: Activity) {
        mgr(act).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                val list = regions?.toList() ?: emptyList()
                if (list.isEmpty()) { showDialog(act, list, emptyList()); return }
                val labels = arrayOfNulls<String>(list.size); var left = list.size
                list.forEachIndexed { i, r ->
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(st: OfflineRegionStatus?) {
                            val mb = (st?.completedResourceSize ?: 0L) / 1_048_576.0
                            val pct = if (st != null && st.requiredResourceCount > 0) (100 * st.completedResourceCount / st.requiredResourceCount).toInt() else 0
                            labels[i] = "${nameOf(r)}  ·  ${"%.0f".format(mb)} MB" + if (st?.isComplete == true) "" else "  ·  $pct% (tap to resume)"
                            if (--left == 0) showDialog(act, list, labels.map { it ?: "Area" })
                        }
                        override fun onError(error: String?) { labels[i] = nameOf(r); if (--left == 0) showDialog(act, list, labels.map { it ?: "Area" }) }
                    })
                }
            }
            override fun onError(error: String) { Toast.makeText(act, "Offline maps unavailable: $error", Toast.LENGTH_LONG).show() }
        })
    }

    private fun showDialog(act: Activity, regions: List<OfflineRegion>, labels: List<String>) {
        val b = AlertDialog.Builder(act).setTitle("Offline maps")
        if (regions.isEmpty()) b.setMessage("No areas downloaded yet.\n\nDownload the map around you so it keeps working with no signal. " +
                "Start your route while you still have signal: navigation then carries on offline.")
        else b.setItems(labels.toTypedArray()) { _, i -> manage(act, regions[i], labels[i]) }
        b.setPositiveButton("Download area around me") { _, _ -> pickRadius(act) }
        b.setNegativeButton("Close", null).show()
    }

    private fun manage(act: Activity, r: OfflineRegion, label: String) {
        AlertDialog.Builder(act).setTitle(nameOf(r)).setMessage(label)
            .setPositiveButton("Update / resume") { _, _ -> watch(act, r, nameOf(r)) }
            .setNeutralButton("Delete") { _, _ ->
                r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() { Toast.makeText(act, "Deleted", Toast.LENGTH_SHORT).show() }
                    override fun onError(error: String) { Toast.makeText(act, "Couldn't delete: $error", Toast.LENGTH_LONG).show() }
                })
            }
            .setNegativeButton("Close", null).show()
    }

    private fun pickRadius(act: Activity) {
        val km = intArrayOf(5, 10, 20, 40)
        val sizes = arrayOf("about 5 MB", "about 15 MB", "about 50 MB", "about 150 MB")
        AlertDialog.Builder(act).setTitle("How far around you?")
            .setItems(km.mapIndexed { i, k -> "$k km  (${sizes[i]})" }.toTypedArray()) { _, i -> download(act, km[i]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun download(act: Activity, radiusKm: Int) {
        val p = act.getSharedPreferences("map", Context.MODE_PRIVATE)
        val lat = p.getFloat("lat", 51.4545f).toDouble(); val lon = p.getFloat("lon", -2.5879f).toDouble()
        val dLat = radiusKm / 111.0; val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)))
        val bounds = LatLngBounds.Builder().include(LatLng(lat - dLat, lon - dLon)).include(LatLng(lat + dLat, lon + dLon)).build()
        val style = act.getSharedPreferences("app", Context.MODE_PRIVATE).getString("mapStyle", "liberty") ?: "liberty"
        val def = OfflineTilePyramidRegionDefinition("https://tiles.openfreemap.org/styles/$style", bounds, 6.0, 14.0,
            act.resources.displayMetrics.density, false)
        val name = "$radiusKm km around ${"%.3f".format(lat)}, ${"%.3f".format(lon)}"
        val meta = JSONObject().put("name", name).toString().toByteArray()
        mgr(act).createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(region: OfflineRegion) { watch(act, region, name) }
            override fun onError(error: String) { Toast.makeText(act, "Couldn't start the download: $error", Toast.LENGTH_LONG).show() }
        })
    }

    /** Starts/resumes a region download and shows progress (the download continues if the dialog is hidden). */
    private fun watch(act: Activity, region: OfflineRegion, name: String) {
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(act).apply { text = "Starting…"; setPadding(0, 0, 0, 16) }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 30, 60, 10); addView(label); addView(bar) }
        val dlg = AlertDialog.Builder(act).setTitle("Downloading map").setView(box)
            .setPositiveButton("Hide") { d, _ -> d.dismiss() }.show()
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(st: OfflineRegionStatus) {
                val pct = if (st.requiredResourceCount > 0) (100 * st.completedResourceCount / st.requiredResourceCount).toInt() else 0
                ui.post {
                    bar.progress = pct
                    label.text = "$name\n$pct%  ·  ${"%.1f".format(st.completedResourceSize / 1_048_576.0)} MB"
                }
                if (st.isComplete) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    ui.post { if (dlg.isShowing) dlg.dismiss(); Toast.makeText(act, "Offline map ready: $name", Toast.LENGTH_LONG).show() }
                }
            }
            override fun onError(error: OfflineRegionError) { ui.post { label.text = "Problem: ${error.message} — it will keep retrying" } }
            override fun mapboxTileCountLimitExceeded(limit: Long) { ui.post { label.text = "Too many tiles — choose a smaller area" } }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }
}
