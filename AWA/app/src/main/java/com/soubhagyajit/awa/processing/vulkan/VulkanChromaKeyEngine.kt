package com.soubhagyajit.awa.processing.vulkan

import android.content.res.AssetManager
import android.util.Log
import com.soubhagyajit.awa.processing.ChromaKeyProcessor

/**
 * Vulkan compute backend for the keyer.
 *
 * Same maths as [ChromaKeyProcessor], one dispatch over the luma plane and one over the chroma
 * plane, so the picture does not change when the backend does. The class owns its frame buffer and
 * never allocates in the steady state; [process] returns null to mean "I cannot do this frame",
 * which is the signal for the caller to use the CPU keyer instead.
 *
 * Not thread safe by design: the camera analyzer calls it from one thread, and the facade serialises
 * the two entry points. It is marked internal because the CPU keyer must stay the only public way
 * to key a frame.
 */
internal class VulkanChromaKeyEngine(assets: AssetManager?) {

    private val lock = Any()

    private var handle = 0L
    private var output = ByteArray(0)
    private val stats = IntArray(4)

    private var frameCounter = 0
    private var keyCb = 43f
    private var keyCr = 21f
    private var blueKey = false
    private var seedColor = Int.MIN_VALUE

    private var appliedLook: Look? = null

    @Volatile
    var deviceName: String = ""
        private set

    @Volatile
    var available: Boolean = false
        private set

    init {
        open(assets)
    }

    private fun open(assets: AssetManager?) {
        if (assets == null || !VulkanChromaKey.isLoaded) return
        handle = try {
            VulkanChromaKey.nativeCreate(assets)
        } catch (t: Throwable) {
            Log.w(TAG, "Vulkan initialisation failed", t)
            0L
        }
        if (handle == 0L) return
        deviceName = runCatching { VulkanChromaKey.nativeDeviceName(handle) }.getOrDefault("")
        available = true
        Log.i(TAG, "GPU keyer on $deviceName")
    }

    fun close() {
        synchronized(lock) {
            if (handle != 0L) {
                runCatching { VulkanChromaKey.nativeDestroy(handle) }
                handle = 0L
            }
            available = false
        }
    }

    /**
     * @return the keyed frame, or null when the GPU path is not usable for this frame.
     */
    fun process(
        src: ByteArray,
        width: Int,
        height: Int,
        config: ChromaKeyProcessor.Config,
    ): ByteArray? {
        if (!available) return null
        // The shaders work on 32-bit words of the NV21 buffer, so odd geometry is not supported.
        if (width % 4 != 0 || height % 2 != 0 || width < 16 || height < 16) return null
        val bytes = width * height * 3 / 2
        if (src.size < bytes) return null

        synchronized(lock) {
            if (!available) return null
            if (output.size < bytes) output = ByteArray(bytes)

            if (applyConfig(config) == null) return null

            // Sampling the screen every few frames, exactly like the CPU keyer does.
            val sample = config.autoKey && frameCounter++ % KEY_REFRESH_FRAMES == 0
            val ok = runCatching {
                VulkanChromaKey.nativeProcess(
                    handle, src, output, width, height, if (sample) stats else null,
                )
            }.getOrDefault(false)

            if (!ok) {
                // A dead driver must not cost a frame every time: switch the backend off for good.
                Log.e(TAG, "Dispatch failed, falling back to the CPU keyer")
                available = false
                return null
            }

            if (sample) updateKey()
            return output
        }
    }

    /** Pushes the key colour and the sliders; returns null when the native call failed. */
    private fun applyConfig(config: ChromaKeyProcessor.Config): Look? {
        if (seedColor != config.keyColor) {
            seedColor = config.keyColor
            keyCb = keyChromaU(config.keyColor).toFloat()
            keyCr = keyChromaV(config.keyColor).toFloat()
            blueKey = keyCb > 128f
            appliedLook = null
        }

        val look = Look(
            cb = keyCb,
            cr = keyCr,
            similarity = config.similarity.coerceIn(0f, 1f),
            smoothness = config.smoothness.coerceIn(0f, 0.6f),
            spill = config.spill.coerceIn(0f, 1f),
            blue = blueKey,
        )
        if (look == appliedLook) return look

        val ok = runCatching {
            VulkanChromaKey.nativeSetLook(
                handle, look.cb, look.cr, look.similarity, look.smoothness, look.spill, look.blue,
            )
        }.isSuccess
        if (!ok) {
            available = false
            return null
        }
        appliedLook = look
        return look
    }

    /**
     * Uploads the background planes that [com.soubhagyajit.awa.processing.ChromaKey] prepared: the
     * CPU keyer already crops the picture to the frame aspect and converts it, so both backends
     * share that work instead of doing it twice.
     *
     * Must be called before the first frame of a new size, because resizing the GPU buffers also
     * clears the background.
     */
    fun setBackground(
        y: ByteArray,
        v: ByteArray,
        u: ByteArray,
        width: Int,
        height: Int,
    ): Boolean {
        if (!available) return false
        return synchronized(lock) {
            runCatching {
                VulkanChromaKey.nativeSetBackground(handle, y, v, u, width, height)
            }.getOrDefault(false)
        }
    }

    private fun updateKey() {
        val hits = stats[2]
        val visited = stats[3]
        if (hits <= 0 || visited < 32 || hits * 5 < visited) return
        val meanCb = stats[0].toFloat() / hits
        val meanCr = stats[1].toFloat() / hits
        keyCb = (keyCb * 3f + meanCb) / 4f
        keyCr = (keyCr * 3f + meanCr) / 4f
    }

    private data class Look(
        val cb: Float,
        val cr: Float,
        val similarity: Float,
        val smoothness: Float,
        val spill: Float,
        val blue: Boolean,
    )

    companion object {
        private const val TAG = "AWA-Vulkan"
        private const val KEY_REFRESH_FRAMES = 8

        fun keyChromaU(color: Int): Int {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return (128f - 0.168736f * r - 0.331264f * g + 0.5f * b).toInt().coerceIn(0, 255)
        }

        fun keyChromaV(color: Int): Int {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return (128f + 0.5f * r - 0.418688f * g - 0.081312f * b).toInt().coerceIn(0, 255)
        }
    }
}
