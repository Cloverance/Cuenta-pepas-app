package app.cuentapepas.core.format

import app.cuentapepas.core.stats.Estimate
import app.cuentapepas.core.stats.Interval
import java.util.TimeZone
import org.junit.Test
import org.junit.Assert.assertEquals

class FmtTest {
    @Test fun numbersUseDecimalComma() {
        assertEquals("3,1", Fmt.num(3.14159))
        assertEquals("3,14", Fmt.num(3.14159, 2))
        assertEquals("12", Fmt.num(12.4, 0))
        assertEquals("1234,5", Fmt.num(1234.5))
        assertEquals("12.345,7", Fmt.num(12345.67))
        assertEquals("-2,5", Fmt.num(-2.5))
        assertEquals("0,0", Fmt.num(-0.04))
    }

    @Test fun nonFiniteIsNoData() {
        assertEquals("sin dato", Fmt.num(Double.NaN))
        assertEquals("sin dato", Fmt.orNoData(null))
        assertEquals("sin dato", Fmt.intOrNoData(null))
        assertEquals("0,0", Fmt.orNoData(0.0))
    }

    @Test fun estimateAlwaysCarriesItsInterval() {
        val e = Estimate(120.0, Interval(100.0, 140.5))
        assertEquals("120,0 (IC 90 %: 100,0–140,5)", Fmt.estimate(e))
        assertEquals("120 (IC 90 %: 100–141)", Fmt.estimate(e, 0))
        assertEquals("sin dato", Fmt.estimateOrNoData(null))
    }

    @Test fun percentEstimate() {
        val e = Estimate(0.4, Interval(0.35, 0.45))
        assertEquals("40,0 % (IC 90 %: 35,0–45,0 %)", Fmt.estimatePercent(e))
        assertEquals("12,5 %", Fmt.percent(0.125))
    }

    @Test fun coverage() {
        assertEquals("30 de 120 plantas (25 %)", Fmt.coverage(30, 120, 0.25))
        assertEquals("Cobertura: sin dato", Fmt.coverage(30, null, null))
    }

    @Test fun calibrationStatus() {
        assertEquals("Calibrado (n=12)", Fmt.calibrationStatus(12, true))
        assertEquals("Sin calibrar (n=4/10)", Fmt.calibrationStatus(4, false))
    }

    @Test fun dateAndDuration() {
        assertEquals("11/10/2026 00:00", Fmt.dateTime(1_791_676_800_000L, TimeZone.getTimeZone("UTC")))
        assertEquals("35 s", Fmt.duration(35.0))
        assertEquals("2 min 05 s", Fmt.duration(125.0))
        assertEquals("1 h 02 min 05 s", Fmt.duration(3725.0))
        assertEquals("sin dato", Fmt.duration(Double.NaN))
    }
}
