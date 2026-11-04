package com.jijilabsdigital.swell.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jijilabsdigital.swell.R
import com.jijilabsdigital.swell.Swell
import com.jijilabsdigital.swell.data.Db
import com.jijilabsdigital.swell.data.Files
import com.jijilabsdigital.swell.data.Reaction
import com.jijilabsdigital.swell.data.StoredMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The six quick reactions (wire: one emoji string of at most 16 bytes). */
val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/** One-line text for a quote or a chat-list preview of [m]. */
@Composable
fun previewText(m: StoredMessage): String = when {
    m.deleted -> stringResource(R.string.msg_deleted)
    m.viewOnce -> stringResource(R.string.view_once_photo)
    m.isVoice -> "🎤 " + stringResource(R.string.voice_message)
    m.fileId == null -> m.text
    m.text.isNotBlank() -> (if (m.isImage) "🖼 " else "📎 ") + m.text
    m.isImage -> stringResource(R.string.photo)
    else -> "📎 " + (m.fileName ?: "")
}

/** The quoted message inside a bubble; tap to jump to the original. */
@Composable
fun QuoteBox(original: StoredMessage?, senderLabel: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.22f))
            .then(if (original != null) Modifier.clickable(onClick = onClick) else Modifier).height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(Ocean.Cyan))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (original != null) {
                Text(senderLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Ocean.Cyan, maxLines = 1)
                Text(previewText(original), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text(stringResource(R.string.original_unavailable), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            }
        }
    }
}

/** Bar above the input that shows what we are replying to or editing. */
@Composable
fun ComposerBanner(title: String, body: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(36.dp).background(Ocean.Cyan))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Ocean.Cyan, maxLines = 1)
            Text(body, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClose) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
    }
}

/** Counts under a bubble: one chip per emoji; our own is outlined. Tap toggles ours. */
@Composable
fun ReactionChips(m: StoredMessage, list: List<Reaction>, modifier: Modifier = Modifier) {
    if (list.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        list.groupBy { it.emoji }.entries.sortedByDescending { it.value.size }.forEach { (emoji, who) ->
            val mine = who.any { it.who == Db.ME }
            Row(
                Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(12.dp))
                    .background(if (mine) Ocean.Electric.copy(alpha = 0.35f) else Ocean.Raised)
                    .clickable { Swell.react(m, emoji) }.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(emoji, fontSize = 14.sp)
                if (who.size > 1) Text(who.size.toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun DeletedText() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Default.Block, null, Modifier.size(16.dp))
        Text(stringResource(R.string.msg_deleted), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
    }
}

/** Play/pause button and a progress bar for a voice note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceRow(m: StoredMessage) {
    val playing by VoicePlayer.playing.collectAsStateWithLifecycle()
    val progress by VoicePlayer.progress.collectAsStateWithLifecycle()
    val active = playing == m.id
    val scope = rememberCoroutineScope()
    LaunchedEffect(active) { while (active) { VoicePlayer.tick(); delay(100) } }
    val length by produceState(m.durationMs, m.id) {
        if (value == 0) value = withContext(Dispatchers.IO) { m.fileId?.let { Files.load(it) }?.let { audioDurationMs(it) } ?: 0 }
    }
    Row(Modifier.widthIn(min = 200.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.18f)).clickable {
            val id = m.fileId ?: return@clickable
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { Files.load(id) } ?: return@launch
                VoicePlayer.toggle(m.id, bytes)
            }
        }, contentAlignment = Alignment.Center) {
            Icon(
                if (active) Icons.Default.Pause else Icons.Default.PlayArrow,
                stringResource(if (active) R.string.voice_pause else R.string.voice_play),
            )
        }
        Column(Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { if (active) progress else 0f }, Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = androidx.compose.material3.LocalContentColor.current, trackColor = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.3f),
            )
            Text(
                if (length > 0) formatDuration(length) else stringResource(R.string.voice_message),
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
