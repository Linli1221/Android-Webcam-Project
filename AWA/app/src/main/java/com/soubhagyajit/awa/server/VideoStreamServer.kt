package com.soubhagyajit.awa.server

import android.util.Log
import com.soubhagyajit.awa.viewModel.CameraViewModel
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStream

object VideoStreamServer {

    @Serializable
    data class FeaturesResponse(
        val resolutions: List<String>,
        val manual_focus: Boolean,
        val exposure_lower: Int,
        val exposure_upper: Int,
        val has_zoom: Boolean,
        val zoom_max: Float,
        val zoom_min: Float,
        val stream_protocol: CameraViewModel.StreamMode,
        val server_port: Int? = null,
        val rtsp_port: Int? = null
    )

    @Serializable
    data class SettingsResponse(
        val camera: String,
        /** The size the camera is really delivering; falls back to the requested one before the first frame. */
        val resolution_str: String,
        /**
         * The size that was asked for. When it differs from [resolution_str] the camera refused it
         * and CameraX picked the closest size it would accept - which is what happens to a sensor
         * size that cannot run next to a preview.
         */
        val resolution_requested: String? = null,
        val zoom: Float,
        val focus_mode: Int,
        val focus_distance: Float,
        val exposure_index: Int,
        val autofocus: Boolean? = null,
        val stream_quality: Int,
        val flash: Boolean = false,
        val has_flash_unit: Boolean? = null,
        // Background removal, applied on the phone before the frame is encoded
        val chroma_key: Boolean = false,
        /** chroma | person | auto */
        val background_mode: String = "auto",
        /** What is actually running, e.g. "Vulkan · Adreno (TM) 640", "CPU" or "ML Kit · person". */
        val background_engine: String = "CPU",
        val chroma_key_color: String = "#00FF00",
        val chroma_key_similarity: Float = 0.28f,
        val chroma_key_smoothness: Float = 0.12f,
        val chroma_key_spill: Float = 0.45f,
        val segmentation_threshold: Float = 0.5f,
        val segmentation_feather: Float = 0.2f,
        val segmentation_smoothing: Float = 0.35f,
        // Background plate engine: shoot the empty scene, cut what differs
        val plate_sensitivity: Float = 0.5f,
        val plate_feather: Float = 0.35f,
        val plate_smoothing: Float = 0.4f,
        /** True once a plate has been shot for the current frame size. */
        val plate_ready: Boolean = false,
        val chroma_key_background: String = "solid",
        val chroma_key_background_color: String = "#101018",
        /** True when the Vulkan compute keyer is doing the work right now. */
        val chroma_key_gpu: Boolean = false,
        /** Human readable backend, e.g. "Vulkan · Adreno (TM) 640" or "CPU". */
        val chroma_key_backend: String = "CPU",
    )

    @Serializable
    data class SettingsUpdateRequest(
        val focus_mode: Int? = null,
        val focus_distance: Float? = null,
        val autofocus: Boolean? = null,
        val exposure_index: Int? = null,
        val zoom: Float? = null,
        val flash: Boolean? = null,
        val resolution_str: String? = null,
        val switchCamera: Boolean? = null,
        val camera: String,
        val stream_quality: Int? = null,
        val chroma_key: Boolean? = null,
        val background_mode: String? = null,
        val chroma_key_color: String? = null,
        val chroma_key_similarity: Float? = null,
        val chroma_key_smoothness: Float? = null,
        val chroma_key_spill: Float? = null,
        val segmentation_threshold: Float? = null,
        val segmentation_feather: Float? = null,
        val segmentation_smoothing: Float? = null,
        val plate_sensitivity: Float? = null,
        val plate_feather: Float? = null,
        val plate_smoothing: Float? = null,
        /** Shoots a new background plate (also switches the engine to `plate`). */
        val plate_capture: Boolean? = null,
        val chroma_key_background_color: String? = null,
        val chroma_key_background: String? = null,
        val chroma_key_gpu: Boolean? = null,
    )

    var featuresProvider: (() -> FeaturesResponse)? = null
    var settingsProvider: (() -> SettingsResponse)? = null

    // Returns null on success, or an error message String if rejected
    var onSettingsUpdated: ((SettingsUpdateRequest) -> String?)? = null

    private var server: EmbeddedServer<*, *>? = null
    @Volatile
    var latestFrame: ByteArray? = null

    var onUserConnected: (() -> Unit)? = null
    var onUserDisconnected: (() -> Unit)? = null
    var onServerStateChanged: ((Boolean) -> Unit)? = null

    val isRunning: Boolean
        get() = server != null

    fun toggleServer(port: Int = 8080) {
        if (isRunning) {
            stop()
        } else {
            start(port)
        }
        onServerStateChanged?.invoke(isRunning)
    }

