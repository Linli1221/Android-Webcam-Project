package com.soubhagyajit.awa.processing.segmentation

import android.graphics.ImageFormat
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Person / background segmentation for the MJPEG pipeline, backed by ML Kit's selfie segmenter.
 *
 * The bundled artifact is used on purpose (`com.google.mlkit:segmentation-selfie`, ~4.5 MB of model
 * inside the APK) rather than the Play-Services variant, so background removal keeps working on
 * phones without Google Play Services.
 *
 * **It is asynchronous and lags by a frame or two.** Inference takes ~25-65 ms, which is most of a
 * 30 fps frame budget, so waiting for it inside the analyzer would simply halve the frame rate.
 * Instead every analyzer frame submits a copy and composites against the newest mask available,
 * which is the standard trade for real-time matting: a 1-2 frame delay on fast motion, no frame
 * drop. Frames that arrive while a request is still in flight are skipped rather than queued -
 * queues only add latency here.
 *
 * The mask is published already rotated into sensor orientation (see [MaskWarp]) and temporally
 * smoothed, because raw per-frame masks flicker on hair and shoulders badly enough to be
 * distracting in a video call.
 */
class PersonSegmenter {

    /** A mask aligned with the *sensor* frame, `width x height`, 0 = background, 255 = person. */
    class Grid(val data: ByteArray, val width: Int, val height: Int, val stamp: Int)

    /** Newest mask, or null before the first inference finishes. */
    @Volatile
    var grid: Grid? = null
        private set

    /** Temporal smoothing, 0 = follow every mask, 0.9 = very still. */
    @Volatile
    var smoothing: Float = 0.35f

    @Volatile
    var unavailable: Boolean = false
        private set

    /** Frames since the current mask was produced; the caller can use it to detect a stuck model. */
    @Volatile
    var framesSinceMask: Int = 0
        private set

    private val inFlight = AtomicBoolean(false)
    private var client: Segmenter? = null
    private var input = ByteArray(0)
    private var rawMask = ByteArray(0)
    private var accum = IntArray(0)
    private var lastRotation = 0
    private var stamp = 0

    /**
     * Set while the warm-up frame is in flight. Its mask describes a grey rectangle, so publishing
     * it would key the whole first frame out; it is dropped instead.
     */
    @Volatile
    private var discardNextResult = false

    /**
     * Loads the model ahead of the first real frame. Inference is what pulls the weights in, and
     * doing that on a grey frame keeps the first keyed frame from being visibly late.
     */
    fun warmUp() {
        if (unavailable) return
        val size = INPUT_SIZE
        val blank = ByteArray(size * size * 3 / 2) { 0x80.toByte() }
        discardNextResult = true
        if (!submit(blank, size, size, 0)) discardNextResult = false
    }

    /**
     * Hands a frame to the segmenter. Returns false when this frame was skipped (a request is
     * already running, no frame can ever be produced, or the backend failed).
     *
     * [nv21] is only read, never retained: the input is resampled into a private buffer first, so
     * the caller may reuse its frame immediately.
     */
    fun submit(nv21: ByteArray, width: Int, height: Int, rotation: Int): Boolean {
        if (unavailable) return false
        if (!inFlight.compareAndSet(false, true)) return false
        framesSinceMask++

        val segmenter = ensureClient()
        if (segmenter == null) {
            inFlight.set(false)
            return false
        }

        try {
            val size = INPUT_SIZE
            val need = size * size * 3 / 2
            if (input.size < need) input = ByteArray(need)
            MaskWarp.prepareInput(nv21, width, height, rotation, size, input)
            lastRotation = rotation

            val image = InputImage.fromByteBuffer(
                ByteBuffer.wrap(input),
                size,
                size,
                0, // already rotated by prepareInput, so the mask matches this buffer exactly
                ImageFormat.NV21,
            )

            segmenter.process(image)
                .addOnSuccessListener { mask -> runCatching { publish(mask) }.onFailure { log(it) } }
                .addOnFailureListener { e -> Log.w(TAG, "Segmentation failed", e) }
                .addOnCompleteListener { inFlight.set(false) }
        } catch (t: Throwable) {
            // Missing model, missing class after shrinking, a driver crash: give up permanently and
            // let the caller fall back instead of paying for an exception on every frame.
            inFlight.set(false)
            unavailable = true
            Log.e(TAG, "Selfie segmentation is not usable on this build", t)
            return false
        }
        return true
    }

    fun close() {
        runCatching { client?.close() }.onFailure { log(it) }
        client = null
        grid = null
    }

    private fun ensureClient(): Segmenter? {
        if (unavailable) return null
        client?.let { return it }
        return try {
            val options = SelfieSegmenterOptions.Builder()
                // STREAM_MODE reuses the previous frame inside the model, which is what makes a
                // video look stable; SINGLE_IMAGE_MODE would flicker.
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                // Keep the mask at the model's own resolution (256x256) and do the upsampling here,
                // combined with the feather and the compositing, instead of paying for two passes.
                .enableRawSizeMask()
                .build()
            val created = Segmentation.getClient(options)
            client = created
            created
        } catch (t: Throwable) {
            unavailable = true
            Log.e(TAG, "Could not create the segmenter", t)
            null
        }
    }

    /** Runs on the ML Kit thread. */
    private fun publish(mask: SegmentationMask) {
        if (discardNextResult) {
            discardNextResult = false
            return
        }

        val mw = mask.width
        val mh = mask.height
        if (mw < 2 || mh < 2) return

        val n = mw * mh
        if (accum.size != n) {
            accum = IntArray(n)
            rawMask = ByteArray(n)
        }

        val buffer = mask.buffer
        if (buffer.capacity() < n) {
            Log.w(TAG, "Mask buffer is ${buffer.capacity()} bytes, expected $n")
            return
        }

        // --- 1. temporal smoothing, in 8.8 fixed point so the average cannot drift downwards ---
        val keep = (smoothing.coerceIn(0f, 0.9f) * 256f).toInt()
        val gain = 256 - keep
        buffer.rewind()
        for (i in 0 until n) {
            val sample = (buffer.get().toInt() and 0xFF) shl 8
            accum[i] = if (keep == 0) sample else (accum[i] * keep + sample * gain) shr 8
            rawMask[i] = (accum[i] shr 8).coerceIn(0, 255).toByte()
        }

        // --- 2. upright -> sensor orientation, so compositing needs no rotation ---
        val data = MaskWarp.toSensorGrid(rawMask, mw, mh, lastRotation, GRID_SIZE)
        stamp++
        framesSinceMask = 0
        grid = Grid(data, GRID_SIZE, GRID_SIZE, stamp)
    }

    private fun log(t: Throwable) = Log.w(TAG, "Segmentation callback failed", t)

    companion object {
        private const val TAG = "AWA-Person"

        /**
         * Inference input, and the resolution ML Kit returns the raw mask at. 256x256 is the
         * model's own size and the minimum ML Kit asks for; smaller loses the silhouette, larger
         * only costs latency.
         */
        const val INPUT_SIZE = 256

        /** Resolution of the published, sensor-aligned grid. */
        const val GRID_SIZE = 256
    }
}
