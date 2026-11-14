package com.soubhagyajit.awa.processing

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Background plate matting: shoot the empty scene once, then cut everything that is not it.
 *
 * This is the third engine, next to the chroma keyer and the person segmenter, and it wins on the
 * two things those cannot do at the same time: a normal room (no screen, no colour to key) and a
 * precise edge that follows the actual silhouette instead of a 256x256 model mask.
 *
 * What it does per frame:
 *
 *  1. **Exposure compensation.** A phone's auto exposure, and the auto white balance behind it,
 *     drift as soon as the subject walks in - the room gets darker, and a fixed difference against
 *     the plate would mark the whole wall as foreground. The luma shift is estimated as the median
 *     difference over the pixels whose chroma still matches the plate (background pixels, and only
 *     those, are the majority there), then applied to the plate before comparing.
 *  2. **Two-axis difference.** A pixel belongs to the background when it is close to the plate in
 *     *both* luma and chroma. Luma alone cannot tell a shadow from a dark sleeve, chroma alone is
 *     blind to a white shirt against a white wall, so the score is the larger of the two
 *     normalised distances.
 *  3. **Shadow rejection.** A cast shadow keeps the chroma of the wall and only loses luma, so a
 *     darkening with matching colour and a sane magnitude is treated as background. A violent
 *     darkening (a black object) still counts as foreground, which is where the compromise is.
 *  4. **Edge.** The score is ramped into a 0..255 matte, then a 3x3 box blur turns the one pixel
 *     step at the silhouette into a soft edge; [ChromaKeyProcessor.processAlpha] composites it.
 *  5. **Slow re-learning.** Background pixels - and only those - creep towards the live frame at
 *     1/32 per frame, so a light left on for a whole meeting, or a chair that was moved, stops
 *     being a hole in the matte after a couple of seconds.
 *
 * Temporal smoothing of the finished matte is a separate, optional stage: sensor noise on a static
 * tripod shot is the one thing that still flickers.
 *
 * The plate is captured as the mean of [CAPTURE_SAMPLES] frames, which halves the noise it bakes in
 * and softens a hand that happened to be in the way for one frame.
 */
class PlateSubtractor {

    enum class State {
        /** Never captured, or invalidated by a resolution change. */
        NONE,

        /** Collecting the first frames of a new plate. */
        CAPTURING,

        /** A plate exists and describes the current frame size. */
        READY,
    }

    @Volatile
    var state: State = State.NONE
        private set

    @Volatile
    private var captureRequested = false

    /**
     * True while frames still have to reach [process]: either a capture was just requested (the
     * next frame starts it) or one is in progress. The capture pipeline uses this to keep encoding
     * even when nobody is watching the stream.
     */
    val needsFrames: Boolean
        get() = captureRequested || state == State.CAPTURING

    private var plateWidth = 0
    private var plateHeight = 0
    private var plateY = ByteArray(0)
    private var plateU = ByteArray(0)
    private var plateV = ByteArray(0)
    private var samples = 0

    // Per-frame scratch, reused across frames: raw matte, blurred matte, temporally smoothed matte.
    private var alpha = ByteArray(0)
    private var blurred = ByteArray(0)
    private var smoothed = ByteArray(0)
    private var resetSmoothing = true

    private val histogram = IntArray(HIST_BUCKETS)

    /** Luma shift of the plate relative to the live frame, in code values. */
    private var lumaOffset = 0
    private var offsetFrames = 0

    /** Asks for a new plate; the next analyzed frame starts collecting. */
    fun startCapture() {
        captureRequested = true
    }

    /** Drops the plate - resolution or camera changed, so it no longer describes the frame. */
    fun clear() {
        state = State.NONE
        captureRequested = false
        plateWidth = 0
        plateHeight = 0
        samples = 0
        resetSmoothing = true
        lumaOffset = 0
        offsetFrames = 0
    }

