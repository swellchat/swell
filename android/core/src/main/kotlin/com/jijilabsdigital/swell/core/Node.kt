package com.jijilabsdigital.swell.core

import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.InvalidVersionException
import org.signal.libsignal.protocol.LegacyMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.Fingerprint
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Opens a stream to `<onion>.onion:port` (over Tor in the app and the peer, direct in tests).
 * [isolation]: requests with different values must not share a Tor circuit (null = default).
 */
fun interface Dialer {
    fun dial(onion: String, port: Int, isolation: String?): Socket
}

/** The other side may or may not have processed the frame (the connection broke after sending). */
class AmbiguousDelivery(cause: Throwable) : IOException("No answer after sending", cause)

class Contact(
    val channelId: ByteArray,
    @Volatile var onion: String,
    val identityKey: IdentityKey,
    /** Empty = the contact has not shared a name; the app shows a placeholder. */
    @Volatile var name: String,
    @Volatile var verified: Boolean = false,
    @Volatile var blocked: Boolean = false,
    @Volatile var expireSec: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** Where to leave messages when the contact is offline: their mailbox onion and queue id. */
    @Volatile var peerMailbox: String = "",
    @Volatile var peerQueue: ByteArray = ByteArray(0),
    /** Secret of our own queue for this contact (one queue per contact, so queues are not linkable). */
    val myQueueSecret: ByteArray = Codec.random(32),
    /** Our queue exists on our mailbox (CREATE succeeded). */
    @Volatile var queueReady: Boolean = false,
    /** True for the side that created the invite (the contact redeemed our code). */
    val viaMyInvite: Boolean = false,
    /** Client-auth key we gave this contact for reaching our onion service (audit H-02). */
    val myAuth: ByteArray = AuthKey.generate(),
    /** Client-auth key for reaching the contact's onion service (empty = none yet). */
    @Volatile var peerAuth: ByteArray = ByteArray(0),
    /** Features the contact announced in HELLO/STATE ([Node.CAP_V11] = replies, reactions, edit/delete, receipts, typing, voice notes). */
    @Volatile var caps: Int = 0,
) {
    val id: String = Codec.hex(channelId)
    @Transient internal var upgraded = false
    val address = SignalProtocolAddress(id, 1)
    val short: String get() = id.take(8)

    /** True if the contact's app understands the 1.1 message types and trailing fields. */
    val supportsV11: Boolean get() = caps and Node.CAP_V11 != 0

    internal fun encode(): ByteArray = Out().u8(5).raw(channelId).str(onion).bytes(identityKey.serialize()).str(name)
        .u8(if (verified) 1 else 0).u8(if (blocked) 1 else 0).i32(expireSec).i64(createdAt)
        .str(peerMailbox).bytes(peerQueue).raw(myQueueSecret).u8(if (queueReady) 1 else 0).u8(if (viaMyInvite) 1 else 0)
        .raw(myAuth).bytes(peerAuth).i32(caps).done()

    companion object {
        internal fun decode(b: ByteArray): Contact {
            val r = In(b)
            val v = r.u8()
            if (v !in 3..5) throw IOException("contact version")
            val c = Contact(
                r.raw(16), r.str(64), IdentityKey(r.bytes(64)), r.str(), r.u8() == 1, r.u8() == 1, r.i32(), r.i64(),
                r.str(64), r.bytes(32), r.raw(32), r.u8() == 1, r.u8() == 1,
                // v3 (before client auth): a new key, announced to the contact at start.
                if (v >= 4) r.raw(32) else AuthKey.generate(), if (v >= 4) r.bytes(32) else ByteArray(0),
                if (v >= 5) r.i32() else 0,
            )
            r.end()
            // Older records: announce our state (new key and/or features) and learn the contact's features.
            c.upgraded = v < 5
            return c
        }
    }
}

data class Message(
    val id: String,
    val contactId: String,
    /** Text, or the caption of a file. */
    val text: String,
    val incoming: Boolean,
    val sentAt: Long,
    val expireSec: Int,
    val file: Attachment? = null,
    /** Id of the message this one answers (reply/quote), or null. */
    val replyTo: String? = null,
    /** Length of a voice note in milliseconds, 0 for anything else. */
    val durationMs: Int = 0,
)

/** A photo or file sent with a message. [data] is filled when it arrives or is sent, then stored by the app. */
class Attachment(val id: String, val name: String, val mime: String, val size: Long, val data: ByteArray? = null)

interface NodeEvents {
    fun log(line: String) {}
    fun onContact(contact: Contact) {}
    /** Called inside the storage transaction that also advanced the ratchet; store the message here. */
    fun onMessage(contact: Contact, message: Message) {}
    /** Someone redeemed one of our invites. The app should ask the user to check the safety number. */
    fun onNewContactFromInvite(contact: Contact) {}
    /** Call signaling from a contact (never from the mailbox: calls are live only). */
    fun onCall(contact: Contact, signal: CallSignal) {}
    /** A reaction was added (one per person and message; a new one replaces the old) or removed. Runs in the receive transaction. */
    fun onReaction(contact: Contact, messageId: String, emoji: String, add: Boolean) {}
    /** The contact deleted ([Modify.DELETE]) or edited ([Modify.EDIT]) a message. The app checks [Modify.allowed] against its stored copy. */
    fun onModify(contact: Contact, op: Int, messageId: String, text: String) {}
    /** Delivery ([Receipt.DELIVERED]) or read ([Receipt.READ]) receipt for our messages. Only called while receipts are enabled. */
    fun onReceipt(contact: Contact, kind: Int, messageIds: List<String>) {}
    /** The contact started or stopped typing. Only called while typing indicators are enabled. */
    fun onTyping(contact: Contact, typing: Boolean) {}
    /** The contact set (JPEG bytes) or removed (null) their profile photo. Already checked: size, hash. Runs in the receive transaction. */
    fun onAvatar(contact: Contact, jpeg: ByteArray?) {}
}

/** Edit and delete-for-everyone rules (wire type MODIFY). */
object Modify {
    const val DELETE = 1
    const val EDIT = 2
    const val WINDOW_MS = 24L * 3600 * 1000
    /** Clock difference and transport delay tolerated on top of the window. */
    const val SLACK_MS = 5L * 60 * 1000

    /**
     * Only the sender may change a message, only a message they sent, and only within 24 hours.
     * [targetFromSender]: the stored message is one this person sent. [targetAt]: when it was sent
     * (outgoing) or received (incoming).
     */
    fun allowed(op: Int, targetFromSender: Boolean, targetAt: Long, now: Long): Boolean =
        op in DELETE..EDIT && targetFromSender && now - targetAt <= WINDOW_MS + SLACK_MS
}

object Receipt {
    const val DELIVERED = 1
    const val READ = 2
}

/** Call setup inside the encrypted channel (CALLS.md §3.1). [sdp] is empty for hangup and busy. */
class CallSignal(val op: Int, val callId: String, val sdp: String = "") {
    companion object {
        const val OFFER = 1
        const val ANSWER = 2
        const val HANGUP = 3
        const val BUSY = 4
    }
}

/** Short-lived TURN credentials for one call, not linked to any identity (CALLS.md §3.4). */
class TurnCredentials(val urls: List<String>, val username: String, val password: String, val expiresAt: Long)

/**
 * Protocol engine for one identity: invites, PQXDH handshake, pairwise channels, messages, calls.
 * All state (keys, sessions, contacts, open invites, onion keys) is persisted in [kv].
 *
 * Reachability (audit H-02): our onion services are published with client authorization. Only
 * holders of an open invite and contacts (one key each) can find them. Removing or blocking a
 * contact revokes its key and moves us to a new address; the old one stays up for a short grace.
 */
