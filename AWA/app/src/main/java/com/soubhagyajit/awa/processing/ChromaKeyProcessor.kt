package com.soubhagyajit.awa.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-device green screen (chroma key) matting for NV21 frames.
 *
 * The decision is taken in the chroma plane (Cb/Cr) instead of RGB: a keyed-out pixel is "close
 * to the key colour" only in colour, so a half-lit green screen and a bright one are removed
 * alike, and the luma detail of the subject is never touched by the threshold. The key colour
 * itself is sampled from the border of the frame, which is what makes an unevenly lit screen work.
 *
 * One pass over the luma pixels does everything: the alpha of the chroma cell the pixel belongs to
 * is ramped between `similarity` and `similarity + smoothness` (soft edge instead of a hard cut),
 * spill is repaired in RGB for the pixels next to the silhouette, then luma and chroma are
 * composited against the background planes. Keeping the despill in the same pass is what lets it
 * correct brightness and colour together - removing the colour of a lit green fringe while keeping
 * its luma is what turns a keyed edge yellow.
 *
 * JPEG (what the MJPEG stream uses) carries no alpha channel, so the matte is composited on the
 * phone onto either a solid colour or a still image. Everything is done in fixed size buffers
 * reused across frames, so a 720p frame costs no allocations in the steady state.
 */
class ChromaKeyProcessor {

    data class Config(
        val enabled: Boolean = false,
        val keyColor: Int = 0xFF00FF00.toInt(),
        /** How much colour difference still counts as background. 0..1 (0.28 ~ "just the screen"). */
        val similarity: Float = 0.28f,
        /** Width of the soft edge, added on top of `similarity`. 0..0.6. */
        val smoothness: Float = 0.12f,
        /** Green cast removal on the remaining foreground. 0..1. */
        val spill: Float = 0.45f,
        val backgroundColor: Int = 0xFF101018.toInt(),
        val useImageBackground: Boolean = false,
        /**
         * Continuously re-estimate the key colour from the frame border (where the screen is).
         * A fixed key colour breaks down as soon as the screen is unevenly lit: a dimmed corner
         * loses chroma and drifts away from "pure green" in every direction. Sampling the screen
         * itself makes the threshold a local one, which is what a real keyer does.
         */
        val autoKey: Boolean = true,
        /**
         * Let the Vulkan backend do the work. Only a hint: without a working driver, or for frame
         * sizes the shaders cannot address, this class keeps doing it on the CPU.
         */
        val useGpu: Boolean = true,
        /**
         * Foreground cut for a segmentation mask, 0..1. 0.5 splits the confidence evenly; moving it
         * up eats into the subject, down grows it. Ignored by the chroma path.
         */
        val maskThreshold: Float = 0.5f,
        /**
         * Soft edge for a segmentation mask, 0.01..0.5. A model mask is already soft, but its edge
         * is wobbly rather than feathered, so this remaps the ramp around [maskThreshold] - small
         * values harden the cut, large values give hair room to blend.
         */
        val maskFeather: Float = 0.2f,
        /**
         * Temporal smoothing of the segmentation mask, 0..0.9. Model masks flicker on hair and
         * shoulders at a rate the eye notices; averaging consecutive masks fixes that for a couple
         * of frames of extra lag.
         */
        val maskSmoothing: Float = 0.35f,
        /**
         * Background plate engine: how much of the scene counts as removed, 0..1. Higher drops the
         * tolerances, so more of the room disappears - at the price of eating into clothes and hair
         * whose colour is close to the wall.
         */
        val plateSensitivity: Float = 0.5f,
        /** Background plate engine: soft edge width, 0..1.5. */
        val plateFeather: Float = 0.35f,
        /** Background plate engine: temporal smoothing of the matte, 0..0.9. */
        val plateSmoothing: Float = 0.4f,
    )

    @Volatile
    var config: Config = Config()

    @Volatile
    private var keyU = 43
    @Volatile
    private var keyV = 21
    private var blueKey = false
    private var seedColor = Int.MIN_VALUE
    private var frameCounter = 0

    /** Background image (already decoded). Null -> the solid `backgroundColor` is used instead. */
    @Volatile
    private var backgroundImage: Bitmap? = null

    private var imageVersion = 0

    internal class Planes(
        val width: Int,
        val height: Int,
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray,
    )

    private data class Spec(
        val width: Int,
        val height: Int,
        val color: Int,
        val useImage: Boolean,
        val imageVersion: Int,
    )