    /**
     * Returns a 0..255 matte for [src] (`width * height` bytes, 0 = background), or null when there
     * is nothing to composite yet: no plate, a capture in progress, or a frame that no longer
     * matches the plate. Null means "pass the frame through untouched", which is also what happens
     * while the user is still lining up the shot.
     *
     * The array is owned by this class and reused for the next frame.
     */
    fun process(
        src: ByteArray,
        width: Int,
        height: Int,
        cfg: ChromaKeyProcessor.Config,
    ): ByteArray? {
        if (width < 8 || height < 8) return null
        if ((width and 1) == 1 || (height and 1) == 1) return null
        if (src.size < width * height * 3 / 2) return null

        if (captureRequested) {
            captureRequested = false
            startNewPlate(width, height)
        }

        if (state == State.CAPTURING) {
            accumulate(src, width, height)
            return null
        }
        if (state != State.READY) return null
        if (width != plateWidth || height != plateHeight) {
            Log.i(TAG, "Frame is ${width}x$height, plate was ${plateWidth}x$plateHeight - dropping it")
            clear()
            return null
        }

        val n = width * height
        ensureBuffers(n)
        val a = alpha
        val uvBase = n
        val uvW = width shr 1

        val offset = estimateOffset(src, width, height, uvBase, uvW)

        val sensitivity = cfg.plateSensitivity.coerceIn(0f, 1f)
        val lumaTolerance = LUMA_TOLERANCE_MAX - LUMA_TOLERANCE_SPAN * sensitivity
        val chromaTolerance = CHROMA_TOLERANCE_MAX - CHROMA_TOLERANCE_SPAN * sensitivity
        val invLuma = 1f / lumaTolerance
        val invChroma = 1f / chromaTolerance
        val feather = EDGE_MIN + cfg.plateFeather.coerceIn(0f, 1.5f) * EDGE_SCALE
        val invFeather = 1f / feather
        val shadowLimit = lumaTolerance * SHADOW_RANGE

        var index = 0
        var y = 0
        while (y < height) {
            val cellRow = (y shr 1) * uvW
            val uvRow = uvBase + (y shr 1) * width
            var x = 0
            while (x < width) {
                val ci = cellRow + (x shr 1)
                val vi = uvRow + x

                val luma = src[index].toInt() and 0xFF
                val curU = src[vi + 1].toInt() and 0xFF
                val curV = src[vi].toInt() and 0xFF
                val plateLuma = plateY[index].toInt() and 0xFF
                val plateUv = plateU[ci].toInt() and 0xFF
                val plateVv = plateV[ci].toInt() and 0xFF

                val dy = luma - (plateLuma + offset)
                val du = curU - plateUv
                val dv = curV - plateVv

                val chromaScore = sqrt((du * du + dv * dv).toFloat()) * invChroma
                var score = abs(dy) * invLuma
                if (chromaScore > score) score = chromaScore

                // A cast shadow scales the light, and chroma is measured relative to luma, so
                // (u-128)/y and (v-128)/y do not move under a shadow but do move for a dark object.
                // That is the only test here that separates "wall in shade" from "dark trousers"
                // without also throwing away every dark garment in the room.
                if (dy < 0 && -dy < shadowLimit) {
                    val curLuma = luma.toFloat()
                    val refLuma = (plateLuma + offset).toFloat()
                    if (curLuma >= SHADOW_MIN_LUMA && refLuma >= SHADOW_MIN_LUMA) {
                        val ratio = curLuma / refLuma
                        if (ratio >= SHADOW_RATIO_MIN && ratio <= SHADOW_RATIO_MAX) {
                            val dcU = (curU - 128f) / curLuma - (plateUv - 128f) / refLuma
                            val dcV = (curV - 128f) / curLuma - (plateVv - 128f) / refLuma
                            if (dcU * dcU + dcV * dcV < SHADOW_CHROMATICITY2) score = 0f
                        }
                    }
                }

                a[index] = (((score - 1f) * invFeather).coerceIn(0f, 1f) * 255f).toInt().toByte()

                index++
                x++
            }
            y++
        }

        blurHorizontal(a, blurred, width, height)
        blurVertical(blurred, a, width, height)
        temporallySmooth(a, n, cfg.plateSmoothing)
        learnBackground(src, width, height, uvBase, uvW, n)

        return smoothed
    }

