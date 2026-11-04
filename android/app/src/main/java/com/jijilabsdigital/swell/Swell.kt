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
        thread(name = "swell-expire", isDaemon = true) {
            while (true) {
                if (db.expire(System.currentTimeMillis()) > 0) bump()
                Thread.sleep(30_000)
            }
        }
    }

    private fun bump() = changes.update { it + 1 }

    // ---------------------------------------------------------------- account

    fun createAccount(): Account = Account.create().also { it.save(db); account = it }

    fun restoreAccount(words: List<String>): Account = Account.restore(words).also { it.save(db); account = it }

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
    override fun log(line: String) { if (BuildConfig.DEBUG) Log.i(TAG, line) }

    /** A contact redeemed one of our codes; the UI asks the user to check the safety number (audit H-01). */
    val newContactPrompt = MutableStateFlow<String?>(null)

    override fun onNewContactFromInvite(contact: Contact) { newContactPrompt.value = contact.id; db.delete("app", "invite"); prebuildInvite() }

    override fun onContact(contact: Contact) = bump()

    override fun onMessage(contact: Contact, message: Message) {
        message.file?.let { f -> f.data?.let { Files.save(f.id, it) } }
        db.addMessage(message, if (message.incoming) StoredMessage.RECEIVED else StoredMessage.SENT)
        log((if (message.incoming) "RECV " else "SENT ") + contact.short)
        if (message.incoming) {
            typingFrom.update { it - contact.id }
            if (openChat != contact.id) Notifications.newMessage(app)
            if (readReceipts) {
                val id = message.id
                val open = openChat == contact.id
                pool.execute { node?.let { n -> runCatching { n.sendReceipt(contact, Receipt.DELIVERED, listOf(id)) } } }
                if (open) markRead(contact.id)
            }
        }
        bump()
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
    fun forgetContact(id: String) { Files.delete("av_$id"); db.delete("nick", id); nicks.remove(id); stamp(id) }

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
        db.raiseStatus(messageIds, if (kind == Receipt.READ) StoredMessage.READ else StoredMessage.DELIVERED)
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

    fun send(contactId: String, text: String, replyTo: String? = null) {
        if (contactId == SELF) { saveNote(text, replyTo); return }
        val c = contact(contactId) ?: return
        // Shown at once as "sending"; marked sent or failed when the other phone answers.
        val pending = Message("p" + System.nanoTime(), contactId, text, false, System.currentTimeMillis(), c.expireSec, replyTo = replyTo)
        db.addMessage(pending, StoredMessage.SENDING)
        bump()
        whenOnline({ db.setStatus(pending.id, StoredMessage.FAILED); bump() }) { n ->
            n.sendText(n.contact(contactId) ?: error("Contact not found."), text, replyTo)
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
    fun canModify(m: StoredMessage): Boolean = !m.incoming && !m.deleted && !m.id.startsWith("p") && m.status != StoredMessage.FAILED &&
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

    fun deleteForMe(m: StoredMessage) { db.deleteMessage(m.id); bump() }

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
        if (m.fileId != null) {
            val data = Files.load(m.fileId) ?: return
            db.deleteMessage(m.id)
            sendFile(m.contactId, m.fileName ?: "file", m.fileMime ?: "application/octet-stream", data, m.text, m.replyTo, m.durationMs)
            return
        }
        db.deleteMessage(m.id)
        send(m.contactId, m.text, m.replyTo)
    }

    /** Upload progress (0–100) of messages being sent, by pending message id. */
    val progress = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Sends a photo or file. Shown at once with progress; marked failed (tap to retry) if it does not go out. */
    fun sendFile(contactId: String, name: String, mime: String, data: ByteArray, caption: String = "", replyTo: String? = null, durationMs: Int = 0) {
        if (contactId == SELF) { saveNote(caption, replyTo, Attachment("n" + System.nanoTime(), name, mime, data.size.toLong(), data), durationMs); return }
        val c = contact(contactId) ?: return
        val pendingId = "p" + System.nanoTime()
        Files.save(pendingId, data)
        db.addMessage(
            Message(pendingId, contactId, caption, false, System.currentTimeMillis(), c.expireSec, Attachment(pendingId, name, mime, data.size.toLong()), replyTo, durationMs),
            StoredMessage.SENDING,
        )
        bump()
        whenOnline({ e -> db.setStatus(pendingId, StoredMessage.FAILED); progress.update { it - pendingId }; bump(); toast(e) }) { n ->
            n.sendFile(n.contact(contactId) ?: error("Contact not found."), name, mime, data, caption, replyTo, durationMs) { p ->
                progress.update { it + (pendingId to p) }
            }
            progress.update { it - pendingId }
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
