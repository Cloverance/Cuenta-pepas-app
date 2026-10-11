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

    @Test
    fun rotatedClockwiseTurnsTheMotionVector() {
        val s = Shift(3f, 1f)
        assertEquals(Shift(3f, 1f), s.rotatedClockwise(0))
        assertEquals(Shift(-1f, 3f), s.rotatedClockwise(90))
        assertEquals(Shift(-3f, -1f), s.rotatedClockwise(180))
        assertEquals(Shift(1f, -3f), s.rotatedClockwise(270))
        assertEquals(s, s.rotatedClockwise(90).rotatedClockwise(270))
        assertEquals(Shift(-1f, 3f), s.rotatedClockwise(-270))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rotatedClockwiseRejectsOddAngles() {
        Shift(1f, 1f).rotatedClockwise(45)
    }
}