class Node(
    private val kv: Kv,
    account: Account,
    private val events: NodeEvents,
    private val dialer: Dialer,
    private val host: OnionHost,
    private val now: () -> Long = System::currentTimeMillis,
    private val powBits: Int = MailboxProtocol.POW_BITS,
    private val pollJitterMs: Long = 2_000,
    private val publishDelayMs: Long = 2_000,
    private val retryDelayMs: Long = 5_000,
    /** 0 = never move the address (the always-online demo contact, whose code is published). */
    private val rotateMs: Long = ROTATE_MS,
) {
    /** Our current onion address (set by [start]). */
    @Volatile var onion: String = ""
        private set
    /** Delivery/read receipts: when false we neither send nor show them (Signal's rule). Off by default. */
    @Volatile var receiptsEnabled = false
    /** Typing indicators: when false we neither send nor show them. Off by default. */
    @Volatile var typingEnabled = false
    /** Our profile photo (JPEG, at most [MAX_AVATAR] bytes), sent to every contact after the handshake and on change. */
    @Volatile var avatar: ByteArray? = null
    /** Name shown to new contacts; optional, may be empty. */
    @Volatile var profileName: String = ""
    /** Onion address of the mailbox that holds our messages while we are offline ("" = none). */
    @Volatile var mailboxOnion: String = ""

    val store = KvSignalStore(kv, account)
    private val lock = Any()
    private val pubLock = Any()
    /** Guards the spare client-auth keys only, so taking one never waits for a slow publish (which holds [pubLock]). */
    private val spareLock = Any()
    private val signedPreKey: SignedPreKeyRecord
    private val contacts = ConcurrentHashMap<String, Contact>()
    private val inboundCount = ConcurrentHashMap<String, LongArray>() // id -> [minute, count]
    private val background = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "swell-node").apply { isDaemon = true } }

    private class OnionEntry(val onion: String, val key: String, val created: Long, var retireAt: Long)
    private var onions = mutableListOf<OnionEntry>()
    private var published = emptySet<String>()
    private var lastFullPublish = 0L
    private var lastFilePrune = 0L

    init {
        val spkId = kv.get(NS_ACCT, "spk")?.let { In(it).i32() }
        val current = spkId?.takeIf { store.containsSignedPreKey(it) }?.let { store.loadSignedPreKey(it) }
        signedPreKey = if (current != null && now() - current.timestamp < SPK_ROTATE_MS) current else newSignedPreKey()
        kv.list(NS_CONTACT).forEach { (_, v) -> runCatching { Contact.decode(v) }.getOrNull()?.let { contacts[it.id] = it } }
    }

    private fun newSignedPreKey(): SignedPreKeyRecord {
        val kp = ECKeyPair.generate()
        val sig = store.identityKeyPair.privateKey.calculateSignature(kp.publicKey.serialize())
        return SignedPreKeyRecord(Codec.randomId(), now(), kp, sig).also {
            store.storeSignedPreKey(it.id, it)
            kv.put(NS_ACCT, "spk", Out().i32(it.id).done())
        }
    }

    val identityKey: IdentityKey get() = store.identityKeyPair.publicKey
    fun contacts(): List<Contact> = contacts.values.sortedBy { it.createdAt }
    fun contact(id: String): Contact? = contacts[id]

    private fun save(c: Contact) {
        kv.put(NS_CONTACT, c.id, c.encode())
        events.onContact(c)
    }

    // ---------------------------------------------------------------- onion services

    /**
     * Publishes our onion services and registers the keys for reaching contacts. Call once Tor is up.
     * [legacy] = (address, key) of a service from before client auth, kept so contacts still find us.
     */
    fun start(legacy: Pair<String, String>? = null): String = synchronized(pubLock) {
        fillSpares()
        onions = loadOnions()
        if (onions.isEmpty() && legacy != null) onions += OnionEntry(legacy.first, legacy.second, now(), 0)
        contacts.values.forEach { c -> if (c.peerAuth.size == 32) runCatching { host.clientAuth(c.onion, c.peerAuth) } }
        published = emptySet()
        lastFullPublish = now()
        if (onions.isEmpty()) {
            val clients = authorized()
            val (o, k) = host.create(clients)
            onions += OnionEntry(o, k, now(), 0)
            saveOnions()
            published = clients.map { Codec.hex(it) }.toSet()
        } else {
            publishLocked(force = true)
        }
        onion = onions.last().onion
        val upgraded = contacts.values.filter { it.upgraded }
        upgraded.forEach { it.upgraded = false; save(it) }
        upgraded.forEach { c -> background.execute { runCatching { sendState(c) } } }
        onion
    }

    /** Invite and onion housekeeping; cheap when nothing changed. Call every few minutes. */
    fun maintain() {
        dropIdle()
        pruneInvites()
        if (now() - lastFilePrune > 6 * 3600_000L) { lastFilePrune = now(); runCatching { prunePendingFiles() } }
        val current = synchronized(pubLock) { onions.lastOrNull() } ?: return
        if (rotateMs > 0 && now() - current.created > rotateMs) rotateAddress() else synchronized(pubLock) { publishLocked(force = false) }
    }

    /** Moves to a new onion address and tells every contact; the old address stays up for [RETIRE_GRACE_MS]. */
    fun rotateAddress() {
        synchronized(pubLock) {
            val (o, k) = host.create(authorized())
            onions.forEach { if (it.retireAt == 0L) it.retireAt = now() + RETIRE_GRACE_MS }
            onions += OnionEntry(o, k, now(), 0)
            onion = o
            saveOnions()
            publishLocked(force = true) // old addresses: only current contacts' keys
            events.log("address rotated")
        }
        contacts.values.filter { !it.blocked }.forEach { c -> background.execute { runCatching { sendState(c) } } }
    }

    /**
     * Invite keys published ahead of time, so a new code works at once: no re-publish (and no wait for
     * the new descriptor to reach the directories) when the user creates a code.
     */
    private fun spares(): MutableList<ByteArray> {
        val b = kv.get(NS_ACCT, "spareAuth") ?: return mutableListOf()
        val r = In(b)
        return MutableList(r.i32()) { r.raw(32) }
    }

    private fun saveSpares(l: List<ByteArray>) = kv.put(NS_ACCT, "spareAuth", Out().i32(l.size).also { o -> l.forEach { o.raw(it) } }.done())

    /** Tops the spare invite keys up to [SPARE_INVITE_KEYS]; returns true if any were added. */
    private fun fillSpares(): Boolean = synchronized(spareLock) {
        val l = spares()
        if (l.size >= SPARE_INVITE_KEYS) return@synchronized false
        while (l.size < SPARE_INVITE_KEYS) l += AuthKey.generate()
        saveSpares(l)
        true
    }

    /** X25519 public keys allowed to reach us: contacts that are not blocked, open invites and spare invite keys. */
    private fun authorized(): List<ByteArray> {
        val keys = contacts.values.filter { !it.blocked }.map { AuthKey.publicOf(it.myAuth) }.toMutableList()
        spares().forEach { keys += AuthKey.publicOf(it) }
        kv.list(NS_INVITE).forEach { (_, v) -> runCatching { InviteRecord.decode(v) }.getOrNull()?.let { if (it.reachable(now())) keys += AuthKey.publicOf(it.auth) } }
        return keys
    }


    private fun publishLocked(force: Boolean) {
        val gone = onions.filter { it.retireAt in 1..now() }
        gone.forEach { host.unpublish(it.onion) }
        onions.removeAll(gone)
        if (gone.isNotEmpty()) saveOnions()
        // Re-publishing (DEL_ONION + ADD_ONION) moves the intro points and leaves contacts' Tor clients
        // holding dead circuits for minutes. So it happens only when a key must be ADDED (no spare was
        // left) or when forced (start, rotation, delete, block). Keys of used or expired invites simply
        // stay until the next forced publish; they no longer open anything in the protocol.
        val want = authorized().map { Codec.hex(it) }.toSet()
        // Stale keys (used/expired invites) are dropped at most once a day, so a code that leaked
        // cannot keep reaching the service for long, yet contacts rarely see a re-publish.
        val cleanup = published.any { it !in want } && now() - lastFullPublish > CLEANUP_PUBLISH_MS
        if (!force && !cleanup && published.containsAll(want)) return
        lastFullPublish = now()
        fillSpares()
        val clients = authorized()
        onions.forEach { host.publish(it.onion, it.key, clients) }
        published = clients.map { Codec.hex(it) }.toSet()
    }

    /** A pre-published spare key for a new contact, so adding a contact needs no re-publish. */
    private fun takeSpare(): ByteArray? = synchronized(spareLock) {
        spares().let { l -> l.removeFirstOrNull()?.also { saveSpares(l) } }
    }

    private fun publishNow() = synchronized(pubLock) { publishLocked(force = false) }

    /** Later, so the answer to the request in progress gets out before the service is re-published. */
    private fun publishSoon() { background.schedule({ runCatching { publishNow() } }, publishDelayMs, TimeUnit.MILLISECONDS) }

    private fun loadOnions(): MutableList<OnionEntry> {
        val b = kv.get(NS_ACCT, "onions") ?: return mutableListOf()
        val r = In(b)
        return MutableList(r.i32()) { OnionEntry(r.str(64), r.str(256), r.i64(), r.i64()) }
    }

    private fun saveOnions() {
        val o = Out().i32(onions.size)
        onions.forEach { o.str(it.onion).str(it.key).i64(it.created).i64(it.retireAt) }
        kv.put(NS_ACCT, "onions", o.done())
    }

    /** Registers the key for reaching [c]; never fails the caller (Tor may be restarting). */
    private fun authorize(c: Contact) {
        if (c.peerAuth.size == 32 && c.onion.isNotEmpty()) runCatching { host.clientAuth(c.onion, c.peerAuth) }.onFailure { events.log("client auth failed: ${it.message}") }
    }

    // ---------------------------------------------------------------- contacts

    fun rename(c: Contact, name: String) { c.name = cleanName(name); save(c) }

    /** Blocking revokes the contact's key and moves us to a new address; unblocking tells them the new one. */
    fun setBlocked(c: Contact, blocked: Boolean) {
        c.blocked = blocked; save(c)
        background.execute {
            runCatching { if (blocked) rotateAddress() else { publishNow(); sendState(c) } }
                .onFailure { events.log("block update: ${it.message}") }
        }
    }

    /** Removes the contact, its session, identity and keys, then moves to a new address. Messages are the app's to delete. */
    fun delete(c: Contact) {
        synchronized(lock) {
            contacts.remove(c.id)
            kv.delete(NS_CONTACT, c.id)
            store.deleteAllSessions(c.id)
            kv.delete("idk", "${c.id}.1")
            listOf(NS_PART + c.id, NS_FILEMETA + c.id).forEach { ns -> kv.list(ns).forEach { kv.delete(ns, it.first) } }
        }
        if (contacts.values.none { it.onion == c.onion }) runCatching { host.clientAuth(c.onion, null) }
        if (rotateMs > 0) background.execute { runCatching { rotateAddress() }.onFailure { events.log("rotate: ${it.message}") } }
    }

    fun safetyNumber(c: Contact): Fingerprint =
        NumericFingerprintGenerator(5200).createFor(2, identityKey.serialize(), identityKey, c.identityKey.serialize(), c.identityKey)

    /** QR verification: true and marked verified if the scanned code belongs to this pair of keys. */
    fun verifyScanned(c: Contact, scanned: ByteArray): Boolean {
        val ok = runCatching { safetyNumber(c).scannableFingerprint.compareTo(scanned) }.getOrDefault(false)
        if (ok) { c.verified = true; save(c) }
        return ok
    }

    fun setVerified(c: Contact, verified: Boolean) { c.verified = verified; save(c) }

    // ---------------------------------------------------------------- invites

    private class InviteRecord(val created: Long, val preId: Int, val kyberId: Int, val bundle: ByteArray, val auth: ByteArray, val usedAt: Long) {
        fun encode() = Out().u8(2).i64(created).i32(preId).i32(kyberId).bytes(bundle).raw(auth).i64(usedAt).done()
        val reusable get() = usedAt == REUSABLE
        fun open(now: Long) = reusable || (usedAt == 0L && now - created <= INVITE_TTL_MS)
        /** Still reachable: open, or used moments ago (the redeemer may retry its last request). */
        fun reachable(now: Long) = open(now) || (usedAt > 0 && now - usedAt < USED_INVITE_GRACE_MS)
        companion object {
            const val REUSABLE = -1L
            fun decode(b: ByteArray): InviteRecord {
                val r = In(b)
                if (r.u8() != 2) throw IOException("invite record")
                return InviteRecord(r.i64(), r.i32(), r.i32(), r.bytes(), r.raw(32), r.i64()).also { r.end() }
            }
        }
    }

    /** The newest unused invite younger than [maxAgeMs], so opening the Add screen does not burn a new one each time. */
    fun openInvite(maxAgeMs: Long = 12L * 3600 * 1000): Invite? {
        if (onion.isEmpty()) return null
        return kv.list(NS_INVITE).mapNotNull { (k, v) -> runCatching { k to InviteRecord.decode(v) }.getOrNull() }
            .filter { (_, r) -> r.open(now()) && now() - r.created < maxAgeMs }
            .maxByOrNull { it.second.created }
            ?.let { (k, r) -> Invite(onion, Codec.unhex(k), identityKey.serialize(), r.auth) }
    }

    /**
     * [reusable] = a code many people can redeem, for the always-online demo contact that app
     * reviewers use. Its one-time keys are kept, so it gives up forward secrecy of first messages;
     * never use it for a real person's identity.
     */
    fun createInvite(reusable: Boolean = false): Invite {
        check(onion.isNotEmpty()) { "onion service not ready" }
        val secret = Codec.random(32)
        // A spare key is already in the published descriptor; take it so the code works right away.
        val auth = takeSpare()
        val (preId, kyberId, bundle) = synchronized(lock) {
            val pre = PreKeyRecord(Codec.randomId(), ECKeyPair.generate())
            store.storePreKey(pre.id, pre)
            val kem = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val kemSig = store.identityKeyPair.privateKey.calculateSignature(kem.publicKey.serialize())
            val kyber = KyberPreKeyRecord(Codec.randomId(), now(), kem, kemSig)
            store.storeKyberPreKey(kyber.id, kyber)
            Triple(pre.id, kyber.id, Out()
                .i32(store.localRegistrationId)
                .i32(pre.id).bytes(pre.keyPair.publicKey.serialize())
                .i32(signedPreKey.id).bytes(signedPreKey.keyPair.publicKey.serialize()).bytes(signedPreKey.signature)
                .bytes(identityKey.serialize())
                .i32(kyber.id).bytes(kem.publicKey.serialize()).bytes(kemSig)
                .done())
        }
        val key = auth ?: AuthKey.generate()
        if (reusable) { kv.put("keep", "pk$preId", byteArrayOf(1)); kv.put("keep", "kpk$kyberId", byteArrayOf(1)) }
        kv.put(NS_INVITE, Codec.hex(secret), InviteRecord(now(), preId, kyberId, bundle, key, if (reusable) InviteRecord.REUSABLE else 0).encode())
        // Never wait for Tor here: with no spare left the new key is published in the background, and a
        // redeemer that arrives first simply retries (client auth is checked on its connect). With few
        // spares left, the pool is topped up and published in the background as well.
        if (auth == null || synchronized(spareLock) { spares().size } < MIN_SPARE_INVITE_KEYS) publishSoon()
        return Invite(onion, secret, identityKey.serialize(), key)
    }

    /** The invite the next "My code" tap will show: built ahead in the background, so the tap only reads it. */
    fun prebuildInvite(): Invite? = openInvite() ?: runCatching { createInvite() }.getOrNull()

    /**
     * Deletes expired and used invites together with their one-time private keys, and signed pre-keys
     * older than the rotation window (audit L-05).
     */
    fun pruneInvites() {
        kv.list(NS_INVITE).forEach { (k, v) ->
            val rec = runCatching { InviteRecord.decode(v) }.getOrNull()
            if (rec == null) { kv.delete(NS_INVITE, k); return@forEach }
            if (!rec.reachable(now())) {
                synchronized(lock) { store.removePreKey(rec.preId); kv.delete("kpk", "${rec.kyberId}") }
                kv.delete(NS_INVITE, k)
            }
        }
        store.loadSignedPreKeys().filter { it.id != signedPreKey.id && now() - it.timestamp > SPK_KEEP_MS }
            .forEach { store.removeSignedPreKey(it.id) }
    }

    private fun decodeBundle(b: ByteArray): PreKeyBundle {
        val r = In(b)
        val reg = r.i32()
        val preId = r.i32(); val pre = ECPublicKey(r.bytes(64))
        val spkId = r.i32(); val spk = ECPublicKey(r.bytes(64)); val spkSig = r.bytes(128)
        val ik = IdentityKey(r.bytes(64))
        val kyId = r.i32(); val ky = KEMPublicKey(r.bytes(4096)); val kySig = r.bytes(128)
        r.end()
        return PreKeyBundle(reg, 1, preId, pre, spkId, spk, spkSig, ik, kyId, ky, kySig)
    }

    /** Bob: redeem Alice's invite and send the first message. Returns the new contact. */
    fun acceptInvite(invite: Invite, firstText: String): Contact {
        check(onion.isNotEmpty()) { "onion service not ready" }
        if (invite.identityKey.contentEquals(identityKey.serialize())) throw IOException("This is your own invite.")
        host.clientAuth(invite.onion, invite.authKey)
        val contact = Contact(Codec.random(16), invite.onion, IdentityKey(invite.identityKey), "", createdAt = now(),
            myAuth = takeSpare() ?: AuthKey.generate(), peerAuth = invite.authKey)
        fun rollback() {
            contacts.remove(contact.id)
            synchronized(lock) { store.deleteAllSessions(contact.id); kv.delete("idk", "${contact.id}.1") }
            val other = contacts.values.firstOrNull { it.onion == invite.onion && it.peerAuth.size == 32 }
            runCatching { host.clientAuth(invite.onion, other?.peerAuth) }
            publishSoon()
        }
        val resp = try { request(invite.onion, frame(T_GET_BUNDLE).raw(invite.secret).done(), attempts = 8) } catch (e: IOException) { rollback(); throw e }
        val r = In(resp)
        if (r.u8() != R_BUNDLE) { rollback(); throw IOException("This invite was already used or has expired.") }
        val bundleBytes = r.bytes()
        r.end()
        val bundle = decodeBundle(bundleBytes)
        if (!bundle.identityKey.serialize().contentEquals(invite.identityKey)) {
            rollback(); throw IOException("The invite does not match the person who answered. Do not continue.")
        }
        // Registered and authorized before sending, so Alice can reach us right away (audit M-06, H-02).
        contacts[contact.id] = contact
        try { publishNow() } catch (e: Exception) { rollback(); throw IOException("Tor could not publish the address.", e) }
        ensureQueue(contact) // so the HELLO can carry a working mailbox address
        val msg = newMessage(contact, firstText)
        val ct = synchronized(lock) {
            SessionBuilder(store, contact.address).process(bundle)
            val hello = Out().u8(I_HELLO).str(onion).str(profileName).also { writeMailbox(it, contact) }.bytes(contact.myAuth)
                .also { writeMsg(it, msg) }.i32(CAPS).done()
            SessionCipher(store, contact.address).encrypt(Padding.pad(hello))
        }
        val ok = try {
            In(request(invite.onion, frame(T_HELLO).raw(invite.secret).raw(contact.channelId).bytes(ct.serialize()).done(), attempts = 4))
        } catch (e: IOException) { rollback(); throw e }
        if (ok.u8() != R_OK) { rollback(); throw IOException("The other phone refused the connection.") }
        val state = ok.bytes()
        ok.end()
        synchronized(lock) {
            kv.tx {
                // Alice's answer carries her name, current address and our own key for reaching her.
                val inner = In(Padding.unpad(SessionCipher(store, contact.address).decrypt(SignalMessage(state))))
                if (inner.u8() != I_STATE) throw IOException("bad answer")
                readState(inner, contact)
                save(contact)
                events.onMessage(contact, msg)
            }
        }
        authorize(contact)
        sendAvatarSoon(contact)
        return contact
    }

    /** New contacts get our photo right after the handshake (only if we have one). */
    private fun sendAvatarSoon(c: Contact) {
        if (avatar == null) return
        background.execute { runCatching { sendAvatar(c, avatar) }.onFailure { events.log("avatar send failed: ${it.message}") } }
    }

    /**
     * Redeems a mailbox invite made by an iPhone (v3): PEEK the sealed bundle in the invite queue,
     * check the inviter's identity, then PUT our HELLO into that same queue. The inviter answers with
     * STATE through our mailbox, which completes the contact. Needs our own mailbox.
     */
    fun acceptMailboxInvite(invite: MailboxInvite, firstText: String): Contact {
        check(onion.isNotEmpty()) { "onion service not ready" }
        if (invite.identityKey.contentEquals(identityKey.serialize())) throw IOException("This is your own invite.")
        if (mailboxOnion.isEmpty()) throw IOException("This code needs a mailbox. Set one up in Settings.")
        val iso = Codec.hex(Codec.random(8))
        val peek = Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.PEEK).raw(invite.queueId).done()
        val r = In(request(invite.mailbox, peek, attempts = 4, isolation = iso))
        if (r.u8() != MailboxProtocol.R_LIST) throw IOException("This code cannot be opened right now.")
        val n = r.i32()
        if (n < 1) throw IOException("This invite was already used or has expired.")
        r.i32(); r.str(64)
        val env = In(r.bytes())
        env.u8(); env.raw(16); env.u8()
        val bundleBytes = MailboxInvite.open(invite, env.bytes())
        val bundle = decodeBundle(bundleBytes)
        if (!bundle.identityKey.serialize().contentEquals(invite.identityKey)) {
            throw IOException("The invite does not match the person who made it. Do not continue.")
        }
        val contact = Contact(Codec.random(16), "", IdentityKey(invite.identityKey), "", createdAt = now(), myAuth = takeSpare() ?: AuthKey.generate())
        fun rollback() {
            contacts.remove(contact.id)
            synchronized(lock) { store.deleteAllSessions(contact.id); kv.delete("idk", "${contact.id}.1") }
        }
        contacts[contact.id] = contact
        try {
            ensureQueue(contact) // so the HELLO carries a working mailbox address
            if (!contact.queueReady) throw IOException("Your mailbox did not answer. Try again.")
            val msg = newMessage(contact, firstText)
            val ct = synchronized(lock) {
                SessionBuilder(store, contact.address).process(bundle)
                val hello = Out().u8(I_HELLO).str(onion).str(profileName).also { writeMailbox(it, contact) }.bytes(contact.myAuth)
                    .also { writeMsg(it, msg) }.i32(CAPS).done()
                SessionCipher(store, contact.address).encrypt(Padding.pad(hello))
            }
            // The HELLO is a normal peer frame; its secret is the seal key, which proves we read the invite.
            val helloFrame = frame(T_HELLO).raw(invite.sealKey).raw(contact.channelId).bytes(ct.serialize()).done()
            val put = Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.PUT).raw(invite.queueId).bytes(helloFrame).done()
            if (In(request(invite.mailbox, put, attempts = 4, isolation = iso)).u8() != MailboxProtocol.R_OK) throw IOException("The invite mailbox refused the request.")
            synchronized(lock) { kv.tx { save(contact); events.onMessage(contact, msg) } }
        } catch (e: Exception) { rollback(); throw e }
        return contact
    }

    // ---------------------------------------------------------------- messages

    private fun newMessage(c: Contact, text: String) =
        Message(Codec.hex(Codec.random(16)), c.id, text, false, now(), c.expireSec)

    private fun writeMsg(o: Out, m: Message) { o.raw(Codec.unhex(m.id)).i64(m.sentAt).i32(m.expireSec).str(m.text) }

    /** The timer is the one agreed for the chat, never what a single message claims (audit L-02). */
    private fun readMsg(r: In, c: Contact): Message {
        val id = Codec.hex(r.raw(16)); val at = r.i64(); r.i32(); val text = r.str()
        return Message(id, c.id, text, true, at, c.expireSec)
    }

    /** Optional trailing field after a TEXT message: the id of the message it answers (16 bytes), or empty. */
    private fun readReplyTo(r: In): String? = runCatching {
        if (!r.more()) null else r.bytes(16).takeIf { it.size == 16 }?.let { Codec.hex(it) }
    }.getOrNull()

    private fun replyBytes(id: String?): ByteArray = id?.takeIf { it.length == 32 }?.let { Codec.unhex(it) } ?: ByteArray(0)

    fun sendText(c: Contact, text: String, replyTo: String? = null): Message {
        val m = newMessage(c, text).copy(replyTo = replyTo?.takeIf { it.length == 32 })
        // Old clients stop reading after the text, so the trailing reply id is safe for them.
        send(c, Out().u8(I_TEXT).also { writeMsg(it, m); if (m.replyTo != null) it.bytes(replyBytes(m.replyTo)) }.done())
        events.onMessage(c, m)
        return m
    }

    /**
     * Sends a photo or file: parts of [PART_SIZE] through the same encrypted channel, then the message
     * that names it (name, type, size, SHA-256). Files above [MAX_OFFLINE_FILE] need the contact online.
     */
    fun sendFile(c: Contact, name: String, mime: String, data: ByteArray, caption: String,
                 replyTo: String? = null, durationMs: Int = 0, progress: (Int) -> Unit = {}): Message {
        if (data.isEmpty() || data.size > MAX_FILE) throw IOException("Files can be up to ${MAX_FILE / 1_000_000} MB.")
        val fileId = Codec.random(16)
        // The first part is small (it fits a mailbox). If it went directly, the rest go in big parts:
        // each part costs one Tor round trip, so fewer parts is much faster.
        var off = 0
        var i = 0
        var direct = true
        while (off < data.size) {
            val size = if (i == 0 || !direct) PART_SIZE else BIG_PART_SIZE
            val chunk = data.copyOfRange(off, minOf(data.size, off + size))
            val wasDirect = send(c, Out().u8(I_FILE_PART).raw(fileId).i32(i).bytes(chunk).done(), useMailbox = size == PART_SIZE)
            if (i == 0) direct = wasDirect
            if (!direct && data.size > MAX_OFFLINE_FILE) {
                throw IOException("The contact is offline. Files over ${MAX_OFFLINE_FILE / 1_000_000.0} MB can only be sent while they are online.")
            }
            off += chunk.size; i++
            progress(off * 100 / (data.size + 1))
        }
        val parts = i
        val m = newMessage(c, caption).copy(file = Attachment(Codec.hex(fileId), cleanFileName(name), mime.take(100), data.size.toLong(), data),
            replyTo = replyTo?.takeIf { it.length == 32 }, durationMs = durationMs.coerceIn(0, MAX_VOICE_MS))
        // Version 1.0 rejects a FILE with trailing bytes, so the extras go only to contacts that announced CAP_V11.
        val extras = c.supportsV11 && (m.replyTo != null || m.durationMs > 0)
        send(c, Out().u8(I_FILE).also { writeMsg(it, m) }.raw(fileId).str(m.file!!.name).str(m.file.mime).i64(m.file.size)
            .raw(sha256(data)).i32(parts).also { if (extras) it.bytes(replyBytes(m.replyTo)).i32(m.durationMs) }.done())
        progress(100)
        events.onMessage(c, m)
        return m
    }

    /** Stores one part; completes the file if its message and every part are here. Runs inside the receive transaction. */
    private fun onFilePart(c: Contact, r: In) {
        val fileId = Codec.hex(r.raw(16)); val index = r.i32(); val data = r.bytes(BIG_PART_SIZE)
        r.end()
        if (index !in 0 until MAX_PARTS) throw IOException("part index")
        val ns = NS_PART + c.id
        val pending = kv.list(ns)
        if (pending.sumOf { it.second.size.toLong() } + data.size > MAX_PENDING_PER_CONTACT) throw IOException("too much pending")
        kv.put(ns, "$fileId.$index", Out().i64(now()).bytes(data).done())
        completeFile(c, fileId)
    }

    private fun onFileMessage(c: Contact, r: In) {
        val m = readMsg(r, c)
        val fileId = Codec.hex(r.raw(16)); val name = cleanFileName(r.str(400)); val mime = r.str(100); val size = r.i64()
        val hash = r.raw(32); val parts = r.i32()
        // Optional trailing fields (1.1): reply id, voice duration. Anything further is ignored.
        val replyTo = readReplyTo(r)
        val duration = runCatching { if (r.more()) r.i32() else 0 }.getOrDefault(0).coerceIn(0, MAX_VOICE_MS)
        if (size !in 1..MAX_FILE.toLong() || parts !in 1..MAX_PARTS) throw IOException("file meta")
        val meta = Out().i64(now()).bytes(Out().raw(Codec.unhex(m.id)).i64(m.sentAt).str(m.text).str(name).str(mime).i64(size).raw(hash).i32(parts)
            .bytes(replyBytes(replyTo)).i32(duration).done()).done()
        kv.put(NS_FILEMETA + c.id, fileId, meta)
        completeFile(c, fileId)
    }

    private fun completeFile(c: Contact, fileId: String) {
        val metaB = kv.get(NS_FILEMETA + c.id, fileId) ?: return
        val mr = In(metaB); mr.i64(); val r = In(mr.bytes())
        val msgId = Codec.hex(r.raw(16)); val at = r.i64(); val caption = r.str(); val name = r.str(); val mime = r.str()
        val size = r.i64(); val hash = r.raw(32); val parts = r.i32()
        val replyTo = readReplyTo(r)
        val duration = if (r.more()) r.i32() else 0
        val ns = NS_PART + c.id
        val chunks = ArrayList<ByteArray>(parts)
        for (i in 0 until parts) {
            val p = kv.get(ns, "$fileId.$i") ?: return // not complete yet
            val pr = In(p); pr.i64(); chunks += pr.bytes()
        }
        val data = java.io.ByteArrayOutputStream(size.toInt()).apply { chunks.forEach { write(it) } }.toByteArray()
        for (i in 0 until parts) kv.delete(ns, "$fileId.$i")
        kv.delete(NS_FILEMETA + c.id, fileId)
        if (data.size.toLong() != size || !sha256(data).contentEquals(hash)) { events.log("file dropped: hash mismatch"); return }
        events.onMessage(c, Message(msgId, c.id, caption, true, at, c.expireSec, Attachment(fileId, name, mime, size, data), replyTo, duration))
    }

    /** Parts and file messages that never completed are dropped after the mailbox TTL. */
    private fun prunePendingFiles() {
        val cutoff = now() - MailboxProtocol.TTL_MS
        contacts.keys.forEach { id ->
            listOf(NS_PART + id, NS_FILEMETA + id).forEach { ns ->
                kv.list(ns).forEach { (k, v) -> if (runCatching { In(v).i64() }.getOrDefault(0) < cutoff) kv.delete(ns, k) }
            }
        }
    }

    // ---------------------------------------------------------------- 1.1 message types

    /** Adds ([add]) or removes our reaction on [messageId]. [emoji] is at most 16 bytes of UTF-8. */
    fun sendReaction(c: Contact, messageId: String, emoji: String, add: Boolean) {
        if (!c.supportsV11) return
        val e = emoji.toByteArray(Charsets.UTF_8)
        require(e.isNotEmpty() && e.size <= MAX_EMOJI_BYTES) { "emoji size" }
        send(c, Out().u8(I_REACTION).raw(Codec.unhex(messageId)).bytes(e).u8(if (add) 1 else 0).done())
    }

    /** Asks the contact to delete ([Modify.DELETE]) or replace the text of ([Modify.EDIT]) one of OUR messages. */
    fun sendModify(c: Contact, op: Int, messageId: String, text: String = "") {
        require(op == Modify.DELETE || op == Modify.EDIT)
        if (!c.supportsV11) return
        send(c, Out().u8(I_MODIFY).u8(op).raw(Codec.unhex(messageId)).str(if (op == Modify.EDIT) text else "").done())
    }

    /**
     * Sends [jpeg] as our profile photo, or removes it for the contact if empty/null. The JPEG must be
     * at most [MAX_AVATAR] bytes (256x256, EXIF stripped by the caller), so it fits one mailbox-safe frame.
     */
    fun sendAvatar(c: Contact, jpeg: ByteArray?) {
        val b = jpeg ?: ByteArray(0)
        require(b.size <= MAX_AVATAR) { "avatar too large" }
        if (!c.supportsV11 || c.blocked) return
        send(c, Out().u8(I_AVATAR).raw(sha256(b)).bytes(b).done())
    }

    /** For tests: sends a hand-made inner message. */
    internal fun sendInner(c: Contact, inner: ByteArray) = send(c, inner)

    /** Sends our current photo (or its removal) to every contact; call after the user changed it. */
    fun sendAvatarToAll() {
        val a = avatar
        contacts.values.filter { !it.blocked }.forEach { c -> background.execute { runCatching { sendAvatar(c, a) } } }
    }

    /** Best effort; does nothing while receipts are off. */
    fun sendReceipt(c: Contact, kind: Int, messageIds: List<String>) {
        if (!receiptsEnabled || !c.supportsV11 || messageIds.isEmpty()) return
        require(kind == Receipt.DELIVERED || kind == Receipt.READ)
        messageIds.chunked(MAX_RECEIPT_IDS).forEach { part ->
            send(c, Out().u8(I_RECEIPT).u8(kind).i32(part.size).also { o -> part.forEach { o.raw(Codec.unhex(it)) } }.done(), directAttempts = 2)
        }
    }

    /**
     * Typing on/off. Direct path only, never the mailbox, and only over a stream that is already
     * open (a chat on screen keeps one warm), so an offline contact costs nothing.
     */
    fun sendTyping(c: Contact, typing: Boolean) {
        if (!typingEnabled || !c.supportsV11 || c.blocked || c.onion.isEmpty()) return
        if (!pool.containsKey("${c.onion}|")) return
        send(c, Out().u8(I_TYPING).u8(if (typing) 1 else 0).done(), useMailbox = false, directAttempts = 1)
    }

    /** Sets the disappearing-message timer for both sides of the chat (0 = off). */
    fun setDisappearing(c: Contact, seconds: Int) {
        send(c, Out().u8(I_SETTINGS).i32(seconds).done())
        c.expireSec = seconds
        save(c)
    }

    /** Our name, mailbox, current address and the contact's key for it; sent whenever one changes. */
    fun sendState(c: Contact) {
        ensureQueue(c)
        send(c, stateInner(c))
    }

    @Deprecated("renamed", ReplaceWith("sendState(c)"))
    fun sendProfile(c: Contact) = sendState(c)

    private fun stateInner(c: Contact) =
        Out().u8(I_STATE).str(profileName).also { writeMailbox(it, c) }.str(onion).bytes(c.myAuth).i32(CAPS).done()

    private fun readState(r: In, c: Contact) {
        val n = cleanName(r.str(256))
        readMailbox(r, c)
        val o = r.str(64)
        val auth = r.bytes(32)
        // Later versions may append fields: ignored here, never an error (forward compatibility).
        if (n.isNotEmpty() && c.name.isEmpty()) c.name = n
        if (o.isEmpty() && c.peerMailbox.isNotEmpty()) c.onion = "" // mailbox-only peer (e.g. iPhone)
        if (ONION.matches(o) && o != c.onion) {
            val old = c.onion
            c.onion = o
            if (contacts.values.none { it !== c && it.onion == old }) runCatching { host.clientAuth(old, null) }
        }
        if (auth.size == 32) c.peerAuth = auth
        // Features (1.1). Absent in 1.0 STATE: stays 0, so nothing new is sent to that contact.
        c.caps = if (r.more()) runCatching { r.i32() }.getOrDefault(0) else 0
    }

    private fun writeMailbox(o: Out, c: Contact) {
        val usable = mailboxOnion.isNotEmpty() && c.queueReady
        o.str(if (usable) mailboxOnion else "").bytes(if (usable) MailboxProtocol.queueId(c.myQueueSecret) else ByteArray(0))
    }

    private fun readMailbox(r: In, c: Contact) {
        val mb = r.str(64)
        val q = r.bytes(32)
        if (mb.isEmpty()) { c.peerMailbox = ""; c.peerQueue = ByteArray(0) }
        else if (ONION.matches(mb) && q.size == 32) { c.peerMailbox = mb; c.peerQueue = q }
    }

    /** Creates our queue for [c] on our mailbox (proof of work, then CREATE). No-op without a mailbox. */
    fun ensureQueue(c: Contact) {
        if (mailboxOnion.isEmpty() || c.queueReady) return
        val q = MailboxProtocol.queueId(c.myQueueSecret)
        val nonce = MailboxProtocol.solvePow(q, powBits)
        val f = Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.CREATE).raw(c.myQueueSecret).i64(nonce).done()
        val ok = runCatching { In(request(mailboxOnion, f, attempts = 3, isolation = Codec.hex(Codec.random(8)))).u8() == MailboxProtocol.R_OK }
            .getOrElse { events.log("mailbox queue not created: ${it.message}"); false }
        if (ok) { c.queueReady = true; if (contacts.containsKey(c.id)) save(c) }
    }

    // ---------------------------------------------------------------- calls

    /** Call signaling goes direct only: a stale offer from a mailbox would ring hours later. */
    fun sendCall(c: Contact, signal: CallSignal) {
        val o = Out().u8(I_CALL).u8(signal.op).raw(Codec.unhex(signal.callId)).str(signal.sdp)
        send(c, o.done(), useMailbox = false, directAttempts = 2)
    }

    @Volatile private var turnCache: TurnCredentials? = null

    /**
     * TURN credentials from our mailbox's credential service, over its own Tor circuit. Cached while
     * valid for at least another hour, so a call does not wait for a Tor round trip.
     */
    fun turnCredentials(): TurnCredentials {
        turnCache?.let { if (it.expiresAt - now() > 3600_000) return it }
        if (mailboxOnion.isEmpty()) throw IOException("No relay is set up for calls.")
        var last: Exception? = null
        repeat(3) {
            // A fresh proof each try: the server accepts only the current minute (±1).
            val minute = now() / 60_000
            val nonce = MailboxProtocol.solveCredPow(minute, MailboxProtocol.CRED_POW_BITS)
            val f = Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.CREDS).i64(minute).i64(nonce).done()
            try {
                val r = In(request(mailboxOnion, f, attempts = 1, isolation = Codec.hex(Codec.random(8))))
                if (r.u8() != MailboxProtocol.R_CREDS) throw IOException("The call relay is busy. Try again in a minute.")
                val urls = List(r.i32().coerceIn(0, 8)) { r.str(256) }
                val c = TurnCredentials(urls, r.str(256), r.str(256), r.i64())
                r.end()
                turnCache = c
                return c
            } catch (e: IOException) { last = e }
        }
        throw last ?: IOException("The call relay is not reachable.")
    }

    // ---------------------------------------------------------------- reports

    /**
     * Sends a report to the Jiji Labs report inbox: [reason], the contact's identity key and only the
     * messages the user chose. Sealed to the inbox key; nothing identifies the reporter.
     */
    fun report(c: Contact, reason: String, messages: List<Message>, inbox: ReportInbox) {
        val body = Out().u8(1).i64(now()).str(reason.take(2000)).bytes(c.identityKey.serialize()).i32(messages.size.coerceAtMost(50))
        messages.take(50).forEach { body.i64(it.sentAt).u8(if (it.incoming) 1 else 0).str(it.text.take(4000)) }
        val env = Report.seal(inbox.publicKey, Padding.pad(body.done()))
        val put = Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.PUT).raw(inbox.queueId).bytes(env).done()
        if (In(request(inbox.mailbox, put, attempts = 3, isolation = Codec.hex(Codec.random(8)))).u8() != MailboxProtocol.R_OK) {
            throw IOException("The report inbox did not accept the report. Try again later.")
        }
    }

    // ---------------------------------------------------------------- sending

    /**
     * Direct over Tor first. If the contact is offline and has a mailbox, the same encrypted frame
     * is left there. If a direct send broke after the frame went out, the mailbox copy is a duplicate
     * at worst, and libsignal on the other phone drops duplicates.
     */
    /** Returns true if delivered directly, false if left in the contact's mailbox. */
    private fun send(c: Contact, inner: ByteArray, useMailbox: Boolean = true, directAttempts: Int = 6): Boolean {
        if (c.blocked) throw IOException("Unblock this contact to send messages.")
        val ct = synchronized(lock) { SessionCipher(store, c.address).encrypt(Padding.pad(inner)) }
        val f = frame(T_MSG).raw(c.channelId).u8(ct.type).bytes(ct.serialize()).done()
        val hasMailbox = useMailbox && c.peerMailbox.isNotEmpty() && c.peerQueue.size == 32
        // Mailbox-only contacts (no onion service) are reached through their mailbox alone.
        val direct = if (c.onion.isEmpty()) Result.failure(IOException("This contact can only receive through their mailbox."))
            else runCatching { request(c.onion, f, attempts = if (hasMailbox) 2 else directAttempts) }
        direct.getOrNull()?.let { if (In(it).u8() == R_OK) return true }
        if (!hasMailbox) {
            val e = direct.exceptionOrNull()
            if (e is AmbiguousDelivery) throw IOException("The message may not have arrived.", e)
            throw e ?: IOException("Message refused.")
        }
        events.log("contact offline, leaving message in mailbox")
        val put = Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.PUT).raw(c.peerQueue).bytes(f).done()
        if (In(request(c.peerMailbox, put, attempts = 3, isolation = Codec.hex(Codec.random(8)))).u8() != MailboxProtocol.R_OK) {
            throw IOException("The contact's mailbox is full.")
        }
        return false
    }

    /**
     * Fetches our mailbox one queue at a time, in random order, each over its own Tor circuit,
     * so the mailbox cannot tell which queues belong to the same person by the request (audit H-05).
     * Envelopes are acknowledged (deleted) only after they were stored or permanently refused.
     */
    fun pollMailbox(): Int {
        if (mailboxOnion.isEmpty()) return 0
        var total = 0
        for (c in contacts.values.filter { it.queueReady }.shuffled()) {
            if (pollJitterMs > 0) Thread.sleep((Math.random() * pollJitterMs).toLong())
            val iso = Codec.hex(Codec.random(8))
            for (round in 0 until 10) {
                val get = Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.GET).i32(1).raw(c.myQueueSecret).done()
                val r = In(request(mailboxOnion, get, attempts = 2, isolation = iso))
                if (r.u8() != MailboxProtocol.R_LIST) throw IOException("mailbox refused")
                val n = r.i32()
                if (n == 0) break
                val ack = ArrayList<String>()
                repeat(n) {
                    r.i32(); val id = r.str(64); val env = r.bytes()
                    if (handleOutcome(env, fromMailbox = true).second != Outcome.TRANSIENT) ack += id
                }
                if (ack.isNotEmpty()) {
                    val f = Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.ACK).raw(c.myQueueSecret).i32(ack.size).also { o -> ack.forEach { o.str(it) } }.done()
                    request(mailboxOnion, f, attempts = 2, isolation = iso)
                }
                total += n
                if (ack.size < n || n < MailboxProtocol.MAX_LIST) break
            }
        }
        return total
    }

    /**
     * Long poll (mailbox op WAIT): one request for up to 8 of our queues that the server answers as soon as an
     * envelope lands, or empty after [maxWaitMs]. Messages are handled and acknowledged like in [pollMailbox].
     * Returns the number of envelopes, or -1 if there is no queue to wait on. Throws if the mailbox refuses
     * (old server, too many waiters): the caller then falls back to [pollMailbox].
     */
    fun waitMailbox(maxWaitMs: Int = MailboxProtocol.MAX_WAIT_MS): Int {
        if (mailboxOnion.isEmpty()) return -1
        val cs = contacts.values.filter { it.queueReady }.sortedByDescending { it.createdAt }.take(MailboxProtocol.MAX_SECRETS_PER_GET)
        if (cs.isEmpty()) return -1
        val f = Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.WAIT).i32(maxWaitMs).i32(cs.size).also { o -> cs.forEach { o.raw(it.myQueueSecret) } }.done()
        // One dedicated Tor stream, reused between waits.
        val r = In(request(mailboxOnion, f, attempts = 2, isolation = "wait"))
        if (r.u8() != MailboxProtocol.R_LIST) throw IOException("mailbox refused WAIT")
        val n = r.i32()
        val acks = HashMap<Int, MutableList<String>>()
        repeat(n) {
            val i = r.i32(); val id = r.str(64); val env = r.bytes()
            if (i in cs.indices && handleOutcome(env, fromMailbox = true).second != Outcome.TRANSIENT) acks.getOrPut(i) { ArrayList() } += id
        }
        acks.forEach { (i, ids) ->
            val ack = Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.ACK).raw(cs[i].myQueueSecret).i32(ids.size).also { o -> ids.forEach { o.str(it) } }.done()
            request(mailboxOnion, ack, attempts = 2, isolation = "wait-ack")
        }
        return n
    }

    private fun frame(type: Int) = Out().u8(PROTOCOL).u8(type)

    /**
     * One request/response exchange. Retries only failures before the frame was sent; a failure
     * after sending is [AmbiguousDelivery] and is never retried blindly (audit M-06).
     */
    private class Pooled(val socket: Socket, @Volatile var lastUsed: Long)
    /** Open Tor streams by "onion|isolation"; reusing one skips the 1–10 s of setting up a new stream. */
    private val pool = ConcurrentHashMap<String, Pooled>()

    private fun keep(key: String, s: Socket) {
        val p = Pooled(s, System.currentTimeMillis())
        pool.put(key, p)?.let { old -> if (old.socket !== s) runCatching { old.socket.close() } }
    }

    private fun exchange(s: Socket, frame: ByteArray): ByteArray {
        Wire.write(s.getOutputStream(), frame)
        return Wire.read(s, RESPONSE_DEADLINE_MS)
    }

    private fun request(onion: String, frame: ByteArray, attempts: Int, isolation: String? = null): ByteArray {
        val key = "$onion|${isolation ?: ""}"
        // An idle open stream first. If it turns out dead before any answer, a fresh one is tried:
        // the other side closed it while idle and never read this frame.
        pool.remove(key)?.let { p ->
            if (System.currentTimeMillis() - p.lastUsed < POOL_IDLE_MS && !p.socket.isClosed) {
                try {
                    val r = exchange(p.socket, frame)
                    keep(key, p.socket)
                    return r
                } catch (e: IOException) {
                    runCatching { p.socket.close() }
                    if (e !is java.io.EOFException && e !is java.net.SocketException) throw AmbiguousDelivery(e)
                }
            } else runCatching { p.socket.close() }
        }
        var last: Exception? = null
        repeat(attempts) { n ->
            // A retry goes over a fresh Tor circuit: the old one may be a dead rendezvous circuit
            // left from before the other phone re-published its service.
            val iso = if (n == 0) isolation else "${isolation ?: ""}#${Codec.hex(Codec.random(4))}"
            val s = try {
                dialer.dial(onion, PORT, iso)
            } catch (e: IOException) {
                last = e
                events.log("connect ${onion.take(8)}… failed (${e.message}), try ${n + 1}/$attempts")
                if (n < attempts - 1) Thread.sleep(minOf(retryDelayMs * (n + 1), 30_000L))
                return@repeat
            }
            try { Wire.write(s.getOutputStream(), frame) } catch (e: IOException) { last = e; runCatching { s.close() }; return@repeat }
            val r = try { Wire.read(s, RESPONSE_DEADLINE_MS) } catch (e: IOException) { runCatching { s.close() }; throw AmbiguousDelivery(e) }
            keep(key, s)
            return r
        }
        throw IOException("Could not reach the other phone. It may be offline.", last)
    }

    /** Opens (or keeps open) the stream to [c] so the next message goes out at once. Call while a chat is open. */
    fun warm(c: Contact) {
        if (c.blocked || c.onion.isEmpty()) return
        runCatching { request(c.onion, frame(T_PING).done(), attempts = 1) }
    }

    /** Closes idle streams (call from [maintain]). */
    private fun dropIdle() {
        val t = System.currentTimeMillis()
        pool.entries.removeIf { (_, p) -> (t - p.lastUsed > POOL_IDLE_MS).also { if (it) runCatching { p.socket.close() } } }
    }

    // ---------------------------------------------------------------- inbound

    internal enum class Outcome { OK, PERMANENT, TRANSIENT }

    /** Handles one inbound frame. Never throws; bad input gets a generic rejection (no oracle). */
    fun handle(frame: ByteArray): ByteArray = handleOutcome(frame).first

    internal fun handleOutcome(frame: ByteArray, fromMailbox: Boolean = false): Pair<ByteArray, Outcome> = try {
        val r = In(frame)
        if (r.u8() != PROTOCOL) throw IOException("protocol")
        when (r.u8()) {
            T_GET_BUNDLE -> {
                if (fromMailbox) throw IOException("not via mailbox")
                val bundle = validInvite(r.raw(32)).bundle; r.end()
                Out().u8(R_BUNDLE).bytes(bundle).done() to Outcome.OK
            }
            T_HELLO -> if (fromMailbox) throw IOException("not via mailbox") else onHello(r)
            T_MSG -> onMsg(r, fromMailbox)
            T_PING -> { r.end(); OK to Outcome.OK }
            else -> REJECT to Outcome.PERMANENT
        }
    } catch (e: Exception) {
        val permanent = e is IOException || e is InvalidMessageException || e is DuplicateMessageException ||
            e is LegacyMessageException || e is InvalidKeyException || e is InvalidKeyIdException ||
            e is UntrustedIdentityException || e is NoSessionException || e is InvalidVersionException ||
            e is IllegalArgumentException || e is IllegalStateException || e is ArithmeticException
        events.log("rejected inbound: ${e.javaClass.simpleName}")
        REJECT to (if (permanent) Outcome.PERMANENT else Outcome.TRANSIENT)
    }

    private fun validInvite(secret: ByteArray): InviteRecord {
        val v = kv.get(NS_INVITE, Codec.hex(secret)) ?: throw IOException("unknown invite")
        val rec = InviteRecord.decode(v)
        if (!rec.open(now())) throw IOException("invite used or expired")
        return rec
    }

    private fun onHello(r: In): Pair<ByteArray, Outcome> {
        val secret = r.raw(32)
        val channelId = r.raw(16)
        val body = r.bytes()
        r.end()
        val id = Codec.hex(channelId)
        // A retried HELLO (answer lost on the way back) gets a fresh answer without other effect.
        contacts[id]?.let { known ->
            val rec = kv.get(NS_INVITE, Codec.hex(secret))?.let { runCatching { InviteRecord.decode(it) }.getOrNull() }
            if (rec == null || !rec.reachable(now())) throw IOException("unknown invite")
            return helloAnswer(known) to Outcome.OK
        }
        val (contact, answer) = synchronized(lock) {
            val rec = validInvite(secret)
            val address = SignalProtocolAddress(id, 1)
            kv.tx {
                val inner = Padding.unpad(SessionCipher(store, address).decrypt(PreKeySignalMessage(body)))
                // One use only: the invite is closed once a handshake succeeds; its key stays valid briefly.
                if (!rec.reusable) kv.put(NS_INVITE, Codec.hex(secret), InviteRecord(rec.created, rec.preId, rec.kyberId, rec.bundle, rec.auth, now()).encode())
                val ir = In(inner)
                if (ir.u8() != I_HELLO) throw IOException("bad hello")
                val peerOnion = ir.str(64)
                // Empty = a mailbox-only peer (no onion service, e.g. an iPhone); it must then give a mailbox.
                if (peerOnion.isNotEmpty() && !ONION.matches(peerOnion)) throw IOException("bad onion")
                val peerName = cleanName(ir.str(256))
                val c = Contact(channelId, peerOnion, store.getIdentity(address) ?: throw IOException("no identity"),
                    peerName, createdAt = now(), viaMyInvite = true, myAuth = takeSpare() ?: AuthKey.generate())
                readMailbox(ir, c)
                val auth = ir.bytes(32)
                if (auth.size == 32) c.peerAuth = auth
                val m = readMsg(ir, c)
                if (ir.more()) c.caps = runCatching { ir.i32() }.getOrDefault(0)
                if (c.onion.isEmpty() && c.peerMailbox.isEmpty()) throw IOException("unreachable peer")
                contacts[c.id] = c
                save(c)
                events.onMessage(c, m)
                c to helloAnswer(c)
            }
        }
        authorize(contact)
        events.onNewContactFromInvite(contact)
        publishSoon() // adds the new contact's key; the invite key stays valid for the grace period
        // Mailbox queue (proof of work) after answering, then tell the contact where to leave messages.
        if (mailboxOnion.isNotEmpty()) background.execute {
            for (wait in longArrayOf(0, 5_000, 20_000, 60_000)) {
                Thread.sleep(wait)
                if (runCatching { sendState(contact) }.onFailure { events.log("state reply failed: ${it.message}") }.isSuccess) { sendAvatarSoon(contact); break }
            }
        }
        else if (avatar != null) background.schedule({
            // After the answer reached the new contact (it registers us then); once more if that was too early.
            if (runCatching { sendAvatar(contact, avatar) }.isFailure) background.schedule({ runCatching { sendAvatar(contact, avatar) } }, 5_000, TimeUnit.MILLISECONDS)
        }, 800, TimeUnit.MILLISECONDS)
        return answer to Outcome.OK
    }

    private fun helloAnswer(c: Contact): ByteArray {
        val ct = synchronized(lock) { SessionCipher(store, c.address).encrypt(Padding.pad(stateInner(c))) }
        return Out().u8(R_OK).bytes(ct.serialize()).done()
    }

    private fun onMsg(r: In, fromMailbox: Boolean): Pair<ByteArray, Outcome> {
        val contact = contacts[Codec.hex(r.raw(16))] ?: throw IOException("unknown channel")
        val type = r.u8()
        val body = r.bytes()
        r.end()
        // Blocked contacts get the same answer as strangers and nothing is decrypted (audit L-03).
        if (contact.blocked) return REJECT to Outcome.PERMANENT
        // File parts are padded to 64 KiB, so a message under 16 KiB counts as a message, larger as a part.
        if (!allowInbound(contact, part = body.size > 20_000)) return REJECT to Outcome.TRANSIENT
        var call: CallSignal? = null
        var typing: Boolean? = null
        var reauth = false
        synchronized(lock) {
            // Ratchet step and the stored message commit together, or not at all (audit M-06).
            kv.tx {
                val cipher = SessionCipher(store, contact.address)
                val inner = Padding.unpad(
                    when (type) {
                        CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(body))
                        CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(body))
                        else -> throw IOException("type")
                    },
                )
                val ir = In(inner)
                when (ir.u8()) {
                    I_TEXT -> { val m = readMsg(ir, contact); events.onMessage(contact, m.copy(replyTo = readReplyTo(ir))) }
                    I_SETTINGS -> { contact.expireSec = ir.i32().coerceIn(0, 365 * 86400); save(contact) }
                    I_STATE -> { readState(ir, contact); save(contact); reauth = true }
                    I_FILE_PART -> onFilePart(contact, ir)
                    I_FILE -> onFileMessage(contact, ir)
                    I_CALL -> {
                        val op = ir.u8()
                        val callId = Codec.hex(ir.raw(16))
                        val sdp = ir.str(MAX_SDP)
                        ir.end()
                        if (op !in CallSignal.OFFER..CallSignal.BUSY) throw IOException("call op")
                        if (!fromMailbox) call = CallSignal(op, callId, sdp)
                    }
                    I_REACTION -> {
                        val id = Codec.hex(ir.raw(16)); val emoji = ir.str(64); val add = ir.u8() == 1
                        if (!allowInbound(contact, ephemeral = true)) events.log("reaction dropped: rate limit")
                        else if (validEmoji(emoji)) events.onReaction(contact, id, emoji, add)
                    }
                    I_MODIFY -> {
                        val op = ir.u8(); val id = Codec.hex(ir.raw(16)); val text = ir.str()
                        if (!allowInbound(contact, ephemeral = true)) events.log("modify dropped: rate limit")
                        else if (op == Modify.DELETE || op == Modify.EDIT) events.onModify(contact, op, id, text)
                    }
                    I_RECEIPT -> {
                        val kind = ir.u8(); val n = ir.i32()
                        if (n !in 0..MAX_RECEIPT_IDS) throw IOException("receipt size")
                        val ids = List(n) { Codec.hex(ir.raw(16)) }
                        // Off means off both ways: nothing is shown and nothing is stored.
                        if (receiptsEnabled && (kind == Receipt.DELIVERED || kind == Receipt.READ)) {
                            if (allowInbound(contact, ephemeral = true)) events.onReceipt(contact, kind, ids)
                        }
                    }
                    I_TYPING -> {
                        val on = ir.u8() == 1
                        // Typing never travels through the mailbox: a copy that arrives from there is stale.
                        if (typingEnabled && !fromMailbox && allowInbound(contact, ephemeral = true)) typing = on
                    }
                    I_AVATAR -> {
                        val hash = ir.raw(32); val jpeg = ir.bytes(MAX_AVATAR + 1)
                        // Oversize or a wrong hash is ignored, not an error: nothing is stored, the frame is still answered OK.
                        if (jpeg.size > MAX_AVATAR || !sha256(jpeg).contentEquals(hash)) events.log("avatar ignored: size or hash")
                        else if (!allowInbound(contact, ephemeral = true)) events.log("avatar dropped: rate limit")
                        else events.onAvatar(contact, jpeg.takeIf { it.isNotEmpty() })
                    }
                    // A newer version's message type: accepted (so it is not retried) and ignored.
                    else -> events.log("ignored unknown message type")
                }
            }
        }
        if (reauth) authorize(contact)
        call?.let { events.onCall(contact, it) }
        typing?.let { events.onTyping(contact, it) }
        return OK to Outcome.OK
    }

    /** At most [MAX_INBOUND_PER_MIN] messages and [MAX_PARTS_PER_MIN] file parts a minute from one contact (audit L-08). */
    private fun allowInbound(c: Contact, part: Boolean = false, ephemeral: Boolean = false): Boolean {
        val m = now() / 60_000
        // Reactions, edits, receipts and typing have their own (post-decrypt) bucket; the pre-decrypt cap
        // covers both, so a flood of them cannot starve texts.
        val slot = inboundCount.getOrPut(c.id + if (ephemeral) "/e" else if (part) "/p" else "") { longArrayOf(m, 0) }
        synchronized(slot) {
            if (slot[0] != m) { slot[0] = m; slot[1] = 0 }
            return ++slot[1] <= if (ephemeral) MAX_EPHEMERAL_PER_MIN else if (part) MAX_PARTS_PER_MIN else MAX_INBOUND_PER_MIN + MAX_EPHEMERAL_PER_MIN
        }
    }

    private fun validEmoji(e: String): Boolean =
        e.isNotEmpty() && e.toByteArray(Charsets.UTF_8).size <= MAX_EMOJI_BYTES && e.none { Character.isISOControl(it) }

    companion object {
        const val PORT = 80
        const val INVITE_TTL_MS = 24L * 3600 * 1000
        const val USED_INVITE_GRACE_MS = 10L * 60 * 1000
        const val ROTATE_MS = 30L * 86400 * 1000
        const val RETIRE_GRACE_MS = 2L * 86400 * 1000
        const val RESPONSE_DEADLINE_MS = 45_000L
        /** Client side; below [InboundServer]'s idle time so the server does not close a stream we are about to use. */
        const val POOL_IDLE_MS = 20_000L
        const val MAX_INBOUND_PER_MIN = 120
        const val MAX_SDP = 12 * 1024
        /** Features announced in HELLO/STATE. */
        const val CAP_V11 = 1
        private const val CAPS = CAP_V11
        const val MAX_EMOJI_BYTES = 16
        /** Profile photo: 256x256 JPEG, at most 40 KB (one mailbox-safe frame). */
        const val MAX_AVATAR = 40 * 1024
        const val MAX_RECEIPT_IDS = 64
        const val MAX_EPHEMERAL_PER_MIN = 90
        /** Voice notes are at most 3 minutes. */
        const val MAX_VOICE_MS = 180_000
        const val MAX_FILE = 5_000_000
        const val PART_SIZE = 48 * 1024
        /** Parts after the first when the contact is online (padded to the 256 KiB bucket). */
        const val BIG_PART_SIZE = 240 * 1024
        const val MAX_PARTS = (MAX_FILE + PART_SIZE - 1) / PART_SIZE
        /** Mailbox queues hold 2 MB; larger files go only directly. */
        const val MAX_OFFLINE_FILE = 1_500_000
        const val MAX_PENDING_PER_CONTACT = 2L * MAX_FILE + BIG_PART_SIZE
        const val MAX_PARTS_PER_MIN = 400
        const val SPARE_INVITE_KEYS = 5
        /** Below this many spare client-auth keys, the pool is refilled and published in the background. */
        const val MIN_SPARE_INVITE_KEYS = 3
        const val CLEANUP_PUBLISH_MS = 24L * 3600 * 1000
        private const val SPK_ROTATE_MS = 7L * 86400 * 1000
        private const val SPK_KEEP_MS = 30L * 86400 * 1000
        private const val PROTOCOL = 2
        val ONION = Regex("[a-z2-7]{56}")

        private const val NS_ACCT = "acct"
        private const val NS_CONTACT = "contact"
        private const val NS_INVITE = "invite"
        private const val NS_PART = "fpart."
        private const val NS_FILEMETA = "fmeta."

        private const val T_GET_BUNDLE = 1
        private const val T_HELLO = 2
        private const val T_MSG = 3
        private const val T_PING = 4
        private const val R_OK = 0x80
        private const val R_BUNDLE = 0x81
        private const val R_REJECT = 0xFF
        private const val I_HELLO = 1
        private const val I_TEXT = 2
        private const val I_SETTINGS = 3
        private const val I_STATE = 5
        private const val I_CALL = 6
        private const val I_FILE_PART = 7
        private const val I_FILE = 8
        private const val I_REACTION = 9
        private const val I_MODIFY = 10
        private const val I_RECEIPT = 11
        private const val I_TYPING = 12
        private const val I_AVATAR = 13

        /** Strips control and direction-override characters from names (audit L-07). */
        fun cleanName(s: String): String =
            s.filter { ch -> !Character.isISOControl(ch) && Character.getType(ch) != Character.FORMAT.toInt() }.trim().take(64)

        /** File names without paths, control characters or direction overrides. */
        fun cleanFileName(s: String): String =
            cleanName(s.substringAfterLast('/').substringAfterLast('\\').replace("..", "_")).ifEmpty { "file" }.take(120)

        /** Contacts as stored, for showing the chat list before the network is up. */
        fun storedContacts(kv: Kv): List<Contact> =
            kv.list(NS_CONTACT).mapNotNull { (_, v) -> runCatching { Contact.decode(v) }.getOrNull() }.sortedBy { it.createdAt }

        private val OK = Out().u8(R_OK).done()
        private val REJECT = Out().u8(R_REJECT).done()
    }
}

