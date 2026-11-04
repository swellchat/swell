package com.jijilabsdigital.swell.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jijilabsdigital.swell.BuildConfig
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.Email
import com.jijilabsdigital.swell.R
import com.jijilabsdigital.swell.Swell
import com.jijilabsdigital.swell.SwellService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onSecureChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(Swell.profileName) }
    var stay by remember { mutableStateOf(Swell.stayConnected) }
    var secure by remember { mutableStateOf(Swell.blockScreenshots) }
    var lock by remember { mutableStateOf(Swell.appLock) }
    var receipts by remember { mutableStateOf(Swell.readReceipts) }
    var typing by remember { mutableStateOf(Swell.typingIndicators) }
    val activity = context as androidx.fragment.app.FragmentActivity
    var showPhrase by remember { mutableStateOf(false) }
    var confirmPhrase by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.settings)) },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.titleMedium)
            val pickAvatar = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
                if (uri != null) Swell.work {
                    runCatching { Swell.makeAvatar(uri) }.onSuccess { Swell.setMyAvatar(it) }.onFailure { Swell.toast(it.message ?: "") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Avatar(name.ifBlank { "?" }, Swell.ME, 72.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton({ pickAvatar.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(stringResource(R.string.profile_photo_pick))
                    }
                    val has = Swell.avatarStamps.collectAsStateWithLifecycle().value[Swell.ME].let { Swell.myAvatar != null }
                    if (has) TextButton({ Swell.work { Swell.setMyAvatar(null) } }) { Text(stringResource(R.string.profile_photo_remove)) }
                }
            }
            Text(stringResource(R.string.profile_photo_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                name, { name = it.take(64) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.profile_name_hint)) },
                supportingText = { Text(stringResource(R.string.name_body)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (name != Swell.profileName) Swell.profileName = name }),
                trailingIcon = { if (name != Swell.profileName) TextButton({ Swell.profileName = name }) { Text(stringResource(R.string.save)) } },
            )
            HorizontalDivider()
            OutlinedButton({ Swell.shareInvite(context) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.invite_friend)) }
            Toggle(stringResource(R.string.stay_connected), stringResource(R.string.stay_connected_body), stay) {
                stay = it; Swell.stayConnected = it
                if (it) SwellService.start(context) else SwellService.stop(context)
            }
            Toggle(stringResource(R.string.block_screenshots), stringResource(R.string.block_screenshots_body), secure) {
                secure = it; Swell.blockScreenshots = it; onSecureChanged(it)
            }
            Toggle(stringResource(R.string.app_lock), stringResource(R.string.app_lock_body), lock) { want ->
                if (want && !com.jijilabsdigital.swell.Auth.available(activity)) {
                    android.widget.Toast.makeText(context, R.string.need_screen_lock, android.widget.Toast.LENGTH_LONG).show()
                } else {
                    com.jijilabsdigital.swell.Auth.request(activity) { lock = want; Swell.appLock = want }
                }
            }
            Toggle(stringResource(R.string.receipts_title), stringResource(R.string.receipts_body), receipts) { receipts = it; Swell.readReceipts = it }
            Toggle(stringResource(R.string.typing_title), stringResource(R.string.typing_body), typing) { typing = it; Swell.typingIndicators = it }
            HorizontalDivider()
            val changes by Swell.changes.collectAsStateWithLifecycle()
            val blocked = remember(changes) { Swell.contacts().filter { it.blocked } }
            Text(stringResource(R.string.blocked_title), style = MaterialTheme.typography.titleMedium)
            if (blocked.isEmpty()) Text(stringResource(R.string.blocked_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            blocked.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(displayName(c), c.id, 36.dp)
                    Text(displayName(c), Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    OutlinedButton({ Swell.node()?.setBlocked(c, false) }) { Text(stringResource(R.string.unblock)) }
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Hero(Icons.Default.Email, 64.dp)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.mailbox_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.mailbox_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.phrase_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.phrase_settings_body), style = MaterialTheme.typography.bodyMedium)
            if (showPhrase) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text(
                        Swell.account?.phrase?.chunked(4)?.joinToString("\n") { it.joinToString("  ") } ?: "",
                        Modifier.padding(16.dp).fillMaxWidth(), fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton({ showPhrase = false }) { Text(stringResource(R.string.hide)) }
            } else {
                OutlinedButton({ confirmPhrase = true }) { Text(stringResource(R.string.show_phrase)) }
            }
            HorizontalDivider()
            OutlinedButton({ confirmWipe = true }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.delete_everything), color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Swell ${BuildConfig.VERSION_NAME} · " + stringResource(R.string.from_jiji),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // AGPL-3.0 section 5(d): appropriate legal notices and where to get the source.
            Text(
                stringResource(R.string.legal_notice),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton({
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://jijilabsdigital.com/swell/source")))
            }) { Text(stringResource(R.string.legal_source)) }
        }
    }
    if (confirmPhrase) {
        AlertDialog(
            onDismissRequest = { confirmPhrase = false },
            title = { Text(stringResource(R.string.show_phrase)) },
            text = { Text(stringResource(R.string.show_phrase_warning)) },
            confirmButton = {
                TextButton({ confirmPhrase = false; com.jijilabsdigital.swell.Auth.request(activity) { showPhrase = true } }) { Text(stringResource(R.string.show)) }
            },
            dismissButton = { TextButton({ confirmPhrase = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(stringResource(R.string.delete_everything)) },
            text = { Text(stringResource(R.string.delete_everything_body)) },
            confirmButton = { TextButton({ Swell.deleteEverything(context) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirmWipe = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Toggle(title: String, body: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}
