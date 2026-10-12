package app.cuentapepas.core.format

import app.cuentapepas.core.stats.Estimate
import app.cuentapepas.core.stats.Interval
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Pure Spanish formatting helpers for the UI. Output never depends on the device locale. */
object Fmt {
    const val NO_DATA = "sin dato"

    /** Number with [decimals] decimals, decimal comma and '.' as thousands separator (only when the integer part has 5+ digits). */
    fun num(v: Double, decimals: Int = 1): String {
        require(decimals >= 0) { "decimals must be >= 0" }
        if (!v.isFinite()) return NO_DATA
        val s = String.format(Locale.US, "%.${decimals}f", v)
        val neg = s.startsWith("-")
        val body = if (neg) s.substring(1) else s
        val dot = body.indexOf('.')
        val intPart = if (dot >= 0) body.substring(0, dot) else body
        val frac = if (dot >= 0) body.substring(dot + 1) else ""
        val grouped = if (intPart.length >= 5) intPart.reversed().chunked(3).joinToString(".").reversed() else intPart
        val out = if (frac.isEmpty()) grouped else "$grouped,$frac"
        // "-0,0" is shown as "0,0"
        val isZero = out.all { it == '0' || it == ',' || it == '.' }
        return if (neg && !isZero) "-$out" else out
    }

    fun orNoData(v: Double?, decimals: Int = 1): String = if (v == null) NO_DATA else num(v, decimals)

    fun intOrNoData(v: Int?): String = v?.toString() ?: NO_DATA

    /** A fraction (0..1) as a percentage: "12,5 %". */
    fun percent(fraction: Double, decimals: Int = 1): String =
        if (fraction.isFinite()) "${num(fraction * 100.0, decimals)} %" else NO_DATA

    fun percentOrNoData(fraction: Double?, decimals: Int = 1): String =
        if (fraction == null) NO_DATA else percent(fraction, decimals)

    private fun level(confidence: Double): String = "IC ${num(confidence * 100.0, 0)} %"

    /** "bajo–alto" without the confidence label. */
    fun range(ci: Interval, decimals: Int = 1): String = "${num(ci.low, decimals)}–${num(ci.high, decimals)}"

    /** "valor (IC 90 %: bajo–alto)". */
    fun estimate(e: Estimate, decimals: Int = 1, confidence: Double = 0.90): String =
        "${num(e.value, decimals)} (${level(confidence)}: ${range(e.ci, decimals)})"

    fun estimateOrNoData(e: Estimate?, decimals: Int = 1, confidence: Double = 0.90): String =
        if (e == null) NO_DATA else estimate(e, decimals, confidence)

    /** Share estimate as a percentage: "40,0 % (IC 90 %: 35,0–45,0 %)". */
    fun estimatePercent(e: Estimate, decimals: Int = 1, confidence: Double = 0.90): String =
        "${percent(e.value, decimals)} (${level(confidence)}: " +
            "${num(e.ci.low * 100.0, decimals)}–${num(e.ci.high * 100.0, decimals)} %)"

    /** Interval only, as "(IC 90 %: bajo–alto)" for figures whose point value is shown elsewhere. */
    fun interval(ci: Interval?, decimals: Int = 1, confidence: Double = 0.90): String =
        if (ci == null) NO_DATA else "(${level(confidence)}: ${range(ci, decimals)})"

    /** "X de N plantas (Y %)" or "Cobertura: sin dato" when the lot total is unknown/invalid. */
    fun coverage(visited: Int, total: Int?, coverage: Double?): String =
        if (total == null || coverage == null) "Cobertura: $NO_DATA"
        else "$visited de $total plantas (${percent(coverage, 0)})"

    /** "Calibrado (n=12)" or "Sin calibrar (n=4/10)". */
    fun calibrationStatus(n: Int, usable: Boolean, minSamples: Int = 10): String =
        if (usable) "Calibrado (n=$n)" else "Sin calibrar (n=$n/$minSamples)"

    /** "dd/MM/yyyy HH:mm" in [zone] (default: device zone). */
    fun dateTime(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).apply { timeZone = zone }.format(epochMs)

    /** "1 h 02 min 05 s" / "2 min 05 s" / "35 s". */
    fun duration(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return NO_DATA
        val total = Math.round(seconds)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "$h h ${"%02d".format(Locale.US, m)} min ${"%02d".format(Locale.US, s)} s"
            m > 0 -> "$m min ${"%02d".format(Locale.US, s)} s"
            else -> "$s s"
        }
    }
}
