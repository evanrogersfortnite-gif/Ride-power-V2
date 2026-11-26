import com.interop.ridepowerble.BatteryCore
import kotlin.math.*
import java.util.Random

// invert the chart: % -> pack volts (14S)
fun voltsFor(pct: Double): Double {
    val c = BatteryCore.CURVE
    for (i in 0 until c.size - 1) { val (hi, ph) = c[i]; val (lo, pl) = c[i + 1]
        if (pct <= ph && pct >= pl) return 14 * (lo + (pct - pl) / (ph - pl) * (hi - lo)) }
    return 14 * c.last().first
}

fun ride(core: BatteryCore, startPct: Double, minutes: Int, seed: Long, label: String) {
    val rnd = Random(seed)
    var truePct = startPct
    var t = 0.0; var phaseLeft = 0.0; var on = false; var thr = 0.0; var rpm = 0.0
    var relax = 0.0          // slow sag still recovering (V)
    var stopLeft = 0.0
    var errSum = 0.0; var errMax = 0.0; var n = 0; var worstAt = 0.0
    while (t < minutes * 60) {
        if (phaseLeft <= 0) {
            if (stopLeft <= 0 && rnd.nextDouble() < 0.08) { stopLeft = 40 + rnd.nextDouble() * 60; on = false }
            else { on = !on; phaseLeft = if (on) 20 + rnd.nextDouble() * 150 else 3 + rnd.nextDouble() * 15
                thr = 0.4 + rnd.nextDouble() * 0.6 }
        }
        val stopped = stopLeft > 0
        if (stopped) { stopLeft -= 0.7; on = false; rpm = max(0.0, rpm - 60) } else phaseLeft -= 0.7
        rpm = if (on) min(450.0, rpm + 25) else if (stopped) rpm else max(150.0, rpm - 3)
        // energy: ~0.65 %/min at full throttle cruising (≈1.5 kWh pack, ~1 kW)
        if (on) truePct -= 0.7 / 60.0 * thr * 0.9 * 1.0
        val ocv = voltsFor(truePct)
        val fastSag = if (on) 5.0 * thr + 1.2 * rpm / 1000.0 else 0.0
        // slow part builds while on, decays (tau 20 s) when off
        relax = if (on) relax + (0.18 * fastSag - relax) * 0.03 else relax * exp(-0.7 / 20.0)
        val v = ocv - fastSag - relax + rnd.nextGaussian() * 0.08
        val thrV = 0.82 + (if (on) thr * 3.4 else 0.0) + rnd.nextGaussian() * 0.005
        val est = core.update(v, thrV, rpm.toInt())
        t += 0.7
        if (t > 20 && est >= 0) { val e = abs(est - truePct); errSum += e; if (e > errMax) { errMax = e; worstAt = t }; n++ }
        if ((t % 300) < 0.7) println("  t=${(t/60).roundToInt()}min true=${"%.1f".format(truePct)}% app=${"%.1f".format(est)}% (${if (core.holding) "load" else "rest"}) V=${"%.1f".format(v)}")
    }
    println("$label: mean error ${"%.1f".format(errSum / n)}%, worst ${"%.1f".format(errMax)}% at ${worstAt.roundToInt()}s, sag samples ${core.sagSamples}, relax ${"%.2f".format(core.relaxRatio)}, end true ${"%.1f".format(truePct)}")
}

fun main() {
    val st = HashMap<String, Double>()
    val core = BatteryCore(st); core.fixedSeries = 14
    ride(core, 60.0, 40, 1, "Ride 1 (new, nothing learned)")
    val core2 = BatteryCore(st); core2.fixedSeries = 14     // next ride: keeps learned state
    ride(core2, 45.0, 40, 2, "Ride 2 (learned)")
    val core3 = BatteryCore(st); core3.fixedSeries = 14
    ride(core3, 25.0, 30, 3, "Ride 3 (learned, low battery)")
    // different seeds, fresh learning, to check it's not luck
    for (seed in 10L..14L) { val s2 = HashMap<String, Double>(); val a = BatteryCore(s2); a.fixedSeries = 14
        ride(a, 80.0, 40, seed, "  fresh seed $seed"); val b = BatteryCore(s2); b.fixedSeries = 14; ride(b, 60.0, 40, seed + 100, "  learned seed $seed") }
}
fun main2() {}
