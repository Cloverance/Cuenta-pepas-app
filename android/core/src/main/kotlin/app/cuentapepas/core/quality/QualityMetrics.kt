package app.cuentapepas.core.quality

/**
 * Global brightness statistics of a frame.
 *
 * @property mean average luma, 0-255.
 * @property darkFraction share of pixels below 20.
 * @property brightFraction share of pixels above 235.
 */
data class Brightness(val mean: Double, val darkFraction: Double, val brightFraction: Double)

object QualityMetrics {

    const val DARK_BELOW = 20
    const val BRIGHT_ABOVE = 235

    /**
     * Sharpness as the variance of the 4-neighbour Laplacian over the image interior.
     * Higher is sharper; a constant image gives 0. Images smaller than 3x3 give 0.
     */
    fun sharpness(img: LumaImage): Double {
        val w = img.width
        val h = img.height
        if (w < 3 || h < 3) return 0.0
        val d = img.data
        var sum = 0L
        var sumSq = 0L
        for (y in 1 until h - 1) {
            var i = y * w + 1
            for (x in 1 until w - 1) {
                val lap = 4 * (d[i].toInt() and 0xFF) -
                    (d[i - 1].toInt() and 0xFF) - (d[i + 1].toInt() and 0xFF) -
                    (d[i - w].toInt() and 0xFF) - (d[i + w].toInt() and 0xFF)
                sum += lap
                sumSq += lap * lap // |lap| <= 1020, so lap * lap fits in Int
                i++
            }
        }
        val n = ((w - 2) * (h - 2)).toDouble()
        val mean = sum / n
        return maxOf(0.0, sumSq / n - mean * mean)
    }

    fun brightness(img: LumaImage): Brightness {
        val d = img.data
        var sum = 0L
        var dark = 0
        var bright = 0
        for (b in d) {
            val v = b.toInt() and 0xFF
            sum += v
            if (v < DARK_BELOW) dark++
            if (v > BRIGHT_ABOVE) bright++
        }
        val n = d.size.toDouble()
        return Brightness(mean = sum / n, darkFraction = dark / n, brightFraction = bright / n)
    }
}
