package com.jijilabsdigital.swell.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.jijilabsdigital.swell.R
import com.jijilabsdigital.swell.Swell
import com.jijilabsdigital.swell.data.StoredMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Per-message timer choices in the composer (seconds; 0 = off). */
val TIMER_CHOICES = listOf(0, 10, 60, 3600, 86400)

@Composable
fun msgTimerLabel(sec: Int): String = when (sec) {
    0 -> stringResource(R.string.timer_off)
    10 -> stringResource(R.string.timer_10s)
    60 -> stringResource(R.string.timer_1m)
    3600 -> stringResource(R.string.timer_1h)
    86400 -> stringResource(R.string.timer_1d)
    else -> "$sec s"
}

/** Short label for the composer button ("10s", "1m", "1h", "1d"). */
fun timerShort(sec: Int): String = when {
    sec <= 0 -> ""
    sec < 60 -> "${sec}s"
    sec < 3600 -> "${sec / 60}m"
    sec < 86400 -> "${sec / 3600}h"
    else -> "${sec / 86400}d"
}

/** Composer timer button: Off / 10 s / 1 min / 1 h / 1 day. Disabled (with "Update needed") if the contact's app is too old. */
@Composable
fun TimerButton(sec: Int, supported: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        val desc = stringResource(if (supported) R.string.timer_button else R.string.timer_update_needed)
        Row(
            Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).clickable { open = true }.padding(horizontal = 8.dp).semantics { contentDescription = desc },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Timer, null, Modifier.size(24.dp), tint = if (sec > 0) Ocean.Cyan else MaterialTheme.colorScheme.onSurfaceVariant)
            if (sec > 0) Text(timerShort(sec), color = Ocean.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        DropdownMenu(open, { open = false }) {
            if (!supported) {
                DropdownMenuItem(text = { Text(stringResource(R.string.update_needed), color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = { open = false }, enabled = false)
            } else TIMER_CHOICES.forEach { t ->
                DropdownMenuItem(
                    text = { Text(msgTimerLabel(t), fontWeight = if (t == sec) FontWeight.Bold else null) },
                    leadingIcon = { Icon(Icons.Default.Timer, null) },
                    onClick = { open = false; onPick(t) },
                )
            }
        }
    }
}

/** Countdown ring on a bubble. Before the countdown starts it is full; afterwards it empties second by second. */
@Composable
fun TimerRing(m: StoredMessage, size: androidx.compose.ui.unit.Dp = 14.dp) {
    val total = if (m.viewOnce) Swell.VIEW_ONCE_GRACE_MS else m.timerSec * 1000L
    if (total <= 0L) return
    val now by produceState(System.currentTimeMillis(), m.vanishAt) {
        while (m.vanishAt > 0) { value = System.currentTimeMillis(); delay(250) }
    }
    val left = if (m.vanishAt > 0) ((m.vanishAt - now).coerceAtLeast(0).toFloat() / total).coerceIn(0f, 1f) else 1f
    val desc = stringResource(R.string.timer_ring)
    val color = LocalContentColorOrWhite()
    Canvas(Modifier.size(size).semantics { contentDescription = desc }) {
        val stroke = Stroke(width = this.size.minDimension * 0.18f, cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val box = Size(this.size.width - stroke.width, this.size.height - stroke.width)
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), box, style = stroke)
        drawArc(color, -90f, 360f * left, false, Offset(inset, inset), box, style = stroke)
    }
}

@Composable
private fun LocalContentColorOrWhite(): Color = androidx.compose.material3.LocalContentColor.current

/** The abstract placeholder of a view-once photo: no pixels of the photo exist on this phone's screen. */
@Composable
fun ViewOnceTile(m: StoredMessage, onOpen: () -> Unit) {
    val opened = m.openedAt > 0
    val canOpen = m.incoming && !opened && m.fileId != null
    Box(
        Modifier.size(width = 200.dp, height = 132.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1E2A6B), Color(0xFF0E7490))))
            .then(if (canOpen) Modifier.clickable(onClick = onOpen) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.TopStart).padding(18.dp).size(70.dp).clip(CircleShape).background(Color(0x5506B6D4)).blur(18.dp))
        Box(Modifier.align(Alignment.BottomEnd).padding(14.dp).size(56.dp).clip(CircleShape).background(Color(0x552390DC)).blur(16.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (opened) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, Modifier.size(34.dp), tint = Color.White)
            Text(
                stringResource(if (m.fileMime?.startsWith("video/") == true) R.string.view_once_video else R.string.view_once_photo),
                color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            )
            Text(
                stringResource(if (opened) R.string.view_once_opened else if (canOpen) R.string.view_once_open else R.string.view_once_sent_hint),
                color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
            )
        }
    }
}

/** Blocks screenshots and screen recording in this dialog's window (and in the activity while it is shown). */
@Composable
fun SecureDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }
}

/** Keeps the activity window secure while [on] (private mode), restoring the app setting afterwards. */
@Composable
fun SecureWhile(on: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(on) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (on && !Swell.blockScreenshots) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * Full-screen viewer for a view-once photo. The bytes exist only in memory: [Swell.beginViewOnce] deletes the file
 * and its key the moment they are read, and closing the viewer starts the one-minute countdown and tells the sender.
 */
@Composable
fun ViewOnceViewer(m: StoredMessage, onClose: () -> Unit) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(m.id) {
        val bytes = withContext(Dispatchers.IO) { Swell.beginViewOnce(m) }
        val bmp = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        bytes?.fill(0)
        if (bmp == null) failed = true else bitmap = bmp
    }
    DisposableEffect(m.id) {
        onDispose {
            bitmap = null
            // Only counts as opened if the file was really read (beginViewOnce marks the row).
            Swell.work { Swell.db.message(m.id)?.let { if (it.openedAt > 0) Swell.endViewOnce(it) } }
        }
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (failed) Text(stringResource(R.string.view_once_opened), Modifier.align(Alignment.Center), color = Color.White)
            Text(
                stringResource(R.string.view_once_notice), Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding().padding(12.dp),
                color = Color.White, fontSize = 13.sp,
            )
            IconButton(onClose, Modifier.align(Alignment.TopEnd).padding(8.dp).size(48.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White)
            }
        }
    }
}

/** The darker look of a chat in private mode. */
object PrivateLook {
    val Background = Color(0xFF020308)
    val Bar = Color(0xFF05060F)
    val Bubble = Color(0xFF1A1030)
    val Accent = Color(0xFFB69CFF)
}

@Composable
fun PrivateBanner(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(Color(0xFF14102B)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = PrivateLook.Accent)
        Text(stringResource(R.string.private_banner), color = PrivateLook.Accent, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