    private var cachedSpec: Spec? = null
    private var cachedPlanes: Planes? = null

    private var output = ByteArray(0)
    private var alphaRow = IntArray(0)
    private var distanceRow = IntArray(0)

    // --- segmentation mask upsampling (see processMasked) ---
    private var colX0 = IntArray(0)
    private var colX1 = IntArray(0)
    private var colWeight = FloatArray(0)
    private var colWidth = -1
    private var colMaskWidth = -1
    private var expandedA = IntArray(0)
    private var expandedB = IntArray(0)
    private var expandRowIndexA = -1
    private var expandRowIndexB = -1
    private var expandWidth = -1
    private var expandMaskWidth = -1

    /** True when a frame actually needs processing (cheap check for the capture pipeline). */
    val isActive: Boolean
        get() = config.enabled

    val hasBackgroundImage: Boolean
        get() = backgroundImage != null

    fun setBackgroundImage(bitmap: Bitmap?) {
        backgroundImage = bitmap
        imageVersion++
        Log.d(TAG, "Background image set: ${bitmap?.width}x${bitmap?.height}")
    }

    fun clearBackgroundImage() = setBackgroundImage(null)

    /**
     * The background for this frame size, as planes. The Vulkan backend uploads the very same
     * planes, so the crop / colour conversion only exists once (here).
     */
    internal fun backgroundPlanes(width: Int, height: Int): Planes =
        planesFor(width, height, config)

    /**
     * Composites [src] (NV21, `width * height * 3 / 2` bytes) onto the background.
     * The returned array is owned by this class and is reused for the next frame, so copy it if
     * it has to outlive the call.
     */
    fun process(src: ByteArray, width: Int, height: Int): ByteArray {
        val cfg = config
        if (!cfg.enabled) return src
        if (width < 8 || height < 8) return src
        if (src.size < width * height * 3 / 2) return src

        val planes = planesFor(width, height, cfg)
        val out = ensureOutput(src.size)
        val alpha = ensureAlphaRow(width)
        val distance = ensureDistanceRow(width)

        if (seedColor != cfg.keyColor) {
            seedColor = cfg.keyColor
            keyU = keyChromaU(cfg.keyColor)
            keyV = keyChromaV(cfg.keyColor)
            blueKey = keyU > 128
        }
        if (cfg.autoKey) refineKey(src, width, height)
        val kU = keyU
        val kV = keyV

        val t = cfg.similarity.coerceIn(0f, 1f) * MAX_CHROMA
        val fade = (cfg.smoothness.coerceIn(0f, 0.6f) * MAX_CHROMA).coerceAtLeast(1f)
        val t2 = t * t
        val edge = t + fade
        val edge2 = edge * edge
        val spill = cfg.spill.coerceIn(0f, 1f)
        // Only pixels near the key can carry spill, so the (comparatively expensive) RGB repair
        // skips the rest of the subject. It also keeps skin tones away from the grey-out.
        val despill2 = (edge * 2f) * (edge * 2f)

        val uvBase = width * height
        val uvW = width shr 1
        val bgY = planes.y
        val bgU = planes.u
        val bgV = planes.v

        // Single pass over luma pixels: alpha comes from the chroma cell the pixel belongs to, the
        // chroma itself is written once per 2x2 cell. Keeping both in this loop means the despill
        // can correct brightness and colour together, which a chroma-only pass cannot do - removing
        // the colour of a lit green fringe while keeping its luma turns the edge yellow.
        var index = 0
        for (y in 0 until height) {
            val chromaRow = (y shr 1) * uvW
            val uvRowBase = uvBase + (y shr 1) * width
            if ((y and 1) == 0) {
                val half = width shr 1
                var c = 0
                var x = 0
                while (c < half) {
                    val vi = uvRowBase + (c shl 1)
                    val dv = (src[vi].toInt() and 0xFF) - kV
                    val du = (src[vi + 1].toInt() and 0xFF) - kU
                    val d2 = du * du + dv * dv
                    val a = alphaFor(d2, t, t2, fade, edge2)
                    alpha[x] = a
                    alpha[x + 1] = a
                    distance[x] = d2
                    distance[x + 1] = d2
                    x += 2
                    c++
                }
                if (x < width) {
                    alpha[x] = if (x > 0) alpha[x - 1] else 256
                    distance[x] = if (x > 0) distance[x - 1] else 0
                }
            }

            val bgRow = y * width
            val writeChroma = (y and 1) == 0
            var x = 0
            while (x < width) {
                val a = alpha[x]
                val vi = uvRowBase + ((x shr 1) shl 1)
                var u = src[vi + 1].toInt() and 0xFF
                var v = src[vi].toInt() and 0xFF
                var luma = src[index].toInt() and 0xFF

                if (a > 0 && spill > 0f && distance[x] < despill2) {
                    val fu = u - 128f
                    val fv = v - 128f
                    val r = luma + 1.402f * fv
                    val g = luma - 0.344136f * fu - 0.714136f * fv
                    val b = luma + 1.772f * fu
                    val cap = (r + b) * 0.5f           // the green level skin/white would have
                    if (g > cap) {
                        val ng = g - (g - cap) * spill
                        luma = (0.299f * r + 0.587f * ng + 0.114f * b).toInt().coerceIn(0, 255)
                        u = (128f - 0.168736f * r - 0.331264f * ng + 0.5f * b).toInt().coerceIn(0, 255)
                        v = (128f + 0.5f * r - 0.418688f * ng - 0.081312f * b).toInt().coerceIn(0, 255)
                    }
                }

                out[index] = when {
                    a <= 0 -> bgY[bgRow + x]
                    a >= 256 -> luma.toByte()
                    else -> blendByte(luma, bgY[bgRow + x], a)
                }

                if (writeChroma && (x and 1) == 0) {
                    val ci = chromaRow + (x shr 1)
                    out[vi] = blendByte(v, bgV[ci], a)
                    out[vi + 1] = blendByte(u, bgU[ci], a)
                }

                index++
                x++
            }
        }

        return out
    }

