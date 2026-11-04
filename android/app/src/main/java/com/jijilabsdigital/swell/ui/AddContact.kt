package com.jijilabsdigital.swell.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jijilabsdigital.swell.R
import com.jijilabsdigital.swell.Swell

/**
 * Add a contact in person (QR) or at a distance (share the code through any app).
 * [incoming] is a code that arrived through a swell: link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(incoming: String?, onBack: () -> Unit, onConnected: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(if (incoming != null) 1 else 0) }
    // An invite link on the clipboard is offered here too (only when no link opened this screen).
    if (incoming == null) ClipInvitePrompt(firstOpenOnly = false) { code ->
        tab = 1
        Swell.accept(code, onDone = { c -> onConnected(c.id) }, onError = { Swell.toast(it) })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.add_contact)) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.tab_my_code)) })
                Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.tab_their_code)) })
            }
            if (tab == 0) MyCode(onConnected) else TheirCode(incoming, onConnected)
        }
    }
}

@Composable
private fun MyCode(onConnected: (String) -> Unit) {
    val context = LocalContext.current
    // The last code is shown at once from the phone's storage; a fresh one replaces it if it was used or expired.
    var code by remember { mutableStateOf(Swell.cachedInvite()) }
    var more by remember { mutableStateOf(false) }
    val shownAt = remember { System.nanoTime() }
    LaunchedEffect(Unit) { Swell.newInvite { code = it } }
    LaunchedEffect(code) { if (code != null && com.jijilabsdigital.swell.BuildConfig.DEBUG) android.util.Log.i("Swell", "MYCODE_MS ${(System.nanoTime() - shownAt) / 1_000_000}") }
    // A bright screen makes the QR easier to scan; restored when the screen closes.
    val activity = context as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(Unit) {
        val w = activity?.window
        val old = w?.attributes?.screenBrightness
        w?.attributes = w?.attributes?.apply { screenBrightness = 1f }
        onDispose { if (w != null && old != null) w.attributes = w.attributes.apply { screenBrightness = old } }
    }
    // When someone redeems this code, MainActivity shows the "check the safety number" prompt (audit H-01).
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.my_code_body), style = MaterialTheme.typography.bodyLarge)
        val c = code
        // The QR holds the invite LINK: any phone camera opens Swell (or the web page when Swell is not installed).
        val qr = Modifier.fillMaxWidth(0.8f).aspectRatio(1f).clip(MaterialTheme.shapes.large)
        if (c == null) Box(qr.background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f)))
        else QrImage(com.jijilabsdigital.swell.Links.invite(c), qr)
        Button({ Swell.shareInvite(context) }, Modifier.fillMaxWidth().height(54.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
            Text(stringResource(R.string.invite_friend), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
        androidx.compose.material3.TextButton({ more = !more }) { Text(stringResource(R.string.more_options)) }
        if (more && c != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Swell", c))
                    android.widget.Toast.makeText(context, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.copy_code)) }
                OutlinedButton({ code = null; Swell.newInvite(fresh = true) { code = it } }) { Text(stringResource(R.string.new_code)) }
            }
        }
        Text(stringResource(R.string.my_code_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TheirCode(incoming: String?, onConnected: (String) -> Unit) {
    var paste by remember { mutableStateOf(incoming ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(incoming == null) }
    var pending by remember { mutableStateOf<String?>(null) }
    // Nothing is sent until the user confirms (audit M-03).
    fun connect(code: String) {
        // A link, a "swell:" code or the bare code; text around a pasted link and line breaks are ignored.
        val flat = code.replace(Regex("\\s+"), " ")
        val clean = Regex("swell:[A-Za-z0-9_-]+").find(flat)?.value
            ?: Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{100,}(?![A-Za-z0-9_-])").find(code.replace(Regex("\\s+"), ""))?.value?.let { "swell:$it" }
            ?: run { error = "This is not a Swell code."; return }
        scanning = false; pending = clean
    }
    // A link from the browser or a chat opens straight to the confirm question.
    androidx.compose.runtime.LaunchedEffect(incoming) { if (incoming != null) connect(incoming) }
    fun confirmed(clean: String) {
        pending = null; busy = true; error = null
        Swell.accept(clean, onDone = { c -> busy = false; onConnected(c.id) }, onError = { e -> busy = false; error = e })
    }
    pending?.let { clean ->
        val name = Swell.profileName
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.confirm_connect_title)) },
            text = { Text(if (name.isBlank()) stringResource(R.string.confirm_connect_body_noname) else stringResource(R.string.confirm_connect_body, name)) },
            confirmButton = { androidx.compose.material3.TextButton({ confirmed(clean) }) { Text(stringResource(R.string.connect)) } },
            dismissButton = { androidx.compose.material3.TextButton({ pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.connecting_contact))
            }
            return@Column
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (scanning) {
            Text(stringResource(R.string.scan_body))
            QrScanner(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large)) { connect(it) }
            OutlinedButton({ scanning = false }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.paste_instead)) }
        } else {
            Text(stringResource(R.string.paste_body))
            OutlinedTextField(paste, { paste = it; error = null }, Modifier.fillMaxWidth().height(120.dp), label = { Text(stringResource(R.string.paste_hint)) })
            Button({ connect(paste) }, Modifier.fillMaxWidth().height(52.dp), enabled = paste.isNotBlank()) { Text(stringResource(R.string.connect)) }
            OutlinedButton({ scanning = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_instead)) }
        }
    }
}