/** Where reports go: a queue on a Jiji Labs mailbox and the public key reports are sealed to. */
class ReportInbox(val mailbox: String, val queueId: ByteArray, val publicKey: ByteArray)

/** Sealed reports: ephemeral X25519 + HKDF-SHA256 + AES-256-GCM. Only the inbox key holder can open them. */
object Report {
    private const val INFO = "swell report v1"

    fun seal(publicKey: ByteArray, plain: ByteArray): ByteArray {
        val eph = ECKeyPair.generate()
        val shared = eph.privateKey.calculateAgreement(ECPublicKey(byteArrayOf(5) + publicKey))
        val key = Hkdf.sha256(shared, INFO.toByteArray(), 32, salt = eph.publicKey.publicKeyBytes + publicKey)
        val nonce = Codec.random(12)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
        return Out().u8(1).raw(eph.publicKey.publicKeyBytes).raw(nonce).bytes(c.doFinal(plain)).done()
    }

    fun open(privateKey: ByteArray, env: ByteArray): ByteArray {
        val r = In(env)
        if (r.u8() != 1) throw IOException("report version")
        val ephPub = r.raw(32); val nonce = r.raw(12); val ct = r.bytes()
        r.end()
        val priv = org.signal.libsignal.protocol.ecc.ECPrivateKey(privateKey)
        val shared = priv.calculateAgreement(ECPublicKey(byteArrayOf(5) + ephPub))
        val key = Hkdf.sha256(shared, INFO.toByteArray(), 32, salt = ephPub + priv.getPublicKey().publicKeyBytes)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
        return c.doFinal(ct)
    }
}

