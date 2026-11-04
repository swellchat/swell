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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jijilabsdigital.swell.R
import com.jijilabsdigital.swell.Swell
import java.util.concurrent.Executors

/** One look: Swell Navy, Midnight (true black), Swell Light or Mist, as in the iOS appearance settings. */
class Palette(
    val dark: Boolean, val deep: Color, val incoming: Color, val card: Color, val line: Color,
    val text: Color, val muted: Color, val foam: Color, val sky: Brush,
)

object Palettes {
    // Swell: the same values as the iOS Theme (dark navy, soft mist in light).
    val SwellNavy = Palette(true, Color(0xFF0A0B22), Color(0xFF121231), Color(0xFF1B2152), Color(0xFF2A3358), Color(0xFFEAF3FF), Color(0xFF93A5CC), Color(0xFFB7E1F2),
        Brush.verticalGradient(listOf(Color(0xFF070818), Color(0xFF121231), Color(0xFF1B2A6B))))
    val SwellLight = Palette(false, Color(0xFFF2F6FA), Color(0xFFE6ECF3), Color(0xFFFFFFFF), Color(0xFFD5DDE8), Color(0xFF0F1B2D), Color(0xFF5B6B85), Color(0xFF0B3A63),
        Brush.verticalGradient(listOf(Color(0xFFF7FAFD), Color(0xFFE9F1F8), Color(0xFFD3E6F5))))
    // Midnight: true black for OLED screens.
    val Midnight = Palette(true, Color(0xFF000000), Color(0xFF0E0F14), Color(0xFF16181F), Color(0xFF272A33), Color(0xFFEDEFF5), Color(0xFF8A90A0), Color(0xFFC3D4F0),
        Brush.verticalGradient(listOf(Color(0xFF000000), Color(0xFF07080C), Color(0xFF0F1220))))
    // Mist: a cooler, greyer light look.
    val Mist = Palette(false, Color(0xFFE9EDF2), Color(0xFFDCE2EA), Color(0xFFF7F9FB), Color(0xFFC8D0DB), Color(0xFF1B2433), Color(0xFF5E6B80), Color(0xFF23405F),
        Brush.verticalGradient(listOf(Color(0xFFF1F4F8), Color(0xFFE3E8EF), Color(0xFFD2DAE6))))
}

/** Accent choices: Swell gradient (blue to cyan, default), Teal, Indigo, Violet, Coral, Amber. Pair = accent and its gradient partner. */
val ACCENT_PAIRS = listOf(
    Color(0xFF2390DC) to Color(0xFF06B6D4), Color(0xFF0EA5A4) to Color(0xFF34D399), Color(0xFF6366F1) to Color(0xFF8B9CFF),
    Color(0xFF8B5CF6) to Color(0xFFC084FC), Color(0xFFF2705B) to Color(0xFFFFA48F), Color(0xFFF5A524) to Color(0xFFFFD166),
)
val ACCENTS = ACCENT_PAIRS.map { it.first }
val ACCENT_NAMES = listOf("Swell", "Teal", "Indigo", "Violet", "Coral", "Amber")

val LocalPalette = androidx.compose.runtime.staticCompositionLocalOf { Palettes.SwellNavy }
val LocalAccent = androidx.compose.runtime.staticCompositionLocalOf { ACCENTS[0] }

