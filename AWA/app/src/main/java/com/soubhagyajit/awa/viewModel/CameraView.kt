package com.soubhagyajit.awa.viewModel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pedro.common.ConnectChecker
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera2
import com.soubhagyajit.awa.processing.BackgroundRemover
import com.soubhagyajit.awa.processing.ChromaKeyProcessor
import com.soubhagyajit.awa.processing.PlateSubtractor
import com.soubhagyajit.awa.server.AwaDiscovery
import com.soubhagyajit.awa.server.VideoStreamServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Widest frame accepted from the sensor. Nothing advertises more than 8K for YUV output, so this
 * only filters nonsense entries.
 */
private const val MAX_FRAME_WIDTH = 8192

/**
 * Biggest frame carried through the MJPEG path. A frame is 1.5 bytes per pixel as NV21 and the
 * pipeline holds two or three copies plus the JPEG buffer while encoding, so 12 MP is about 60 MB
 * of peak allocation - the point where a mid-range phone starts to thrash, not a hard limit.
 */
private const val NATIVE_MAX_PIXELS = 12_000_000

class CameraViewModel : ViewModel() {

    enum class StreamMode(val label: String) {
        MJPEG("MJPEG"),
        H264_RTSP("H.264 (RTSP)")
    }

    /** A stream size. The available ones are read from the camera (see queryResolutions). */
    data class StreamResolution(val size: Size) {
        val key: String get() = "${size.width}x${size.height}"

        val label: String
            get() = when (size.width to size.height) {
                640 to 480 -> "$key (480p)"
                1280 to 720 -> "$key (720p)"
                1920 to 1080 -> "$key (1080p)"
                3840 to 2160 -> "$key (4K)"
                // Everything else is a size the sensor itself offers, so megapixels tell the user
                // more than a "p" number that does not exist.
                else -> "$key (%.1fMP)".format(size.width * size.height / 1e6f)
            }

        /** Sensor pixels in millions, for the "this is going to cost you" hints. */
        val megapixels: Float get() = size.width * size.height / 1e6f

        /** CameraX only understands 4:3 and 16:9, so use whichever is closer. */
        val aspectRatio: Int
            get() {
                val r = size.width.toFloat() / size.height
                return if (abs(r - 4f / 3f) <= abs(r - 16f / 9f)) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
            }

        companion object {
            val P720 = StreamResolution(Size(1280, 720))
            val P1080 = StreamResolution(Size(1920, 1080))
            // Used only until the camera has been queried, or if the query fails
            val FALLBACK = listOf(StreamResolution(Size(640, 480)), P720, P1080)
        }
    }

    enum class FocusMode { AUTO, MANUAL }

    data class CameraSettings(
        val resolution: StreamResolution = StreamResolution.P720,
        // CHANGED: resolutions the bound camera can actually deliver in MJPEG (CameraX) mode.
        // Defaults to everything until the first bindCamera() computes the real list.
        val supportedResolutions: List<StreamResolution> = StreamResolution.FALLBACK,
        /**
         * Largest size this camera advertises for the mode in use - the sensor's own frame, not a
         * size the video encoder happens to like. Null until the camera has been queried.
         */
        val nativeResolution: StreamResolution? = null,
        // Size of the frames the camera is really delivering (null until the first frame arrives)
        val activeSize: Size? = null,
        // Correction for cameras whose 1080p output is stretched. 1.0 = off.
        // >1: image looks too wide (zoom vertically). <1: image looks too narrow (zoom horizontally).
        // Only applied while 1080p is selected.
        val stretchFix: Float = 1f,
        val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
        val focusMode: FocusMode = FocusMode.AUTO,
        val focusDistance: Float = 0f, // 0.0 = infinity, 1.0 = closest
        val exposureIndex: Int = 0,
        val exposureRange: IntRange = 0..0,
        val jpegQuality: Int = 80,
        val hasFlashUnit: Boolean = false,
        val isFlashEnabled: Boolean = false,
        val zoom: Float = 1.0f,
        val zoomMin: Float = 1.0f,
        val zoomMax: Float = 1.0f,
        // --- background removal (MJPEG mode, applied on the phone before JPEG encoding) ---
        val chromaKeyEnabled: Boolean = false,
        /** Which engine removes the background: a coloured screen, a person mask, or auto. */
        val backgroundMode: BackgroundRemover.Mode = BackgroundRemover.Mode.AUTO,
        val chromaKeyColor: Int = 0xFF00FF00.toInt(),
        val chromaSimilarity: Float = 0.28f,
        val chromaSmoothness: Float = 0.12f,
        val chromaSpill: Float = 0.45f,
        /** Foreground cut of the segmentation mask, 0..1. */
        val personThreshold: Float = 0.5f,
        /** Soft edge applied to the segmentation mask, 0..0.5. */
        val personFeather: Float = 0.2f,
        /** Temporal smoothing of the segmentation mask, 0..0.9. */
        val personSmooth: Float = 0.35f,
        // --- background plate: shot the empty scene, cut what differs (camera must stay put) ---
        /** How much of the scene the plate removes, 0..1. */
        val plateSensitivity: Float = 0.5f,
        /** Soft edge width of the plate matte, 0..1.5. */
        val plateFeather: Float = 0.35f,
        /** Temporal smoothing of the plate matte, 0..0.9. */
        val plateSmooth: Float = 0.4f,
        val chromaBackgroundColor: Int = 0xFF101018.toInt(),
        val chromaUseImageBackground: Boolean = false,
        val chromaBackgroundLabel: String? = null,
        /** Use the Vulkan compute keyer when the device supports it (falls back to the CPU). */
        val chromaGpu: Boolean = true,
    )

    @Volatile
    var currentFrame: ByteArray? = null
        private set

    // Diagnostics: how many frames the camera/analyzer produces (only touched on the analyzer thread)
    @Volatile private var lastReportedSize: Size? = null
    private var analyzerFrames = 0
    private var analyzerBusyNs = 0L
    private var analyzerWindowStart = System.nanoTime()

    private var mjpegCamera: androidx.camera.core.Camera? = null
    private var rtspCamera: RtspServerCamera2? = null
    private var appContext: Context? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    private val _isPreviewActive = mutableStateOf(false)
    val isPreviewActive: State<Boolean> = _isPreviewActive

    private val _isServerRunning = mutableStateOf(true)
    val isServerRunning: State<Boolean> = _isServerRunning

    private val _showLocalPreview = mutableStateOf(false)
    val showLocalPreview: State<Boolean> = _showLocalPreview
    private var hidePreviewRunnable: Runnable? = null
    private val previewPeekDurationMs = 50000L

    private val _streamMode = mutableStateOf(StreamMode.MJPEG)
    val streamMode: State<StreamMode> = _streamMode