/**
 * Accepts connections on 127.0.0.1 (the onion service forwards here); one frame in, one frame out.
 * Every connection has an overall deadline and the number of open connections is capped, so a
 * slow sender cannot hold the server (audit H-03).
 */
class InboundServer(
    private val handler: (ByteArray) -> ByteArray,
    private val log: (String) -> Unit,
    port: Int = 0,
    maxConnections: Int = 64,
    private val deadlineMs: Long = 15_000,
    private val idleMs: Long = 30_000,
    private val maxLifeMs: Long = 10 * 60_000,
) {
    constructor(node: Node, events: NodeEvents) : this(node::handle, events::log)

    private val slots = java.util.concurrent.Semaphore(maxConnections)
    private val reaper = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "swell-reaper").apply { isDaemon = true } }

    // 127.0.0.1 explicitly: on Android getLoopbackAddress() is ::1 and Tor forwards to IPv4.
    private val server = ServerSocket(port, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val port: Int get() = server.localPort

    fun start() {
        thread(name = "swell-accept", isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: IOException) { break }
                if (!slots.tryAcquire()) { runCatching { s.close() }; continue }
                open += s
                // Hard stop for the whole connection, whatever the peer does.
                val kill = reaper.schedule({ runCatching { s.close() } }, maxLifeMs, TimeUnit.MILLISECONDS)
                thread(name = "swell-conn", isDaemon = true) {
                    try {
                        s.use {
                            // Several frames per connection: the first within the deadline, later ones after
                            // at most [idleMs] of quiet; the whole connection is capped by the reaper.
                            var first = true
                            while (true) {
                                val req = try { Wire.read(it, if (first) deadlineMs else idleMs + deadlineMs) } catch (e: IOException) {
                                    if (first) throw e else break
                                }
                                Wire.write(it.getOutputStream(), handler(req))
                                first = false
                            }
                        }
                    } catch (e: IOException) {
                        log("inbound connection dropped: ${e.message}")
                    } finally {
                        open -= s
                        kill.cancel(false)
                        slots.release()
                    }
                }
            }
        }
    }

    private val open = java.util.Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    /** Stops listening and drops every open connection. */
    fun close() {
        server.close()
        open.toList().forEach { runCatching { it.close() } }
    }
}

/** Server on a fixed local port (mailbox). */
fun InboundServerAt(port: Int, handler: (ByteArray) -> ByteArray, log: (String) -> Unit) = InboundServer(handler, log, port)
