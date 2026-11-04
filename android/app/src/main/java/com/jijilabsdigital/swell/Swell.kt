package com.jijilabsdigital.swell

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.jijilabsdigital.swell.core.Account
import com.jijilabsdigital.swell.core.Contact
import com.jijilabsdigital.swell.core.Dialer
import com.jijilabsdigital.swell.core.InboundServer
import com.jijilabsdigital.swell.core.Invite
import com.jijilabsdigital.swell.core.Message
import com.jijilabsdigital.swell.core.Node
import com.jijilabsdigital.swell.core.NodeEvents
import com.jijilabsdigital.swell.core.Codec
import com.jijilabsdigital.swell.core.ReportInbox
import com.jijilabsdigital.swell.core.Socks5
import com.jijilabsdigital.swell.core.TorControlHost
import com.jijilabsdigital.swell.core.Attachment
import com.jijilabsdigital.swell.core.Modify
import com.jijilabsdigital.swell.core.Receipt
import com.jijilabsdigital.swell.core.PrivateMessages
import com.jijilabsdigital.swell.data.Db
import com.jijilabsdigital.swell.data.Files
import com.jijilabsdigital.swell.data.StoredMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.concurrent.thread

sealed interface Net {
    data object Off : Net
    data class Connecting(val percent: Int) : Net
    data object Online : Net
    data class Failed(val reason: String) : Net
}

/** App-wide state: database, identity, Tor, onion service and the protocol node. */
@SuppressLint("StaticFieldLeak") // application context only
object Swell : NodeEvents {
    private const val TAG = "Swell"
    /** Chat id of the local-only "Note to Self" chat. Nothing in it is ever sent. */
    const val SELF = "self"

    private lateinit var app: Context
    lateinit var db: Db
        private set
    var account: Account? = null
        private set
    private var node: Node? = null

    private val _net = MutableStateFlow<Net>(Net.Off)
    val net: StateFlow<Net> = _net
    /** Bumped on every contact or message change; screens reload from [db]. */
    val changes = MutableStateFlow(0L)
    val hasAccount = MutableStateFlow(false)
    /** Contact whose chat is open (no notification for it). */
    @Volatile var openChat: String? = null

    private var connecting = false
    private var ready = CountDownLatch(1)
    /** Bounded: user actions queue up instead of piling threads while Tor is down (audit L-04). */
    private val pool = Executors.newFixedThreadPool(4)