    // --- capture ---------------------------------------------------------------------------

    private fun startNewPlate(width: Int, height: Int) {
        val n = width * height
        val uv = (width shr 1) * (height shr 1)
        if (plateY.size != n) {
            plateY = ByteArray(n)
            plateU = ByteArray(uv)
            plateV = ByteArray(uv)
        }
        plateWidth = width
        plateHeight = height
        samples = 0
        lumaOffset = 0
        offsetFrames = 0
        resetSmoothing = true
        state = State.CAPTURING
        Log.i(TAG, "Capturing background plate at ${width}x$height")
    }

    private fun accumulate(src: ByteArray, width: Int, height: Int) {
        val n = width * height
        val uvW = width shr 1
        val uvBase = n
        val den = samples + 1

        if (samples == 0) {
            System.arraycopy(src, 0, plateY, 0, n)
            var cy = 0
            while (cy < height shr 1) {
                val uvRow = uvBase + cy * width
                val cellRow = cy * uvW
                var cx = 0
                while (cx < uvW) {
                    val vi = uvRow + (cx shl 1)
                    plateV[cellRow + cx] = src[vi]
                    plateU[cellRow + cx] = src[vi + 1]
                    cx++
                }
                cy++
            }
        } else {
            // Running mean: no extra buffer, and the third sample averages out the sensor noise the
            // first one carried.
            var i = 0
            while (i < n) {
                val cur = plateY[i].toInt() and 0xFF
                plateY[i] = (cur + (((src[i].toInt() and 0xFF) - cur) / den)).toByte()
                i++
            }
            var cy = 0
            while (cy < height shr 1) {
                val uvRow = uvBase + cy * width
                val cellRow = cy * uvW
                var cx = 0
                while (cx < uvW) {
                    val vi = uvRow + (cx shl 1)
                    val c = cellRow + cx
                    val cu = plateU[c].toInt() and 0xFF
                    val cv = plateV[c].toInt() and 0xFF
                    plateU[c] = (cu + (((src[vi + 1].toInt() and 0xFF) - cu) / den)).toByte()
                    plateV[c] = (cv + (((src[vi].toInt() and 0xFF) - cv) / den)).toByte()
                    cx++
                }
                cy++
            }
        }

        samples++
        if (samples >= CAPTURE_SAMPLES) {
            state = State.READY
            offsetFrames = 0
            Log.i(TAG, "Background plate ready")
        }
    }

    // --- difference ------------------------------------------------------------------------

    /**
     * Median luma difference over the pixels whose chroma still matches the plate. Auto exposure
     * is the reason this exists: without it a subject entering the shot dims the wall by 20-40
     * code values and the entire background turns into foreground for a second.
     */
    private fun estimateOffset(
        src: ByteArray,
        width: Int,
        height: Int,
        uvBase: Int,
        uvW: Int,
    ): Int {
        histogram.fill(0)
        var count = 0
        var y = 0
        while (y < height) {
            val row = y * width
            val uvRow = uvBase + (y shr 1) * width
            val cellRow = (y shr 1) * uvW
            var x = 0
            while (x < width) {
                val ci = cellRow + (x shr 1)
                val du = (src[uvRow + x + 1].toInt() and 0xFF) - (plateU[ci].toInt() and 0xFF)
                val dv = (src[uvRow + x].toInt() and 0xFF) - (plateV[ci].toInt() and 0xFF)
                if (du * du + dv * dv < CHROMA_MATCH * CHROMA_MATCH) {
                    var d = (src[row + x].toInt() and 0xFF) - (plateY[row + x].toInt() and 0xFF)
                    if (d < -HIST_HALF) d = -HIST_HALF
                    if (d > HIST_HALF) d = HIST_HALF
                    histogram[d + HIST_HALF]++
                    count++
                }
                x += OFFSET_STRIDE
            }
            y += OFFSET_STRIDE
        }

        if (count < MIN_OFFSET_SAMPLES) return lumaOffset

        var acc = 0
        var median = 0
        var bucket = 0
        while (bucket < HIST_BUCKETS) {
            acc += histogram[bucket]
            if (acc * 2 >= count) {
                median = bucket - HIST_HALF
                break
            }
            bucket++
        }

        // The first frames after a capture trust the measurement outright; later ones only follow
        // it slowly, so a subject that fills the frame cannot drag the whole exposure estimate.
        val next = if (offsetFrames++ < 2) median else lumaOffset + (median - lumaOffset) / 4
        lumaOffset = next.coerceIn(-MAX_OFFSET, MAX_OFFSET)
        return lumaOffset
    }

