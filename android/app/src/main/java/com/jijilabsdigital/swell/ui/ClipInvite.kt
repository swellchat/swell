package com.jijilabsdigital.swell.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jijilabsdigital.swell.Links
import com.jijilabsdigital.swell.R
import com.jijilabsdigital.swell.Swell
import kotlinx.coroutines.delay

/**
 * Deferred invite: the web page copies the invite link to the clipboard when "Get Swell" is tapped, so the code survives
 * the store round trip. The clipboard is read only at two moments (first open after install, and the Add contact
 * screen), and only an invite link is ever used.
 */
object ClipInvite {
    private val LINK = Regex("^https://jijilabsdigital\\.com/swell/i#(swell:)?[A-Za-z0-9_-]{20,}$")

    /** The invite code (`swell:...`) if the clipboard holds a Swell invite link, else null. Nothing else is looked at. */
    fun read(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val text = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim() }.getOrNull() ?: return null
        if (!LINK.matches(text)) return null
        return Links.codeFrom(text)
    }

    /** Clears the clipboard if it still holds an invite link (ours). */
    fun clear(context: Context) {
        if (read(context) == null) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching { if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(android.content.ClipData.newPlainText("", "")) }
    }

    fun isLink(text: String) = LINK.matches(text.trim())
}

/**
 * Offers "Connect with the person who invited you?" when the clipboard holds an invite link. With [firstOpenOnly] it asks
 * once per install (the first time it is shown), otherwise every time the screen appears.
 */
@Composable
fun ClipInvitePrompt(firstOpenOnly: Boolean, onConnect: (String) -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (firstOpenOnly && Swell.clipChecked) return@LaunchedEffect
        if (firstOpenOnly) Swell.clipChecked = true
        delay(500) // Android lets only the focused window read the clipboard
        code = ClipInvite.read(context)
    }
    code?.let { c ->
        AlertDialog(
            onDismissRequest = { code = null; ClipInvite.clear(context) },
            title = { Text(stringResource(R.string.clip_invite_title)) },
            text = { Text(stringResource(R.string.clip_invite_body)) },
            confirmButton = { TextButton({ code = null; ClipInvite.clear(context); onConnect(c) }) { Text(stringResource(R.string.connect)) } },
            dismissButton = { TextButton({ code = null; ClipInvite.clear(context) }) { Text(stringResource(R.string.not_now)) } },
        )
    }
}