    /** The encrypted database could not be opened (Keystore key lost, e.g. after a device restore) (audit L-06). */
    val storageBroken = MutableStateFlow(false)

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        db = try { Db.open(app) } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "db", e)
            storageBroken.value = true
            Notifications.createChannels(app)
            return
        }
        Files.init(app, db)
        account = Account.load(db)
        hasAccount.value = account != null
        Notifications.createChannels(app)
        if (account != null) PollWorker.schedule(app)
        sweepViewOnce()
        registerScreenOff()
        thread(name = "swell-expire", isDaemon = true) {
            var tick = 0
            while (true) {
                if (db.expire(System.currentTimeMillis()) > 0) bump()
                // Private chats that saw no activity for 5 minutes are wiped, here and on the other phone.
                if (++tick % 5 == 0) privateStore.idleChats(PRIVATE_IDLE_MS).forEach { wipePrivate(it) }
                // Short timers (10 s) and the 1-minute view-once countdown need a fine clock while the app is on screen.
                Thread.sleep(if (foreground) 1_000 else 15_000)
            }
        }
    }

    /** Screen locked or turned off: private chats are wiped (wire section 5b). */
    private fun registerScreenOff() {
        androidx.core.content.ContextCompat.registerReceiver(
            app,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) { wipeAllPrivate() }
            },
            android.content.IntentFilter(Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** A view-once photo whose viewer was open when the app died: it counts as opened, the countdown starts. */
    private fun sweepViewOnce() {
        db.openedNotCounting().forEach { m ->
            db.setVanish(m.id, System.currentTimeMillis() + VIEW_ONCE_GRACE_MS)
            pendingOpened += m.contactId to m.id
        }
    }

    private fun bump() = changes.update { it + 1 }

    private const val PRIVATE_IDLE_MS = 5 * 60_000L
    /** How long a view-once row stays after the photo was closed (receiver) or opened (sender). */
    const val VIEW_ONCE_GRACE_MS = 60_000L
    /** Opened receipts that could not be sent yet (the app died while viewing). */
    private val pendingOpened = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

    /** Messages of chats in private mode: memory only, never written anywhere. */
    val privateStore = PrivateMessages()
    /** Chats currently in private mode. Memory only. */
    val privateOn = MutableStateFlow<Set<String>>(emptySet())

    // ---------------------------------------------------------------- account

    fun createAccount(): Account = Account.create().also {
        it.save(db); account = it
        db.put("app", "created", System.currentTimeMillis().toString().toByteArray())
    }

    /** A restored account: the person already has the phrase, so no reminder card. */
    fun restoreAccount(words: List<String>): Account = Account.restore(words).also { it.save(db); account = it; phraseSaved = true }

    // ------------------------------------------------------------ settings

    private fun flag(key: String, default: Boolean = false): Boolean = db.get("app", key)?.let { it[0].toInt() == 1 } ?: default
    private fun setFlag(key: String, v: Boolean) = db.put("app", key, byteArrayOf(if (v) 1 else 0))

    /** Link previews: what this phone SENDS (it fetches over Tor and embeds the preview). Off by default. */
    var linkPreviews: Boolean get() = flag("linkprev"); set(v) = setFlag("linkprev", v)
    /** Next voice note starts by itself when one ends. */
    var autoplayVoice: Boolean get() = flag("autoplay", true); set(v) = setFlag("autoplay", v)
    /** Faint wave pattern behind chats. */
    var chatWaves: Boolean get() = flag("waves", true); set(v) = setFlag("waves", v)
    /** Notification content: 0 = nothing (just "New message"), 1 = name only, 2 = name and content. */
    var notifContent: Int get() = db.get("app", "notifc")?.get(0)?.toInt() ?: 0; set(v) = db.put("app", "notifc", byteArrayOf(v.toByte()))
    /** Notification sound as a URI string; null = system default, "" = silent. */
    var notifSound: String? get() = db.get("app", "notifsound")?.let { String(it) }; set(v) { if (v == null) db.delete("app", "notifsound") else db.put("app", "notifsound", v.toByteArray()); Notifications.createChannels(app) }
    /** Play the sound for a message that arrives while the app is open. */
    var soundWhileOpen: Boolean get() = flag("soundopen", true); set(v) = setFlag("soundopen", v)
    /** The recovery phrase can no longer be shown on this phone (only restoring from it remains possible elsewhere). */
    var phraseHidden: Boolean get() = flag("phrasehidden"); set(v) = setFlag("phrasehidden", v)

    // ------------------------------------------------------------ chat list: pin, mute, archive, mark unread

    class Meta(val pinned: Boolean = false, val mutedUntil: Long = 0, val archived: Boolean = false, val unread: Boolean = false) {
        val muted: Boolean get() = mutedUntil > System.currentTimeMillis()
    }
    private val metas = java.util.concurrent.ConcurrentHashMap<String, Meta>()
    fun meta(id: String): Meta = metas.getOrPut(id) {
        db.get("chatmeta", id)?.let { b -> runCatching { com.jijilabsdigital.swell.core.In(b).let { r -> Meta(r.u8() == 1, r.i64(), r.u8() == 1, r.u8() == 1) } }.getOrNull() } ?: Meta()
    }
    private fun saveMeta(id: String, m: Meta) {
        metas[id] = m
        db.put("chatmeta", id, com.jijilabsdigital.swell.core.Out().u8(if (m.pinned) 1 else 0).i64(m.mutedUntil).u8(if (m.archived) 1 else 0).u8(if (m.unread) 1 else 0).done())
        bump()
    }
    fun setPinned(id: String, v: Boolean) = meta(id).let { saveMeta(id, Meta(v, it.mutedUntil, it.archived, it.unread)) }
    /** [ms] from now; Long.MAX_VALUE = always; 0 = unmute. */
    fun setMuted(id: String, ms: Long) = meta(id).let { saveMeta(id, Meta(it.pinned, if (ms == 0L) 0 else if (ms == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + ms, it.archived, it.unread)) }
    fun setArchived(id: String, v: Boolean) = meta(id).let { saveMeta(id, Meta(it.pinned, it.mutedUntil, v, it.unread)) }
    fun setMarkedUnread(id: String, v: Boolean) = meta(id).let { saveMeta(id, Meta(it.pinned, it.mutedUntil, it.archived, v)) }

    /** Appearance: theme id ("system", "navy", "midnight", "light", "mist") and accent index. */
    data class Look(val theme: String, val accent: Int)
    val look by lazy { MutableStateFlow(runCatching { Look(db.get("app", "theme")?.let { String(it) } ?: "system", db.get("app", "accent")?.let { it[0].toInt() } ?: 0) }.getOrDefault(Look("system", 0))) }
    fun setLook(theme: String = look.value.theme, accent: Int = look.value.accent) {
        db.put("app", "theme", theme.toByteArray()); db.put("app", "accent", byteArrayOf(accent.toByte()))
        look.value = Look(theme, accent)
    }

    /** The clipboard was already offered once for a deferred invite on this install. */
    var clipChecked: Boolean
        get() = db.get("app", "clipChecked") != null
        set(v) { if (v) db.put("app", "clipChecked", byteArrayOf(1)) }

    /** The person wrote the recovery phrase down (or restored with it). */
    var phraseSaved: Boolean
        get() = db.get("app", "phraseSaved") != null
        set(v) { if (v) db.put("app", "phraseSaved", byteArrayOf(1)) else db.delete("app", "phraseSaved") }

    /**
     * The chat list shows the "save your recovery phrase" card after the first chat, or after one day.
     * Accounts from before this version (no creation time stored) are not nagged.
     */
    fun phraseCardDue(hasChats: Boolean): Boolean {
        if (phraseSaved) return false
        val created = db.get("app", "created")?.let { String(it).toLongOrNull() } ?: return false
        return hasChats || System.currentTimeMillis() - created > 24 * 3600_000L
    }

    /** Called when onboarding is finished; starts the connection. */
    fun finishOnboarding(context: Context) {
        hasAccount.value = true
        PollWorker.schedule(app)
        SwellService.start(context)
    }

    var profileName: String
        get() = db.get("app", "name")?.let { String(it) } ?: ""
        set(v) {
            db.put("app", "name", v.trim().take(64).toByteArray())
            node?.let { n ->
                n.profileName = v.trim().take(64)
                // Contacts learn the new name at once.
                n.contacts().filter { !it.blocked }.forEach { c -> pool.execute { runCatching { n.sendState(c) } } }
            }
        }

    var stayConnected: Boolean
        get() = db.get("app", "stay")?.let { it[0].toInt() == 1 } ?: true
        set(v) { db.put("app", "stay", byteArrayOf(if (v) 1 else 0)) }

    /** Mailbox for messages that arrive while this phone is offline. */
    var mailboxOnion: String
        get() = db.get("app", "mailbox")?.let { String(it) } ?: BuildConfig.MAILBOX_ONION
        set(v) {
            db.put("app", "mailbox", v.trim().removeSuffix(".onion").toByteArray())
            node?.let { n ->
                n.mailboxOnion = mailboxOnion
                // Tell every contact where to leave messages now.
                n.contacts().forEach { c -> pool.execute { runCatching { n.sendProfile(c) } } }
            }
        }

    /** Ask for fingerprint / screen lock when opening the app (audit M-02). */
    var appLock: Boolean
        get() = db.get("app", "lock")?.let { it[0].toInt() == 1 } ?: false
        set(v) { db.put("app", "lock", byteArrayOf(if (v) 1 else 0)) }

    /** Delivered/read receipts, both ways. Off by default (privacy); when off we neither send nor see them. */
    var readReceipts: Boolean
        get() = db.get("app", "receipts")?.let { it[0].toInt() == 1 } ?: false
        set(v) { db.put("app", "receipts", byteArrayOf(if (v) 1 else 0)); node?.receiptsEnabled = v }

    /** Typing indicators, both ways. Off by default. */
    var typingIndicators: Boolean
        get() = db.get("app", "typing")?.let { it[0].toInt() == 1 } ?: false
        set(v) { db.put("app", "typing", byteArrayOf(if (v) 1 else 0)); node?.typingEnabled = v; if (!v) typingFrom.value = emptyMap() }

    var blockScreenshots: Boolean
        get() = db.get("app", "secure")?.let { it[0].toInt() == 1 } ?: !BuildConfig.DEBUG
        set(v) { db.put("app", "secure", byteArrayOf(if (v) 1 else 0)) }

    /** Deletes identity, contacts, messages and keys from this phone, then restarts the app clean. */
    fun deleteEverything(context: Context) {
        SwellService.stop(context)
        node = null
        if (storageBroken.value) Db.wipe(app) else db.destroy(app)
        val restart = app.packageManager.getLaunchIntentForPackage(app.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        app.startActivity(restart)
        // A new process also drops the old onion service from Tor.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ---------------------------------------------------------------- network

    /** Binds Tor (once per process) and brings up the onion service. */
    fun connect() {
        val acct = account ?: return
        synchronized(this) { if (connecting) return; connecting = true }
        _net.value = Net.Connecting(0)
        val t0 = System.nanoTime()
        app.bindService(
            Intent(app, TorService::class.java),
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val tor = (binder as TorService.LocalBinder).service
                    thread(name = "swell-boot") { boot(tor, acct, t0) }
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    _net.value = Net.Off
                    synchronized(this@Swell) { connecting = false }
                }
            },
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun boot(tor: TorService, acct: Account, t0: Long) {
        try {
            var ctl: TorControlConnection? = tor.torControlConnection
            while (ctl == null) { Thread.sleep(200); ctl = tor.torControlConnection }
            while (true) {
                val phase = ctl.getInfo("status/bootstrap-phase")
                val pct = Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1)?.toInt() ?: 0
                _net.value = Net.Connecting(pct)
                if (pct == 100) break
                Thread.sleep(500)
            }
            log("TOR_READY ${(System.nanoTime() - t0) / 1_000_000} ms")
            val socks = tor.socksPort
            torCtl = ctl; socksPort = socks
            lateinit var n: Node
            val server = InboundServer({ n.handle(it) }, ::log).also { it.start() }
            val host = TorControlHost(ctl, server.port, log = ::log)
            n = Node(db, acct, this, Dialer { onion, port, iso -> Socks5.connect(socks, "$onion.onion", port, timeoutMs = 35_000, isolation = iso) }, host)
            n.profileName = profileName
            n.mailboxOnion = mailboxOnion
            n.avatar = myAvatar
            n.receiptsEnabled = readReceipts
            n.typingEnabled = typingIndicators
            // Address from before client auth (H-02), kept so existing contacts still reach us.
            val legacy = db.get("acct", "onionKey")?.let { k -> db.get("acct", "onion")?.let { String(it) to String(k) } }
            n.start(legacy)
            n.pruneInvites()
            pool.execute { runCatching { n.prebuildInvite()?.encode()?.let { db.put("app", "invite", it.toByteArray()) } } }
            node = n
            log("ONION ${n.onion}")
            _net.value = Net.Online
            ready.countDown()
            pendingOpened.forEach { (c, id) -> sendOpened(c, id) }
            pendingOpened.clear()
            bump()
            thread(name = "swell-poll", isDaemon = true) {
                var waitOffUntil = 0L
                var lastPoll = 0L
                while (node === n) {
                    // In the foreground: keep one stream to the mailbox and long-poll (WAIT), so a message shows
                    // within a second or two. A refusal (older server) falls back to the periodic poll for 5 min.
                    if (foreground && System.currentTimeMillis() > waitOffUntil) {
                        val r = runCatching { n.waitMailbox() }
                        if (r.isSuccess && (r.getOrNull() ?: -1) >= 0) {
                            // Other contacts than the 8 waited on, and the housekeeping, still need the slow poll now and then.
                            if (System.currentTimeMillis() - lastPoll > 60_000) { lastPoll = System.currentTimeMillis(); pollNow(); runCatching { n.maintain() } }
                            continue
                        }
                        if (r.isFailure) { waitOffUntil = System.currentTimeMillis() + 300_000; log("WAIT unavailable: ${r.exceptionOrNull()?.message}") }
                    }
                    lastPoll = System.currentTimeMillis()
                    pollNow()
                    runCatching { n.maintain() }.onFailure { log("maintain: ${it.message}") }
                    // Irregular, so the mailbox cannot fingerprint a phone by its polling rhythm (audit L-09).
                    val wait = if (foreground) 20_000L + (Math.random() * 20_000).toLong() else POLL_MIN_MS + (Math.random() * POLL_SPREAD_MS).toLong()
                    synchronized(pollSignal) { pollSignal.wait(wait) }
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "boot", e)
            _net.value = Net.Failed(e.message ?: "error")
            synchronized(this) { connecting = false }
        }
    }

    private const val POLL_MIN_MS = 80_000L
    private const val POLL_SPREAD_MS = 100_000L

    /** Fetches waiting messages from our mailbox. Safe to call from any background thread. */
    fun pollNow(): Int {
        val n = node ?: return 0
        return try {
            n.pollMailbox().also { if (it > 0) log("MAILBOX fetched $it") }
        } catch (e: Exception) {
            log("mailbox poll failed: ${e.message}")
            0
        }
    }

    // ---------------------------------------------------------------- events from the node

    /** Logs only in debug builds: release builds never write codes, addresses or events to logcat (audit M-01). */
    override fun log(line: String) { DebugLog.add(line); if (BuildConfig.DEBUG) Log.i(TAG, line) }

    @Volatile private var torCtl: TorControlConnection? = null
    @Volatile private var socksPort = 0
    fun torControl(): TorControlConnection? = torCtl

    /** A contact redeemed one of our codes; the UI asks the user to check the safety number (audit H-01). */
    val newContactPrompt = MutableStateFlow<String?>(null)

    override fun onNewContactFromInvite(contact: Contact) { newContactPrompt.value = contact.id; db.delete("app", "invite"); prebuildInvite() }

    override fun onContact(contact: Contact) = bump()

    override fun onMessage(contact: Contact, message: Message) {
        if (message.isPrivate) { onPrivateMessage(contact, message); return }
        // View once: the receiver's copy has its own key; the sender keeps nothing at all.
        val keepFile = !(message.viewOnce && !message.incoming)
        if (keepFile) message.file?.let { f -> f.data?.let { if (message.viewOnce) Files.saveOnce(f.id, it) else Files.save(f.id, it) } }
        db.addMessage(message, if (message.incoming) StoredMessage.RECEIVED else StoredMessage.SENT, if (keepFile) message.file?.id else null)
        log((if (message.incoming) "RECV " else "SENT ") + contact.short)
        if (message.incoming) {
            typingFrom.update { it - contact.id }
            val muted = meta(contact.id).muted
            if (meta(contact.id).archived && !muted) setArchived(contact.id, false)
            if (!muted) {
                if (openChat != contact.id || !foreground) {
                    val preview = message.text.ifBlank { message.file?.let { app.getString(if (it.mime.startsWith("image/")) R.string.photo_plain else R.string.file_plain) } ?: "" }
                    Notifications.newMessage(app, if (message.viewOnce) Notifications.Kind.VIEW_ONCE else Notifications.Kind.PLAIN, nickname(contact.id).ifBlank { contact.name }, preview)
                } else if (soundWhileOpen) Notifications.playInApp(app)
            }
            if (readReceipts) {
                val id = message.id
                val open = openChat == contact.id
                pool.execute { node?.let { n -> runCatching { n.sendReceipt(contact, Receipt.DELIVERED, listOf(id)) } } }
                if (open) markRead(contact.id)
            }
        }
        bump()
    }

    // ---------------------------------------------------------------- private mode (memory only)

    private fun onPrivateMessage(contact: Contact, message: Message) {
        // Never to the database, files, caches or notifications' text. The clock is ours for incoming messages.
        val m = if (message.incoming) message.copy(sentAt = System.currentTimeMillis()) else message
        privateStore.add(contact.id, m, if (m.incoming) StoredMessage.RECEIVED else StoredMessage.SENT)
        privateOn.update { it + contact.id }
        if (m.incoming) {
            typingFrom.update { it - contact.id }
            if (!meta(contact.id).muted) {
                if (openChat != contact.id || !foreground) Notifications.newMessage(app, Notifications.Kind.PRIVATE)
                else if (soundWhileOpen) Notifications.playInApp(app)
            }
        }
        bump()
    }

    override fun onControl(contact: Contact, op: Int) {
        when (op) {
            Node.CONTROL_ON -> privateOn.update { it + contact.id }
            Node.CONTROL_OFF -> { privateOn.update { it - contact.id }; privateStore.wipe(contact.id); Notifications.cancelMessages(app) }
            Node.CONTROL_WIPE -> { privateStore.wipe(contact.id); Notifications.cancelMessages(app) }
        }
        bump()
    }

    /** Messages of the chat in private mode, newest last, as the chat screen shows them. */
    fun privateMessages(contactId: String): List<StoredMessage> = privateStore.messages(contactId).map { e ->
        val m = e.message
        StoredMessage(
            m.id, contactId, m.text, m.incoming, m.sentAt, 0, e.status,
            m.file?.id, m.file?.name, m.file?.mime, m.file?.size ?: 0, null, false, false, m.durationMs, m.flags,
        )
    }

    /** Turns private mode on or off for a chat. Turning it on needs the other person online. */
    fun setPrivate(contactId: String, on: Boolean, onError: (String) -> Unit = { toast(it) }) {
        val n = node
        if (n == null) { onError(app.getString(R.string.error_offline)); return }
        pool.execute {
            val c = n.contact(contactId) ?: return@execute
            try {
                n.setPrivateMode(c, on)
                privateOn.update { if (on) it + contactId else it - contactId }
                if (!on) { privateStore.wipe(contactId); Notifications.cancelMessages(app) } else privateStore.touch(contactId)
                bump()
            } catch (e: Exception) {
                onMain { onError(if (e.message == "Update needed") app.getString(R.string.update_needed) else app.getString(R.string.private_needs_online)) }
            }
        }
    }

    /** Wipes the chat's private messages here and (best effort) tells the other phone. The mode itself stays on. */
    fun wipePrivate(contactId: String, notify: Boolean = true) {
        val had = privateStore.has(contactId)
        privateStore.wipe(contactId)
        if (had) { Notifications.cancelMessages(app); bump() }
        if (notify && contactId in privateOn.value) pool.execute { node?.let { n -> n.contact(contactId)?.let { n.sendWipe(it) } } }
    }

    /** App in the background, screen locked: every private chat is wiped (also on the other phones). */
    fun wipeAllPrivate() {
        // The picker for a private photo is ours: leaving for it is not "leaving".
        if (pickerOpen) return
        privateOn.value.forEach { wipePrivate(it) }
    }

    @Volatile var pickerOpen = false

    fun touchPrivate(contactId: String) = privateStore.touch(contactId)

    // ---------------------------------------------------------------- view once and timers

    /** Opened view-once photos (kept only in memory) that failed to send: retry needs the bytes. */
    private val onceRetry = java.util.concurrent.ConcurrentHashMap<String, Pair<String, ByteArray>>()

    /**
     * Opens a received view-once photo: returns its bytes (memory only) and deletes the file and its key right away,
     * so it cannot be opened again, not even after a crash. Returns null if it was already opened.
     */
    fun beginViewOnce(m: StoredMessage): ByteArray? {
        val id = m.fileId ?: return null
        if (!m.viewOnce || !m.incoming || m.openedAt > 0) return null
        val bytes = Files.load(id)
        db.markOpened(m.id, System.currentTimeMillis())
        Notifications.cancelMessages(app)
        bump()
        return bytes
    }

    /** The viewer closed: the row disappears in a minute and the sender is told it was opened. */
    fun endViewOnce(m: StoredMessage) {
        db.setVanish(m.id, System.currentTimeMillis() + VIEW_ONCE_GRACE_MS)
        sendOpened(m.contactId, m.id)
        bump()
    }

    private fun sendOpened(contactId: String, id: String) = whenOnline { n -> n.contact(contactId)?.let { n.sendOpened(it, id) } }

    /** An incoming message with a per-message timer came on screen: its countdown starts (once). */
    fun shown(m: StoredMessage) {
        if (!m.incoming || m.timerSec <= 0 || m.vanishAt > 0) return
        pool.execute { if (db.startTimer(m.id, System.currentTimeMillis()) > 0) bump() }
    }

    // ---------------------------------------------------------------- profile photo and nicknames

    /** Changes when a photo changes: key = contact id or [ME]. */
    val avatarStamps = MutableStateFlow<Map<String, Long>>(emptyMap())
    const val ME = "me"
    private fun stamp(id: String) = avatarStamps.update { it + (id to System.nanoTime()) }

    val myAvatar: ByteArray? get() = db.get("app", "avatar")

    /** Our photo (null = remove) goes to every contact, end-to-end encrypted. */
    fun setMyAvatar(jpeg: ByteArray?) {
        if (jpeg == null) db.delete("app", "avatar") else db.put("app", "avatar", jpeg)
        stamp(ME)
        node?.let { n -> n.avatar = jpeg; n.sendAvatarToAll() }
    }

    /** A contact's photo, or null. Stored encrypted like attachments. */
    fun avatarBytes(id: String): ByteArray? = if (id == ME) myAvatar else Files.load("av_$id")

    override fun onAvatar(contact: Contact, jpeg: ByteArray?) {
        if (jpeg != null) Files.save("av_${contact.id}", jpeg) else Files.delete("av_${contact.id}")
        stamp(contact.id)
    }

    /** Square 256x256 JPEG (centre crop, EXIF removed), at most [Node.MAX_AVATAR] bytes. */
    fun makeAvatar(uri: android.net.Uri): ByteArray {
        val cr = app.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
        val bmp = cr.openInputStream(uri)!!.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException(app.getString(R.string.error_photo))
        val rotation = runCatching { cr.openInputStream(uri)!!.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } }.getOrDefault(0)
        val side = minOf(bmp.width, bmp.height)
        val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        val square = android.graphics.Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, m, true)
        val out = android.graphics.Bitmap.createScaledBitmap(square, 256, 256, true)
        var q = 80
        while (true) {
            val b = java.io.ByteArrayOutputStream().also { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
            if (b.size <= Node.MAX_AVATAR || q <= 30) return b.also { require(it.size <= Node.MAX_AVATAR) }
            q -= 10
        }
    }

    private val nicks = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Local nickname for a contact; never sent. Empty = none. */
    fun nickname(id: String): String = nicks.getOrPut(id) { db.get("nick", id)?.let { String(it) } ?: "" }

    fun setNickname(id: String, name: String) {
        val n = name.trim().take(64)
        nicks[id] = n
        if (n.isEmpty()) db.delete("nick", id) else db.put("nick", id, n.toByteArray())
        bump()
    }

    /** Removes what the app keeps per contact (photo, nickname) when the contact is deleted. */
    fun forgetContact(id: String) { db.delete("chatmeta", id); metas.remove(id); Files.delete("av_$id"); db.delete("nick", id); nicks.remove(id); stamp(id) }

    override fun onReaction(contact: Contact, messageId: String, emoji: String, add: Boolean) {
        val m = db.message(messageId) ?: return
        if (m.contactId != contact.id) return
        db.setReaction(messageId, contact.id, if (add) emoji else null)
        bump()
    }

    override fun onModify(contact: Contact, op: Int, messageId: String, text: String) {
        val m = db.message(messageId) ?: return
        // Only the contact's own messages, only within 24 h (measured from our receipt of it).
        if (m.contactId != contact.id || !Modify.allowed(op, m.incoming, m.at, System.currentTimeMillis()) || m.deleted) return
        if (op == Modify.DELETE) db.applyDelete(messageId)
        else if (m.fileId == null && text.isNotBlank()) db.applyEdit(messageId, text)
        bump()
    }

    override fun onReceipt(contact: Contact, kind: Int, messageIds: List<String>) {
        val now = System.currentTimeMillis()
        if (kind == Receipt.OPENED) {
            // Only our own view-once messages to this contact: the row goes a minute from now, without a trace.
            messageIds.forEach { id ->
                val m = db.message(id)
                if (m != null && !m.incoming && m.viewOnce && m.contactId == contact.id) { db.raiseStatus(listOf(id), StoredMessage.READ); db.setVanish(id, now + VIEW_ONCE_GRACE_MS) }
            }
        } else {
            db.raiseStatus(messageIds, if (kind == Receipt.READ) StoredMessage.READ else StoredMessage.DELIVERED)
            // The read receipt starts the sender's countdown of a per-message timer, same moment as on the other phone.
            if (kind == Receipt.READ) db.startTimers(messageIds, now)
        }
        bump()
    }

    /** Contact id -> time until which "typing…" is shown. */
    val typingFrom = MutableStateFlow<Map<String, Long>>(emptyMap())

    override fun onTyping(contact: Contact, typing: Boolean) {
        typingFrom.update { if (typing) it + (contact.id to System.currentTimeMillis() + 8_000) else it - contact.id }
    }

    /** Marks the incoming messages of an open chat as seen and, if receipts are on, tells the sender. */
    fun markRead(contactId: String) {
        if (contactId == SELF) return
        pool.execute {
            val ids = db.unread(contactId)
            if (ids.isEmpty()) return@execute
            db.markSeen(ids)
            if (readReceipts) node?.let { n -> n.contact(contactId)?.let { c -> runCatching { n.sendReceipt(c, Receipt.READ, ids) } } }
        }
    }

    /** Called while the user types; the node only sends over an already open stream and only if enabled. */
    fun typing(contactId: String, on: Boolean) {
        if (!typingIndicators || contactId == SELF) return
        val n = node ?: return
        pool.execute { n.contact(contactId)?.let { c -> runCatching { n.sendTyping(c, on) } } }
    }

    // ---------------------------------------------------------------- actions (off the main thread)

    fun contacts(): List<Contact> = node?.contacts() ?: Node.storedContacts(db)
    fun contact(id: String): Contact? = node?.contact(id) ?: Node.storedContacts(db).firstOrNull { it.id == id }
    fun node(): Node? = node

    /** Runs [block] once the node is online; reports a plain error through [onError]. */
    fun whenOnline(onError: (String) -> Unit = {}, block: (Node) -> Unit) = pool.execute {
        if (account == null) return@execute
        connect()
        // Callbacks touch the UI (navigation, toasts): always on the main thread.
        if (!ready.await(3, java.util.concurrent.TimeUnit.MINUTES)) { onMain { onError(app.getString(R.string.error_offline)) }; return@execute }
        val n = node ?: return@execute
        try { block(n) } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "action failed", e)
            onMain { onError(e.message ?: "Something went wrong.") }
        }
    }

    /** [fresh] = always a new code (the "New code" button); otherwise the open one is shown again. */
    fun newInvite(fresh: Boolean = false, onReady: (String) -> Unit) = whenOnline { n ->
        val code = ((if (fresh) null else n.openInvite()) ?: n.createInvite()).encode()
        db.put("app", "invite", code.toByteArray())
        log("INVITE $code")
        onMain { onReady(code) }
    }

    /** The last invite code made on this phone: shown at once, before Tor is up (replaced if it was used or expired). */
    fun cachedInvite(): String? = db.get("app", "invite")?.let { String(it) }

    /** Builds the next invite in the background so "My code" and "Invite a friend" only read it. */
    fun prebuildInvite() = whenOnline { n -> n.prebuildInvite()?.encode()?.let { db.put("app", "invite", it.toByteArray()) } }

    /** Opens the share sheet with the invite link; instant when a code is cached, otherwise as soon as one exists. */
    fun shareInvite(context: Context) {
        fun go(code: String) {
            val text = context.getString(R.string.share_text, Links.invite(code))
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val cached = cachedInvite()
        if (cached != null) { go(cached); prebuildInvite() } else newInvite { go(it) }
    }

    fun accept(code: String, onDone: (Contact) -> Unit, onError: (String) -> Unit) = whenOnline(onError) { n ->
        // v3 codes come from an iPhone (mailbox invite); v2 codes from an Android phone.
        val c = if (com.jijilabsdigital.swell.core.MailboxInvite.versionOf(code) == com.jijilabsdigital.swell.core.MailboxInvite.VERSION) {
            val invite = runCatching { com.jijilabsdigital.swell.core.MailboxInvite.decode(code) }.getOrElse { throw IllegalArgumentException("This is not a Swell code.") }
            n.acceptMailboxInvite(invite, "👋")
        } else {
            val invite = runCatching { Invite.decode(code) }.getOrElse { throw IllegalArgumentException("This is not a Swell code.") }
            n.acceptInvite(invite, "👋")
        }
        onMain { onDone(c) }
    }

    /** A message in "Note to Self": stored on this phone only. */
    private fun saveNote(text: String, replyTo: String?, file: Attachment? = null, durationMs: Int = 0) {
        file?.data?.let { Files.save(file.id, it) }
        db.addMessage(Message(Codec.hex(Codec.random(16)), SELF, text, false, System.currentTimeMillis(), 0, file?.let { Attachment(it.id, it.name, it.mime, it.size) }, replyTo, durationMs), StoredMessage.SENT, file?.id)
        bump()
    }

    fun send(contactId: String, text: String, replyTo: String? = null, timerSec: Int = 0) {
        if (contactId == SELF) { saveNote(text, replyTo); return }
        val c = contact(contactId) ?: return
        if (contactId in privateOn.value) {
            // Private: nothing is queued or stored; it goes out now, direct, or fails.
            touchPrivate(contactId)
            whenOnline({ toast(if (it == "Update needed") app.getString(R.string.update_needed) else app.getString(R.string.private_needs_online)) }) { n ->
                n.sendText(n.contact(contactId) ?: error("Contact not found."), text, null, Node.FLAG_PRIVATE, timerSec)
            }
            return
        }
        // Shown at once as "sending"; marked sent or failed when the other phone answers.
        val pending = Message("p" + System.nanoTime(), contactId, text, false, System.currentTimeMillis(), c.expireSec, replyTo = replyTo, timerSec = timerSec)
        db.addMessage(pending, StoredMessage.SENDING)
        bump()
        whenOnline({ db.setStatus(pending.id, StoredMessage.FAILED); bump() }) { n ->
            val to = n.contact(contactId) ?: error("Contact not found.")
            // Link previews are built here, over Tor, and only if the setting is on and the contact can show them.
            val preview = if (linkPreviews && to.supportsLinkPreview && socksPort > 0) Previews.firstUrl(text)?.let { Previews.build(it, socksPort) } else null
            n.sendText(to, text, replyTo, 0, timerSec, preview)
            db.deleteMessage(pending.id)
            bump()
        }
    }

    /** Toggles our reaction: the same emoji again removes it. A new emoji replaces the old one. */
    fun react(m: StoredMessage, emoji: String) {
        if (m.deleted || m.fileId?.startsWith("p") == true || m.id.startsWith("p")) return
        val mine = db.reactions(listOf(m.id))[m.id]?.firstOrNull { it.who == Db.ME }?.emoji
        val add = mine != emoji
        db.setReaction(m.id, Db.ME, if (add) emoji else null)
        bump()
        if (m.contactId == SELF) return
        whenOnline({ toast(it) }) { n -> n.sendReaction(n.contact(m.contactId) ?: return@whenOnline, m.id, emoji.takeIf { add } ?: mine ?: emoji, add) }
    }

    /** True if [m] can still be edited or deleted for everyone by us. */
    fun canModify(m: StoredMessage): Boolean = !m.incoming && !m.deleted && !m.isPrivate && !m.viewOnce && !m.id.startsWith("p") && m.status != StoredMessage.FAILED &&
        (m.contactId == SELF || Modify.allowed(Modify.DELETE, true, m.at, System.currentTimeMillis()))

    fun canEdit(m: StoredMessage): Boolean = canModify(m) && m.fileId == null && m.text.isNotBlank()

    fun editMessage(m: StoredMessage, text: String) {
        val t = text.trim()
        if (!canEdit(m) || t.isEmpty() || t == m.text) return
        if (m.contactId == SELF) { db.applyEdit(m.id, t); bump(); return }
        whenOnline({ toast(it) }) { n ->
            n.sendModify(n.contact(m.contactId) ?: error("Contact not found."), Modify.EDIT, m.id, t)
            db.applyEdit(m.id, t); bump()
        }
    }

    fun deleteForEveryone(m: StoredMessage) {
        if (!canModify(m)) return
        if (m.contactId == SELF) { db.applyDelete(m.id); bump(); return }
        whenOnline({ toast(it) }) { n ->
            n.sendModify(n.contact(m.contactId) ?: error("Contact not found."), Modify.DELETE, m.id)
            db.applyDelete(m.id); bump()
        }
    }

    fun deleteForMe(m: StoredMessage) {
        if (m.isPrivate) privateStore.remove(m.contactId, m.id) else db.deleteMessage(m.id)
        bump()
    }

    /** Report inbox from the build (Jiji Labs); null when this build has none. */
    private val reportInbox: ReportInbox? by lazy {
        val parts = BuildConfig.REPORT_INBOX.split(':')
        if (parts.size != 3) null else ReportInbox(parts[0], Codec.unhex(parts[1]), Codec.unhex(parts[2]))
    }

    val canReport: Boolean get() = reportInbox != null

    /** Sends a sealed report with the reason and the chosen messages; then the UI offers to block. */
    fun report(contactId: String, reason: String, messageIds: Set<String>, onDone: () -> Unit, onError: (String) -> Unit) = whenOnline(onError) { n ->
        val c = n.contact(contactId) ?: error("Contact not found.")
        val picked = db.messages(contactId).filter { it.id in messageIds }
            .map { Message(it.id, it.contactId, it.text, it.incoming, it.at, it.expireSec) }
        n.report(c, reason, picked, reportInbox ?: error("Reporting is not available in this build."))
        onMain(onDone)
    }

    fun retry(m: StoredMessage) {
        if (m.viewOnce) {
            // The sender keeps no copy of a view-once photo; only a failed send still has it, in memory.
            val (_, data) = onceRetry.remove(m.id) ?: run { db.deleteMessage(m.id); bump(); return }
            db.deleteMessage(m.id)
            sendFile(m.contactId, m.fileName ?: "photo.jpg", m.fileMime ?: "image/jpeg", data, "", null, 0, viewOnce = true)
            return
        }
        if (m.fileId != null) {
            val data = Files.load(m.fileId) ?: return
            db.deleteMessage(m.id)
            sendFile(m.contactId, m.fileName ?: "file", m.fileMime ?: "application/octet-stream", data, m.text, m.replyTo, m.durationMs, timerSec = m.timerSec)
            return
        }
        db.deleteMessage(m.id)
        send(m.contactId, m.text, m.replyTo, m.timerSec)
    }

    /** Upload progress (0–100) of messages being sent, by pending message id. */
    val progress = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Sends a photo or file. Shown at once with progress; marked failed (tap to retry) if it does not go out. */
    fun sendFile(contactId: String, name: String, mime: String, data: ByteArray, caption: String = "", replyTo: String? = null, durationMs: Int = 0,
                 viewOnce: Boolean = false, timerSec: Int = 0) {
        if (contactId == SELF) { saveNote(caption, replyTo, Attachment("n" + System.nanoTime(), name, mime, data.size.toLong(), data), durationMs); return }
        val c = contact(contactId) ?: return
        if (contactId in privateOn.value) {
            touchPrivate(contactId)
            whenOnline({ toast(if (it == "Update needed") app.getString(R.string.update_needed) else app.getString(R.string.private_needs_online)) }) { n ->
                n.sendFile(n.contact(contactId) ?: error("Contact not found."), name, mime, data, "", null, durationMs, Node.FLAG_PRIVATE, timerSec)
            }
            return
        }
        val pendingId = "p" + System.nanoTime()
        val flags = if (viewOnce) Node.FLAG_VIEW_ONCE else 0
        // A view-once photo is never stored on the sender's phone; a failed send keeps it in memory for "tap to retry".
        if (viewOnce) onceRetry[pendingId] = name to data else Files.save(pendingId, data)
        db.addMessage(
            Message(pendingId, contactId, caption, false, System.currentTimeMillis(), c.expireSec, Attachment(pendingId, name, mime, data.size.toLong()), replyTo, durationMs, flags, timerSec),
            StoredMessage.SENDING, if (viewOnce) null else pendingId,
        )
        bump()
        whenOnline({ e -> db.setStatus(pendingId, StoredMessage.FAILED); progress.update { it - pendingId }; bump(); toast(if (e == "Update needed") app.getString(R.string.update_needed) else e) }) { n ->
            n.sendFile(n.contact(contactId) ?: error("Contact not found."), name, mime, data, caption, replyTo, durationMs, flags, timerSec) { p ->
                progress.update { it + (pendingId to p) }
            }
            progress.update { it - pendingId }
            onceRetry.remove(pendingId)
            db.deleteMessage(pendingId)
            bump()
        }
    }

    /** Reads a picked photo: scaled to at most 1600 px and re-encoded as JPEG, which drops EXIF (location, camera). */
    fun readPhoto(uri: android.net.Uri): ByteArray {
        val cr = app.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = cr.openInputStream(uri)!!.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException(app.getString(R.string.error_photo))
        // Keep the photo upright: EXIF rotation is applied to the pixels, then EXIF is gone.
        val rotation = runCatching {
            cr.openInputStream(uri)!!.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees }
        }.getOrDefault(0)
        val scale = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
        val m = android.graphics.Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        val out = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return java.io.ByteArrayOutputStream().also { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }

    /** Reads a picked file (name, type, bytes); refuses files above the limit. */
    fun readFile(uri: android.net.Uri): Triple<String, String, ByteArray> {
        val cr = app.contentResolver
        var name = "file"
        cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val bytes = cr.openInputStream(uri)!!.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(chunk); if (r < 0) break
                buf.write(chunk, 0, r)
                if (buf.size() > Node.MAX_FILE) throw IllegalArgumentException(app.getString(R.string.error_file_big))
            }
            buf.toByteArray()
        }
        return Triple(name, mime, bytes)
    }

    /** Runs [block] on the bounded worker pool. */
    fun work(block: () -> Unit) = pool.execute(block)

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    fun onMain(block: () -> Unit) { main.post(block) }

    fun toast(text: String) = main.post { android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show() }

    /** Keeps the Tor stream to an open chat ready, so a message goes out at once. */
    fun warm(contactId: String) { val n = node ?: return; pool.execute { n.contact(contactId)?.let { n.warm(it) } } }

    private val pollSignal = Object()
    @Volatile private var foreground = false

    /** While the app is on screen the mailbox is checked more often; opening the app checks at once. */
    fun setForeground(on: Boolean) {
        foreground = on
        if (on) synchronized(pollSignal) { pollSignal.notifyAll() }
    }
}
