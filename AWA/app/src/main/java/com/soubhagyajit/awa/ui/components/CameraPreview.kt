package com.soubhagyajit.awa.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import com.pedro.library.view.OpenGlView
import com.soubhagyajit.awa.viewModel.CameraViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun Preview(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val streamMode by viewModel.streamMode
    val settings by viewModel.settings
    val context = LocalContext.current

    if (streamMode == CameraViewModel.StreamMode.MJPEG) {

        // Reading this here makes the preview recompose when the slider changes
        val stretchFix = viewModel.effectiveStretchFix()

        if (settings.chromaKeyEnabled) {
            // The camera surface shows the untouched sensor image, which is useless for tuning a
            // key, so the matted frame that is actually being streamed is drawn on top of it. The
            // surface itself is kept: it is what binds the camera, and it shows through until the
            // first keyed frame arrives.
            Box(modifier = modifier.clipToBounds()) {
                CameraSurface(viewModel, stretchFix, Modifier.matchParentSize())
                MattePreview(viewModel, Modifier.matchParentSize())
            }
        } else {
            CameraSurface(viewModel, stretchFix, modifier.clipToBounds())
        }

    } else {

        // Create ONLY ONE OpenGlView for this composition
        val openGlView = remember {
            OpenGlView(context)
        }

        val isPinching = remember { mutableStateOf(false) }

        val scaleDetector = remember {
            ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isPinching.value = true
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    viewModel.pinchZoom(detector.scaleFactor)
                    return true
                }
            })
        }

        AndroidView(
            factory = {
                openGlView.apply {
                    setOnTouchListener { view, event ->
                        scaleDetector.onTouchEvent(event)
                        if (event.action == MotionEvent.ACTION_UP) {
                            if (!isPinching.value) {
                                viewModel.tapToFocus(view, event)
                                viewModel.notifyScreenTapped()
                            }
                            isPinching.value = false
                        }
                        true
                    }
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            Log.d("AWA", "Attach preview called")
                            viewModel.attachRtspPreviewSurface(this@apply)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.detachRtspPreviewSurface()
                        }
                    })
                }

            }
        )

        DisposableEffect(Unit) {
            onDispose {
                viewModel.detachRtspPreviewSurface()
            }
        }
    }
}

/**
 * The CameraX preview surface. Keeping it in its own composable lets the keyed frames be drawn on
 * top of it without losing the surface that binds the camera.
 */
@Composable
private fun CameraSurface(viewModel: CameraViewModel, stretchFix: Float, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        // clipToBounds hides the part of the zoomed preview that falls outside the screen
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                // CHANGED: TextureView-based preview avoids the SurfaceView + Compose
                // stretch bug that affects Android 10/11 (CameraX issue 183864890)
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                viewModel.attachPreviewSurface(
                    context,
                    lifecycleOwner,
                    this
                )
            }
        },
        update = { view ->
            // Same correction as the MJPEG stream: zoom on the centre along one axis
            view.scaleY = maxOf(1f, stretchFix)
            view.scaleX = maxOf(1f, 1f / stretchFix)
        }
    )
}

/**
 * Draws the JPEG frames the app is actually streaming, i.e. after the green screen has been keyed.
 * Only used while the preview is visible, so decoding a frame is paid for when somebody is looking.
 */
@Composable
private fun MattePreview(viewModel: CameraViewModel, modifier: Modifier = Modifier) {    val holder = remember { FrameBitmap() }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        var last: ByteArray? = null
        while (isActive) {
            val frame = viewModel.currentFrame
            if (frame != null && frame !== last) {
                last = frame
                if (holder.decode(frame, options) != null) tick++
            }
            delay(20)
        }
    }

    Canvas(modifier) {
        // Reading the frame counter inside the draw scope is what schedules the next redraw
        val bitmap = if (tick >= 0) holder.bitmap else null
        if (bitmap == null || bitmap.isRecycled) return@Canvas
        // Centre-crop, matching PreviewView.ScaleType.FILL_CENTER used for the camera surface
        val scale = maxOf(size.width / bitmap.width, size.height / bitmap.height)
        val width = (bitmap.width * scale).toInt()
        val height = (bitmap.height * scale).toInt()
        drawImage(
            image = bitmap.asImageBitmap(),
            dstOffset = IntOffset(((size.width - width) / 2f).toInt(), ((size.height - height) / 2f).toInt()),
            dstSize = IntSize(width, height),
        )
    }
}

/** Decodes streamed JPEGs, reusing the previous bitmap so no garbage is created per frame. */
private class FrameBitmap {
    var bitmap: Bitmap? = null
        private set

    fun decode(frame: ByteArray, options: BitmapFactory.Options): Bitmap? {
        options.inBitmap = bitmap?.takeIf { it.isMutable && !it.isRecycled }
        val decoded = try {
            BitmapFactory.decodeByteArray(frame, 0, frame.size, options)
        } catch (e: IllegalArgumentException) {
            // A reused bitmap must match the frame size exactly; start over when it does not.
            options.inBitmap = null
            BitmapFactory.decodeByteArray(frame, 0, frame.size, options)
        }
        if (decoded == null) {
            bitmap = null
            options.inBitmap = null
        } else {
            bitmap = decoded
        }
        return decoded
    }
}