    // Galaxy J6 (SM-J600*) delivers 1080p squeezed from the 4:3 sensor, so start with 16:9 / 4:3.
    // Adjustable at runtime from the settings panel.
    private fun defaultStretchFix(): Float =
        if (Build.MODEL.startsWith("SM-J600")) 4f / 3f else 1f

    private val _settings = mutableStateOf(CameraSettings(stretchFix = defaultStretchFix()))
    val settings: State<CameraSettings> = _settings

    /** Runs on the analyzer thread; owns its buffers, so no allocation per frame. */
    private val background = BackgroundRemover()

    /** Which backend is actually removing the background (Vulkan, CPU, ML Kit). */
    private val _chromaBackend = mutableStateOf("CPU")
    val chromaBackend: State<String> = _chromaBackend

    /** NONE / CAPTURING / READY of the background plate, mirrored to the UI thread. */
    private val _plateState = mutableStateOf(PlateSubtractor.State.NONE)
    val plateState: State<PlateSubtractor.State> = _plateState

    /** Written on the analyzer thread only, so the mirror above is posted just when it changes. */
    @Volatile
    private var plateStateShadow = PlateSubtractor.State.NONE

    private fun refreshBackendLabel() {
        _chromaBackend.value = background.backendLabel
        plateStateShadow = background.plateState
        _plateState.value = plateStateShadow
    }

    /** Mirrors the current settings into the processor (called from the main thread only). */
    private fun syncChromaConfig() {
        val s = _settings.value
        background.mode = s.backgroundMode
        background.config = ChromaKeyProcessor.Config(
            enabled = s.chromaKeyEnabled,
            keyColor = s.chromaKeyColor,
            similarity = s.chromaSimilarity,
            smoothness = s.chromaSmoothness,
            spill = s.chromaSpill,
            backgroundColor = s.chromaBackgroundColor,
            useImageBackground = s.chromaUseImageBackground,
            useGpu = s.chromaGpu,
            maskThreshold = s.personThreshold,
            maskFeather = s.personFeather,
            maskSmoothing = s.personSmooth,
            plateSensitivity = s.plateSensitivity,
            plateFeather = s.plateFeather,
            plateSmoothing = s.plateSmooth,
        )
        refreshBackendLabel()
    }

