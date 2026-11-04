package com.jijilabsdigital.swell.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.jijilabsdigital.swell.R
import java.util.concurrent.Executors

/** Swell "night ocean" palette, taken from the pixel-art icon. */
object Ocean {
    val Deep = Color(0xFF0A0B22)
    val Navy = Color(0xFF121231)
    val Raised = Color(0xFF1B2152)
    val Line = Color(0xFF2A3358)
    val Electric = Color(0xFF2390DC)
    val Cyan = Color(0xFF06B6D4)
    val Foam = Color(0xFFB7E1F2)
    val Text = Color(0xFFEAF3FF)
    val Muted = Color(0xFF93A5CC)
    val Danger = Color(0xFFFF6B7A)
    val Bubble = Brush.linearGradient(listOf(Color(0xFF2563EB), Color(0xFF06B6D4)))
    val Sky = Brush.verticalGradient(listOf(Color(0xFF070818), Color(0xFF121231), Color(0xFF1B2A6B)))
}

val Brand = Ocean.Cyan

@Composable
fun SwellTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ocean.Electric, onPrimary = Color.White,
            primaryContainer = Ocean.Raised, onPrimaryContainer = Ocean.Foam,
            secondary = Ocean.Electric, onSecondary = Color.White,
            secondaryContainer = Ocean.Raised, onSecondaryContainer = Ocean.Foam,
            background = Ocean.Deep, onBackground = Ocean.Text,
            surface = Ocean.Deep, onSurface = Ocean.Text,
            surfaceVariant = Ocean.Raised, onSurfaceVariant = Ocean.Muted,
            surfaceContainer = Ocean.Navy, surfaceContainerHigh = Ocean.Raised,
            outline = Ocean.Line, outlineVariant = Ocean.Line,
            error = Ocean.Danger,
        ),
        typography = base,
        content = content,
    )
}

/** The Swell mark (white), as on the iOS welcome and empty states. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Image(androidx.compose.ui.res.painterResource(R.drawable.brand_mark), null, modifier.size(size))
}

/** Gradient circle with a Material icon: the Android counterpart of iOS "Hero". */
@Composable
fun Hero(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Dp = 96.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Ocean.Bubble), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(icon, null, Modifier.size(size * 0.46f), tint = Color.White)
    }
}

/** Same colour for the same contact, from the ocean palette. */
private val AVATARS = listOf(Color(0xFF2390DC), Color(0xFF06B6D4), Color(0xFF6366F1), Color(0xFF0EA5A4), Color(0xFF3B82F6), Color(0xFF8B5CF6))
fun avatarColor(id: String): Color = AVATARS[(id.hashCode() and 0x7fffffff) % AVATARS.size]

/** Soft gradient for initials avatars, one per contact (like the iOS Avatar). */
fun avatarBrush(id: String): Brush { val c = avatarColor(id); return Brush.linearGradient(listOf(c, c.copy(alpha = 0.65f).compositeOver(Ocean.Deep))) }

/** Up to two initials. */
fun initials(name: String): String = name.trim().split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }

/** Text or bytes as a QR code (ISO-8859-1 keeps bytes intact). */
@Composable
fun QrImage(content: String, modifier: Modifier) {
    // Black on white with a 4-module quiet zone, whatever the app theme: cameras need the contrast.
    val bmp = remember(content) {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q, EncodeHintType.CHARACTER_SET to "UTF-8"))
        Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until m.width) for (y in 0 until m.height) setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    Image(BitmapPainter(bmp.asImageBitmap(), filterQuality = FilterQuality.None), null, modifier)
}

@Composable
fun QrImage(content: String, size: Dp) {
    val bmp = remember(content) {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "ISO-8859-1"))
        Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until m.width) for (y in 0 until m.height) setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    Image(BitmapPainter(bmp.asImageBitmap(), filterQuality = FilterQuality.None), null, Modifier.size(size))
}

/** Camera QR scanner. Calls [onResult] once with the raw text (ISO-8859-1). No Google Play services. */
@Composable
fun QrScanner(modifier: Modifier, onResult: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Box(modifier, contentAlignment = Alignment.Center) { Text(stringResource(R.string.camera_needed)) }
        return
    }
    val owner = LocalLifecycleOwner.current
    val exec = remember { Executors.newSingleThreadExecutor() }
    var done by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { exec.shutdown() } }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val reader = MultiFormatReader().apply {
                    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.CHARACTER_SET to "ISO-8859-1"))
                }
                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder().setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(exec) { img ->
                    try {
                        if (done) return@setAnalyzer
                        val plane = img.planes[0]
                        val buf = plane.buffer
                        val data = ByteArray(buf.remaining()).also { buf.get(it) }
                        val src = PlanarYUVLuminanceSource(data, plane.rowStride, img.height, 0, 0, img.width, img.height, false)
                        val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text }.getOrNull()
                        reader.reset()
                        if (text != null) {
                            done = true
                            ContextCompat.getMainExecutor(ctx).execute { onResult(text) }
                        }
                    } finally {
                        img.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}


@androidx.compose.runtime.Composable
fun LockScreen(onUnlock: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onUnlock() }
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().background(Ocean.Sky),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(120.dp)
        Text(androidx.compose.ui.res.stringResource(R.string.locked_title), style = MaterialTheme.typography.headlineSmall, color = Ocean.Foam, modifier = Modifier.padding(16.dp))
        androidx.compose.material3.Button(onUnlock) { Text(androidx.compose.ui.res.stringResource(R.string.unlock)) }
    }
}

@androidx.compose.runtime.Composable
fun NewContactDialog(id: String, onCheck: () -> Unit, onLater: () -> Unit) {
    val c = com.jijilabsdigital.swell.Swell.contact(id) ?: return
    val who = c.name.ifBlank { androidx.compose.ui.res.stringResource(R.string.someone) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onLater,
        title = { Text(androidx.compose.ui.res.stringResource(R.string.new_contact_title, who)) },
        text = { Text(androidx.compose.ui.res.stringResource(R.string.new_contact_body)) },
        confirmButton = { androidx.compose.material3.TextButton(onCheck) { Text(androidx.compose.ui.res.stringResource(R.string.check_now)) } },
        dismissButton = { androidx.compose.material3.TextButton(onLater) { Text(androidx.compose.ui.res.stringResource(R.string.later)) } },
    )
}

/** Shown when the encrypted database cannot be opened on this phone any more (audit L-06). */
@androidx.compose.runtime.Composable
fun BrokenStorage(onReset: () -> Unit) {
    var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.broken_title), style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.padding(8.dp))
        androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.broken_body))
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.padding(12.dp))
        androidx.compose.material3.Button({ if (confirm) onReset() else confirm = true }) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(if (confirm) R.string.broken_confirm else R.string.broken_reset))
        }
    }
}
