package com.soubhagyajit.awa.processing

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import com.soubhagyajit.awa.processing.segmentation.PersonSegmenter

/**
 * Front end for background removal, which no longer implies a green screen.
 *
 * Two engines share one compositor and one background (solid colour or still image):
 *
 * | Mode | Engine | Wins when |
 * |:---|:---|:---|
 * | `CHROMA_KEY` | [ChromaKey] - Vulkan or CPU | there is a real screen: edge follows the colour boundary, so hair, motion blur and half-transparent edges survive |
 * | `PERSON` | ML Kit selfie segmentation via [PersonSegmenter] | there is no screen: works on a wall, a room, a busy office |
 * | `PLATE` | [PlateSubtractor] | no screen, but the camera is on a tripod: shoot the empty scene once and the matte follows the real silhouette, hair included |
 * | `AUTO` | screen or person, chosen per frame sequence | the user should not have to know which one applies |
 *
 * `AUTO` is not "run both and blend": switching engines mid-stream is visible, and the two produce
 * different edges. It resolves once from [ScreenProbe] (border colour coverage), then only revisits
 * the decision every [AUTO_PROBE_INTERVAL] frames and needs [AUTO_SWITCH_STREAK] consecutive
 * agreeing verdicts before moving - so a green mug entering the shot cannot flip the whole stream.
 *
 * The segmentation path runs on the CPU compositor on purpose. The mask arrives asynchronously at
 * ~15-30 Hz and is 256x256; uploading it to the GPU every frame would cost more in mapping and
 * synchronisation than the compositing itself, and the Vulkan path is still there for the mode where
 * it actually pays off.
 */
class BackgroundRemover {

    enum class Mode(val id: String) {
        /** Coloured screen (green/blue) - the precise path. */
        CHROMA_KEY("chroma"),

        /** Any background - person segmentation. */
        PERSON("person"),

        /** Shot the empty scene once, then cut whatever differs from it. Camera must stay put. */
        PLATE("plate"),

        /** Pick one automatically. */
        AUTO("auto");

