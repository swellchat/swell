package com.jijilabsdigital.swell.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.jijilabsdigital.swell.data.Files
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.rememberCoroutineScope
import com.jijilabsdigital.swell.data.Reaction
import com.jijilabsdigital.swell.data.Db
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jijilabsdigital.swell.Net
import com.jijilabsdigital.swell.R
import com.jijilabsdigital.swell.Swell
import com.jijilabsdigital.swell.core.Contact
import com.jijilabsdigital.swell.data.StoredMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The nickname (local only), else the contact's own name, else a placeholder. */
@Composable
fun displayName(c: Contact): String = Swell.nickname(c.id).ifBlank { c.name }.ifBlank { stringResource(R.string.new_contact) }

/** Profile photo of a contact (or "me"); initials on a colour when there is none. */
@Composable
fun Avatar(name: String, id: String, size: androidx.compose.ui.unit.Dp = 46.dp) {
    val stamps by Swell.avatarStamps.collectAsStateWithLifecycle()
    val img by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, id, stamps[id]) {
        value = withContext(Dispatchers.IO) { Swell.avatarBytes(id)?.let { decode(it, 256) } }
    }
    Box(Modifier.size(size).clip(CircleShape).background(avatarBrush(id)), contentAlignment = Alignment.Center) {
        val i = img
        if (i != null) Image(i, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(initials(name), color = Color.White, fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NoteAvatar() {
    Box(Modifier.size(46.dp).clip(CircleShape).background(Ocean.Bubble), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Bookmark, null, tint = Color.White)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(onOpen: (String, String?) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit, onPhrase: () -> Unit = {}) {
    val changes by Swell.changes.collectAsStateWithLifecycle()
    val net by Swell.net.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    var phraseCardShown by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val data by produceState(Pair(emptyList<Contact>(), emptyMap<String, StoredMessage>()), changes, net) {
        value = withContext(Dispatchers.IO) { Swell.contacts() to Swell.db.lastMessages() }
    }
    val hits by produceState(emptyList<StoredMessage>(), query, changes) {
        value = if (query.isBlank()) emptyList() else withContext(Dispatchers.IO) { Swell.db.search(query) }
    }
    val (contacts, last) = data
    val noteLabel = stringResource(R.string.note_to_self)
    // First open after install: an invite link on the clipboard (from the web page's "Get Swell") is offered in one tap.
    val ctx0 = androidx.compose.ui.platform.LocalContext.current
    ClipInvitePrompt(firstOpenOnly = true) { code ->
        Swell.accept(code, onDone = { c -> onOpen(c.id, null) }, onError = { Swell.toast(it) })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        androidx.compose.material3.TextField(
                            query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                            placeholder = { Text(stringResource(R.string.search_chats_hint)) },
                            colors = androidx.compose.material3.TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                        )
                    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BrandMark(30.dp)
                        Text(stringResource(R.string.app_name), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (searching) IconButton({ searching = false; query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.search_close)) }
                    else {
                        IconButton({ searching = true }) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                        IconButton(onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!searching) ExtendedFloatingActionButton(
                onClick = onAdd, containerColor = Ocean.Cyan, contentColor = Ocean.OnAccent,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.add_contact), fontWeight = FontWeight.Bold) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            @Composable fun Row1(title: String, avatar: @Composable () -> Unit, m: StoredMessage?, verified: Boolean, blocked: Boolean, subtitle: String?, onClick: () -> Unit) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    avatar()
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (verified) Icon(Icons.Default.Verified, stringResource(R.string.verified), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            if (blocked) Icon(Icons.Default.Block, stringResource(R.string.blocked), Modifier.size(16.dp))
                        }
                        val sub = subtitle ?: m?.let { previewText(it) }
                        if (sub != null) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    m?.let { Text(if (System.currentTimeMillis() - it.at < 60_000) stringResource(R.string.time_now) else DateUtils.getRelativeTimeSpanString(it.at).toString(), style = MaterialTheme.typography.labelSmall) }
                }
                HorizontalDivider(Modifier.padding(start = 72.dp))
            }
            if (searching && query.isNotBlank()) {
                val q = query.trim()
                val nameHits = contacts.filter { it.name.contains(q, true) }
                val noteHit = noteLabel.contains(q, true)
                val byId = contacts.associateBy { it.id }
                LazyColumn {
                    if (nameHits.isNotEmpty() || noteHit) {
                        item { SectionLabel(stringResource(R.string.contacts_section)) }
                        if (noteHit) item { Row1(noteLabel, { NoteAvatar() }, null, false, false, null) { onOpen(Swell.SELF, null) } }
                        items(nameHits, key = { "c" + it.id }) { c -> Row1(displayName(c), { Avatar(displayName(c), c.id) }, null, c.verified, c.blocked, null) { onOpen(c.id, null) } }
                    }
                    if (hits.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.messages_section)) }
                        items(hits, key = { "m" + it.id }) { m ->
                            val c = byId[m.contactId]
                            val title = if (m.contactId == Swell.SELF) noteLabel else c?.let { it.name.ifBlank { stringResource(R.string.new_contact) } } ?: ""
                            Row1(title, { if (c != null) Avatar(displayName(c), c.id) else NoteAvatar() }, m, false, false, m.text) { onOpen(m.contactId, m.id) }
                        }
                    }
                    if (nameHits.isEmpty() && !noteHit && hits.isEmpty()) item {
                        Text(stringResource(R.string.no_results), Modifier.fillMaxWidth().padding(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn {
                    // Note to Self is always first (pinned) and never leaves the phone.
                    item(key = "self") {
                        Row1(noteLabel, { NoteAvatar() }, last[Swell.SELF], false, false, if (last[Swell.SELF] == null) stringResource(R.string.note_to_self_empty) else null) { onOpen(Swell.SELF, null) }
                    }
                    if (phraseCardShown && Swell.phraseCardDue(contacts.isNotEmpty())) item(key = "phrase-card") {
                        PhraseCard(onPhrase) { phraseCardShown = false }
                    }
                    if (contacts.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            BrandMark(112.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ocean.Text)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Spacer(Modifier.size(20.dp))
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            androidx.compose.material3.Button({ Swell.shareInvite(ctx) }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp)) {
                                Text(stringResource(R.string.invite_friend), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    items(contacts.sortedByDescending { last[it.id]?.at ?: it.createdAt }, key = { it.id }) { c ->
                        Row1(displayName(c), { Avatar(displayName(c), c.id) }, last[c.id], c.verified, c.blocked, null) { onOpen(c.id, null) }
                    }
                }
            }
        }
    }
}

/** A gentle reminder to write the recovery phrase down: after the first chat or after a day. "Later" hides it until the app is opened again. */
@Composable
private fun PhraseCard(onSave: () -> Unit, onLater: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.phrase_card_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.phrase_card_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(onSave, Modifier.height(48.dp)) { Text(stringResource(R.string.phrase_card_action)) }
            androidx.compose.material3.TextButton(onLater, Modifier.height(48.dp)) { Text(stringResource(R.string.later)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = Ocean.Cyan)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(contactId: String, onBack: () -> Unit, onInfo: () -> Unit, jumpTo: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isSelf = contactId == Swell.SELF
    val changes by Swell.changes.collectAsStateWithLifecycle()
    val contact = remember(changes) { if (isSelf) null else Swell.contact(contactId) }
    val data by produceState(Pair(emptyList<StoredMessage>(), emptyMap<String, List<Reaction>>()), changes) {
        value = withContext(Dispatchers.IO) {
            // Private messages come from memory and are mixed in by time; they are never in the database.
            Swell.db.messages(contactId).let { ms -> (ms + Swell.privateMessages(contactId)).sortedBy { it.at }.let { all -> all to Swell.db.reactions(ms.map { it.id }) } }
        }
    }
    val privateIds by Swell.privateOn.collectAsStateWithLifecycle()
    val privateOn = !isSelf && contactId in privateIds
    var timerSec by remember { mutableStateOf(0) }
    var photoOnce by remember { mutableStateOf(false) }
    val (messages, reactions) = data
    val byId = remember(messages) { messages.associateBy { it.id } }
    var text by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<StoredMessage?>(null) }
    var editing by remember { mutableStateOf<StoredMessage?>(null) }
    var menuFor by remember { mutableStateOf<StoredMessage?>(null) }
    var confirmDelete by remember { mutableStateOf<StoredMessage?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchPos by remember { mutableStateOf(0) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    fun jump(id: String) {
        val i = messages.indexOfFirst { it.id == id }
        if (i < 0) return
        scope.launch {
            list.animateScrollToItem(i + 1)
            flash = id; delay(1400); if (flash == id) flash = null
        }
    }
    val matches = remember(query, messages) {
        if (query.isBlank()) emptyList() else messages.filter { !it.deleted && it.text.contains(query.trim(), ignoreCase = true) }.map { it.id }
    }
    LaunchedEffect(query, messages.size) { matchPos = matches.lastIndex.coerceAtLeast(0); matches.getOrNull(matchPos)?.let { jump(it) } }
    var jumped by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size) {
        if (jumpTo != null && !jumped && messages.any { it.id == jumpTo }) { jumped = true; jump(jumpTo) }
        else if (messages.isNotEmpty() && !searching && (jumpTo == null || jumped)) list.animateScrollToItem(messages.size - 1)
    }
    DisposableEffect(contactId) {
        Swell.openChat = contactId
        onDispose {
            Swell.openChat = null
            // Leaving the chat wipes its private messages here and on the other phone (not on a rotation).
            if ((context as? android.app.Activity)?.isChangingConfigurations != true) Swell.wipePrivate(contactId)
        }
    }
    SecureWhile(privateOn)
    // Incoming messages shown here count as read (and, if receipts are on, the sender is told).
    LaunchedEffect(contactId, changes) { Swell.markRead(contactId) }
    // Keep the Tor stream to this contact open while the chat is on screen.
    LaunchedEffect(contactId) {
        if (!isSelf) while (true) { Swell.warm(contactId); delay(15_000) }
    }
    // Typing indicator: "on" at most every 5 s while typing, "off" after 3 s idle or when sent.
    var typingSentAt by remember { mutableStateOf(0L) }
    LaunchedEffect(text) {
        if (text.isNotBlank() && editing == null) {
            val t = System.currentTimeMillis()
            if (t - typingSentAt > 5_000) { typingSentAt = t; Swell.typing(contactId, true) }
            delay(3_000); typingSentAt = 0; Swell.typing(contactId, false)
        } else if (typingSentAt != 0L) { typingSentAt = 0; Swell.typing(contactId, false) }
    }
    val typingMap by Swell.typingFrom.collectAsStateWithLifecycle()
    val tick by produceState(0L) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    val isTyping = Swell.typingIndicators && tick >= 0 && (typingMap[contactId] ?: 0L) > System.currentTimeMillis()

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        Swell.pickerOpen = false
        if (uri != null) { val r = replyTo?.id; val once = photoOnce; val tm = timerSec; Swell.work {
            runCatching { Swell.readPhoto(uri) }.onSuccess { Swell.sendFile(contactId, "photo-${System.currentTimeMillis()}.jpg", "image/jpeg", it, if (once) "" else text.trim(), r, viewOnce = once, timerSec = tm); text = "" }
                .onFailure { Swell.toast(it.message ?: "") }
        }; replyTo = null }
        photoOnce = false
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        Swell.pickerOpen = false
        if (uri != null) { val r = replyTo?.id; val tm = timerSec; Swell.work {
            runCatching { Swell.readFile(uri) }.onSuccess { (n, mime, b) -> Swell.sendFile(contactId, n, mime, b, text.trim(), r, timerSec = tm); text = "" }
                .onFailure { Swell.toast(it.message ?: "") }
        }; replyTo = null }
    }

    // Voice notes: RECORD_AUDIO is asked at the first recording only.
    var recording by remember { mutableStateOf(false) }
    var limitHit by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(context) { limitHit = true } }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    fun startRecording() {
        VoicePlayer.stop()
        if (recorder.start()) { recording = true; limitHit = false } else Swell.toast(context.getString(R.string.voice_failed))
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else Swell.toast(context.getString(R.string.mic_denied))
    }
    fun finishRecording(send: Boolean) {
        recording = false
        if (!send) { recorder.cancel(); return }
        val note = recorder.finish()
        if (note == null) { Swell.toast(context.getString(R.string.voice_too_short)); return }
        val r = replyTo?.id; replyTo = null
        Swell.work { Swell.sendFile(contactId, "voice-${System.currentTimeMillis()}.m4a", "audio/mp4", note.bytes, "", r, note.durationMs) }
    }
    val elapsed by produceState(0, recording) { while (recording) { value = recorder.elapsedMs(); delay(200) }; value = 0 }
    LaunchedEffect(limitHit) { if (limitHit && recording) { Swell.toast(context.getString(R.string.voice_limit)) } }

    var attachMenu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<StoredMessage?>(null) }
    var viewingOnce by remember { mutableStateOf<StoredMessage?>(null) }
    // A wipe closes whatever private photo is open.
    LaunchedEffect(messages) { viewing?.let { v -> if (v.isPrivate && messages.none { it.id == v.id }) viewing = null } }

    fun submit() {
        val t = text.trim()
        if (t.isEmpty()) return
        val e = editing
        if (e != null) { Swell.editMessage(e, t); editing = null } else { Swell.send(contactId, t, replyTo?.id, timerSec); replyTo = null }
        text = ""
    }
    fun senderLabel(m: StoredMessage) = if (m.incoming) (contact?.let { it.name.ifBlank { context.getString(R.string.new_contact) } } ?: "") else context.getString(R.string.you)

    Scaffold(
        containerColor = if (privateOn) PrivateLook.Background else MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = if (privateOn) androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = PrivateLook.Bar) else androidx.compose.material3.TopAppBarDefaults.topAppBarColors(),
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    if (searching) {
                        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        androidx.compose.material3.TextField(
                            query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                            placeholder = { Text(stringResource(R.string.search_in_chat)) },
                            colors = androidx.compose.material3.TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                        )
                    } else {
                        Column(Modifier.then(if (isSelf) Modifier else Modifier.clickable(onClick = onInfo))) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (contact != null) Avatar(displayName(contact), contact.id, 34.dp)
                                Text(
                                    if (isSelf) stringResource(R.string.note_to_self) else contact?.let { displayName(it) } ?: "",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                                )
                                if (contact?.verified == true) Icon(Icons.Default.Verified, stringResource(R.string.verified), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                if ((contact?.expireSec ?: 0) > 0) Icon(Icons.Default.Timer, stringResource(R.string.disappearing), Modifier.size(18.dp))
                            }
                            if (isTyping) Text(stringResource(R.string.typing), style = MaterialTheme.typography.labelSmall, color = Ocean.Cyan)
                        }
                    }
                },
                actions = {
                    if (searching) {
                        if (query.isNotBlank()) Text(
                            if (matches.isEmpty()) stringResource(R.string.no_results) else stringResource(R.string.matches_count, matchPos + 1, matches.size),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        IconButton({ if (matches.isNotEmpty()) { matchPos = (matchPos - 1 + matches.size) % matches.size; jump(matches[matchPos]) } }, enabled = matches.isNotEmpty()) {
                            Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.search_prev))
                        }
                        IconButton({ if (matches.isNotEmpty()) { matchPos = (matchPos + 1) % matches.size; jump(matches[matchPos]) } }, enabled = matches.isNotEmpty()) {
                            Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.search_next))
                        }
                        IconButton({ searching = false; query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.search_close)) }
                    } else {
                        if (contact != null && contact.blocked.not()) IconButton({
                            if (!privateOn && !contact.supportsPrivate) Swell.toast(context.getString(R.string.private_update_needed))
                            else Swell.setPrivate(contactId, !privateOn)
                        }) {
                            Icon(
                                if (privateOn) Icons.Default.Lock else Icons.Default.LockOpen,
                                stringResource(if (privateOn) R.string.private_toggle_off else R.string.private_toggle_on),
                                tint = if (privateOn) PrivateLook.Accent else if (contact.supportsPrivate) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f),
                            )
                        }
                        IconButton({ searching = true }) { Icon(Icons.Default.Search, stringResource(R.string.search_in_chat)) }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().then(if (privateOn) Modifier.background(PrivateLook.Background) else Modifier)) {
            if (privateOn) PrivateBanner()
            if (contact != null && !contact.verified && !privateOn) {
                Text(
                    stringResource(R.string.unverified_banner),
                    Modifier.fillMaxWidth().clickable(onClick = onInfo).background(Ocean.Raised).padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = Ocean.Foam,
                )
            }
            if (isSelf) {
                Text(
                    stringResource(R.string.note_to_self_hint),
                    Modifier.fillMaxWidth().background(Ocean.Raised).padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = Ocean.Foam,
                )
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Spacer(Modifier.size(8.dp)) }
                if (privateOn && messages.isEmpty()) item {
                    Text(stringResource(R.string.private_hint), Modifier.fillMaxWidth().padding(24.dp), color = PrivateLook.Accent.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                items(messages, key = { it.id }) { m ->
                    // A per-message timer starts the moment the message is on screen.
                    if (m.incoming && m.timerSec > 0 && m.vanishAt == 0L) LaunchedEffect(m.id) { Swell.shown(m) }
                    Bubble(
                        m, byId[m.replyTo], reactions[m.id].orEmpty(), highlighted = flash == m.id || (searching && m.id in matches),
                        label = m.replyTo?.let { byId[it] }?.let(::senderLabel).orEmpty(),
                        onOpen = { viewing = it }, onOpenOnce = { viewingOnce = it }, onLongPress = { if (!m.deleted && !m.id.startsWith("p")) menuFor = m },
                        onReply = { if (!m.deleted && !m.id.startsWith("p") && contact?.blocked != true && !m.viewOnce && !m.isPrivate) { replyTo = m; editing = null } },
                        onQuote = { jump(it) },
                    )
                }
                item { Spacer(Modifier.size(8.dp)) }
            }
            if (contact?.blocked == true) {
                Text(stringResource(R.string.blocked_note), Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            } else {
                replyTo?.let { ComposerBanner(stringResource(R.string.replying_to, senderLabel(it)), previewText(it)) { replyTo = null } }
                editing?.let { ComposerBanner(stringResource(R.string.editing_message), it.text) { editing = null; text = "" } }
                if (recording) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ finishRecording(false) }) { Icon(Icons.Default.Delete, stringResource(R.string.cancel), tint = MaterialTheme.colorScheme.error) }
                        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                        Text(
                            "  " + stringResource(R.string.recording) + "  " + formatDuration(elapsed), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        IconButton({ finishRecording(true) }) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send), tint = MaterialTheme.colorScheme.primary) }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton({ attachMenu = true }, enabled = editing == null) { Icon(Icons.Default.AttachFile, stringResource(R.string.attach)) }
                            DropdownMenu(attachMenu, { attachMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.attach_photo)) }, leadingIcon = { Icon(Icons.Default.Image, null) },
                                    onClick = { attachMenu = false; photoOnce = false; Swell.pickerOpen = true; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                )
                                if (!privateOn && !isSelf) {
                                    val onceOk = contact?.supportsViewOnce == true
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (onceOk) R.string.attach_view_once else R.string.attach_update_needed)) },
                                        leadingIcon = { Icon(Icons.Default.Visibility, null) }, enabled = onceOk,
                                        onClick = { attachMenu = false; photoOnce = true; Swell.pickerOpen = true; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    )
                                }
                                if (!privateOn) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.attach_file)) }, leadingIcon = { Icon(Icons.Default.Description, null) },
                                    onClick = { attachMenu = false; Swell.pickerOpen = true; pickFile.launch(arrayOf("*/*")) },
                                )
                            }
                        }
                        OutlinedTextField(text, { text = it; if (privateOn) Swell.touchPrivate(contactId) }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.message_hint)) }, maxLines = 5, shape = RoundedCornerShape(24.dp))
                        if (!isSelf && !privateOn && editing == null) TimerButton(timerSec, contact?.supportsViewOnce == true) { timerSec = it }
                        // Voice notes need a temporary file to play, so they are not offered in private mode.
                        if (text.isBlank() && editing == null && privateOn) Unit
                        else if (text.isBlank() && editing == null) {
                            IconButton({
                                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startRecording()
                                else askMic.launch(android.Manifest.permission.RECORD_AUDIO)
                            }) { Icon(Icons.Default.Mic, stringResource(R.string.record_voice), tint = MaterialTheme.colorScheme.primary) }
                        } else {
                            IconButton({ submit() }) {
                                Icon(if (editing != null) Icons.Default.Check else Icons.AutoMirrored.Filled.Send, stringResource(R.string.send), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    viewing?.let { PhotoViewer(it) { viewing = null } }
    viewingOnce?.let { ViewOnceViewer(it) { viewingOnce = null } }

    menuFor?.let { m ->
        androidx.compose.material3.ModalBottomSheet({ menuFor = null }) {
            val mine = reactions[m.id]?.firstOrNull { it.who == Db.ME }?.emoji
            Column(Modifier.padding(bottom = 24.dp)) {
                if (!m.viewOnce && !m.isPrivate) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    QUICK_REACTIONS.forEach { e ->
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(if (e == mine) Ocean.Electric.copy(alpha = 0.4f) else Color.Transparent)
                                .clickable { menuFor = null; Swell.react(m, e) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 26.sp) }
                    }
                }
                @Composable fun Item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false, run: () -> Unit) {
                    Row(
                        Modifier.fillMaxWidth().clickable { menuFor = null; run() }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (contact?.blocked != true && !m.viewOnce && !m.isPrivate) Item(stringResource(R.string.reply), Icons.AutoMirrored.Filled.Reply) { replyTo = m; editing = null }
                if (m.text.isNotBlank() && !m.isPrivate) Item(stringResource(R.string.copy), Icons.Default.ContentCopy) {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.text)); Swell.toast(context.getString(R.string.copied))
                }
                if (Swell.canEdit(m)) Item(stringResource(R.string.edit), Icons.Default.Edit) { editing = m; replyTo = null; text = m.text }
                Item(stringResource(R.string.delete_for_me), Icons.Default.Delete) { Swell.deleteForMe(m) }
                if (Swell.canModify(m)) Item(stringResource(R.string.delete_for_everyone), Icons.Default.DeleteForever, danger = true) { confirmDelete = m }
            }
        }
    }
    confirmDelete?.let { m ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_for_everyone)) },
            confirmButton = { androidx.compose.material3.TextButton({ confirmDelete = null; Swell.deleteForEveryone(m) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { androidx.compose.material3.TextButton({ confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Decrypted, downsampled image for a bubble; cached so scrolling does not decrypt again. */
private val thumbs = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(40)

private fun decode(bytes: ByteArray, maxPx: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
    var sample = 1
    while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= maxPx) sample *= 2
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

@Composable
private fun Thumb(m: StoredMessage, maxPx: Int, modifier: Modifier) {
    val id = m.fileId ?: return
    // Private photos come from memory and are never put in the thumbnail cache.
    val priv = m.isPrivate
    val img by produceState(if (priv) null else thumbs.get("$id@$maxPx"), id) {
        if (value == null) value = withContext(Dispatchers.IO) {
            if (priv) Swell.privateStore.file(id)?.let { decode(it, maxPx) }
            else Files.load(id)?.let { decode(it, maxPx) }?.also { thumbs.put("$id@$maxPx", it) }
        }
    }
    img?.let { Image(it, null, modifier, contentScale = ContentScale.Fit) }
        ?: Box(modifier.size(200.dp, 150.dp).background(Ocean.Deep), contentAlignment = Alignment.Center) { Icon(Icons.Default.Image, null) }
}

/** Saves a decrypted copy where the user chooses (the only way a file leaves Swell's storage). */
@Composable
private fun rememberSaver(m: StoredMessage): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(m.fileMime ?: "application/octet-stream")) { uri ->
        if (uri != null) Swell.work {
            val data = m.fileId?.let { Files.load(it) }
            runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(data!!) } }
                .onSuccess { Swell.toast(context.getString(R.string.saved)) }
                .onFailure { Swell.toast(context.getString(R.string.save_failed)) }
        }
    }
    return { launcher.launch(m.fileName ?: "file") }
}

@Composable
private fun PhotoViewer(m: StoredMessage, onClose: () -> Unit) {
    val save = rememberSaver(m)
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false,
        securePolicy = if (m.isPrivate) androidx.compose.ui.window.SecureFlagPolicy.SecureOn else androidx.compose.ui.window.SecureFlagPolicy.Inherit)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Thumb(m, 2048, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                if (!m.isPrivate) IconButton(save) { Icon(Icons.Default.Download, stringResource(R.string.save), tint = Color.White) }
                IconButton(onClose) { Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White) }
            }
        }
    }
}

