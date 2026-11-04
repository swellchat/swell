package com.jijilabsdigital.swell.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jijilabsdigital.swell.R
import com.jijilabsdigital.swell.Swell

private val TIMERS = listOf(0, 3600, 86400, 7 * 86400)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactInfoScreen(contactId: String, onBack: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val changes by Swell.changes.collectAsStateWithLifecycle()
    val c = remember(changes) { Swell.contact(contactId) } ?: return
    val node = Swell.node() ?: return
    val safety = remember(contactId) { node.safetyNumber(c) }
    var name by remember(contactId) { mutableStateOf(c.name) }
    val shown = displayName(c)
    var mode by remember { mutableStateOf("") } // "", "show", "scan"
    var confirmDelete by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_LONG).show()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(shown) },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(shown, c.id, 96.dp)
            var nick by remember(contactId) { mutableStateOf(Swell.nickname(contactId)) }
            OutlinedTextField(
                nick, { nick = it.take(64) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.nickname)) },
                supportingText = { Text(stringResource(R.string.nickname_hint)) },
                trailingIcon = {
                    if (nick.trim() != Swell.nickname(contactId)) TextButton({ Swell.setNickname(contactId, nick) }) { Text(stringResource(R.string.nickname_save)) }
                },
            )
            OutlinedTextField(
                name, { name = it.take(64) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.contact_name)) },
                trailingIcon = {
                    if (name.trim() != c.name && name.isNotBlank()) TextButton({ node.rename(c, name) }) { Text(stringResource(R.string.save)) }
                },
            )

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.safety_title), style = MaterialTheme.typography.titleMedium)
                if (c.verified) Icon(Icons.Default.Verified, null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(stringResource(if (c.verified) R.string.safety_verified else R.string.safety_body), style = MaterialTheme.typography.bodyMedium)
            Text(
                safety.displayableFingerprint.displayText.chunked(5).chunked(4).joinToString("\n") { it.joinToString("  ") },
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium,
            )
            when (mode) {
                "show" -> Surface(color = Color.White, shape = MaterialTheme.shapes.large, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Box(Modifier.padding(12.dp)) { QrImage(String(safety.scannableFingerprint.serialized, Charsets.ISO_8859_1), 220.dp) }
                }
                "scan" -> QrScanner(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large)) { text ->
                    mode = ""
                    if (node.verifyScanned(c, text.toByteArray(Charsets.ISO_8859_1))) toast(context.getString(R.string.verify_ok))
                    else toast(context.getString(R.string.verify_bad))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ mode = if (mode == "show") "" else "show" }) { Text(stringResource(R.string.show_my_qr)) }
                Button({ mode = if (mode == "scan") "" else "scan" }) { Text(stringResource(R.string.scan_their_qr)) }
            }
            if (c.verified) TextButton({ node.setVerified(c, false) }) { Text(stringResource(R.string.unverify)) }

            HorizontalDivider()
            Text(stringResource(R.string.disappearing), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.disappearing_body), style = MaterialTheme.typography.bodyMedium)
            TIMERS.forEach { sec ->
                Row(
                    Modifier.fillMaxWidth().selectable(c.expireSec == sec) {
                        Swell.whenOnline({ Swell.toast(it) }) { n -> n.setDisappearing(c, sec) }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(c.expireSec == sec, null)
                    Text(timerLabel(sec), Modifier.padding(start = 8.dp))
                }
            }

            HorizontalDivider()
            OutlinedButton({ node.setBlocked(c, !c.blocked) }, Modifier.fillMaxWidth()) {
                Text(stringResource(if (c.blocked) R.string.unblock else R.string.block))
            }
            if (Swell.canReport) OutlinedButton({ reporting = true }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.report), color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton({ confirmDelete = true }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.delete_contact), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (reporting) ReportDialog(c.id, onDone = { blocked ->
        reporting = false
        if (blocked) node.setBlocked(c, true)
    }, onCancel = { reporting = false })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_contact)) },
            text = { Text(stringResource(R.string.delete_contact_body)) },
            confirmButton = {
                TextButton({
                    node.delete(c); Swell.db.deleteMessages(c.id); Swell.forgetContact(c.id); Swell.changes.value++
                    confirmDelete = false; onDeleted()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun timerLabel(sec: Int): String = when (sec) {
    0 -> stringResource(R.string.timer_off)
    3600 -> stringResource(R.string.timer_hour)
    86400 -> stringResource(R.string.timer_day)
    else -> stringResource(R.string.timer_week)
}

private val REASONS = listOf(R.string.reason_spam, R.string.reason_harassment, R.string.reason_threat, R.string.reason_child, R.string.reason_other)

/**
 * Report: reason, optional note, and the contact's last messages only if the user keeps the box ticked.
 * The user sees exactly what is sent before sending.
 */
@Composable
private fun ReportDialog(contactId: String, onDone: (blocked: Boolean) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var reason by remember { mutableStateOf(REASONS.first()) }
    var note by remember { mutableStateOf("") }
    var include by remember { mutableStateOf(true) }
    var block by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    val recent = remember(contactId) { Swell.db.messages(contactId).filter { it.incoming && it.fileId == null }.takeLast(10) }
    AlertDialog(
        onDismissRequest = { if (!sending) onCancel() },
        title = { Text(stringResource(R.string.report_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.report_body), style = MaterialTheme.typography.bodyMedium)
                REASONS.forEach { r ->
                    Row(Modifier.fillMaxWidth().selectable(reason == r) { reason = r }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(reason == r, null); Text(stringResource(r), Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.report_note)) }, maxLines = 3)
                Row(Modifier.fillMaxWidth().clickable { include = !include }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(include, null)
                    Text(stringResource(R.string.report_include, recent.size), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (include) recent.forEach { Text("• " + it.text.take(120), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(Modifier.fillMaxWidth().clickable { block = !block }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(block, null)
                    Text(stringResource(R.string.report_block), Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton({
                sending = true
                val text = context.getString(reason) + if (note.isNotBlank()) " — " + note.trim() else ""
                Swell.report(contactId, text, if (include) recent.map { it.id }.toSet() else emptySet(),
                    onDone = { Swell.toast(context.getString(R.string.report_sent)); onDone(block) },
                    onError = { Swell.toast(it); sending = false })
            }, enabled = !sending) { Text(stringResource(if (sending) R.string.status_sending else R.string.report_send), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onCancel, enabled = !sending) { Text(stringResource(R.string.cancel)) } },
    )
}
