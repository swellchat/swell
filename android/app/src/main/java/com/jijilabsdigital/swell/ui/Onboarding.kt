package com.jijilabsdigital.swell.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jijilabsdigital.swell.R
import com.jijilabsdigital.swell.Swell
import com.jijilabsdigital.swell.core.RecoveryPhrase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Onboarding() {
    var step by remember { mutableStateOf("welcome") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        Swell.finishOnboarding(context)
    }
    fun finish() {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else Swell.finishOnboarding(context)
    }
    when (step) {
        "welcome" -> Welcome(
            // One tap: the account is made now; the recovery phrase is offered later as a gentle card in the chat list.
            onStart = { scope.launch { withContext(Dispatchers.IO) { Swell.createAccount() }; step = "name" } },
            onRestore = { step = "restore" },
        )
        "restore" -> RestoreScreen(onBack = { step = "welcome" }, onDone = { step = "name" })
        "name" -> NameScreen(onDone = { finish() })
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

@Composable
private fun Welcome(onStart: () -> Unit, onRestore: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ocean.Sky)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            BrandMark(120.dp)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Ocean.Text)
            Text(stringResource(R.string.welcome_line), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = Ocean.Muted)
            Spacer(Modifier.height(4.dp))
            listOf(
                Icons.Default.PersonOff to R.string.welcome_p1, Icons.Default.Lock to R.string.welcome_p2,
                Icons.Default.Shield to R.string.welcome_p3, Icons.Default.PanTool to R.string.welcome_p4,
            ).forEach { (icon, text) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(icon, null, Modifier.width(28.dp), tint = Ocean.Electric)
                    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = Ocean.Muted)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onStart, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp)) {
                Text(stringResource(R.string.welcome_start), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onRestore, Modifier.fillMaxWidth()) { Text(stringResource(R.string.welcome_restore)) }
        }
    }
}

@Composable
fun PhraseScreen(words: List<String>, onDone: () -> Unit) = Page {
    var ok by remember { mutableStateOf(false) }
    Text(stringResource(R.string.phrase_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.phrase_body))
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            words.chunked(2).forEachIndexed { row, pair ->
                Row {
                    pair.forEachIndexed { i, w ->
                        Text("${row * 2 + i + 1}. $w", fontFamily = FontFamily.Monospace, modifier = Modifier.width(150.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().toggleable(ok, role = androidx.compose.ui.semantics.Role.Checkbox) { ok = it }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(ok, null)
        Text(stringResource(R.string.phrase_confirm))
    }
    Button(onDone, Modifier.fillMaxWidth().height(52.dp), enabled = ok) { Text(stringResource(R.string.done)) }
}

@Composable
private fun RestoreScreen(onBack: () -> Unit, onDone: () -> Unit) = Page {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Text(stringResource(R.string.restore_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.restore_body))
    OutlinedTextField(
        text, { text = it; error = null }, Modifier.fillMaxWidth().height(160.dp),
        label = { Text(stringResource(R.string.restore_hint)) },
        isError = error != null, supportingText = { error?.let { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
    )
    Text(stringResource(R.string.restore_note), style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = {
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Swell.restoreAccount(RecoveryPhrase.parse(text)) } }
                r.onSuccess { onDone() }.onFailure { error = it.message }
            }
        },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        enabled = RecoveryPhrase.parse(text).size == 12,
    ) { Text(stringResource(R.string.restore_button)) }
    TextButton(onBack) { Text(stringResource(R.string.back)) }
}

@Composable
private fun NameScreen(onDone: () -> Unit) = Page {
    var name by remember { mutableStateOf("") }
    Text(stringResource(R.string.name_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.name_body))
    OutlinedTextField(name, { name = it.take(64) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.name_hint)) })
    Button({ Swell.profileName = name; onDone() }, Modifier.fillMaxWidth().height(52.dp)) {
        Text(stringResource(if (name.isBlank()) R.string.skip else R.string.next))
    }
}