private fun size(n: Long) = when {
    n >= 1_000_000 -> "%.1f MB".format(n / 1_000_000.0)
    n >= 1_000 -> "${n / 1_000} KB"
    else -> "$n B"
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: StoredMessage, quoted: StoredMessage?, reactions: List<Reaction>, highlighted: Boolean, label: String,
    onOpen: (StoredMessage) -> Unit, onOpenOnce: (StoredMessage) -> Unit, onLongPress: () -> Unit, onReply: () -> Unit, onQuote: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxDrag = with(density) { 72.dp.toPx() }
    var drag by remember { mutableStateOf(0f) }
    val dragState = androidx.compose.foundation.gestures.rememberDraggableState { d -> drag = (drag + d).coerceIn(0f, maxDrag) }
    Box(Modifier.fillMaxWidth()) {
        if (drag > 8f) Icon(
            Icons.AutoMirrored.Filled.Reply, null,
            Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(22.dp), tint = Ocean.Cyan.copy(alpha = (drag / maxDrag).coerceIn(0f, 1f)),
        )
        Column(
            Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(drag.toInt(), 0) }
                .draggable(dragState, androidx.compose.foundation.gestures.Orientation.Horizontal, onDragStopped = {
                    if (drag >= maxDrag * 0.9f) onReply()
                    drag = 0f
                }),
            horizontalAlignment = if (m.incoming) Alignment.Start else Alignment.End,
        ) {
            val shape = if (m.incoming) RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp) else RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp)
            Surface(
                color = if (m.incoming) (if (m.isPrivate) PrivateLook.Bubble else Ocean.Navy) else Color.Transparent,
                contentColor = if (m.incoming) Ocean.Text else Color.White,
                shape = shape,
                border = if (highlighted) androidx.compose.foundation.BorderStroke(2.dp, Ocean.Cyan) else null,
                modifier = Modifier.widthIn(max = 300.dp).clip(shape).combinedClickable(
                    onClick = { if (m.status == StoredMessage.FAILED) Swell.retry(m) },
                    onLongClick = onLongPress,
                ),
            ) {
                Column(Modifier.then(if (m.incoming) Modifier else Modifier.background(if (m.isPrivate) Brush.linearGradient(listOf(Color(0xFF4C1D95), Color(0xFF6D28D9))) else Ocean.Bubble)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    if (m.replyTo != null && !m.deleted) {
                        QuoteBox(quoted, label) { onQuote(m.replyTo) }
                        Spacer(Modifier.size(6.dp))
                    }
                    if (m.deleted) {
                        DeletedText()
                    } else if (m.viewOnce) {
                        ViewOnceTile(m) { onOpenOnce(m) }
                    } else {
                        if (m.fileId != null) {
                            if (m.isImage) {
                                Thumb(m, 720, Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(m) })
                            } else if (m.isVoice) {
                                VoiceRow(m)
                            } else {
                                val save = rememberSaver(m)
                                Row(
                                    Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.18f)).clickable(onClick = save).padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(Icons.Default.Description, null)
                                    Column(Modifier.widthIn(max = 180.dp)) {
                                        Text(m.fileName ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                        Text(size(m.fileSize), style = MaterialTheme.typography.labelSmall)
                                    }
                                    Icon(Icons.Default.Download, stringResource(R.string.save))
                                }
                            }
                            if (m.text.isNotBlank()) Spacer(Modifier.size(6.dp))
                        }
                        if (m.text.isNotBlank() || m.fileId == null) Text(m.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    val pct by Swell.progress.collectAsStateWithLifecycle()
                    val status = when (m.status) {
                        // Queued: a clock glyph, no network text. Attachments show their upload percentage.
                        StoredMessage.SENDING -> pct[m.id]?.let { stringResource(R.string.status_sending_pct, it) }
                        StoredMessage.FAILED -> stringResource(R.string.status_failed)
                        else -> null
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.End)) {
                        if (m.timerSec > 0 || (m.viewOnce && m.vanishAt > 0)) TimerRing(m)
                        else if (m.expireSec > 0) Icon(Icons.Default.Timer, null, Modifier.size(12.dp))
                        if (m.status == StoredMessage.SENDING) Icon(Icons.Default.Schedule, stringResource(R.string.status_sending), Modifier.size(13.dp))
                        if (m.edited && !m.deleted) Text(stringResource(R.string.edited), style = MaterialTheme.typography.labelSmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                        Text(
                            status ?: DateUtils.formatDateTime(context, m.at, DateUtils.FORMAT_SHOW_TIME),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (m.status == StoredMessage.FAILED) FontWeight.Bold else null,
                        )
                        // Ticks only appear while receipts are on (both ways).
                        if (!m.incoming && Swell.readReceipts && m.contactId != Swell.SELF && !m.isPrivate) when (m.status) {
                            StoredMessage.SENT -> Icon(Icons.Default.Check, stringResource(R.string.tick_sent), Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.6f))
                            StoredMessage.DELIVERED -> Icon(Icons.Default.DoneAll, stringResource(R.string.tick_delivered), Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.6f))
                            // Read = full white (the gradient ends in cyan, so a cyan tick would vanish).
                            StoredMessage.READ -> Icon(Icons.Default.DoneAll, stringResource(R.string.tick_read), Modifier.size(14.dp), tint = Color.White)
                        }
                    }
                }
            }
            ReactionChips(m, reactions, Modifier.padding(horizontal = 8.dp).offset(y = (-4).dp))
        }
    }
}
