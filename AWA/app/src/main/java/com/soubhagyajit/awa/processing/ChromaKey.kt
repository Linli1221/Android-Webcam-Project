package com.soubhagyajit.awa.processing

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import com.soubhagyajit.awa.processing.vulkan.VulkanChromaKeyEngine

/**
 * Front end of the on-device keyer.
 *
 * Two interchangeable backends produce the same picture:
 *
 *  - **Vulkan** ([VulkanChromaKeyEngine]) runs the keyer as two compute dispatches and is the
 *    default. It only handles NV21 frames whose width is a multiple of 4 (the shaders work on
 *    32-bit words, see `app/src/main/cpp/shaders`).
 *  - **CPU** ([ChromaKeyProcessor]) is the reference implementation and the fallback. It is used
 *    when the device has no Vulkan driver, when the native module is missing, when the driver
 *    rejects a dispatch, or when the user turns the GPU path off.
 *
 * Falling back is silent by design: a keyed stream that suddenly turns into an unkeyed one would be
 * worse than a slower one, and the settings panel shows which backend is live.
 */
class ChromaKey(
    /**
     * Injectable so [BackgroundRemover] can share one processor - with its background plane cache -
     * between the chroma path and the segmentation path.
     */
    private val cpu: ChromaKeyProcessor = ChromaKeyProcessor(),
) {

    /** Set once from the main thread, read from the analyzer thread. */
    @Volatile
    private var engine: VulkanChromaKeyEngine? = null

    private var backgroundVersion = 0
    private var uploadedFor = 0L
    private var uploadedStamp = -1

    @Volatile
    var config: ChromaKeyProcessor.Config = ChromaKeyProcessor.Config()
        set(value) {
            field = value
            cpu.config = value
        }

    /** True when the GPU path is currently doing the work (i.e. not falling back). */
    val isVulkanActive: Boolean
        get() = engine?.available == true && config.useGpu

    /** Shown in the settings panel so it is obvious which backend is running. */
    val backendLabel: String
        get() {
            val gpu = engine
            return if (gpu != null && gpu.available) {
                if (config.useGpu) "Vulkan · ${gpu.deviceName}" else "CPU (GPU off)"
            } else {
                "CPU"
            }
        }

    val isActive: Boolean
        get() = config.enabled

    val hasBackgroundImage: Boolean
        get() = cpu.hasBackgroundImage

    /**
     * Called once the application context is available. The Vulkan device is created here rather
     * than in the constructor because the asset manager is needed to load the SPIR-V blobs.
     */
    fun attachAssets(assets: AssetManager?) {
        if (engine != null || assets == null) return
        engine = VulkanChromaKeyEngine(assets)
        uploadedFor = 0L
        uploadedStamp = -1
        Log.i(TAG, "Backend: $backendLabel")
    }

    fun setBackgroundImage(bitmap: Bitmap?) {
        cpu.setBackgroundImage(bitmap)
        backgroundVersion++
    }

    fun clearBackgroundImage() = setBackgroundImage(null)

    /**
     * Keyes [src] (NV21, `width * height * 3 / 2` bytes). The returned array is reused for the next
     * frame, so copy it if it has to outlive the call.
     */
    fun process(src: ByteArray, width: Int, height: Int): ByteArray {
        val cfg = config
        if (!cfg.enabled) return src

        val gpu = engine
        if (cfg.useGpu && gpu != null && gpu.available && ensureBackground(gpu, width, height, cfg)) {
            val out = gpu.process(src, width, height, cfg)
            if (out != null) return out
        }
        return cpu.process(src, width, height)
    }

    fun close() {
        engine?.close()
        engine = null
    }

    /**
     * Uploads the background whenever the picture, its colour or the frame size changed. The planes
     * come from the CPU keyer so the crop/scale logic exists exactly once.
     *
     * @return false when the GPU buffers cannot hold this frame's background; the caller then uses
     *         the CPU keyer for it rather than compositing against an empty background.
     */
    private fun ensureBackground(
        gpu: VulkanChromaKeyEngine,
        width: Int,
        height: Int,
        cfg: ChromaKeyProcessor.Config,
    ): Boolean {
        if (width % 4 != 0 || height % 2 != 0) return false
        val frame = (width.toLong() shl 32) or height.toLong()
        val stamp = stamp(cfg)
        if (frame == uploadedFor && stamp == uploadedStamp) return true

        val planes = cpu.backgroundPlanes(width, height)
        if (!gpu.setBackground(planes.y, planes.v, planes.u, width, height)) return false
        uploadedFor = frame
        uploadedStamp = stamp
        return true
    }

    private fun stamp(cfg: ChromaKeyProcessor.Config): Int =
        31 * (31 * backgroundVersion + cfg.backgroundColor) +
            if (cfg.useImageBackground) 1 else 0

    private companion object {
        const val TAG = "AWA-Chroma"
    }
}
