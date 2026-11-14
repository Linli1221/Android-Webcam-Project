package com.soubhagyajit.awa.processing.vulkan

import android.content.res.AssetManager
import android.util.Log

/**
 * JNI surface of the Vulkan compute keyer (`app/src/main/cpp/chroma_vulkan.cpp`).
 *
 * Deliberately public rather than internal: Kotlin mangles the names of internal declarations, and
 * the native symbols are looked up by name, so this must stay a plain `Java_<pkg>_<class>_<method>`.
 *
 * The library is optional at runtime: a device without a Vulkan driver, a build where the native
 * module was not compiled, or a driver that rejects the shaders all end up with [isLoaded] false or
 * a null handle, and the caller keeps using the CPU keyer.
 */
object VulkanChromaKey {

    private const val TAG = "AWA-Vulkan"

    val isLoaded: Boolean

    init {
        isLoaded = try {
            System.loadLibrary("awa_chromakey")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Native library unavailable, the CPU keyer will be used", t)
            false
        }
    }

    /** @return an opaque handle, or 0 when Vulkan cannot be initialised. */
    external fun nativeCreate(assets: AssetManager): Long

    external fun nativeDestroy(handle: Long)

    external fun nativeDeviceName(handle: Long): String

    /**
     * Chroma of the key colour plus the look sliders. [similarity] and [smoothness] are the same
     * 0..1 values the CPU keyer uses; the shader scales them to chroma units itself.
     */
    external fun nativeSetLook(
        handle: Long,
        keyCb: Float,
        keyCr: Float,
        similarity: Float,
        smoothness: Float,
        spill: Float,
        blueKey: Boolean,
    )

    /** Background in plane form. The native side stores it as Y | V | U and crops nothing. */
    external fun nativeSetBackground(
        handle: Long,
        y: ByteArray,
        v: ByteArray,
        u: ByteArray,
        width: Int,
        height: Int,
    ): Boolean

    /**
     * Keyed [src] (NV21) into [dst]. When [statsOut] is not null the shader also samples the frame
     * border for the key colour and writes `[sumCb, sumCr, hits, visited]` into it.
     *
     * @return false when the frame could not be processed, i.e. "fall back to the CPU keyer".
     */
    external fun nativeProcess(
        handle: Long,
        src: ByteArray,
        dst: ByteArray,
        width: Int,
        height: Int,
        statsOut: IntArray?,
    ): Boolean
}
