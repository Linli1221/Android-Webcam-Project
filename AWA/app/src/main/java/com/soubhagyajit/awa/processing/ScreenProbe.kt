package com.soubhagyajit.awa.processing

/**
 * Decides whether a frame looks like it was shot in front of a coloured screen.
 *
 * This is only used by `BackgroundRemover.Mode.AUTO`, and it answers one question: would the chroma
 * keyer do a better job than the segmentation model here? When there really is a green or blue
 * screen the keyer wins clearly - its edge follows the actual colour boundary instead of a learned
 * silhouette, so hair and motion blur survive. When there is not, the keyer is actively harmful (it
 * punches holes through anything green in the room), so the model has to take over.
 *
 * The measurement is deliberately crude: the fraction of the frame border whose chroma sits on the
 * green/blue side of neutral. A screen fills the border; a room does not. Sampling the border rather
 * than the whole frame also means a person standing in the middle cannot influence the verdict.
 */
internal object ScreenProbe {

    /**
     * Coverage in 0..1. Roughly 0 for a normal room, above [SCREEN_COVERAGE] for a real screen.
     */
    fun coverage(nv21: ByteArray, width: Int, height: Int): Float {
        if (width < 32 || height < 32) return 0f
        if (nv21.size < width * height * 3 / 2) return 0f

        val uvBase = width * height
        val uvW = width shr 1
        val uvH = height shr 1
        val bandX = (uvW / 14).coerceAtLeast(2)
        val bandY = (uvH / 14).coerceAtLeast(2)

        var hits = 0
        var visited = 0
        var cy = 0
        while (cy < uvH) {
            val inBandRow = cy < bandY || cy >= uvH - bandY
            val rowBase = uvBase + cy * width
            var cx = 0
            while (cx < uvW) {
                if (inBandRow || cx < bandX || cx >= uvW - bandX) {
                    val i = rowBase + (cx shl 1)
                    val v = nv21[i].toInt() and 0xFF
                    val u = nv21[i + 1].toInt() and 0xFF
                    visited++
                    // Both screens share a low Cr (v < 112): green pulls Cb down, blue pushes Cb up,
                    // so only Cr separates "screen" from skin, wood, grey walls and most clothing.
                    if (v < 112 && (u < 112 || u > 144)) hits++
                }
                cx += 4
            }
            cy += 2
        }
        return if (visited == 0) 0f else hits.toFloat() / visited
    }

    /** Border coverage above which a frame is treated as a real screen. */
    const val SCREEN_COVERAGE = 0.45f
}