        companion object {
            fun fromId(value: String?): Mode? {
                if (value.isNullOrBlank()) return null
                return values().firstOrNull {
                    it.id.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true)
                }
            }
        }
    }

    private val processor = ChromaKeyProcessor()

    /** Chroma backend, with the Vulkan engine inside. Shares [processor] so planes are built once. */
    private val chroma = ChromaKey(processor)

    private var segmenter: PersonSegmenter? = null

    /** Background plate: the empty scene, shot once, plus the matte it produces every frame. */
    private val plate = PlateSubtractor()

    /** Requested mode; [resolvedMode] is what is actually running. */
    @Volatile
    var mode: Mode = Mode.AUTO
        set(value) {
            if (field == value) return
            field = value
            // A new mode may mean a different engine, so drop any AUTO state and the old mask.
            autoResolved = null
            autoCandidate = null
            autoStreak = 0
            autoFrame = 0
            Log.i(TAG, "Background mode: ${value.id}")
        }

    @Volatile
    var config: ChromaKeyProcessor.Config = ChromaKeyProcessor.Config()
        set(value) {
            field = value
            chroma.config = value
            processor.config = value
            segmenter?.smoothing = value.maskSmoothing
        }

    /** What is actually running right now (resolves `AUTO`). */
    @Volatile
    var resolvedMode: Mode = Mode.PERSON
        private set

    private var autoResolved: Mode? = null
    private var autoCandidate: Mode? = null
    private var autoStreak = 0
    private var autoFrame = 0

    val isActive: Boolean
        get() = config.enabled

    val hasBackgroundImage: Boolean
        get() = processor.hasBackgroundImage

    /** NONE / CAPTURING / READY of the background plate, for the settings panel. */
    val plateState: PlateSubtractor.State
        get() = plate.state

    /** True while the analyzer has to keep handing frames over, even if nobody is watching. */
    val plateNeedsFrames: Boolean
        get() = plate.needsFrames

    /** Asks for a new plate; the next analyzed frame starts collecting. */
    fun startPlateCapture() = plate.startCapture()

    /** Throws the plate away - the camera, the resolution or the light setup changed. */
    fun clearPlate() = plate.clear()

    /** Shown in the settings panel, so it is obvious which engine is doing the work. */
    val backendLabel: String
        get() {
            // In AUTO the answer is whatever the last frame resolved to; in a fixed mode it is that
            // mode, even before the first frame has been processed.
            val effective = if (mode == Mode.AUTO) resolvedMode else mode
            return when (effective) {
                Mode.PERSON ->
                    if (segmenter?.unavailable == true) "Segmentation unavailable" else "ML Kit · person"
                Mode.PLATE -> when (plate.state) {
                    PlateSubtractor.State.READY -> "Background plate"
                    PlateSubtractor.State.CAPTURING -> "Plate · capturing"
                    PlateSubtractor.State.NONE -> "Plate · not captured"
                }
                else -> chroma.backendLabel
            }
        }

    fun attachAssets(assets: AssetManager?) = chroma.attachAssets(assets)

    fun setBackgroundImage(bitmap: Bitmap?) = chroma.setBackgroundImage(bitmap)

    fun clearBackgroundImage() = chroma.clearBackgroundImage()

    /**
     * Removes the background of [src] (NV21, `width * height * 3 / 2` bytes) and composites it onto
     * the configured background. [rotationDegrees] is `ImageProxy.imageInfo.rotationDegrees`: the
     * segmenter needs it because its mask is in the upright frame while [src] is not.
     *
     * The returned array is reused for the next frame (or is [src] itself when nothing can be done
     * yet), so copy it if it has to outlive the call.
     */
    fun process(src: ByteArray, width: Int, height: Int, rotationDegrees: Int): ByteArray {
        val cfg = config
        if (!cfg.enabled) return src

        val target = resolveMode(src, width, height)
        return when (target) {
            Mode.PERSON -> processPerson(src, width, height, rotationDegrees, cfg)
            Mode.PLATE -> processPlate(src, width, height, cfg)
            else -> chroma.process(src, width, height)
        }
    }

    fun close() {
        segmenter?.close()
        segmenter = null
        plate.clear()
        chroma.close()
    }

    /**
     * Background plate path. The matte is full resolution, so it goes straight to the compositor -
     * the solid colour or still image is the same one the other two engines use.
     */
    private fun processPlate(
        src: ByteArray,
        width: Int,
        height: Int,
        cfg: ChromaKeyProcessor.Config,
    ): ByteArray {
        // Null covers all three "nothing to cut with yet" cases: no plate, a capture in progress,
        // and a frame size the plate does not describe. Passing the frame through is correct in
        // each of them - the alternative is a black stream while the user lines up the shot.
        val matte = plate.process(src, width, height, cfg) ?: return src
        return processor.processAlpha(src, width, height, matte)
    }

    private fun processPerson(
        src: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        cfg: ChromaKeyProcessor.Config,
    ): ByteArray {
        val seg = ensureSegmenter()
        // No usable model on this device. Passing the frame through unchanged is the honest
        // answer: the chroma keyer would punch holes through anything green in a room that the
        // probe already decided is *not* a screen, and the settings panel reports "unavailable".
        if (seg == null) return src

        seg.smoothing = cfg.maskSmoothing
        seg.submit(src, width, height, rotationDegrees)

        val grid = seg.grid ?: return src // first frames: no mask yet, passing through beats a black frame
        return processor.processMasked(src, width, height, grid.data, grid.width, grid.height)
    }

    private fun ensureSegmenter(): PersonSegmenter? {
        segmenter?.let { existing -> return if (existing.unavailable) null else existing }
        val created = PersonSegmenter()
        created.smoothing = config.maskSmoothing
        segmenter = created
        created.warmUp()
        Log.i(TAG, "Person segmentation started")
        return if (created.unavailable) null else created
    }

    private fun resolveMode(src: ByteArray, width: Int, height: Int): Mode {
        val requested = mode
        if (requested != Mode.AUTO) {
            resolvedMode = requested
            return requested
        }

        val current = autoResolved
        if (current == null) {
            // First frame after switching to AUTO: decide immediately, one probe is enough because a
            // screen fills the entire border and a room does not come close.
            val decision = probe(src, width, height)
            autoResolved = decision
            resolvedMode = decision
            autoCandidate = null
            autoStreak = 0
            return decision
        }

        if (++autoFrame % AUTO_PROBE_INTERVAL != 0) {
            resolvedMode = current
            return current
        }

        val want = probe(src, width, height)
        if (want == current) {
            autoCandidate = null
            autoStreak = 0
            resolvedMode = current
            return current
        }
        if (want == autoCandidate) {
            autoStreak++
        } else {
            autoCandidate = want
            autoStreak = 1
        }
        if (autoStreak >= AUTO_SWITCH_STREAK) {
            Log.i(TAG, "AUTO switched ${current.id} -> ${want.id}")
            autoResolved = want
            autoCandidate = null
            autoStreak = 0
            resolvedMode = want
            return want
        }
        resolvedMode = current
        return current
    }

    private fun probe(src: ByteArray, width: Int, height: Int): Mode {
        val coverage = ScreenProbe.coverage(src, width, height)
        return if (coverage >= ScreenProbe.SCREEN_COVERAGE) Mode.CHROMA_KEY else Mode.PERSON
    }

    private companion object {
        const val TAG = "AWA-Background"

        /** How often AUTO re-checks, in frames (~2 s at 30 fps). */
        const val AUTO_PROBE_INTERVAL = 60

        /** Consecutive agreeing probes needed before AUTO actually switches. */
        const val AUTO_SWITCH_STREAK = 2
    }
}