    /** 0 = fully background, 256 = fully foreground. */
    private fun alphaFor(d2: Int, t: Float, t2: Float, fade: Float, edge2: Float): Int = when {
        d2 <= t2 -> 0
        d2 >= edge2 -> 256
        else -> (((sqrt(d2.toFloat()) - t) / fade) * 256f).toInt().coerceIn(0, 256)
    }

    /**
     * Composites [src] onto the background using an external matte - the person segmenter's mask -
     * instead of a chroma threshold. Everything about the *background* (solid colour, still image,
     * crop, plane cache) is shared with [process]; only the alpha differs.
     *
     * [mask] is the sensor-aligned grid from [com.soubhagyajit.awa.processing.segmentation.PersonSegmenter],
     * `maskWidth x maskHeight`, 0 = background. It is usually far smaller than the frame (256x256),
     * so it is upsampled here with:
     *
     *  - separable bilinear (the horizontally expanded rows are cached, since a 256-row mask covers
     *    ~3 frame rows each, so most rows reuse the previous expansion),
     *  - a threshold/feather remap around `maskThreshold`, which is what turns a wobbly model edge
     *    into a controlled one without a separate blur pass.
     *
     * No despill runs on this path: there is no coloured screen to bounce light, and "fixing" a
     * green cast on a normal photograph would shift real colours.
     */
    fun processMasked(
        src: ByteArray,
        width: Int,
        height: Int,
        mask: ByteArray,
        maskWidth: Int,
        maskHeight: Int,
    ): ByteArray {
        val cfg = config
        if (width < 8 || height < 8) return src
        if (src.size < width * height * 3 / 2) return src
        if (maskWidth < 2 || maskHeight < 2) return src
        if (mask.size < maskWidth * maskHeight) return src

        val planes = planesFor(width, height, cfg)
        val out = ensureOutput(src.size)

        val threshold = cfg.maskThreshold.coerceIn(0.02f, 0.98f)
        val feather = cfg.maskFeather.coerceIn(0.01f, 0.5f)
        val invSpan = 1f / (2f * feather)

        prepareColumns(width, maskWidth)
        val x0 = colX0
        val x1 = colX1
        val xw = colWeight
        val rowA = ensureExpanded(width, first = true)
        val rowB = ensureExpanded(width, first = false)
        if (expandWidth != width || expandMaskWidth != maskWidth) {
            expandRowIndexA = -1
            expandRowIndexB = -1
            expandWidth = width
            expandMaskWidth = maskWidth
        }

        val bgY = planes.y
        val bgU = planes.u
        val bgV = planes.v
        val uvBase = width * height
        val uvW = width shr 1

        var index = 0
        var y = 0
        while (y < height) {
            // Where this frame row sits between two mask rows.
            val gy = (((y + 0.5f) / height) * maskHeight - 0.5f).coerceIn(0f, maskHeight - 1f)
            val j0 = gy.toInt()
            val j1 = if (j0 + 1 < maskHeight) j0 + 1 else j0
            val wy = gy - j0

            if (j0 != expandRowIndexA) {
                expandRow(mask, j0 * maskWidth, width, x0, x1, xw, rowA)
                expandRowIndexA = j0
            }
            if (j1 != expandRowIndexB) {
                expandRow(mask, j1 * maskWidth, width, x0, x1, xw, rowB)
                expandRowIndexB = j1
            }

            val bgRow = y * width
            val writeChroma = (y and 1) == 0
            val chromaRow = uvBase + (y shr 1) * width
            var x = 0
            while (x < width) {
                // 8.8 fixed point all the way until the threshold remap.
                val m0 = rowA[x]
                val m1 = rowB[x]
                val m = m0 + ((m1 - m0) * wy).toInt()
                val value = m * INV_FIXED
                val a = ((((value - threshold) * invSpan) + 0.5f).coerceIn(0f, 1f) * 256f).toInt()

                val luma = src[index].toInt() and 0xFF
                out[index] = when {
                    a <= 0 -> bgY[bgRow + x]
                    a >= 256 -> luma.toByte()
                    else -> blendByte(luma, bgY[bgRow + x], a)
                }

                if (writeChroma && (x and 1) == 0) {
                    val vi = chromaRow + x
                    val ci = (y shr 1) * uvW + (x shr 1)
                    out[vi] = blendByte(src[vi].toInt() and 0xFF, bgV[ci], a)
                    out[vi + 1] = blendByte(src[vi + 1].toInt() and 0xFF, bgU[ci], a)
                }

                index++
                x++
            }
            y++
        }

        return out
    }

