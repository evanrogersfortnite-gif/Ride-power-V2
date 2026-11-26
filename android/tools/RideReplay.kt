import com.interop.ridepowerble.BatteryCore
import java.io.File
fun main(args: Array<String>) {
    val lines = File(args[0]).readLines()
    val h = lines[0].split(","); fun ix(n: String) = h.indexOf(n)
    val st = HashMap<String, Double>(); val core = BatteryCore(st); core.fixedSeries = 14; core.capAh = 30.0
    var prevT = ""; var n = 0
    println("time      V      thr  mph  | app-then  NEW   (rest V seen)")
    var minNew = 999.0; var maxNew = -1.0
    for (l in lines.drop(1)) {
        val c = l.split(","); if (c.size < h.size) continue
        val v = c[ix("battery_v")].toDoubleOrNull() ?: continue
        val thr = c[ix("throttle_v")].toDoubleOrNull() ?: continue
        val raw = c[ix("motor_rpm")].toDoubleOrNull() ?: continue
        val mph = if (raw <= 0 || raw >= 20000) 0.0 else 3820.0 / raw
        // log is ~1 line per second; the app sees ~1.4 samples per line, close enough
        val est = core.update(v, thr, mph)
        val then = c[ix("battery_pct")]
        if (n++ % 75 == 0) println("${c[0]}  ${"%5.2f".format(v)}  ${"%4.2f".format(thr)} ${"%4.1f".format(mph)} |  ${then.padStart(5)}   ${"%5.1f".format(est)}  ${if (core.holding) "hold" else "read"}  ${"%4.0f".format(core.watts)} W")
        if (n > 60 && est >= 0) { minNew = minOf(minNew, est); maxNew = maxOf(maxNew, est) }
    }
    println("R=${"%.3f".format(core.resistance)} k=${"%.2f".format(core.coulombScale)} (${core.coulombSamples})"); println("NEW range over ride: ${"%.1f".format(maxNew)} -> ${"%.1f".format(minNew)}; sag samples ${core.sagSamples}, relax ${"%.2f".format(core.relaxRatio)} (${core.relaxSamples} stops)")
}
