import com.interop.ridepowerble.BatteryCore
import java.io.File
fun main(args: Array<String>) {
    val lines = File(args[0]).readLines()
    val h = lines[0].split(","); fun ix(n: String) = h.indexOf(n)
    val st = HashMap<String, Double>(); val core = BatteryCore(st); core.fixedSeries = 14; core.capAh = 30.0
    var prevT = ""; var n = 0; var lastT = -1
    println("time      V      thr  mph  | app-then  NEW   (rest V seen)")
    var minNew = 999.0; var maxNew = -1.0
    for (l in lines.drop(1)) {
        val c = l.split(","); if (c.size < h.size) continue
        val v = c[ix("battery_v")].toDoubleOrNull() ?: continue
        val thr = c[ix("throttle_v")].toDoubleOrNull() ?: continue
        val raw = c[(if (ix("motor_raw") >= 0) ix("motor_raw") else ix("motor_rpm"))].toDoubleOrNull() ?: continue
        val mph = if (raw <= 0 || raw >= 20000) 0.0 else 3820.0 / raw
        // log is ~1 line per second; the app sees ~1.4 samples per line, close enough
        val ws = if (ix("work_state") >= 0) c[ix("work_state")].toIntOrNull() ?: 0 else 0
        val gear = when (ws and 0x0F) { in 13..15 -> 5; in 10..12 -> 4; in 7..9 -> 3; in 4..6 -> 2; in 1..3 -> 1; else -> 0 }
        val lim = 45.0 * (if (gear in 1..5) 1.0 else 1.0)
        val tp = c[0].split(":").map { it.toInt() }; val tsec = tp[0] * 3600 + tp[1] * 60 + tp[2]
        val dts = if (lastT < 0) 0.7 else (tsec - lastT).toDouble().coerceIn(0.1, 3.0); lastT = tsec
        val est = core.update(v, thr, mph, (ws and 0x40) != 0, (ws and 0x80) != 0, lim, gear == 5, dts)
        val then = c[ix("battery_pct")]
        // SEGDBG: voltage-chart % vs counted charge, at each settled reading
        if (!core.holding && thr < 1.0 && mph < 25) {
            val ah = st["ahTotal"] ?: 0.0
            if (n % 60 == 0) println("  SEG ${c[0]} restV=${"%.2f".format(core.lastOcv)} chart=${"%.1f".format(BatteryCore.pctFromCell(core.lastOcv / 14))} countedAh=${"%.2f".format(ah)}")
        }
        if (n++ % 75 == 0) println("${c[0]}  ${"%5.2f".format(v)}  ${"%4.2f".format(thr)} ${"%4.1f".format(mph)} |  ${then.padStart(5)}   ${"%5.1f".format(est)}  ${if (core.holding) "hold" else "read"}  ${"%4.0f".format(core.watts)} W")
        if (n > 60 && est >= 0) { minNew = minOf(minNew, est); maxNew = maxOf(maxNew, est) }
    }
    println("R=${"%.3f".format(core.resistance)} pulls=${core.resistanceSamples} k=${"%.2f".format(core.coulombScale)} (${core.coulombSamples}) Ah counted=${"%.2f".format(st["ahTotal"] ?: 0.0)} Wh=${"%.0f".format(st["whTotal"] ?: 0.0)}"); println("NEW range over ride: ${"%.1f".format(maxNew)} -> ${"%.1f".format(minNew)}; sag samples ${core.sagSamples}, relax ${"%.2f".format(core.relaxRatio)} (${core.relaxSamples} stops)")
}