/** Colours for the current look. Everything here follows the chosen appearance and accent. */
object Ocean {
    val Deep: Color @Composable get() = LocalPalette.current.deep
    val Navy: Color @Composable get() = LocalPalette.current.incoming
    val Raised: Color @Composable get() = LocalPalette.current.card
    val Line: Color @Composable get() = LocalPalette.current.line
    val Text: Color @Composable get() = LocalPalette.current.text
    val Muted: Color @Composable get() = LocalPalette.current.muted
    val Foam: Color @Composable get() = LocalPalette.current.foam
    val Sky: Brush @Composable get() = LocalPalette.current.sky
    /** The accent (primary) colour and a lighter companion for gradients. */
    val Electric: Color @Composable get() = LocalAccent.current
    val Cyan: Color @Composable get() = ACCENT_PAIRS.firstOrNull { it.first == LocalAccent.current }?.second ?: lighten(LocalAccent.current)
    val Danger = Color(0xFFFF6B7A)
    val Bubble: Brush @Composable get() = Brush.linearGradient(listOf(if (LocalAccent.current == ACCENTS[0]) Color(0xFF2563EB) else darken(LocalAccent.current), Cyan))
    /** Fixed navy for text on bright accent surfaces. */
    val OnAccent = Color(0xFF0A0B22)
}

fun lighten(c: Color, f: Float = 0.25f) = Color(c.red + (1 - c.red) * f, c.green + (1 - c.green) * f, c.blue + (1 - c.blue) * f)
fun darken(c: Color, f: Float = 0.18f) = Color(c.red * (1 - f), c.green * (1 - f), c.blue * (1 - f))

@Composable
fun SwellTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val look by Swell.look.collectAsStateWithLifecycle()
    val system = androidx.compose.foundation.isSystemInDarkTheme()
    val p = when (look.theme) {
        "navy" -> Palettes.SwellNavy
        "midnight" -> Palettes.Midnight
        "light" -> Palettes.SwellLight
        "mist" -> Palettes.Mist
        else -> if (system) Palettes.SwellNavy else Palettes.SwellLight // follow the system
    }
    val accent = ACCENTS[look.accent.coerceIn(0, ACCENTS.lastIndex)]
    val scheme = if (p.dark) androidx.compose.material3.darkColorScheme(
        primary = accent, onPrimary = Color.White,
        primaryContainer = p.card, onPrimaryContainer = p.foam,
        secondary = accent, onSecondary = Color.White,
        secondaryContainer = p.card, onSecondaryContainer = p.foam,
        background = p.deep, onBackground = p.text,
        surface = p.deep, onSurface = p.text,
        surfaceVariant = p.card, onSurfaceVariant = p.muted,
        surfaceContainer = p.incoming, surfaceContainerHigh = p.card,
        outline = p.line, outlineVariant = p.line, error = Ocean.Danger,
    ) else androidx.compose.material3.lightColorScheme(
        primary = darken(accent, 0.12f), onPrimary = Color.White,
        primaryContainer = p.incoming, onPrimaryContainer = p.foam,
        secondary = accent, onSecondary = Color.White,
        secondaryContainer = p.incoming, onSecondaryContainer = p.foam,
        background = p.deep, onBackground = p.text,
        surface = p.deep, onSurface = p.text,
        surfaceVariant = p.card, onSurfaceVariant = p.muted,
        surfaceContainer = p.incoming, surfaceContainerHigh = p.card,
        outline = p.line, outlineVariant = p.line, error = Color(0xFFD93A4A),
    )
    // System bar icons follow the chosen look (dark icons on a light look).
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        val w = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val c = androidx.core.view.WindowCompat.getInsetsController(w, view)
        c.isAppearanceLightStatusBars = !p.dark; c.isAppearanceLightNavigationBars = !p.dark
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides p, LocalAccent provides accent) {
        MaterialTheme(colorScheme = scheme, typography = base, content = content)
    }
}

/** The Swell mark (white), as on the iOS welcome and empty states. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    // The mark is white; on a light look it takes the accent colour.
    val p = LocalPalette.current
    Image(androidx.compose.ui.res.painterResource(R.drawable.brand_mark), null, modifier.size(size),
        colorFilter = if (p.dark) null else androidx.compose.ui.graphics.ColorFilter.tint(LocalAccent.current))
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
fun avatarBrush(id: String): Brush { val c = avatarColor(id); return Brush.linearGradient(listOf(c, c.copy(alpha = 0.65f).compositeOver(Color(0xFF0A0B22)))) }

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