    fun start(port: Int = 8080) {
        if (server != null) return

        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = true
                        ignoreUnknownKeys = true
                    }
                )
            }
            install(CORS) {
                anyHost()
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowHeader(HttpHeaders.ContentType)
            }
            routing {
                get("/video") {
                    onUserConnected?.invoke()
                    try {
                        call.respondOutputStream(
                            contentType = ContentType.parse("multipart/x-mixed-replace; boundary=--frame"),
                            status = HttpStatusCode.OK
                        ) {
                            streamMjpeg(this)
                        }
                    } finally {
                        onUserDisconnected?.invoke()
                    }
                }

                get("/features") {
                    val response = featuresProvider?.invoke() ?: FeaturesResponse(
                        resolutions = listOf("640x480", "1280x720", "1920x1080"),
                        manual_focus = false,
                        exposure_upper = 0,
                        exposure_lower = 0,
                        has_zoom = false,
                        zoom_max = 1.0f,
                        zoom_min = 1.0f,
                        stream_protocol = CameraViewModel.StreamMode.MJPEG,
                        server_port = null,
                        rtsp_port = null
                    )
                    call.respond(response)
                }

                get("/settings") {
                    val response = settingsProvider?.invoke() ?: SettingsResponse(
                        camera = "back",
                        resolution_str = "1280x720",
                        zoom = 1.0f,
                        flash = false,
                        exposure_index = 1,
                        autofocus = true,
                        focus_mode = 0,
                        focus_distance = 0f,
                        has_flash_unit = false,
                        stream_quality = 80,
                    )
                    call.respond(response)
                }

                // POST Endpoint
                post("/settings") {
                    try {
                        val request = call.receive<SettingsUpdateRequest>()
                        val error = onSettingsUpdated?.invoke(request)
                        if (error != null) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to error))
                        } else {
                            call.respond(HttpStatusCode.OK, mapOf("status" to "success"))
                        }
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid payload")))
                    }
                }

                // GET Endpoint
                get("/control") {
                    val params = call.request.queryParameters
                    Log.d("AWA", "$params")
                    val update = SettingsUpdateRequest(
                        camera = params["camera"] ?: "back",
                        resolution_str = params["resolution_str"],
                        zoom = params["zoom"]?.toFloatOrNull(),
                        flash = params["flash"]?.toBooleanStrictOrNull(),
                        exposure_index = params["exposure_index"]?.toIntOrNull(),
                        autofocus = params["autofocus"]?.toBooleanStrictOrNull(),
                        focus_mode = params["focus_mode"]?.toIntOrNull(),
                        focus_distance = params["focus_distance"]?.toFloatOrNull(),
                        switchCamera = if (params.contains("switch_camera")) true else null,
                        stream_quality = 80,
                        chroma_key = params["chroma_key"]?.toBooleanStrictOrNull(),
                        background_mode = params["background_mode"],
                        chroma_key_color = params["chroma_key_color"],
                        chroma_key_similarity = params["chroma_key_similarity"]?.toFloatOrNull(),
                        chroma_key_smoothness = params["chroma_key_smoothness"]?.toFloatOrNull(),
                        chroma_key_spill = params["chroma_key_spill"]?.toFloatOrNull(),
                        segmentation_threshold = params["segmentation_threshold"]?.toFloatOrNull(),
                        segmentation_feather = params["segmentation_feather"]?.toFloatOrNull(),
                        segmentation_smoothing = params["segmentation_smoothing"]?.toFloatOrNull(),
                        plate_sensitivity = params["plate_sensitivity"]?.toFloatOrNull(),
                        plate_feather = params["plate_feather"]?.toFloatOrNull(),
                        plate_smoothing = params["plate_smoothing"]?.toFloatOrNull(),
                        plate_capture = params["plate_capture"]?.toBooleanStrictOrNull(),
                        chroma_key_background_color = params["chroma_key_background_color"],
                        chroma_key_gpu = params["chroma_key_gpu"]?.toBooleanStrictOrNull(),
                    )

                    val error = onSettingsUpdated?.invoke(update)
                    if (error != null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to error))
                    } else {
                        val response = settingsProvider?.invoke() ?: SettingsResponse(
                            focus_mode = 0, focus_distance = 0f, exposure_index = 1,
                            zoom = 1.0f, stream_quality = 80, resolution_str = "1280x720",
                            camera = "back"
                        )
                        call.respond(HttpStatusCode.OK, response)
                    }
                }
                staticResources("/static", "static")
                get("/help"){
                    call.respondRedirect("/static/help.html")
                }

            }
        }.start(wait = false)
    }

    private suspend fun streamMjpeg(outputStream: OutputStream) {
        val boundary = "\r\n--frame\r\n".toByteArray()
        val contentType = "Content-Type: image/jpeg\r\n".toByteArray()

        // CHANGED: only send frames that are new. The old loop re-sent the same latest frame
        // every 33 ms, so a slow camera (or slow link) still pushed ~30 full JPEGs per second.
        var lastSent: ByteArray? = null
        var frames = 0
        var bytes = 0L
        var windowStart = System.nanoTime()

        while (true) {
            val frame = latestFrame
            if (frame != null && frame !== lastSent) {
                try {
                    withContext(Dispatchers.IO) {
                        outputStream.write(boundary)
                        outputStream.write(contentType)
                        outputStream.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                        outputStream.write(frame)
                        outputStream.flush()
                    }
                } catch (_: Exception) {
                    break
                }
                lastSent = frame
                frames++
                bytes += frame.size

                val now = System.nanoTime()
                if (now - windowStart >= 2_000_000_000L) {
                    val secs = (now - windowStart) / 1e9
                    Log.d("AWA", "MJPEG out: ${"%.1f".format(frames / secs)} fps, ${(bytes / 1024 / secs).toLong()} KB/s")
                    frames = 0
                    bytes = 0L
                    windowStart = now
                }
            } else {
                delay(5)
            }
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}