    // --- matte shaping ---------------------------------------------------------------------

    private fun blurHorizontal(src: ByteArray, dst: ByteArray, width: Int, height: Int) {
        var y = 0
        while (y < height) {
            val row = y * width
            val last = row + width - 1
            var i = row
            dst[i] = (((src[i].toInt() and 0xFF) * 2 + (src[i + 1].toInt() and 0xFF)) / 3).toByte()
            i++
            while (i < last) {
                val sum = (src[i - 1].toInt() and 0xFF) + (src[i].toInt() and 0xFF) +
                    (src[i + 1].toInt() and 0xFF)
                dst[i] = (sum / 3).toByte()
                i++
            }
            dst[last] = (((src[last - 1].toInt() and 0xFF) + (src[last].toInt() and 0xFF) * 2) / 3).toByte()
            y++
        }
    }

    private fun blurVertical(src: ByteArray, dst: ByteArray, width: Int, height: Int) {
        var i = 0
        while (i < width) {
            dst[i] = (((src[i].toInt() and 0xFF) * 2 + (src[i + width].toInt() and 0xFF)) / 3).toByte()
            i++
        }
        var y = 1
        while (y < height - 1) {
            val row = y * width
            i = 0
            while (i < width) {
                val sum = (src[row - width + i].toInt() and 0xFF) + (src[row + i].toInt() and 0xFF) +
                    (src[row + width + i].toInt() and 0xFF)
                dst[row + i] = (sum / 3).toByte()
                i++
            }
            y++
        }
        val last = (height - 1) * width
        i = 0
        while (i < width) {
            val sum = (src[last - width + i].toInt() and 0xFF) + (src[last + i].toInt() and 0xFF) * 2
            dst[last + i] = (sum / 3).toByte()
            i++
        }
    }

    /**
     * Fixed point EMA over the matte. [smoothing] is 0..0.9: 0 keeps every frame as it is (visible
     * flicker on hair), 0.9 trades a couple of frames of lag for a rock steady edge.
     */
    private fun temporallySmooth(src: ByteArray, n: Int, smoothing: Float) {
        if (resetSmoothing) {
            System.arraycopy(src, 0, smoothed, 0, n)
            resetSmoothing = false
            return
        }
        val keep = ((1f - smoothing.coerceIn(0f, 0.9f)) * 256f).toInt()
        if (keep >= 256) {
            System.arraycopy(src, 0, smoothed, 0, n)
            return
        }
        var i = 0
        while (i < n) {
            val prev = smoothed[i].toInt() and 0xFF
            val cur = src[i].toInt() and 0xFF
            smoothed[i] = (prev + (((cur - prev) * keep) shr 8)).toByte()
            i++
        }
    }

