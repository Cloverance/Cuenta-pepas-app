package app.cuentapepas.core.quality

import org.junit.Assert.assertEquals
import org.junit.Test

class ShiftTest {

    @Test
    fun zeroIsTheOrigin() {
        assertEquals(0f, Shift.ZERO.dx, 0f)
        assertEquals(0f, Shift.ZERO.dy, 0f)
        assertEquals(Shift(0f, 0f), Shift.ZERO)
    }

    @Test
    fun scaledMultipliesEachAxisIndependently() {
        val s = Shift(3f, -2f).scaled(8f, 4f)
        assertEquals(24f, s.dx, 0f)
        assertEquals(-8f, s.dy, 0f)
    }

    @Test
    fun scalesFromTheDownscaledImageToTheSourceFrame() {
        val s = Shift(-7f, 5f).scaled(1280f / 160f, 720f / 90f)
        assertEquals(Shift(-56f, 40f), s)
    }
}