    /**
     * Composites [src] onto the background using a full resolution matte that somebody else
     * produced - the background plate engine ([PlateSubtractor]) - instead of a chroma threshold or
     * a model mask.
     *
     * [alpha] is `width * height` bytes, 0 = background. That is one byte per pixel rather than a
     * 256x256 grid on purpose: the plate matte is already pixel accurate, and resampling it down and
     * back up would delete exactly the hair detail it was computed for.
     *
     * Chroma is written once per 2x2 cell from the **largest** of the four alphas: taking the cell's
     * average would drag a foreground pixel's colour towards the background along the silhouette,
     * which shows up as a coloured rim.
     */
    fun processAlpha(src: ByteArray, width: Int, height: Int, alpha: ByteArray): ByteArray {
        val cfg = config
        if (width < 8 || height < 8) return src
        if (src.size < width * height * 3 / 2) return src
        if (alpha.size < width * height) return src

        val planes = planesFor(width, height, cfg)
        val out = ensureOutput(src.size)
        val bgY = planes.y
        val bgU = planes.u
        val bgV = planes.v
        val uvBase = width * height
        val uvW = width shr 1

        var index = 0
        var y = 0
        while (y < height) {
            val chromaRow = uvBase + (y shr 1) * width
            val writeChroma = (y and 1) == 0
            var x = 0
            while (x < width) {
                val a = alphaAt(alpha[index])
                val luma = src[index].toInt() and 0xFF
                out[index] = when {
                    a <= 0 -> bgY[index]
                    a >= 256 -> luma.toByte()
                    else -> blendByte(luma, bgY[index], a)
                }

                if (writeChroma && (x and 1) == 0) {
                    var cellAlpha = a
                    if (x + 1 < width) cellAlpha = maxOf(cellAlpha, alphaAt(alpha[index + 1]))
                    if (y + 1 < height) {
                        cellAlpha = maxOf(cellAlpha, alphaAt(alpha[index + width]))
                        if (x + 1 < width) cellAlpha = maxOf(cellAlpha, alphaAt(alpha[index + width + 1]))
                    }
                    val vi = chromaRow + x
                    val ci = (y shr 1) * uvW + (x shr 1)
                    out[vi] = blendByte(src[vi].toInt() and 0xFF, bgV[ci], cellAlpha)
                    out[vi + 1] = blendByte(src[vi + 1].toInt() and 0xFF, bgU[ci], cellAlpha)
                }

                index++
                x++
            }
            y++
        }

        return out
    }

    /** 0..255 stored, 0..256 used, so full opacity really is full opacity. */
    private fun alphaAt(value: Byte): Int {
        val v = value.toInt() and 0xFF
        return v + (v shr 7)
    }

