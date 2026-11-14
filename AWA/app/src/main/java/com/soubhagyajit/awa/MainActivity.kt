package com.soubhagyajit.awa

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.soubhagyajit.awa.processing.BackgroundRemover
import com.soubhagyajit.awa.processing.PlateSubtractor
import com.soubhagyajit.awa.ui.theme.AWATheme
import com.soubhagyajit.awa.ui.components.Preview
import com.soubhagyajit.awa.viewModel.CameraViewModel
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen on (no sleep/lock) while the camera server is running in the foreground
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        enableEdgeToEdge()
        setContent {
            AWATheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CameraScreen()
                }
            }
        }
    }
}
@Composable
fun CameraScreen(camView: CameraViewModel = viewModel()) {
    val hasPermission by checkPermissions(*requiredPermissions())
    if (hasPermission) {
        CameraContent(camView)
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera, audio and local network permissions are required to stream.")
        }
    }
}

fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 37) {
        add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }
}.toTypedArray()

@Composable
private fun CameraContent(camView: CameraViewModel) {
    val isServerRunning by camView.isServerRunning
    val isPreviewActive by camView.isPreviewActive
    val showLocalPreview by camView.showLocalPreview

    var isSettingsOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val streamMode by camView.streamMode
    var showExposureSlider by remember { mutableStateOf(false) }

    val settings by camView.settings
    val exposureIndex = settings.exposureIndex
    val exposureRange = settings.exposureRange
    val focusMode = settings.focusMode
    val focusDistance = settings.focusDistance
    var showFocusSlider by remember { mutableStateOf(false) }
    var showZoomSlider by remember { mutableStateOf(false) }
    val ipAddress by remember { mutableStateOf(getLocalIpAddress()) }

    // Blank-screen state: draws an opaque black overlay (burn-in safe) and dims the
    // backlight, without ever letting the display actually sleep or lock.
    var isScreenBlanked by remember { mutableStateOf(false) }

    LaunchedEffect(isScreenBlanked) {
        (context as? Activity)?.window?.let { win ->
            win.attributes = win.attributes.apply {
                screenBrightness = if (isScreenBlanked) {
                    0.0f
                } else {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    LaunchedEffect (Unit) {
        camView.initialize(context)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        //Camera Preview

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        camView.notifyScreenTapped()
                        camView.tapToFocus(
                            x = offset.x,
                            y = offset.y,
                            width = size.width.toFloat(),
                            height = size.height.toFloat()
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        camView.pinchZoom(zoomChange)
                    }
                }
        ) {
            Preview(
                camView,
                Modifier
                    .fillMaxSize()
                    .alpha(if (showLocalPreview) 1f else 0f)
            )

            if (!showLocalPreview) {
                val message = when {
                    !isServerRunning -> "Server Stopped"
                    streamMode == CameraViewModel.StreamMode.H264_RTSP && isPreviewActive -> "Client connected — tap to preview"
                    streamMode == CameraViewModel.StreamMode.H264_RTSP -> "Waiting for client... (tap to preview)"
                    isPreviewActive -> "Client connected — tap to preview"
                    else -> "No client connected. (tap to preview)"
                }
                Box(
                    Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Text(message)
                }
            }
        }
        //Top Controls
        Row (
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ){
            Row (modifier = Modifier, verticalAlignment = Alignment.CenterVertically , horizontalArrangement = Arrangement.spacedBy(16.dp)){
                Box {
                    TextButton(
                        onClick = {
                            showExposureSlider = !showExposureSlider
                            showZoomSlider = false
                            Log.d("AWA", "Exposure button tapped")
                        }
                    ) {
                        Text("EXP")
                    }

                    if (showExposureSlider) {
                        Popup (alignment = Alignment.TopStart, offset = IntOffset(0, 120),  onDismissRequest = { showExposureSlider = false }) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xCC1A1A1A), shape = RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Slider(
                                    value = exposureIndex.toFloat(),
                                    onValueChange = { camView.setExposure(it.toInt()) },
                                    valueRange = exposureRange.first.toFloat()..exposureRange.last.toFloat(),
                                    modifier = Modifier.width(400.dp)
                                )
                            }
                        }
                    }
                }
                Box {
                    TextButton(
                        onClick = {
                            showZoomSlider = !showZoomSlider
                            showExposureSlider = false
                            Log.d("AWA", "Zoom button tapped")
                        }
                    ) {
                        Text("ZOOM")
                    }

                    if (showZoomSlider) {
                        Popup (alignment = Alignment.TopStart, offset = IntOffset(0, 120),  onDismissRequest = { showZoomSlider = false }) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xCC1A1A1A), shape = RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Zoom ${"%.1f".format(settings.zoom)}x", color = Color.White, fontSize = 12.sp)
                                    Slider(
                                        value = settings.zoom,
                                        onValueChange = { camView.setZoom(it) },
                                        valueRange = settings.zoomMin..maxOf(settings.zoomMax, settings.zoomMin + 0.1f),
                                        modifier = Modifier.width(300.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Box {
                    SmallFloatingActionButton(
                        onClick = {
                            Log.d("AWA", "Focus button tapped")
                            camView.toggleFocusMode()
                            showFocusSlider = (settings.focusMode == CameraViewModel.FocusMode.MANUAL)
                        },
                        shape = CircleShape,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text(
                            text = if (focusMode == CameraViewModel.FocusMode.AUTO) "A" else "M",
                            fontSize = 16.sp
                        )
                    }
                    if (focusMode == CameraViewModel.FocusMode.MANUAL && showFocusSlider) {
                        Popup(
                            alignment = Alignment.TopStart,
                            offset = IntOffset(0, 120),
                            onDismissRequest = { showFocusSlider = false }) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xCC1A1A1A), shape = RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Focus Distance", color = Color.White, fontSize = 12.sp)
                                    Slider(
                                        value = focusDistance,
                                        onValueChange = { camView.setFocusDistance(it) },
                                        valueRange = 0f..1f,
                                        modifier = Modifier.width(300.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Box {
                    SmallFloatingActionButton(
                        onClick = {
                            Log.d("AWA", "Flash button tapped")
                            camView.toggleFlash()
                        },
                        shape = CircleShape,
                        modifier = Modifier.size(36.dp)
                    ) {
                        if (settings.isFlashEnabled){
                            Icon(Icons.Default.FlashOff, contentDescription = "Flash", Modifier.size(24.dp), tint = Color.White)
                        }
                        else{
                            Icon(Icons.Default.FlashOn, contentDescription = "Flash", Modifier.size(24.dp), tint = Color.White)
                        }

                    }
                }
                Box {
                    TextButton(
                        onClick = {
                            isScreenBlanked = true
                            Log.d("AWA", "Screen blanked")
                        }
                    ) {
                        Text("DIM")
                    }
                }
            }
            Row (verticalAlignment = Alignment.CenterVertically){
                if (isServerRunning)

                    Box (modifier = Modifier, contentAlignment = Alignment.Center){
                        Text("IP : ${ipAddress}:${ if (streamMode == CameraViewModel.StreamMode.H264_RTSP) 8554 else 8080 }",modifier = Modifier.padding(4.dp))
                    }
                if (isServerRunning)
                    Icon(Icons.Default.Link, contentDescription = "Server Status", tint = Color.Green,modifier = Modifier.padding(horizontal = 2.dp))
                else
                    Icon(Icons.Default.Link, contentDescription = "Server Status", tint = Color.Red,modifier = Modifier.padding(horizontal = 2.dp))
            }
        }

        //Bottom Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left slot
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                IconButton(onClick = {
                    isSettingsOpen = true
                    Log.d("AWA", "Settings button tapped")
                }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }

            // Center slot — the shutter, fixed size, not weighted
            FilledTonalButton (
                onClick = { camView.toggleServer()
//                    Log.d("AWA","Server button toggled, server status: "+camView.isServerRunning)
                },
                contentPadding = PaddingValues(12.dp)
            ){
                Icon (
                    imageVector = if (isServerRunning) Icons.Default.PowerSettingsNew else Icons.Default.PowerSettingsNew,
                    contentDescription = if (isServerRunning) "Stop server" else "Start server",
                    tint = if (isServerRunning) Color.Green else Color.Red,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Right slot
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                IconButton(onClick = { camView.switchCamera() }) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Flip Camera", tint = Color.White)
                }
            }
        }

        // Burn-in-safe blank screen: opaque black, tap anywhere to restore controls & brightness
        if (isScreenBlanked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            isScreenBlanked = false
                        }
                    }
            )
        }

    }
    SettingsPanel(
        isOpen = isSettingsOpen,
        onClose = { isSettingsOpen = false },
        camView = camView,
        modifier = Modifier.fillMaxSize(),
        settings
    )
}

@Preview(name = "Idle", showBackground = true, uiMode = Configuration.ORIENTATION_LANDSCAPE)
@Composable
fun CameraScreenPreview() {
    CameraScreen()
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(
    isOpen: Boolean,
    onClose: () -> Unit,
    camView: CameraViewModel,
    modifier: Modifier = Modifier,
    settings: CameraViewModel.CameraSettings
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val quality = settings.jpegQuality
    var sliderPosition by remember { mutableFloatStateOf(quality.toFloat()) }
    val streamMode by camView.streamMode
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = modifier
    ) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .then(if (isLandscape) Modifier.fillMaxWidth(0.5f) else Modifier.fillMaxWidth())
                    .align(Alignment.CenterEnd)
                    .background(Color(0xCC1A1A1A))
                    .systemBarsPadding()
                    .padding(24.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {

                    // Fixed header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Settings", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Scrollable settings, takes all remaining space
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                    ) {
                        StreamTypeSelector(camView = camView)

                        Spacer(modifier = Modifier.height(16.dp))

                        ResolutionDropdown(camView = camView, settings = settings)

                        Spacer(modifier = Modifier.height(16.dp))

                        if (streamMode == CameraViewModel.StreamMode.MJPEG) {
                            Text("Quality: ${sliderPosition.toInt()}", color = Color.White)
                            Spacer(modifier = Modifier.height(16.dp))
                            Slider(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(24.dp),
                                value = sliderPosition,
                                onValueChange = { sliderPosition = it },
                                onValueChangeFinished = { camView.setQuality(sliderPosition.toInt()) },
                                valueRange = 10f..100f,
                                track = { state ->
                                    SliderDefaults.Track(
                                        sliderState = state,
                                        modifier = Modifier.height(6.dp)
                                    )
                                }
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            ChromaKeySection(camView = camView, settings = settings)
                        } else {
                            Text(
                                "Background removal works on the MJPEG stream. Switch the stream type to MJPEG to do it on the phone.",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Fixed footer, always visible
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(context, HelpActivity::class.java))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("API Help & Info")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamTypeSelector(camView: CameraViewModel) {
    val streamMode by camView.streamMode
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = streamMode.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Stream Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            CameraViewModel.StreamMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        camView.setStreamMode(mode)
                        Log.d("AWA", "Stream mode set to ${mode.label}")
                        expanded = false
                    }
                )
            }
        }
    }
}

private val CHROMA_KEY_COLORS = listOf(0xFF00FF00.toInt(), 0xFF0000FF.toInt())
private val CHROMA_BACKDROPS = listOf(
    0xFF101018.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF1E6FD9.toInt()
)

@Composable
fun ChromaKeySection(camView: CameraViewModel, settings: CameraViewModel.CameraSettings) {
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) camView.setChromaBackgroundImage(uri)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Background removal", color = Color.White, fontSize = 16.sp)
                Text(
                    "Removed on the phone, before encoding",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = settings.chromaKeyEnabled,
                onCheckedChange = { camView.setChromaKeyEnabled(it) }
            )
        }

        if (settings.chromaKeyEnabled) {
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeChip(
                    "Auto",
                    settings.backgroundMode == BackgroundRemover.Mode.AUTO
                ) { camView.setBackgroundMode(BackgroundRemover.Mode.AUTO) }
                ModeChip(
                    "Screen",
                    settings.backgroundMode == BackgroundRemover.Mode.CHROMA_KEY
                ) { camView.setBackgroundMode(BackgroundRemover.Mode.CHROMA_KEY) }
                ModeChip(
                    "Person",
                    settings.backgroundMode == BackgroundRemover.Mode.PERSON
                ) { camView.setBackgroundMode(BackgroundRemover.Mode.PERSON) }
                ModeChip(
                    "Plate",
                    settings.backgroundMode == BackgroundRemover.Mode.PLATE
                ) { camView.setBackgroundMode(BackgroundRemover.Mode.PLATE) }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                when (settings.backgroundMode) {
                    BackgroundRemover.Mode.CHROMA_KEY ->
                        "Best edge quality, but needs a green or blue screen behind you."
                    BackgroundRemover.Mode.PERSON ->
                        "Works with any background. Segments one person; costs more CPU than a screen."
                    BackgroundRemover.Mode.PLATE ->
                        "Shoot the empty room once, then everything that differs is cut. Sharpest edge of the three, as long as the phone does not move."
                    BackgroundRemover.Mode.AUTO ->
                        "Uses a coloured screen when it sees one, otherwise segments the person."
                },
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                // Tells you what is really running right now, which is what matters when AUTO is
                // in charge: a driver that refuses the shaders drops back to the CPU silently.
                "Running: ${camView.chromaBackend.value}",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp
            )

            if (settings.backgroundMode != BackgroundRemover.Mode.PERSON &&
                settings.backgroundMode != BackgroundRemover.Mode.PLATE
            ) {
                Spacer(modifier = Modifier.height(14.dp))
                Text("Coloured screen", color = Color.White, fontSize = 12.sp)

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CHROMA_KEY_COLORS.forEach { value ->
                        SwatchDot(
                            color = Color(value),
                            selected = settings.chromaKeyColor == value,
                            onClick = { camView.setChromaKeyColor(value) }
                        )
                    }
                    Text(
                        if (settings.chromaKeyColor == CHROMA_KEY_COLORS[1]) "Blue screen" else "Green screen",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                ChromaSlider("Similarity", settings.chromaSimilarity, 0.05f..0.9f) {
                    camView.setChromaSimilarity(it)
                }
                ChromaSlider("Soft edge", settings.chromaSmoothness, 0f..0.5f) {
                    camView.setChromaKeySmoothness(it)
                }
                ChromaSlider("Spill removal", settings.chromaSpill, 0f..1f) {
                    camView.setChromaSpill(it)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("GPU keying (Vulkan)", color = Color.White, fontSize = 12.sp)
                        Text(
                            "Two compute passes per frame; drops back to the CPU when the driver refuses them.",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.chromaGpu,
                        onCheckedChange = { camView.setChromaGpu(it) }
                    )
                }

                if (settings.resolution.pixels >= HD_PIXELS) {
                    Text(
                        if (settings.chromaGpu)
                            "%.1f MP on the GPU: two compute dispatches per frame, the CPU only copies the frame in and out."
                                .format(settings.resolution.megapixels)
                        else
                            "Keying %.1f MP on the CPU is heavy - 720p keeps the frame rate up, or enable the GPU path."
                                .format(settings.resolution.megapixels),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
            }

            if (settings.backgroundMode != BackgroundRemover.Mode.CHROMA_KEY &&
                settings.backgroundMode != BackgroundRemover.Mode.PLATE
            ) {
                Spacer(modifier = Modifier.height(14.dp))
                Text("Person mask", color = Color.White, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))

                ChromaSlider("Cut", settings.personThreshold, 0.3f..0.7f) {
                    camView.setPersonThreshold(it)
                }
                ChromaSlider("Edge softness", settings.personFeather, 0.02f..0.5f) {
                    camView.setPersonFeather(it)
                }
                ChromaSlider("Stabilise", settings.personSmooth, 0f..0.85f) {
                    camView.setPersonSmooth(it)
                }

                Text(
                    "The mask trails the frame by a frame or two; stabilise trades more lag for less flicker.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
                if (settings.resolution.pixels >= HD_PIXELS) {
                    Text(
                        "The mask itself is 256x256, but compositing is per pixel - %.1f MP still costs more per frame."
                            .format(settings.resolution.megapixels),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
            }

            if (settings.backgroundMode == BackgroundRemover.Mode.PLATE) {
                Spacer(modifier = Modifier.height(14.dp))
                Text("Background shot", color = Color.White, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    when (camView.plateState.value) {
                        PlateSubtractor.State.NONE ->
                            "Step out of the frame, point the camera at the empty scene, then capture. " +
                                "Three frames are averaged, so hold still for a moment."
                        PlateSubtractor.State.CAPTURING ->
                            "Capturing… keep the scene empty and the phone still."
                        PlateSubtractor.State.READY ->
                            "Captured. The matte now follows your real silhouette, hair included - " +
                                "but only while the tripod does not move."
                    },
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { camView.capturePlate() },
                        enabled = camView.plateState.value != PlateSubtractor.State.CAPTURING,
                    ) {
                        Text(
                            if (camView.plateState.value == PlateSubtractor.State.READY) "Retake" else "Capture",
                            fontSize = 12.sp
                        )
                    }
                    if (camView.plateState.value == PlateSubtractor.State.READY) {
                        TextButton(onClick = { camView.clearPlate() }) {
                            Text("Clear", fontSize = 12.sp)
                        }
                    }
                }

                ChromaSlider("Sensitivity", settings.plateSensitivity, 0.15f..0.85f) {
                    camView.setPlateSensitivity(it)
                }
                ChromaSlider("Edge softness", settings.plateFeather, 0f..0.8f) {
                    camView.setPlateFeather(it)
                }
                ChromaSlider("Stabilise", settings.plateSmooth, 0f..0.9f) {
                    camView.setPlateSmooth(it)
                }
                Text(
                    "Sensitivity trades leftovers of the room against keeping clothes and hair that " +
                        "match the wall. Slow light changes are absorbed; a moved phone is not - re-capture then.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
                if (camView.plateState.value != PlateSubtractor.State.READY) {
                    Text(
                        "Without a plate the frame passes through unchanged - nothing is cut yet.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
                if (settings.resolution.pixels >= HD_PIXELS) {
                    Text(
                        ("The plate path is six passes over the whole frame on the CPU, so " +
                                "%.1f MP will drop the frame rate; 720p keeps it up.")
                            .format(settings.resolution.megapixels),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("Replace background", color = Color.White, fontSize = 12.sp)
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CHROMA_BACKDROPS.forEach { value ->
                    SwatchDot(
                        color = Color(value),
                        selected = !settings.chromaUseImageBackground && settings.chromaBackgroundColor == value,
                        onClick = { camView.setChromaBackgroundColor(value) }
                    )
                }
                TextButton(onClick = { pickImage.launch("image/*") }) {
                    Text(if (settings.chromaUseImageBackground) "Change photo" else "Photo...", fontSize = 12.sp)
                }
            }
            if (settings.chromaUseImageBackground) {
                Text(
                    settings.chromaBackgroundLabel ?: "Custom image",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f)
            )
            .border(
                width = 1.dp,
                color = if (selected) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(50)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun SwatchDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.35f),
                shape = CircleShape
            )
            .clickable { onClick() }
    )
}

@Composable
private fun ChromaSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("$label  ${"%.2f".format(value)}", color = Color.White, fontSize = 12.sp)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
        )
    }
}

/** 1080p: above this the per-frame cost of the background engines stops being free. */
private const val HD_PIXELS = 1920 * 1080

/** Pixel count of a stream size, used by the "this will cost you" hints. */
private val CameraViewModel.StreamResolution.pixels: Int
    get() = size.width * size.height

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolutionDropdown(camView: CameraViewModel, settings: CameraViewModel.CameraSettings) {
    val currentResolution = settings.resolution
    val native = settings.nativeResolution
    val delivered = settings.activeSize
    var expanded by remember { mutableStateOf(false) }

    Column {
        ExposedDropdownMenuBox (
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = currentResolution.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Resolution") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                settings.supportedResolutions.forEach { res ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (res == native) "${res.label} · sensor"
                                else res.label
                            )
                        },
                        onClick = {
                            camView.setResolution(res)
                            Log.d("AWA", "Resolution set to ${res.label}:${res.size}")
                            expanded = false
                        }
                    )
                }
            }
        }

        // CameraX may hand over a different size than the one asked for - the sensor may refuse a
        // size next to a preview, or the bind may have fallen back. Show what is really arriving.
        if (delivered != null && delivered != currentResolution.size) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "Camera delivers ${delivered.width}x${delivered.height}",
                color = Color(0xFFFFC107),
                fontSize = 11.sp
            )
        } else if (currentResolution.size.width * currentResolution.size.height > 1920 * 1080) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "Sensor native: %.1f MP per frame, so the frame rate will be lower"
                    .format(currentResolution.megapixels),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
        }
    }
}
fun getLocalIpAddress(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (networkInterface in interfaces) {
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            for (address in networkInterface.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
        }
    } catch (e: Exception) {
        Log.e("AWA", "Failed to get IP", e)
    }
    return null
}
@Composable
fun checkPermissions(vararg permissions: String): State<Boolean> {
    val context = LocalContext.current
    if (LocalInspectionMode.current) return remember { mutableStateOf(true) }

    val permissionList = permissions.toList()

    fun isAllGranted() = permissionList.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    val hasPermissions = remember(permissionList) { mutableStateOf(isAllGranted()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPermissions.value = isAllGranted() }

    LaunchedEffect(permissionList) {
        if (!hasPermissions.value) launcher.launch(permissionList.toTypedArray())
    }

    return hasPermissions
}