    fun setChromaKeyEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(chromaKeyEnabled = enabled)
        syncChromaConfig()
        // Show what the stream looks like straight away, so the sliders can be tuned on the phone.
        if (enabled) notifyScreenTapped()
    }

    fun setBackgroundMode(mode: BackgroundRemover.Mode) {
        _settings.value = _settings.value.copy(backgroundMode = mode)
        syncChromaConfig()
        if (_settings.value.chromaKeyEnabled) notifyScreenTapped()
    }

    fun setPersonThreshold(value: Float) {
        _settings.value = _settings.value.copy(personThreshold = value.coerceIn(0.1f, 0.9f))
        syncChromaConfig()
    }

    fun setPersonFeather(value: Float) {
        _settings.value = _settings.value.copy(personFeather = value.coerceIn(0.01f, 0.5f))
        syncChromaConfig()
    }

    fun setPersonSmooth(value: Float) {
        _settings.value = _settings.value.copy(personSmooth = value.coerceIn(0f, 0.9f))
        syncChromaConfig()
    }

    fun setPlateSensitivity(value: Float) {
        _settings.value = _settings.value.copy(plateSensitivity = value.coerceIn(0.1f, 0.9f))
        syncChromaConfig()
    }

    fun setPlateFeather(value: Float) {
        _settings.value = _settings.value.copy(plateFeather = value.coerceIn(0f, 1.5f))
        syncChromaConfig()
    }

    fun setPlateSmooth(value: Float) {
        _settings.value = _settings.value.copy(plateSmooth = value.coerceIn(0f, 0.9f))
        syncChromaConfig()
    }

    /**
     * Shoots a new background plate: switches to the plate engine (so the shot is taken even if the
     * user was in another mode) and asks the analyzer for the next frames. The user has to be out of
     * the shot for those two or three frames - the panel says so.
     */
    fun capturePlate() {
        // Capturing only makes sense if the engine is going to run, so both switches are turned on
        // here: a plate without background removal would just never be used.
        val current = _settings.value
        if (!current.chromaKeyEnabled || current.backgroundMode != BackgroundRemover.Mode.PLATE) {
            _settings.value = current.copy(
                chromaKeyEnabled = true,
                backgroundMode = BackgroundRemover.Mode.PLATE,
            )
            syncChromaConfig()
        }
        background.startPlateCapture()
        // The analyzer keeps encoding while frames are needed, but the panel should still show the
        // result of the shot without a second tap.
        notifyScreenTapped()
        refreshBackendLabel()
    }

    /** Drops the plate, e.g. after the phone was moved. */
    fun clearPlate() {
        background.clearPlate()
        refreshBackendLabel()
    }

    fun setChromaKeyColor(color: Int) {
        _settings.value = _settings.value.copy(chromaKeyColor = color)
        syncChromaConfig()
    }

    fun setChromaSimilarity(value: Float) {
        _settings.value = _settings.value.copy(chromaSimilarity = value.coerceIn(0.05f, 0.9f))
        syncChromaConfig()
    }

    fun setChromaKeySmoothness(value: Float) {
        _settings.value = _settings.value.copy(chromaSmoothness = value.coerceIn(0f, 0.5f))
        syncChromaConfig()
    }

    fun setChromaSpill(value: Float) {
        _settings.value = _settings.value.copy(chromaSpill = value.coerceIn(0f, 1f))
        syncChromaConfig()
    }

    /**
     * Switches between the Vulkan compute keyer and the CPU one. The GPU path silently falls back
     * to the CPU anyway, so this is for comparing the two or working around a driver bug.
     */
    fun setChromaGpu(enabled: Boolean) {
        _settings.value = _settings.value.copy(chromaGpu = enabled)
        syncChromaConfig()
    }

    fun setChromaBackgroundColor(color: Int) {
        _settings.value = _settings.value.copy(
            chromaBackgroundColor = color,
            chromaUseImageBackground = false,
            chromaBackgroundLabel = null,
        )
        background.clearBackgroundImage()
        syncChromaConfig()
    }

    /**
     * Loads a still picture and uses it as the replacement background. The content URI grant from
     * the picker only lives as long as the process, so the picture is kept in memory, not on disk.
     */
    fun setChromaBackgroundImage(uri: Uri) {
        val ctx = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bitmap = decodeBackground(ctx, uri)
                if (bitmap == null) {
                    Log.e("AWA", "Could not decode background image $uri")
                    return@launch
                }
                background.setBackgroundImage(bitmap)
                withContext(Dispatchers.Main) {
                    _settings.value = _settings.value.copy(
                        chromaUseImageBackground = true,
                        chromaBackgroundLabel = "Custom image",
                    )
                    syncChromaConfig()
                }
            } catch (e: Exception) {
                Log.e("AWA", "Failed to load background image", e)
            }
        }
    }

    private fun decodeBackground(ctx: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // The planes are built per frame size anyway, so a 4K photo would only waste memory.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1920) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private val viewerCount = AtomicInteger(0)
    private val jpegViewerCount = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null

    private var openGlView: OpenGlView? = null
    private val rtspPort = 8554
    private val rtspUrl = "rtsp://192.168.31.30:8554/awa"
    private val rtspBitrate = 16_000_000 // 16 Mbps default

    /** Resolutions offered for the current camera and stream mode (kept up to date by refreshResolutions). */
    private fun resolutionsForCurrentMode(): List<StreamResolution> = _settings.value.supportedResolutions

    private fun isUsableSize(s: Size): Boolean {
        if (s.width < 640 || s.width > MAX_FRAME_WIDTH || s.height <= 0) return false
        val r = s.width.toFloat() / s.height
        return abs(r - 4f / 3f) < 0.02f || abs(r - 16f / 9f) < 0.02f
    }

    /**
     * Reads the sizes this camera can output, smallest first. Only 4:3 and 16:9 sizes that are at
     * least 640 wide are kept, because those are the shapes CameraX can be asked for.
     * Preview and video-recording sizes are always included, so sizes that are only offered for
     * video (for example 1080p on older phones) still show up.
     *
     * In MJPEG mode the list goes up to the sensor's own largest size (12 MP at most, see
     * [NATIVE_MAX_PIXELS]) rather than stopping at the camcorder profile - that cap belongs to the
     * hardware video encoder, and the MJPEG path never touches it. In RTSP mode the cap stays,
     * because there the encoder really does decide what it will take.
     */
    private fun queryResolutions(lensFacing: Int, forRtsp: Boolean): List<StreamResolution> {
        val ctx = appContext ?: return StreamResolution.FALLBACK
        return try {
            val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val wanted = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraMetadata.LENS_FACING_BACK else CameraMetadata.LENS_FACING_FRONT
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
            } ?: return StreamResolution.FALLBACK
            val map = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return StreamResolution.FALLBACK

            val preview = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            val video = map.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
            val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            val hwLevel = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            Log.d(
                "AWA",
                "Camera2 sizes id=$id hwLevel=$hwLevel (0=LIMITED 1=FULL 2=LEGACY 3=LEVEL_3)\n" +
                        "  preview=${preview.joinToString()}\n  video=${video.joinToString()}\n  yuv=${yuv.joinToString()}"
            )

            // The camera lists sizes it cannot stream (for example 4128x3096 on a LIMITED camera).
            // The largest size the *video encoder* can stream alongside a preview is its biggest
            // camcorder profile (CameraX's "RECORD" size). That is the right cap for RTSP and no
            // cap at all for MJPEG, which is where the sensor's own sizes become available.
            val recordMax: Size? = try {
                val camId = id.toInt()
                if (CamcorderProfile.hasProfile(camId, CamcorderProfile.QUALITY_HIGH)) {
                    CamcorderProfile.get(camId, CamcorderProfile.QUALITY_HIGH)
                        ?.let { Size(it.videoFrameWidth, it.videoFrameHeight) }
                } else null
            } catch (e: Exception) {
                null
            }
            val profileArea = recordMax?.let { it.width * it.height } ?: Int.MAX_VALUE
            Log.d("AWA", "Largest streamable size (camcorder profile): $recordMax")

            val raw = HashSet<Size>()
            raw.addAll(preview)
            raw.addAll(video)
            if (!forRtsp) raw.addAll(yuv)

            val pixelCap = if (forRtsp) profileArea else NATIVE_MAX_PIXELS
            val list = raw.filter { isUsableSize(it) && it.width * it.height <= pixelCap }
                .map { StreamResolution(it) }
                .sortedBy { it.size.width * it.size.height }
            val tooBig = raw.filter { isUsableSize(it) && it.width * it.height > pixelCap }
            if (tooBig.isNotEmpty()) {
                Log.d("AWA", "Left out, too large for this path: ${tooBig.sortedBy { it.width * it.height }.joinToString()}")
            }
            Log.d(
                "AWA",
                "Resolutions (${if (lensFacing == CameraSelector.LENS_FACING_BACK) "back" else "front"}, " +
                        "${if (forRtsp) "RTSP" else "MJPEG"}): ${list.joinToString { it.key }}"
            )
            list.ifEmpty { StreamResolution.FALLBACK }
        } catch (e: Exception) {
            Log.e("AWA", "Could not read camera resolutions", e)
            StreamResolution.FALLBACK
        }
    }

    /** Re-reads the resolution list for the current camera and mode, and keeps the selection valid. */
    private fun refreshResolutions() {
        val list = queryResolutions(
            _settings.value.lensFacing,
            _streamMode.value == StreamMode.H264_RTSP
        )
        val current = _settings.value.resolution
        // Don't change the size under a running RTSP stream
        val chosen = if (current in list || rtspCamera != null) {
            current
        } else {
            val area = current.size.width * current.size.height
            list.minByOrNull { abs(it.size.width * it.size.height - area) } ?: current
        }
        _settings.value = _settings.value.copy(
            supportedResolutions = list,
            resolution = chosen,
            // The list is sorted smallest first, so its last entry is what this camera calls its
            // own frame. In MJPEG that is the sensor size, in RTSP the biggest the encoder takes.
            nativeResolution = list.lastOrNull(),
        )
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        // Creates the Vulkan device up front, so the first keyed frame is not the one that pays
        // for it (and so the settings panel can show which backend is in use).
        background.attachAssets(appContext?.assets)
        refreshBackendLabel()
        refreshResolutions()

        // 1. Viewer Connection Callbacks
        VideoStreamServer.onUserConnected = {
            jpegViewerCount.incrementAndGet()
            if (viewerCount.incrementAndGet() == 1 && _isServerRunning.value && _streamMode.value == StreamMode.MJPEG) {
                viewModelScope.launch(Dispatchers.Main) {
                    _isPreviewActive.value = true
                    bindCamera()
                }
            }
        }

        VideoStreamServer.onUserDisconnected = {
            jpegViewerCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            if (viewerCount.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) {
                viewModelScope.launch(Dispatchers.Main) {
                    _isPreviewActive.value = false
                    unbindCamera()
                }
            }
        }

        // 2. Features Provider
        VideoStreamServer.featuresProvider = {
            val s = _settings.value
            val currentMode = _streamMode.value
            val maxZoom = getMaxZoom()
            val minZoom = getMinZoom()
            VideoStreamServer.FeaturesResponse(
                // CHANGED: only advertise resolutions the camera can really deliver
                resolutions = resolutionsForCurrentMode().map { it.key },
                manual_focus = true,
                exposure_lower = s.exposureRange.first,
                exposure_upper = s.exposureRange.last,
                stream_protocol = currentMode,
                server_port = 8080, // TODO - Give users option to modify it
                zoom_max = maxZoom,
                zoom_min = minZoom,
                has_zoom = maxZoom > minZoom,
                rtsp_port = if (currentMode == StreamMode.H264_RTSP) rtspPort else null
            )
        }

        // 3. Settings Provider
        VideoStreamServer.settingsProvider = {
            val s = _settings.value
            VideoStreamServer.SettingsResponse(
                camera = if (s.lensFacing == CameraSelector.LENS_FACING_BACK) "back" else "front",
                // Report the size of the frames really being delivered, so the PC client sizes its camera correctly
                resolution_str = (s.activeSize ?: s.resolution.size).let { "${it.width}x${it.height}" },
                resolution_requested = s.resolution.key,
                zoom = s.zoom,
                flash = s.isFlashEnabled,
                exposure_index = s.exposureIndex,
                focus_mode = if (s.focusMode == FocusMode.AUTO) 0 else 1,
                focus_distance = s.focusDistance*1000,
                stream_quality = s.jpegQuality,
                has_flash_unit = s.hasFlashUnit,
                chroma_key = s.chromaKeyEnabled,
                background_mode = s.backgroundMode.id,
                background_engine = _chromaBackend.value,
                chroma_key_color = toHexColor(s.chromaKeyColor),
                chroma_key_similarity = s.chromaSimilarity,
                chroma_key_smoothness = s.chromaSmoothness,
                chroma_key_spill = s.chromaSpill,
                segmentation_threshold = s.personThreshold,
                segmentation_feather = s.personFeather,
                segmentation_smoothing = s.personSmooth,
                plate_sensitivity = s.plateSensitivity,
                plate_feather = s.plateFeather,
                plate_smoothing = s.plateSmooth,
                plate_ready = _plateState.value == PlateSubtractor.State.READY,
                chroma_key_background = if (s.chromaUseImageBackground) "image" else "solid",
                chroma_key_background_color = toHexColor(s.chromaBackgroundColor),
                chroma_key_gpu = _chromaBackend.value.startsWith("Vulkan"),
                chroma_key_backend = _chromaBackend.value,
            )
        }

        // 4. Remote Control Settings Callback
        VideoStreamServer.onSettingsUpdated = { update ->
            val isRtspActive = _streamMode.value == StreamMode.H264_RTSP && rtspCamera != null
            val requestedRes = update.resolution_str?.let { rs ->
                // "native" is the sensor's own frame: whatever this camera calls its biggest size.
                if (rs == "native" || rs == "max") {
                    _settings.value.nativeResolution
                } else {
                    resolutionsForCurrentMode().find { it.key == rs }
                }
            }

            if (update.resolution_str != null && isRtspActive) {
                "Cannot change resolution while RTSP stream is active. Stop the stream first."
            } else if (update.resolution_str != null && requestedRes == null) {
                // Reject sizes this camera does not offer
                "Resolution ${update.resolution_str} is not supported by this camera."
            } else {
                viewModelScope.launch(Dispatchers.Main) {
                    update.switchCamera?.let { if (it) switchCamera() }

                    update.autofocus?.let { enable ->
                        if (enable) {
                            _settings.value = _settings.value.copy(focusMode = FocusMode.AUTO)
                            cancelFocusAndMetering()
                        }
                    }
                    update.focus_distance?.let { dist ->
                        _settings.value = _settings.value.copy(focusMode = FocusMode.MANUAL)
                        val normalized = (dist.coerceIn(1f, 1000f) - 1f) / 999f
                        setFocusDistance(normalized)
                    }
                    update.focus_mode?.let {mode ->
                        Log.d("AWA","$mode")
                        if (mode == 1 ){
                            _settings.value = _settings.value.copy(focusMode = FocusMode.MANUAL)
                        }
                        else if (mode == 0){
                            _settings.value = _settings.value.copy(focusMode = FocusMode.AUTO)
                            cancelFocusAndMetering()
                        }
                    }
                    update.exposure_index?.let { setExposure(it) }

                    update.flash?.let { enable ->
                        if (_streamMode.value == StreamMode.MJPEG && mjpegCamera?.cameraInfo?.hasFlashUnit() == false) {
                            return@let
                        }
                        _settings.value = _settings.value.copy(isFlashEnabled = enable)
                        applyFlash(enable)
                    }

                    update.zoom?.let { setZoom(it) }

                    requestedRes?.let { setResolution(it) }
                    update.camera.let { state ->
                        val requestedFacing = if (state == "back") CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
                        if (_settings.value.lensFacing != requestedFacing) {
                            setCameraFacing(back = state == "back")
                        }
                    }
                    update.stream_quality?.let{value ->
                        _settings.value = _settings.value.copy(jpegQuality = value)
                    }

                    // --- background removal, settable from the desktop client ---
                    update.chroma_key?.let { enable ->
                        _settings.value = _settings.value.copy(chromaKeyEnabled = enable)
                        syncChromaConfig()
                    }
                    update.background_mode?.let { id ->
                        BackgroundRemover.Mode.fromId(id)?.let { setBackgroundMode(it) }
                    }
                    update.chroma_key_color?.let { hex ->
                        parseHexColor(hex)?.let { color ->
                            _settings.value = _settings.value.copy(chromaKeyColor = color)
                            syncChromaConfig()
                        }
                    }
                    update.chroma_key_similarity?.let { setChromaSimilarity(it) }
                    update.chroma_key_smoothness?.let { setChromaKeySmoothness(it) }
                    update.chroma_key_spill?.let { setChromaSpill(it) }
                    update.segmentation_threshold?.let { setPersonThreshold(it) }
                    update.segmentation_feather?.let { setPersonFeather(it) }
                    update.segmentation_smoothing?.let { setPersonSmooth(it) }
                    update.plate_sensitivity?.let { setPlateSensitivity(it) }
                    update.plate_feather?.let { setPlateFeather(it) }
                    update.plate_smoothing?.let { setPlateSmooth(it) }
                    update.plate_capture?.let { if (it) capturePlate() }
                    update.chroma_key_background_color?.let { hex ->
                        parseHexColor(hex)?.let { setChromaBackgroundColor(it) }
                    }
                    update.chroma_key_gpu?.let { setChromaGpu(it) }
                }
                null
            }
        }

        VideoStreamServer.start(8080)
        AwaDiscovery.register(context.applicationContext, 8080, _streamMode.value.name)
    }

    // --- Unified start/stop ---
    fun toggleServer() {
        if (_isServerRunning.value) {
            stopActiveStream()
            _isServerRunning.value = false
        } else {
            startActiveStream()
            _isServerRunning.value = true
        }
    }

    fun setStreamMode(mode: StreamMode) {
        if (_streamMode.value == mode) return
        val wasRunning = _isServerRunning.value
        val switchingFromMjpeg = _streamMode.value == StreamMode.MJPEG

        if (wasRunning) stopActiveStream()
        _streamMode.value = mode
        refreshResolutions()

        if (!wasRunning) return

        if (switchingFromMjpeg && mode == StreamMode.H264_RTSP) {
            // Camera2 device release from CameraX is async — give it
            // a moment before Camera2 (RootEncoder) tries to open it.
            mainHandler.postDelayed({ startActiveStream() }, 300L)
        } else {
            startActiveStream()
        }
    }

    private fun startActiveStream() {
        when (_streamMode.value) {
            StreamMode.MJPEG -> { }
            StreamMode.H264_RTSP -> startRtspCamera()
        }
    }

    private fun stopActiveStream() {
        when (_streamMode.value) {
            StreamMode.MJPEG -> {
                unbindCamera()
                _isPreviewActive.value = false
            }
            StreamMode.H264_RTSP -> stopRtspCamera()
        }
        hideLocalPreview()
    }

    // --- Touch-driven local preview ---

    fun notifyScreenTapped() {
        if (!_isServerRunning.value) return
        _showLocalPreview.value = true
        if (_streamMode.value == StreamMode.MJPEG) {
            startPreviewHideTimer()
        }
    }

    private fun startPreviewHideTimer() {
        hidePreviewRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { hideLocalPreview() }
        hidePreviewRunnable = runnable
        mainHandler.postDelayed(runnable, previewPeekDurationMs)
    }

    private fun hideLocalPreview() {
        hidePreviewRunnable?.let { mainHandler.removeCallbacks(it) }
        hidePreviewRunnable = null
        _showLocalPreview.value = false
    }

    private fun buildConnectChecker() = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            Log.d("AWA", "RTSP: connection started, url=$url")
        }
        override fun onConnectionSuccess() {
            Log.d("AWA", "RTSP: client connected")
            mainHandler.post { _isPreviewActive.value = true }
        }
        override fun onConnectionFailed(reason: String) {
            Log.e("AWA", "RTSP: connection failed - $reason")
        }
        override fun onNewBitrate(bitrate: Long) { }
        override fun onDisconnect() {
            Log.d("AWA", "RTSP: client disconnected")
            mainHandler.post { _isPreviewActive.value = false }
        }
        override fun onAuthError() {
            Log.e("AWA", "RTSP: auth error")
        }
        override fun onAuthSuccess() {
            Log.d("AWA", "RTSP: auth success")
        }
    }

    fun attachPreviewSurface(context: Context, owner: LifecycleOwner, preview: PreviewView) {
        appContext = context.applicationContext
        lifecycleOwner = owner
        previewView = preview
        if (_streamMode.value == StreamMode.MJPEG) bindCamera()
    }

    fun attachRtspPreviewSurface(view: OpenGlView) {
        Log.d("AWA", "Attach rtsp preview called")
        openGlView = view
        try {
            rtspCamera?.replaceView(view)
            rtspCamera?.startPreview()
            _showLocalPreview.value = true
            startPreviewHideTimer()
        } catch (e: Exception) {
            Log.e("AWA", "RTSP replaceView(view) failed", e)
        }
    }

    fun detachRtspPreviewSurface() {
        val ctx = appContext
        val wasAttached = openGlView != null
        openGlView = null
        if (ctx != null && wasAttached) {
            try {
                if (rtspCamera?.isOnPreview == true) {
                    rtspCamera?.stopPreview()
                }
                rtspCamera?.replaceView(ctx)
            } catch (e: Exception) {
                Log.e("AWA", "RTSP replaceView(context) failed", e)
            }
        }
    }

    private fun startRtspCamera() {
        val ctx = appContext ?: return
        val s = _settings.value

        val camera = RtspServerCamera2(ctx, buildConnectChecker(), rtspPort)
        rtspCamera = camera

        val videoOk = try {
            camera.prepareVideo(s.resolution.size.width, s.resolution.size.height, 30, rtspBitrate, 0)
        } catch (e: Exception) {
            Log.e("AWA", "RTSP prepareVideo threw", e)
            false
        }
        val audioOk = camera.prepareAudio()
        Log.d("AWA", "RTSP prepareVideo=$videoOk prepareAudio=$audioOk")

        if (videoOk) {
            val flashAvailable = camera.cameraCharacteristics
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            _settings.value = _settings.value.copy(hasFlashUnit = flashAvailable)

            // Refresh zoom range for the RTSP camera and re-apply stored zoom
            val zoomRange = runCatching { camera.getZoomRange() }.getOrNull()
            val maxZoom = zoomRange?.upper ?: _settings.value.zoomMax
            val minZoom = zoomRange?.lower ?: _settings.value.zoomMin
            val currentZoom = _settings.value.zoom.coerceIn(minZoom, maxZoom)
            _settings.value = _settings.value.copy(zoomMin = minZoom, zoomMax = maxZoom, zoom = currentZoom)
            if (currentZoom != 1f) {
                runCatching { camera.setZoom(currentZoom) }
                    .onFailure { Log.e("AWA", "RTSP zoom reapply failed", it) }
            }

            camera.startStream()
            Log.d("AWA", "RTSP server started on port $rtspPort")
            openGlView?.let { view ->
                try {
                    camera.replaceView(view)
                    camera.startPreview()
                } catch (e: Exception) {
                    Log.e("AWA", "RTSP replaceView during start failed", e)
                }
            }
        } else {
            Log.e("AWA", "RTSP: prepareVideo failed, not starting stream")
            rtspCamera = null
        }
    }

    private fun stopRtspCamera() {
        rtspCamera?.stopStream()
        rtspCamera = null
        _isPreviewActive.value = false
    }

    private fun restartRtspCamera(reason: String) {
        if (rtspCamera == null) return
        Log.d("AWA", "Restarting RTSP camera — reason: $reason")

        val viewToReattach = openGlView
        stopRtspCamera()
        startRtspCamera()

        if (viewToReattach != null && _showLocalPreview.value) {
            attachRtspPreviewSurface(viewToReattach)
        }
    }

    // --- MJPEG (CameraX) pipeline ---

    private fun bindCamera() {
        val ctx = appContext ?: return
        val owner = lifecycleOwner ?: return
        val preview = previewView ?: return
        val s = _settings.value

        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(s.lensFacing)
                .build()

            val activeRes = s.resolution
            lastReportedSize = null
            _settings.value = _settings.value.copy(activeSize = null)

            // Selector for ImageAnalysis (the stream that is sent to the PC)
            val resSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy(activeRes.aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                )
                .setResolutionStrategy(
                    ResolutionStrategy(activeRes.size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()

            // CHANGED: Preview is back to CameraX's default size. Giving it the same 1080p selector
            // as ImageAnalysis (earlier change) made the J6 pipeline much slower and did not fix the stretch.
            val previewUseCase = Preview.Builder()
                .build().also {
                    it.setSurfaceProvider(preview.surfaceProvider)
                }

            val executor = cameraExecutor ?: Executors.newSingleThreadExecutor().also { cameraExecutor = it }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resSelector)
                .build()
                .also {
                    it.setAnalyzer(executor) { imageProxy ->
                        val frameStart = System.nanoTime()
                        try {
                            val width = imageProxy.width
                            val height = imageProxy.height
                            val fix = effectiveStretchFix()
                            val rawNv21 = if (kotlin.math.abs(fix - 1f) > 0.01f) {
                                imageProxyToNv21Corrected(imageProxy, fix)
                            } else {
                                imageProxyToNv21(imageProxy)
                            }

                            // Background removal happens here, on the phone, before JPEG
                            // encoding: the stream that leaves the device already has the
                            // background replaced. Whether that is a chroma key or a person mask
                            // is decided inside BackgroundRemover (possibly per frame, in AUTO).
                            // The local preview also runs off these frames once removal is on,
                            // which is the only way to see the result while tuning it.
                            val removing = background.isActive
                            // A pending plate capture has to see frames even when nobody is
                            // watching the stream, otherwise pressing "capture" does nothing
                            // until a viewer connects.
                            val encodeFrame = jpegViewerCount.get() > 0 ||
                                (removing && _showLocalPreview.value) ||
                                (removing && background.plateNeedsFrames)

                            val nv21 = if (removing && encodeFrame) {
                                // The segmenter's mask is in the upright frame, so it needs the
                                // rotation the buffer itself does not carry.
                                background.process(
                                    rawNv21,
                                    width,
                                    height,
                                    imageProxy.imageInfo.rotationDegrees,
                                )
                            } else {
                                rawNv21
                            }

                            if (encodeFrame) {
                                try {
                                    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                                    val out = ByteArrayOutputStream()
                                    yuvImage.compressToJpeg(Rect(0, 0, width, height), _settings.value.jpegQuality, out)
                                    val jpegBytes = out.toByteArray()
                                    currentFrame = jpegBytes
                                    VideoStreamServer.latestFrame = jpegBytes
                                } catch (e: Exception) {
                                    Log.e("AWA", "JPEG compression failed", e)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AWA", "Analyzer callback failed", e)
                        } finally {
                            val fw = imageProxy.width
                            val fh = imageProxy.height
                            imageProxy.close()
                            val nowNs = System.nanoTime()
                            if (lastReportedSize?.let { it.width != fw || it.height != fh } != false) {
                                val real = Size(fw, fh)
                                lastReportedSize = real
                                mainHandler.post { _settings.value = _settings.value.copy(activeSize = real) }
                            }
                            analyzerFrames++
                            analyzerBusyNs += nowNs - frameStart
                            // A capture finishes on its own after a few frames, so the panel has to
                            // hear about it even when the user is not touching anything.
                            val plateNow = background.plateState
                            if (plateNow != plateStateShadow) {
                                plateStateShadow = plateNow
                                mainHandler.post {
                                    _plateState.value = plateNow
                                    _chromaBackend.value = background.backendLabel
                                }
                            }
                            if (nowNs - analyzerWindowStart >= 2_000_000_000L) {
                                val secs = (nowNs - analyzerWindowStart) / 1e9
                                Log.d(
                                    "AWA",
                                    "Analyzer in: ${"%.1f".format(analyzerFrames / secs)} fps, " +
                                            "busy ${analyzerBusyNs / analyzerFrames / 1_000_000} ms/frame, size=${fw}x${fh}"
                                )
                                analyzerFrames = 0
                                analyzerBusyNs = 0L
                                analyzerWindowStart = nowNs
                                // The live backend can change without the user touching anything
                                // (AUTO re-resolving, or segmentation giving up), so the label is
                                // refreshed from here instead of only on settings changes.
                                mainHandler.post {
                                    val label = background.backendLabel
                                    if (_chromaBackend.value != label) _chromaBackend.value = label
                                }
                            }
                        }
                    }
                }

            provider.unbindAll()
            val camera = try {
                provider.bindToLifecycle(owner, cameraSelector, previewUseCase, imageAnalysis)
            } catch (e: Exception) {
                // A sensor-native size can be advertised for YUV and still be rejected once a
                // preview has to run next to it. Step down instead of leaving a black screen.
                val fallback = safeFallbackFor(activeRes)
                Log.e("AWA", "Binding ${activeRes.key} failed, retrying at ${fallback?.key}", e)
                if (fallback != null && fallback != activeRes) {
                    _settings.value = _settings.value.copy(resolution = fallback)
                    mainHandler.post { bindCamera() }
                } else {
                    Log.e("AWA", "No smaller size to fall back to - camera stays unbound")
                }
                return@addListener
            }
            mjpegCamera = camera
            Log.d(
                "AWA",
                "Requested ${activeRes.label} | preview=${previewUseCase.resolutionInfo?.resolution} " +
                        "analysis=${imageAnalysis.resolutionInfo?.resolution}"
            )
            camera.cameraInfo.exposureState.let { exposureState ->
                _settings.value = _settings.value.copy(
                    exposureRange = exposureState.exposureCompensationRange.lower..exposureState.exposureCompensationRange.upper,
                    exposureIndex = exposureState.exposureCompensationIndex
                )
            }
            _settings.value = _settings.value.copy(
                hasFlashUnit = camera.cameraInfo.hasFlashUnit()  // add this field to CameraSettings
            )

            // Refresh zoom range for the bound camera and re-apply stored zoom
            val zoomState = camera.cameraInfo.zoomState.value
            val maxZoom = zoomState?.maxZoomRatio ?: _settings.value.zoomMax
            val minZoom = zoomState?.minZoomRatio ?: _settings.value.zoomMin
            val currentZoom = s.zoom.coerceIn(minZoom, maxZoom)
            _settings.value = _settings.value.copy(zoomMin = minZoom, zoomMax = maxZoom, zoom = currentZoom)
            if (currentZoom != 1f) {
                runCatching { camera.cameraControl.setZoomRatio(currentZoom) }
                    .onFailure { Log.e("AWA", "MJPEG zoom reapply failed", it) }
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    /**
     * Largest size below 1080p (or 1080p itself) that the camera offers, used when binding a
     * sensor-native frame fails. Falls back to the smallest listed size rather than to nothing.
     */
    private fun safeFallbackFor(failed: StreamResolution): StreamResolution? {
        val list = resolutionsForCurrentMode().filter { it != failed }
        if (list.isEmpty()) return null
        val hdArea = StreamResolution.P1080.size.width * StreamResolution.P1080.size.height
        return list.lastOrNull { it.size.width * it.size.height <= hdArea } ?: list.first()
    }

    private fun unbindCamera() {
        cameraProvider?.unbindAll()
        cameraExecutor?.shutdown()
        cameraExecutor = null
        cameraProvider = null
        currentFrame = null
        mjpegCamera = null
        VideoStreamServer.latestFrame = null
    }

    private fun imageProxyToNv21(image: androidx.camera.core.ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val yRowStride = image.planes[0].rowStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride

        var outputOffset = 0
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            outputOffset = ySize
        } else {
            var yOffset = 0
            for (i in 0 until height) {
                yBuffer.position(yOffset)
                yBuffer.get(nv21, outputOffset, width)
                outputOffset += width
                yOffset += yRowStride
            }
        }

        val uvHeight = height / 2
        val uvWidth = width / 2

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uvIndex = row * uvRowStride + col * uvPixelStride
                nv21[outputOffset++] = vBuffer.get(uvIndex)
                nv21[outputOffset++] = uBuffer.get(uvIndex)
            }
        }

        return nv21
    }

    /**
     * Same as imageProxyToNv21, but corrects a stretched frame by zooming in on the centre
     * and resampling (nearest neighbour) back to the original size.
     * fix > 1: frame looks too wide  -> crop rows (zoom vertically)
     * fix < 1: frame looks too narrow -> crop columns (zoom horizontally)
     */
    private fun imageProxyToNv21Corrected(image: androidx.camera.core.ImageProxy, fix: Float): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + width * height / 2)

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val yRowStride = image.planes[0].rowStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride

        val rowZoom = maxOf(1f, fix)
        val colZoom = maxOf(1f, 1f / fix)
        val cropH = height / rowZoom
        val cropTop = (height - cropH) / 2f
        val cropW = width / colZoom
        val cropLeft = (width - cropW) / 2f

        val srcX = IntArray(width) { x ->
            (cropLeft + (x + 0.5f) * cropW / width).toInt().coerceIn(0, width - 1)
        }
        val rowBuf = ByteArray(width)
        var out = 0
        for (y in 0 until height) {
            val srcY = (cropTop + (y + 0.5f) * cropH / height).toInt().coerceIn(0, height - 1)
            yBuffer.position(srcY * yRowStride)
            yBuffer.get(rowBuf, 0, width)
            if (colZoom == 1f) {
                System.arraycopy(rowBuf, 0, nv21, out, width)
                out += width
            } else {
                for (x in 0 until width) nv21[out++] = rowBuf[srcX[x]]
            }
        }

        val uvHeight = height / 2
        val uvWidth = width / 2
        val srcUx = IntArray(uvWidth) { x ->
            ((cropLeft / 2f) + (x + 0.5f) * (cropW / 2f) / uvWidth).toInt().coerceIn(0, uvWidth - 1)
        }
        for (row in 0 until uvHeight) {
            val srcRow = ((cropTop / 2f) + (row + 0.5f) * (cropH / 2f) / uvHeight).toInt().coerceIn(0, uvHeight - 1)
            val rowBase = srcRow * uvRowStride
            for (col in 0 until uvWidth) {
                val idx = rowBase + srcUx[col] * uvPixelStride
                nv21[out++] = vBuffer.get(idx)
                nv21[out++] = uBuffer.get(idx)
            }
        }
        return nv21
    }

    // --- Focus ---

    fun toggleFocusMode() {
        val newMode = if (_settings.value.focusMode == FocusMode.AUTO) FocusMode.MANUAL else FocusMode.AUTO
        _settings.value = _settings.value.copy(focusMode = newMode)
        if (newMode == FocusMode.MANUAL) {
            applyManualFocus(_settings.value.focusDistance)
        } else {
            cancelFocusAndMetering()
        }
    }

    fun setFocusDistance(distance: Float) {
        _settings.value = _settings.value.copy(focusDistance = distance)
        if (_settings.value.focusMode == FocusMode.MANUAL) {
            applyManualFocus(distance)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyManualFocus(dist: Float) {
        Log.d("AWA","$dist")
        val distance = 1f - dist
        if (_streamMode.value == StreamMode.MJPEG) {
            val currentCamera = mjpegCamera ?: return
            val camera2Info = Camera2CameraInfo.from(currentCamera.cameraInfo)
            val minFocusDistance = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            ) ?: 0f

            if (minFocusDistance == 0f) return

            val focusDiopters = distance * minFocusDistance
            val camera2Control = Camera2CameraControl.from(currentCamera.cameraControl)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
                .build()

            camera2Control.setCaptureRequestOptions(options)
        } else {
            val currentCamera = rtspCamera ?: return
            val minFocusDistance = currentCamera.cameraCharacteristics.get(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            ) ?: 0f

            if (minFocusDistance == 0f) return

            if (currentCamera.isAutoFocusEnabled) {
                currentCamera.disableAutoFocus()
            }
            Log.d("AWA", "AutoFocus state: ${currentCamera.isAutoFocusEnabled}")
            currentCamera.setFocusDistance(distance * minFocusDistance)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun cancelFocusAndMetering() {
        if (_streamMode.value == StreamMode.MJPEG) {
            val currentCamera = mjpegCamera ?: return
            val camera2Control = Camera2CameraControl.from(currentCamera.cameraControl)
            camera2Control.clearCaptureRequestOptions()
            currentCamera.cameraControl.cancelFocusAndMetering()
        } else {
            var focus = rtspCamera?.isAutoFocusEnabled
            Log.d("AWA", "RTSP cancel manual , Autofocus before : $focus")
            rtspCamera?.enableAutoFocus()
            focus = rtspCamera?.isAutoFocusEnabled
            Log.d("AWA", "RTSP cancel manual , Autofocus after: $focus")
        }
    }

    fun tapToFocus(x: Float, y: Float, width: Float, height: Float) {
        val cameraControl = mjpegCamera?.cameraControl ?: return
        _settings.value = _settings.value.copy(focusMode = FocusMode.AUTO)

        val factory = SurfaceOrientedMeteringPointFactory(width, height)
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        cameraControl.startFocusAndMetering(action)
    }

    fun tapToFocus(view: View, event: MotionEvent) {
        _settings.value = _settings.value.copy(focusMode = FocusMode.AUTO)
        rtspCamera?.tapToFocus(view, event)
    }

    fun switchCamera() {
        val newFacing = if (_settings.value.lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        _settings.value = _settings.value.copy(lensFacing = newFacing)
        // Another lens sees another room: the plate is worthless from here on.
        background.clearPlate()
        refreshResolutions()

        when (_streamMode.value) {
            StreamMode.MJPEG -> bindCamera() // already re-derives hasFlashUnit via its callback
            StreamMode.H264_RTSP -> {
                try {
                    rtspCamera?.switchCamera()
                    val flashAvailable = rtspCamera?.cameraCharacteristics
                        ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    _settings.value = _settings.value.copy(hasFlashUnit = flashAvailable)
                    Log.d("AWA", "RTSP switchCamera successful")
                } catch (e: Exception) {
                    Log.e("AWA", "RTSP switchCamera failed", e)
                }
            }
        }
    }

    fun setCameraFacing(back: Boolean) {
        val target = if (back) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        if (_settings.value.lensFacing == target) return
        applyCameraFacing(target)
    }

    private fun applyCameraFacing(facing: Int) {
        _settings.value = _settings.value.copy(lensFacing = facing)
        background.clearPlate()
        refreshResolutions()
        when (_streamMode.value) {
            StreamMode.MJPEG -> bindCamera()
            StreamMode.H264_RTSP -> {
                try {
                    rtspCamera?.switchCamera()
                } catch (e: Exception) {
                    Log.e("AWA", "RTSP switchCamera failed", e)
                }
            }
        }
    }

    fun toggleFlash() {
        val targetState = !_settings.value.isFlashEnabled
        _settings.value = _settings.value.copy(isFlashEnabled = targetState)
        applyFlash(targetState)
    }

    fun applyFlash(enable: Boolean) {
        when (_streamMode.value) {
            StreamMode.MJPEG -> {
                mjpegCamera?.let { camera ->
                    if (camera.cameraInfo.hasFlashUnit()) {
                        camera.cameraControl.enableTorch(enable)
                    } else {
                        Log.w("AWA", "MJPEG: Device has no flash unit")
                    }
                }
            }

            StreamMode.H264_RTSP -> {
                rtspCamera?.let { camera ->
                    try {
                        if (enable) {
                            camera.enableLantern()
                        } else {
                            camera.disableLantern()
                        }
                        Log.d("AWA", "RTSP Lantern active: ${camera.isLanternEnabled}")
                    } catch (e: Exception) {
                        Log.e("AWA", "Failed to toggle RTSP lantern", e)
                    }
                }
            }
        }
    }

    fun setResolution(res: StreamResolution) {
        // CHANGED: ignore sizes this camera can't deliver in MJPEG mode
        if (res !in resolutionsForCurrentMode()) {
            Log.w("AWA", "Resolution ${res.label} not supported by this camera, ignoring")
            return
        }
        _settings.value = _settings.value.copy(resolution = res)
        // The plate is tied to the frame grid it was shot on; the subtractor would drop it anyway
        // on the first odd-sized frame, this just makes it immediate.
        background.clearPlate()
        when (_streamMode.value) {
            StreamMode.MJPEG -> {
                if (cameraProvider != null) bindCamera()
            }
            StreamMode.H264_RTSP -> restartRtspCamera("resolution changed to ${res.label}")
        }
    }

    /** Stretch correction actually in effect (only while 1080p is selected). */
    fun effectiveStretchFix(): Float {
        val s = _settings.value
        return if (s.resolution == StreamResolution.P1080) s.stretchFix else 1f
    }

    fun setStretchFix(value: Float) {
        _settings.value = _settings.value.copy(stretchFix = value.coerceIn(0.67f, 1.5f))
    }

    fun setQuality(quality: Int) {
        _settings.value = _settings.value.copy(jpegQuality = quality)
    }

    fun setZoom(zoom: Float) {
        val (minZoom, maxZoom) = getZoomRange()
        val clamped = zoom.coerceIn(minZoom, maxZoom)
        if (_settings.value.zoom == clamped) return
        _settings.value = _settings.value.copy(zoom = clamped)
        applyZoom(clamped)
    }

    fun pinchZoom(zoomChange: Float) {
        if (zoomChange <= 0f || zoomChange.isNaN()) return
        setZoom(_settings.value.zoom * zoomChange)
    }

    fun getMaxZoom(): Float {
        val live = when (_streamMode.value) {
            StreamMode.MJPEG -> mjpegCamera?.cameraInfo?.zoomState?.value?.maxZoomRatio
            StreamMode.H264_RTSP -> runCatching { rtspCamera?.getZoomRange()?.upper }.getOrNull()
        }
        val resolved = live ?: _settings.value.zoomMax
        return if (resolved > 1f) resolved else _settings.value.zoomMax
    }

    fun getMinZoom(): Float {
        val live = when (_streamMode.value) {
            StreamMode.MJPEG -> mjpegCamera?.cameraInfo?.zoomState?.value?.minZoomRatio
            StreamMode.H264_RTSP -> runCatching { rtspCamera?.getZoomRange()?.lower }.getOrNull()
        }
        val resolved = live ?: _settings.value.zoomMin
        return if (resolved <= 1f) resolved else 1f
    }

    fun getZoomRange(): Pair<Float, Float> {
        val min = getMinZoom()
        val max = getMaxZoom()
        return min to maxOf(max, min)
    }

    private fun applyZoom(zoom: Float) {
        when (_streamMode.value) {
            StreamMode.MJPEG -> runCatching {
                mjpegCamera?.cameraControl?.setZoomRatio(zoom)
            }.onFailure { Log.e("AWA", "MJPEG setZoomRatio failed", it) }
            StreamMode.H264_RTSP -> runCatching {
                rtspCamera?.setZoom(zoom)
            }.onFailure { Log.e("AWA", "RTSP setZoom failed", it) }
        }
    }

    fun setExposure(index: Int) {
        if (_streamMode.value == StreamMode.MJPEG) {
            mjpegCamera?.cameraControl?.setExposureCompensationIndex(index)
        } else {
            rtspCamera?.exposure = index
        }
        _settings.value = _settings.value.copy(exposureIndex = index)
    }

    override fun onCleared() {
        hideLocalPreview()
        unbindCamera()
        stopRtspCamera()
        AwaDiscovery.unregister()
        background.close()
    }
}

/** "#RRGGBB", the format the HTTP API uses for colours. */
private fun toHexColor(color: Int): String = "#%06X".format(color and 0xFFFFFF)

private fun parseHexColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    val parsed = hex.toLongOrNull(16) ?: return null
    return if (hex.length == 6) (0xFF000000L or parsed).toInt() else parsed.toInt()
}