    /** Column -> mask-column mapping, rebuilt only when the frame or mask width changes. */
    private fun prepareColumns(width: Int, maskWidth: Int) {
        if (colWidth == width && colMaskWidth == maskWidth) return
        if (colX0.size < width) {
            colX0 = IntArray(width)
            colX1 = IntArray(width)
            colWeight = FloatArray(width)
        }
        var x = 0
        while (x < width) {
            val fx = (((x + 0.5f) / width) * maskWidth - 0.5f).coerceIn(0f, maskWidth - 1f)
            val i0 = fx.toInt()
            colX0[x] = i0
            colX1[x] = if (i0 + 1 < maskWidth) i0 + 1 else i0
            colWeight[x] = fx - i0
            x++
        }
        colWidth = width
        colMaskWidth = maskWidth
    }

    private fun expandRow(
        mask: ByteArray,
        base: Int,
        width: Int,
        x0: IntArray,
        x1: IntArray,
        weight: FloatArray,
        out: IntArray,
    ) {
        var x = 0
        while (x < width) {
            val a = (mask[base + x0[x]].toInt() and 0xFF) shl 8
            val b = (mask[base + x1[x]].toInt() and 0xFF) shl 8
            out[x] = a + ((b - a) * weight[x]).toInt()
            x++
        }
    }

    private fun ensureExpanded(width: Int, first: Boolean): IntArray {
        if (first) {
            if (expandedA.size < width) expandedA = IntArray(width)
            return expandedA
        }
        if (expandedB.size < width) expandedB = IntArray(width)
        return expandedB
    }

    /**
     * Samples the chroma of the frame border (normally pure screen) and nudges the key colour
     * towards it. Runs every few frames and moves slowly, so somebody walking through the shot
     * cannot steal the key. Does nothing when the border is not dominated by a green-ish colour,
     * which keeps the configured key colour as a safe fallback.
     */
    private fun refineKey(src: ByteArray, width: Int, height: Int) {
        if (frameCounter++ % KEY_REFRESH_FRAMES != 0) return

        val uvBase = width * height
        val uvW = width shr 1
        val uvH = height shr 1
        val bandX = (uvW / 14).coerceAtLeast(2)
        val bandY = (uvH / 14).coerceAtLeast(2)
        val strideX = 2

        var sumU = 0L
        var sumV = 0L
        var hits = 0
        var visited = 0

        var cy = 0
        while (cy < uvH) {
            val inBandRow = cy < bandY || cy >= uvH - bandY
            val rowBase = uvBase + cy * width
            var cx = 0
            while (cx < uvW) {
                val inBand = inBandRow || cx < bandX || cx >= uvW - bandX
                if (inBand) {
                    val i = rowBase + (cx shl 1)
                    val v = src[i].toInt() and 0xFF
                    val u = src[i + 1].toInt() and 0xFF
                    visited++
                    // "screen-ness": the two chroma components pulled towards the key's side of
                    // neutral (Cb and Cr both below 128 for a green screen, mirrored for blue).
                    val score = if (blueKey) min(u - 128, 128 - v) else min(128 - u, 128 - v)
                    if (score > 16) {
                        sumU += u
                        sumV += v
                        hits++
                    }
                }
                cx += strideX
            }
            cy++
        }

        if (visited < 32 || hits * 5 < visited) return // border is not (mostly) screen

        val meanU = (sumU / hits).toInt()
        val meanV = (sumV / hits).toInt()
        keyU = (keyU * 3 + meanU) / 4
        keyV = (keyV * 3 + meanV) / 4
    }

    private fun blendByte(fg: Int, bg: Byte, alpha: Int): Byte = when {
        alpha <= 0 -> bg
        alpha >= 256 -> fg.toByte()
        else -> (((fg * alpha) + (bg.toInt() and 0xFF) * (256 - alpha)) shr 8).toByte()
    }

    private fun ensureOutput(size: Int): ByteArray {
        if (output.size < size) output = ByteArray(size)
        return output
    }

    private fun ensureAlphaRow(width: Int): IntArray {
        if (alphaRow.size < width) alphaRow = IntArray(width)
        return alphaRow
    }

    private fun ensureDistanceRow(width: Int): IntArray {
        if (distanceRow.size < width) distanceRow = IntArray(width)
        return distanceRow
    }