    /**
     * Nudges the plate towards the frame wherever the matte says background. Slow on purpose: it is
     * there to absorb a lamp switching on or a chair being pushed aside, not to track the subject.
     */
    private fun learnBackground(
        src: ByteArray,
        width: Int,
        height: Int,
        uvBase: Int,
        uvW: Int,
        n: Int,
    ) {
        if (!LEARN_ENABLED) return
        val uvH = height shr 1
        var cy = 0
        while (cy < uvH) {
            val cellRow = cy * uvW
            val row0 = (cy shl 1) * width
            val row1 = row0 + width
            var cx = 0
            while (cx < uvW) {
                val i0 = row0 + (cx shl 1)
                val i1 = i0 + 1
                val i2 = row1 + (cx shl 1)
                val i3 = i2 + 1
                if ((smoothed[i0].toInt() and 0xFF) == 0 &&
                    (smoothed[i1].toInt() and 0xFF) == 0 &&
                    (i2 >= n || (smoothed[i2].toInt() and 0xFF) == 0) &&
                    (i3 >= n || (smoothed[i3].toInt() and 0xFF) == 0)
                ) {
                    lerpInPlace(plateY, i0, src[i0].toInt() and 0xFF)
                    lerpInPlace(plateY, i1, src[i1].toInt() and 0xFF)
                    if (i2 < n) lerpInPlace(plateY, i2, src[i2].toInt() and 0xFF)
                    if (i3 < n) lerpInPlace(plateY, i3, src[i3].toInt() and 0xFF)

                    val vi = uvBase + cy * width + (cx shl 1)
                    lerpInPlace(plateU, cellRow + cx, src[vi + 1].toInt() and 0xFF)
                    lerpInPlace(plateV, cellRow + cx, src[vi].toInt() and 0xFF)
                }
                cx++
            }
            cy++
        }
    }

    private fun lerpInPlace(plane: ByteArray, index: Int, target: Int) {
        val current = plane[index].toInt() and 0xFF
        plane[index] = (current + ((target - current) shr LEARN_SHIFT)).toByte()
    }

    private fun ensureBuffers(n: Int) {
        if (alpha.size < n) {
            alpha = ByteArray(n)
            blurred = ByteArray(n)
            smoothed = ByteArray(n)
            resetSmoothing = true
        }
    }

    private companion object {
        private const val TAG = "AWA-Plate"

        /** Frames averaged into a new plate. */
        const val CAPTURE_SAMPLES = 3

        /** Luma tolerance at sensitivity 0 and the amount sensitivity 1 removes from it. */
        const val LUMA_TOLERANCE_MAX = 46f
        const val LUMA_TOLERANCE_SPAN = 30f

        /** Chroma distance tolerance (0..~180) at sensitivity 0 and its span. */
        const val CHROMA_TOLERANCE_MAX = 40f
        const val CHROMA_TOLERANCE_SPAN = 28f

        /** Soft edge width, in score units, at feather 0 plus the scale applied to the slider. */
        const val EDGE_MIN = 0.1f
        const val EDGE_SCALE = 0.5f

        /** How far a darkening may go and still count as a shadow, as a multiple of the tolerance. */
        const val SHADOW_RANGE = 3f
        /** Luma below which chromaticity is too noisy to reason about; such a pixel stays foreground. */
        const val SHADOW_MIN_LUMA = 24f
        /**
         * Brightness ratio accepted as a shadow, relative to the plate. Above the upper bound it is
         * barely a shadow; below the lower bound it is dark enough to be a garment that happens to
         * have the wall's hue - keeping a hole in the subject is worse than keeping a shadow.
         */
        const val SHADOW_RATIO_MIN = 0.5f
        const val SHADOW_RATIO_MAX = 0.95f
        /** Squared chromaticity distance ((u-128)/y, (v-128)/y) under which a darkening is a shadow. */
        const val SHADOW_CHROMATICITY2 = 0.0036f // 0.06

        /** Exposure estimate: subsample stride, chroma gate, histogram size, clamps. */
        const val OFFSET_STRIDE = 8
        const val CHROMA_MATCH = 24
        const val HIST_HALF = 63
        const val HIST_BUCKETS = HIST_HALF * 2 + 1
        const val MIN_OFFSET_SAMPLES = 400
        const val MAX_OFFSET = 48

        /** Plate learning rate: 1 / 2^LEARN_SHIFT per frame. */
        const val LEARN_SHIFT = 5
        const val LEARN_ENABLED = true
    }
}
