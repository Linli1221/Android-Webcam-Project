package com.soubhagyajit.awa.processing.segmentation

/**
 * Geometry between the three coordinate systems involved in person segmentation.
 *
 *  - **sensor** - the NV21 frame exactly as CameraX hands it over: landscape, `rotationDegrees`
 *    *not* applied. This is the frame that gets composited and encoded.
 *  - **upright** - what the user sees, i.e. sensor rotated clockwise by `rotationDegrees`.
 *  - **mask** - what the segmenter returns, which is always in the **upright** frame.
 *
 * The mask therefore cannot be sampled with the frame's own coordinates: on a phone held upright
 * the sensor frame is turned 90 degrees relative to the mask. Doing that rotation per pixel while
 * compositing would cost two multiplies per pixel on every frame, so the mask is rotated once
 * (256x256, ~65k pixels) into a sensor-aligned grid and upsampling afterwards is plain axis
 * aligned bilinear - which is also what lets the horizontal expansion be cached per mask row.
 *
 * Rotations are expressed as normalised coordinates in [0,1). For a clockwise rotation `r`,
 * sensor -> upright is:
 *
 * ```
 *  r = 0    (px, py) = ( sx,      sy     )
 *  r = 90   (px, py) = ( 1 - sy,  sx     )
 *  r = 180  (px, py) = ( 1 - sx,  1 - sy )
 *  r = 270  (px, py) = ( sy,      1 - sx )
 * ```
 *
 * and the inverse (upright -> sensor), used when building the segmenter input, is the same table
 * solved for `(sx, sy)`.
 */
internal object MaskWarp {

    /**
     * Resamples [nv21] into a square, **upright** NV21 of `size x size` for the segmenter.
     *
     * The segmenter is given a rotation of 0 on purpose: its mask then refers to exactly this
     * buffer, so there is no hidden rotation to guess at. Resampling (rather than memcpy + rotate)
     * is also free anti-aliasing, and it keeps the per-frame cost proportional to the 256x256
     * output instead of the frame size.
     */
    fun prepareInput(
        nv21: ByteArray,
        width: Int,
        height: Int,
        rotation: Int,
        size: Int,
        dst: ByteArray,
    ) {
        val halfW = width shr 1
        val halfH = height shr 1
        val dstLuma = size * size
        if (dst.size < dstLuma + dstLuma / 2) return
        if (width < 2 || height < 2 || halfW < 1 || halfH < 1) return

        var i = 0
        var my = 0
        while (my < size) {
            val py = (my + 0.5f) / size
            var mx = 0
            while (mx < size) {
                val px = (mx + 0.5f) / size
                var sx = px
                var sy = py
                when (rotation) {
                    90 -> {
                        sx = py
                        sy = 1f - px
                    }
                    270 -> {
                        sx = 1f - py
                        sy = px
                    }
                    180 -> {
                        sx = 1f - px
                        sy = 1f - py
                    }
                }
                dst[i++] = sampleLuma(nv21, width, height, sx, sy)
                mx++
            }
            my++
        }

        // Chroma travels in 2x2 blocks. Nearest neighbour is enough here: this plane only gives
        // the model colour context and is 1/4 the size of the luma plane.
        val uvBase = width * height
        val dstHalf = size shr 1
        var uv = dstLuma
        var cy = 0
        while (cy < dstHalf) {
            val py = (2 * cy + 1).toFloat() / size
            var cx = 0
            while (cx < dstHalf) {
                val px = (2 * cx + 1).toFloat() / size
                var sx = px
                var sy = py
                when (rotation) {
                    90 -> {
                        sx = py
                        sy = 1f - px
                    }
                    270 -> {
                        sx = 1f - py
                        sy = px
                    }
                    180 -> {
                        sx = 1f - px
                        sy = 1f - py
                    }
                }
                val cu = (sx * halfW).toInt().coerceIn(0, halfW - 1)
                val cv = (sy * halfH).toInt().coerceIn(0, halfH - 1)
                val src = uvBase + (cv shl 1) * width + (cu shl 1)
                dst[uv++] = nv21[src]        // V
                dst[uv++] = nv21[src + 1]    // U
                cx++
            }
            cy++
        }
    }

    /**
     * Rotates an upright `maskW x maskH` mask into a `size x size` **sensor aligned** grid, so the
     * compositor can walk it row by row without any rotation per pixel.
     *
     * A square grid is used even for a 16:9 frame: the grid keeps the segmenter's 256x256
     * resolution on the short axis and simply samples it anisotropically, which is the same amount
     * of detail the mask actually carries.
     */
    fun toSensorGrid(
        mask: ByteArray,
        maskW: Int,
        maskH: Int,
        rotation: Int,
        size: Int,
    ): ByteArray {
        val out = ByteArray(size * size)
        if (maskW < 2 || maskH < 2) return out
        var i = 0
        var j = 0
        while (j < size) {
            val sy = (j + 0.5f) / size
            var ix = 0
            while (ix < size) {
                val sx = (ix + 0.5f) / size
                var px = sx
                var py = sy
                when (rotation) {
                    90 -> {
                        px = 1f - sy
                        py = sx
                    }
                    270 -> {
                        px = sy
                        py = 1f - sx
                    }
                    180 -> {
                        px = 1f - sx
                        py = 1f - sy
                    }
                }
                out[i++] = sampleMask(mask, maskW, maskH, px, py)
                ix++
            }
            j++
        }
        return out
    }

    private fun sampleLuma(src: ByteArray, width: Int, height: Int, sx: Float, sy: Float): Byte {
        val fx = (sx * width - 0.5f).coerceIn(0f, width - 1f)
        val fy = (sy * height - 0.5f).coerceIn(0f, height - 1f)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = if (x0 + 1 < width) x0 + 1 else x0
        val y1 = if (y0 + 1 < height) y0 + 1 else y0
        val wx = fx - x0
        val wy = fy - y0
        val r0 = y0 * width
        val r1 = y1 * width
        val top = (src[r0 + x0].toInt() and 0xFF) * (1f - wx) + (src[r0 + x1].toInt() and 0xFF) * wx
        val bottom = (src[r1 + x0].toInt() and 0xFF) * (1f - wx) + (src[r1 + x1].toInt() and 0xFF) * wx
        return (top * (1f - wy) + bottom * wy).toInt().toByte()
    }

    private fun sampleMask(src: ByteArray, width: Int, height: Int, sx: Float, sy: Float): Byte {
        val fx = (sx * width - 0.5f).coerceIn(0f, width - 1f)
        val fy = (sy * height - 0.5f).coerceIn(0f, height - 1f)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = if (x0 + 1 < width) x0 + 1 else x0
        val y1 = if (y0 + 1 < height) y0 + 1 else y0
        val wx = fx - x0
        val wy = fy - y0
        val r0 = y0 * width
        val r1 = y1 * width
        val top = (src[r0 + x0].toInt() and 0xFF) * (1f - wx) + (src[r0 + x1].toInt() and 0xFF) * wx
        val bottom = (src[r1 + x0].toInt() and 0xFF) * (1f - wx) + (src[r1 + x1].toInt() and 0xFF) * wx
        return (top * (1f - wy) + bottom * wy).toInt().toByte()
    }
}