    private fun planesFor(width: Int, height: Int, cfg: Config): Planes {
        val spec = Spec(width, height, cfg.backgroundColor, cfg.useImageBackground, imageVersion)
        val cached = cachedPlanes
        if (cached != null && cachedSpec == spec) return cached

        val image = backgroundImage
        val planes = if (spec.useImage && image != null) {
            runCatching { planesFromBitmap(image, width, height) }
                .getOrElse {
                    Log.e(TAG, "Could not build background planes, falling back to solid colour", it)
                    planesSolid(cfg.backgroundColor, width, height)
                }
        } else {
            planesSolid(cfg.backgroundColor, width, height)
        }
        cachedSpec = spec
        cachedPlanes = planes
        return planes
    }

    private fun planesSolid(color: Int, width: Int, height: Int): Planes {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val yv = clampToByte(0.299f * r + 0.587f * g + 0.114f * b)
        val uv = clampToByte(128f - 0.168736f * r - 0.331264f * g + 0.5f * b)
        val vv = clampToByte(128f + 0.5f * r - 0.418688f * g - 0.081312f * b)
        val uvW = width shr 1
        val uvH = height shr 1
        return Planes(
            width = width,
            height = height,
            y = ByteArray(width * height) { yv },
            u = ByteArray(uvW * uvH) { uv },
            v = ByteArray(uvW * uvH) { vv },
        )
    }

    private fun planesFromBitmap(bitmap: Bitmap, width: Int, height: Int): Planes {
        // Centre-crop the picture to the frame aspect, so the background never looks squeezed.
        // Integer rects: drawBitmap has no overload that takes two RectF arguments.
        val scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val srcRatio = bitmap.width.toFloat() / bitmap.height
        val dstRatio = width.toFloat() / height
        val srcRect = if (srcRatio > dstRatio) {
            val w = bitmap.height * dstRatio
            val left = (bitmap.width - w) / 2f
            Rect(left.roundToInt(), 0, (left + w).roundToInt(), bitmap.height)
        } else {
            val h = bitmap.width / dstRatio
            val top = (bitmap.height - h) / 2f
            Rect(0, top.roundToInt(), bitmap.width, (top + h).roundToInt())
        }
        Canvas(scaled).drawBitmap(
            bitmap,
            srcRect,
            Rect(0, 0, width, height),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        scaled.recycle()

        val yPlane = ByteArray(width * height)
        for (i in pixels.indices) {
            val c = pixels[i]
            yPlane[i] = clampToByte(
                0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            )
        }

        val uvW = width shr 1
        val uvH = height shr 1
        val uPlane = ByteArray(uvW * uvH)
        val vPlane = ByteArray(uvW * uvH)
        var cy = 0
        while (cy < uvH) {
            val rowBase = (cy shl 1) * width
            var cx = 0
            while (cx < uvW) {
                val c = pixels[rowBase + (cx shl 1)]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val o = cy * uvW + cx
                uPlane[o] = clampToByte(128f - 0.168736f * r - 0.331264f * g + 0.5f * b)
                vPlane[o] = clampToByte(128f + 0.5f * r - 0.418688f * g - 0.081312f * b)
                cx++
            }
            cy++
        }

        Log.d(TAG, "Built background planes for ${width}x$height")
        return Planes(width, height, yPlane, uPlane, vPlane)
    }

    private fun clampToByte(value: Float): Byte = when {
        value <= 0f -> 0.toByte()
        value >= 255f -> 255.toByte()
        else -> value.toInt().toByte()
    }

    private fun keyChromaU(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (128f - 0.168736f * r - 0.331264f * g + 0.5f * b).toInt().coerceIn(0, 255)
    }

    private fun keyChromaV(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (128f + 0.5f * r - 0.418688f * g - 0.081312f * b).toInt().coerceIn(0, 255)
    }

    companion object {
        private const val TAG = "AWA-Chroma"
        /** Radius of the (Cb, Cr) plane used to normalise the sliders. */
        private const val MAX_CHROMA = 128f
        /** How often the key colour is re-sampled, in frames. */
        private const val KEY_REFRESH_FRAMES = 8
        /**
         * Converts the mask's 8.8 fixed point value (0..65280) back to 0..1. Keeping the mask
         * column/row interpolation in integers until this point avoids four float conversions per
         * pixel, which is most of the cost of upsampling 256x256 to 1080p.
         */
        private const val INV_FIXED = 1f / (255f * 256f)
    }
}
