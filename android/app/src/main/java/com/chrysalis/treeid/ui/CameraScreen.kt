package com.chrysalis.treeid.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chrysalis.treeid.Screen
import com.chrysalis.treeid.TreeViewModel
import com.chrysalis.treeid.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.min

@Composable
fun CameraScreen(vm: TreeViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { Images.decodeUri(context, uri) }.getOrNull()
                }
                if (bitmap != null) vm.identify(bitmap)
                else Toast.makeText(context, "Couldn't open that photo", Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturing by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            CameraPreview(onCaptureReady = { imageCapture = it })
            ViewfinderOverlay()
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Camera access lets you photograph a tree to identify it. " +
                        "Photos never leave your phone.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Allow camera")
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("NY Tree ID", color = Color.White, style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { vm.navigate(Screen.About) }) {
                Icon(Icons.Outlined.Info, contentDescription = "About", tint = Color.White)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            vm.modelProblem?.let { problem ->
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(problem, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                "Fill the square with one leaf, a needle cluster, bark, flowers, or fruit",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Photos") }

                ShutterButton(enabled = imageCapture != null && !capturing) {
                    val capture = imageCapture ?: return@ShutterButton
                    capturing = true
                    takePhoto(
                        context, capture,
                        onPhoto = { capturing = false; vm.identify(it) },
                        onError = {
                            capturing = false
                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                        },
                    )
                }

                FilledTonalButton(onClick = { vm.navigate(Screen.Guide) }) { Text("Guide") }
            }
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(60.dp).clip(CircleShape)
                .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f)),
        )
    }
}

/** Dims everything outside the centre square, which is exactly what the model looks at. */
@Composable
private fun ViewfinderOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val side = min(size.width, size.height)
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val shade = Color.Black.copy(alpha = 0.45f)
        drawRect(shade, topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(size.width, top))
        drawRect(
            shade,
            topLeft = Offset(0f, top + side),
            size = androidx.compose.ui.geometry.Size(size.width, size.height - top - side),
        )
        drawRect(shade, topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(left, side))
        drawRect(
            shade,
            topLeft = Offset(left + side, top),
            size = androidx.compose.ui.geometry.Size(size.width - left - side, side),
        )

        val inset = 12.dp.toPx()
        val arm = 36.dp.toPx()
        val stroke = 4.dp.toPx()
        val l = left + inset
        val t = top + inset
        val r = left + side - inset
        val b = top + side - inset
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(Color.White, Offset(x, y), Offset(x + dx, y), stroke, StrokeCap.Round)
            drawLine(Color.White, Offset(x, y), Offset(x, y + dy), stroke, StrokeCap.Round)
        }
        corner(l, t, arm, arm)
        corner(r, t, -arm, arm)
        corner(l, b, arm, -arm)
        corner(r, b, -arm, -arm)
    }
}

@Composable
private fun CameraPreview(onCaptureReady: (ImageCapture?) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    DisposableEffect(lifecycleOwner) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            val p = future.get()
            provider = p
            previewView.doOnLayout {
                if (!disposed) onCaptureReady(bindCamera(p, previewView, lifecycleOwner))
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbindAll()
            onCaptureReady(null)
        }
    }
}

private fun bindCamera(
    provider: ProcessCameraProvider,
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
): ImageCapture? {
    val selector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(Size(2048, 1536), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
        )
        .build()
    val preview = Preview.Builder().setResolutionSelector(selector).build()
    preview.setSurfaceProvider(previewView.surfaceProvider)
    val capture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(selector)
        .build()
    // The view port makes the captured photo's crop rect match what the viewfinder shows.
    val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
    previewView.viewPort?.let { group.setViewPort(it) }
    return try {
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
        capture
    } catch (e: Exception) {
        null
    }
}

private val captureExecutor = Executors.newSingleThreadExecutor()

private fun takePhoto(
    context: Context,
    capture: ImageCapture,
    onPhoto: (Bitmap) -> Unit,
    onError: (String) -> Unit,
) {
    val main = ContextCompat.getMainExecutor(context)
    capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val bitmap = try {
                Images.fromImageProxy(image)
            } catch (e: Exception) {
                null
            } finally {
                image.close()
            }
            main.execute {
                if (bitmap != null) onPhoto(bitmap) else onError("Couldn't read the photo")
            }
        }

        override fun onError(exception: ImageCaptureException) {
            main.execute { onError(exception.message ?: "Couldn't take the photo") }
        }
    })
}
