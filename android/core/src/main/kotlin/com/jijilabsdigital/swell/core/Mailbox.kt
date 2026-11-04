package com.jijilabsdigital.swell.core

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Mailbox: holds encrypted envelopes for phones that are offline.
 *
 * - A queue is addressed by `queueId = SHA-256("swell-queue" || secret)`.
 * - Only the secret holder can CREATE a queue, and creating costs a proof of work, so nobody can
 *   cheaply invent queues to fill the disk or use up the creation budget (audit H-04).
 * - Anyone who knows the queue id may PUT (the contact), but only into a queue that exists.
 * - Only the secret holder may GET and ACK. Clients fetch one queue per request over separate Tor
 *   circuits, so requests do not link a user's queues together (audit H-05).
 * - The mailbox sees queue ids, envelope sizes and timing. It never sees identities, IP addresses
 *   (onion service only) or content (envelopes are libsignal ciphertext).
 */
object MailboxProtocol {
    const val VERSION = 2
    const val PUT = 0x11
    const val GET = 0x12
    const val ACK = 0x13
    const val CREATE = 0x14
    /** TURN credentials for a call (CALLS.md §3.4). */
    const val CREDS = 0x15
    /** Read the oldest envelope of a queue by queue id; deletes nothing (v3 mailbox invites). */
    const val PEEK = 0x16
    /** Long poll: answers like GET for up to 64 queues as soon as one has mail, or empty after the wait (iOS foreground). */
    const val WAIT = 0x17
    /** Swell Bell: read the public tag log without naming a queue (PROTOCOL.md 6a). */
    const val BELL = 0x18
    const val R_BELL = 0x84
    const val R_OK = 0x80
    const val R_LIST = 0x82
    const val R_CREDS = 0x83
    const val R_REJECT = 0xFF

    const val MAX_ENVELOPE = 80 * 1024
    const val MAX_PER_QUEUE = 200
    const val MAX_QUEUE_BYTES = 2L * 1024 * 1024
    const val MAX_SECRETS_PER_GET = 8
    const val MAX_LIST = 50
    /** Response budget per GET, so 64 parallel requests stay within the server heap (audit M-07). */
    const val MAX_LIST_BYTES = 192 * 1024
    const val TTL_MS = 14L * 86400 * 1000
    /** Whole-server limits, so nobody can fill the disk. */
    const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
    const val MIN_FREE_DISK = 2L * 1024 * 1024 * 1024
    const val MAX_NEW_QUEUES_PER_MIN = 1200
    /** Proof of work for CREATE: about 4 million SHA-256 hashes, 1–3 s on a phone. */
    const val POW_BITS = 22
    /** Proof of work per TURN credential: about 260 thousand hashes, well under a second. */
    const val CRED_POW_BITS = 18
    const val MAX_CREDS_PER_MIN = 600
    const val MAX_PEEKS_PER_MIN = 600
    /** Longest WAIT the server honours, and how many queues may be waited on at once (each holds a connection thread). */
    const val MAX_WAIT_MS = 25_000
    /** Default cap on parked waiters (the mailbox process can raise or lower it; one waiter holds one connection). */
    const val MAX_WAITS = 4_000
    /** Queues per WAIT: 64 since mailbox 1.2 (8 before). GET stays at [MAX_SECRETS_PER_GET]. */
    const val MAX_SECRETS_PER_WAIT = 64

    fun queueId(secret: ByteArray): ByteArray = sha256("swell-queue".toByteArray() + secret)

    private fun powHash(queueId: ByteArray, nonce: Long) = sha256("swell-pow".toByteArray() + queueId + Out().i64(nonce).done())

    private fun credHash(minute: Long, nonce: Long) = sha256("swell-cred".toByteArray() + Out().i64(minute).i64(nonce).done())

    fun credPowOk(minute: Long, nonce: Long, bits: Int) = leadingZeros(credHash(minute, nonce), bits)

    fun solveCredPow(minute: Long, bits: Int): Long {
        var n = Codec.random(8).let { In(it).i64() } and Long.MAX_VALUE
        while (!credPowOk(minute, n, bits)) n++
        return n
    }

    fun powOk(queueId: ByteArray, nonce: Long, bits: Int): Boolean = leadingZeros(powHash(queueId, nonce), bits)

    private fun leadingZeros(h: ByteArray, bits: Int): Boolean {
        var need = bits
        for (b in h) {
            val v = b.toInt() and 0xFF
            if (need >= 8) { if (v != 0) return false; need -= 8 }
            else return need == 0 || (v ushr (8 - need)) == 0
        }
        return true
    }

    fun solvePow(queueId: ByteArray, bits: Int): Long {
        var n = Codec.random(8).let { In(it).i64() } and Long.MAX_VALUE
        while (!powOk(queueId, n, bits)) n++
        return n
    }
}

/** Storage behind the mailbox server. */
interface MailboxStore {
    /** True if the queue now exists (also when it existed); false if server limits are reached. */
    fun create(queue: String): Boolean
    /** False if the queue does not exist or is full. */
    fun put(queue: String, envelope: ByteArray): Boolean
    /** Oldest first, at most [max] items and about [maxBytes]. Also marks the queue as in use. */
    fun list(queue: String, max: Int, maxBytes: Int = MailboxProtocol.MAX_LIST_BYTES): List<Pair<String, ByteArray>>
    fun delete(queue: String, id: String)
    /** Deletes envelopes older than [olderThan] and queues nobody fetched since then. */
    fun purge(olderThan: Long)
}

class MemoryMailboxStore(private val now: () -> Long = System::currentTimeMillis) : MailboxStore {
    private class E(val id: String, val at: Long, val bytes: ByteArray)
    private class Q { val items = mutableListOf<E>(); var used = 0L }
    private val q = ConcurrentHashMap<String, Q>()
    override fun create(queue: String): Boolean = synchronized(this) {
        q[queue]?.let { it.used = now(); return true } // idempotent for the secret holder
        q[queue] = Q().also { it.used = now() }; true
    }
    override fun put(queue: String, envelope: ByteArray): Boolean = synchronized(this) {
        val l = q[queue]?.items ?: return false
        if (l.size >= MailboxProtocol.MAX_PER_QUEUE || l.sumOf { it.bytes.size.toLong() } + envelope.size > MailboxProtocol.MAX_QUEUE_BYTES) return false
        l += E(Codec.hex(Codec.random(16)), now(), envelope)
        true
    }
    override fun list(queue: String, max: Int, maxBytes: Int): List<Pair<String, ByteArray>> = synchronized(this) {
        val qq = q[queue] ?: return emptyList()
        qq.used = now()
        var size = 0
        qq.items.take(max).takeWhile { size += it.bytes.size; size <= maxBytes || size == it.bytes.size }.map { it.id to it.bytes }
    }
    override fun delete(queue: String, id: String) { synchronized(this) { q[queue]?.items?.removeAll { it.id == id } } }
    override fun purge(olderThan: Long) {
        synchronized(this) {
            q.values.forEach { l -> l.items.removeAll { it.at < olderThan } }
            q.entries.removeIf { it.value.items.isEmpty() && it.value.used < olderThan }
        }
    }
}

/** One directory per queue, one file per envelope; file name = `<millis>-<random>`. */
class FileMailboxStore(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxTotal: Long = MailboxProtocol.MAX_TOTAL_BYTES,
    private val minFree: Long = MailboxProtocol.MIN_FREE_DISK,
) : MailboxStore {
    private var total = 0L
    private var minute = 0L
    private var newQueues = 0

    init {
        root.mkdirs()
        total = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Current bytes stored (for tests and monitoring). */
    fun totalBytes() = synchronized(this) { total }

    private fun dir(queue: String): File {
        require(queue.matches(Regex("[0-9a-f]{64}")))
        return File(root, queue)
    }

    override fun create(queue: String): Boolean = synchronized(this) {
        val d = dir(queue)
        if (d.isDirectory) return d.setLastModified(now()) || true // idempotent for the secret holder
        if (root.usableSpace < minFree) return false
        val m = now() / 60_000
        if (m != minute) { minute = m; newQueues = 0 }
        if (++newQueues > MailboxProtocol.MAX_NEW_QUEUES_PER_MIN) return false
        d.mkdirs() && d.setLastModified(now())
    }

    override fun put(queue: String, envelope: ByteArray): Boolean = synchronized(this) {
        if (total + envelope.size > maxTotal || root.usableSpace - envelope.size < minFree) return false
        val d = dir(queue)
        if (!d.isDirectory) return false
        val files = d.listFiles().orEmpty()
        if (files.size >= MailboxProtocol.MAX_PER_QUEUE || files.sumOf { it.length() } + envelope.size > MailboxProtocol.MAX_QUEUE_BYTES) return false
        val f = File(d, "${now()}-${Codec.hex(Codec.random(8))}")
        val tmp = File(d, ".${f.name}.tmp")
        tmp.writeBytes(envelope)
        tmp.renameTo(f).also { if (it) total += envelope.size }
    }

    override fun list(queue: String, max: Int, maxBytes: Int): List<Pair<String, ByteArray>> = synchronized(this) {
        val d = dir(queue)
        if (!d.isDirectory) return emptyList()
        d.setLastModified(now()) // queue is in use
        val out = ArrayList<Pair<String, ByteArray>>()
        var size = 0L
        for (f in d.listFiles { f -> !f.name.startsWith(".") }.orEmpty().sortedBy { it.name }) {
            if (out.size >= max) break
            if (out.isNotEmpty() && size + f.length() > maxBytes) break
            size += f.length()
            out += f.name to f.readBytes()
        }
        out
    }

    override fun delete(queue: String, id: String) {
        if (!id.matches(Regex("[0-9]+-[0-9a-f]{16}"))) return
        synchronized(this) {
            val f = File(dir(queue), id)
            val n = f.length()
            if (f.delete()) total -= n
        }
    }

    override fun purge(olderThan: Long) = synchronized(this) {
        root.listFiles().orEmpty().forEach { d ->
            d.listFiles().orEmpty().forEach { f -> val n = f.length(); if (f.lastModified() < olderThan && f.delete()) total -= n }
            if (d.isDirectory && d.list().isNullOrEmpty() && d.lastModified() < olderThan) d.delete()
        }
    }
}

/** Swell Bell helpers shared by clients and the server (PROTOCOL.md 6a). */
object Bell {
    /** Caps bit 16. */
    const val CAP_BELL = 1 shl 16
    /** Inner message type 15: `raw32 key`. */
    const val INNER_BELLKEY = 15
    const val TAG_SIZE = 12
    const val KEY_SIZE = 32
    /** Most tags in one BELL answer. */
    const val MAX_TAGS_PER_REPLY = 2048
    /** Tags are published to listeners at most this often (trailing edge). */
    const val COALESCE_MS = 100L
    const val RETENTION_MS = 14L * 86400 * 1000
    /** 192 MiB of disk = about 16.7 million tags (12 bytes each). */
    const val DEFAULT_MAX_BYTES = 192L * 1024 * 1024
    /** Parked BELL requests the server holds at once (each is one connection). */
    const val DEFAULT_MAX_LISTENERS = 5_000

    fun newKey(): ByteArray = Codec.random(KEY_SIZE)

    private fun mac(key: ByteArray, nonce: ByteArray): ByteArray {
        val m = javax.crypto.Mac.getInstance("HmacSHA256")
        m.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(nonce)
    }

    /** `nonce(4) || HMAC-SHA256(key, nonce)[0..8]`. */
    fun tag(key: ByteArray, nonce: ByteArray = Codec.random(4)): ByteArray {
        require(key.size == KEY_SIZE && nonce.size == 4)
        return nonce + mac(key, nonce).copyOf(8)
    }

    /** True if [tag] was made with [key] (constant-time compare). */
    fun matches(key: ByteArray, tag: ByteArray): Boolean {
        if (tag.size != TAG_SIZE || key.size != KEY_SIZE) return false
        return java.security.MessageDigest.isEqual(mac(key, tag.copyOf(4)).copyOf(8), tag.copyOfRange(4, 12))
    }

    /** Indices of the keys that match [tag]. */
    fun matching(keys: List<ByteArray>, tag: ByteArray): List<Int> = keys.indices.filter { matches(keys[it], tag) }
}

/**
 * The public tag log behind op BELL. Tags have no queue id and no time; a cursor is the number of the next tag.
 *
 * - [append] only touches memory (a ring of the newest tags) under a short lock; a writer thread copies the ring to
 *   segment files (`bell-<first cursor, 16 hex>.seg`, 12 bytes per tag), so disk speed never delays a PUT or a WAIT.
 * - Listeners are parked on one [java.util.concurrent.locks.Condition] (no `synchronized`/`wait`, so virtual threads do
 *   not pin their carrier). A publisher timer moves the visible end of the log at most every [coalesceMs] and wakes all.
 *   Answers end at publish points, so every listener with the same cursor gets the same bytes.
 * - Nothing here records who listens: no per-listener state beyond one parked call.
 * - Pass `dir = null` for memory only (tests).
 */
class BellLog(
    private val dir: File? = null,
    private val maxBytes: Long = Bell.DEFAULT_MAX_BYTES,
    private val retentionMs: Long = Bell.RETENTION_MS,
    private val coalesceMs: Long = Bell.COALESCE_MS,
    private val maxListeners: Int = Bell.DEFAULT_MAX_LISTENERS,
    private val ringSize: Int = 262_144,
    private val segmentEntries: Int = 262_144,
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val T = Bell.TAG_SIZE
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val published = lock.newCondition()
    private val dirty = lock.newCondition()
    private val ring = ByteArray(ringSize * T)
    private var appended = 0L      // next cursor to assign; guarded by [lock]
    private var visible = 0L       // listeners see tags below this; guarded by [lock]
    private var written = 0L       // next cursor the writer will write; guarded by [lock]
    private var listeners = 0      // parked BELL calls; guarded by [lock]
    private var publishScheduled = false
    private var closed = false
    private var cacheAt = -1L
    private val cache = HashMap<Long, ByteArray>() // cursor -> answer, valid while visible == cacheAt
    /** cursor of the first tag -> file; guarded by [lock]. */
    private val segments = java.util.TreeMap<Long, File>()
    private var segStart = -1L       // writer's open segment (first cursor); writer thread only
    private var segOut: java.io.FileOutputStream? = null
    private var segCount = 0
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bell-publish").apply { isDaemon = true } }
    private var writer: Thread? = null
    private var ringFloor = 0L // lowest cursor in the ring that is valid after a restart; guarded by [lock]

    init {
        if (dir != null) {
            dir.mkdirs()
            dir.listFiles { f -> f.name.matches(Regex("bell-[0-9a-f]{16}\\.seg")) }.orEmpty().sortedBy { it.name }.forEach {
                segments[it.name.substring(5, 21).toLong(16)] = it
            }
            segments.lastEntry()?.let { (start, f) ->
                val whole = f.length() / T * T
                if (whole != f.length()) java.io.RandomAccessFile(f, "rw").use { r -> r.setLength(whole) }
                appended = start + whole / T
                // Fill the ring with the newest tags so a restart keeps serving recent cursors from memory.
                val from = maxOf(segments.firstKey(), appended - ringSize)
                val b = readDisk(from, (appended - from).toInt())
                if (b != null) for (i in 0 until (appended - from).toInt()) System.arraycopy(b, i * T, ring, (((from + i) % ringSize).toInt()) * T, T)
                ringFloor = from
            }
            visible = appended; written = appended
            writer = Thread(::writeLoop, "bell-writer").apply { isDaemon = true; start() }
            timer.scheduleWithFixedDelay({ runCatching { trim() } }, 5, 60, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /** The visible end of the log (for tests and monitoring). */
    fun end(): Long { lock.lock(); try { return visible } finally { lock.unlock() } }
    fun parked(): Int { lock.lock(); try { return listeners } finally { lock.unlock() } }
    /** Oldest cursor still served. */
    fun oldest(): Long { lock.lock(); try { return oldestLocked() } finally { lock.unlock() } }
    fun diskBytes(): Long { lock.lock(); try { return segments.values.sumOf { it.length() } } finally { lock.unlock() } }

    private fun oldestLocked(): Long =
        if (dir != null) (segments.firstEntry()?.key ?: appended) else maxOf(0L, appended - ringSize)

    /** Adds a tag after its envelope was stored. Memory only; never blocks on disk. */
    fun append(tag: ByteArray) {
        require(tag.size == T)
        lock.lock()
        try {
            if (closed) return
            System.arraycopy(tag, 0, ring, (appended % ringSize).toInt() * T, T)
            appended++
            if (coalesceMs <= 0) publishLocked()
            else if (!publishScheduled) { publishScheduled = true; timer.schedule({ publish() }, coalesceMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            if (dir != null) dirty.signal()
        } finally { lock.unlock() }
    }

    private fun publish() { lock.lock(); try { publishLocked() } finally { lock.unlock() } }
    private fun publishLocked() { publishScheduled = false; visible = appended; published.signalAll() }

    /**
     * One BELL request. Returns the full answer frame, or null if the listener cap is reached (the caller rejects).
     * Parks up to [maxWaitMs] (0..25000) when [cursor] is at the end of the log.
     */
    fun listen(cursor: Long, maxWaitMs: Int): ByteArray? {
        if (cursor < -1) return null
        val deadline = System.nanoTime() + maxWaitMs.coerceIn(0, MailboxProtocol.MAX_WAIT_MS) * 1_000_000L
        lock.lock()
        var counted = false
        try {
            while (true) {
                if (cursor == -1L) return answer(visible, false, 0, ByteArray(0))
                if (cursor > visible) return answer(visible, true, 0, ByteArray(0)) // newer than the log: the log was lost
                if (cursor < visible) break
                val left = deadline - System.nanoTime()
                if (left <= 0 || closed) return answer(visible, false, 0, ByteArray(0))
                if (!counted) { if (listeners >= maxListeners) return null; listeners++; counted = true }
                try { published.awaitNanos(left) } catch (e: InterruptedException) { return answer(visible, false, 0, ByteArray(0)) }
            }
            // cursor < visible: serve from memory or disk.
            if (counted) { listeners--; counted = false }
            val end = visible
            if (cursor < oldestLocked()) return answer(end, true, 0, ByteArray(0))
            val n = minOf(end - cursor, Bell.MAX_TAGS_PER_REPLY.toLong()).toInt()
            if (end - cursor <= ringSize && cursor >= maxOf(appended - ringSize, ringFloor)) {
                if (cacheAt != end) { cache.clear(); cacheAt = end }
                return cache.getOrPut(cursor) { answer(cursor + n, false, n, copyRing(cursor, n)) }
            }
            val diskN = (written - cursor).coerceIn(0, n.toLong()).toInt() // what the writer has already put in files
            lock.unlock()
            try {
                val head = if (diskN > 0) readDisk(cursor, diskN) ?: return answer(end, true, 0, ByteArray(0)) else ByteArray(0)
                lock.lock()
                try {
                    // The rest is still in the ring (the writer never falls more than a ring behind).
                    if (diskN < n && cursor + diskN < appended - ringSize) return answer(end, true, 0, ByteArray(0))
                    return answer(cursor + n, false, n, head + copyRing(cursor + diskN, n - diskN))
                } finally { lock.unlock() }
            } finally { lock.lock() }
        } finally {
            if (counted) listeners--
            lock.unlock()
        }
    }

    private fun copyRing(cursor: Long, n: Int): ByteArray {
        val out = ByteArray(n * T)
        for (i in 0 until n) System.arraycopy(ring, ((cursor + i) % ringSize).toInt() * T, out, i * T, T)
        return out
    }

    private fun answer(newCursor: Long, lost: Boolean, n: Int, tags: ByteArray): ByteArray =
        Out().u8(MailboxProtocol.R_BELL).i64(newCursor).u8(if (lost) 1 else 0).i32(n).raw(tags).done()

    /** Reads [n] tags from the segment files; null if they are gone. Takes no lock for the file I/O. */
    private fun readDisk(cursor: Long, n: Int): ByteArray? {
        val out = ByteArray(n * T)
        var got = 0
        var c = cursor
        while (got < n) {
            val e = lock.let { it.lock(); try { segments.floorEntry(c) } finally { it.unlock() } } ?: return null
            val off = (c - e.key) * T
            try {
                java.io.RandomAccessFile(e.value, "r").use { r ->
                    val avail = ((r.length() - off) / T).toInt()
                    if (avail <= 0) return null
                    val take = minOf(avail, n - got)
                    r.seek(off); r.readFully(out, got * T, take * T)
                    got += take; c += take
                }
            } catch (ex: IOException) { return null }
        }
        return out
    }

    // ---------------------------------------------------------------- disk writer

    private fun writeLoop() {
        while (true) {
            var from: Long; var n: Int; var buf: ByteArray
            lock.lock()
            try {
                while (written >= appended && !closed) dirty.awaitNanos(1_000_000_000L)
                if (written >= appended && closed) break
                if (appended - written > ringSize) { written = appended - ringSize; segStart = -1 } // fell too far behind: leave a gap, never write wrong cursors
                from = written; n = minOf((appended - written).toInt(), 4096)
                buf = copyRing(from, n)
            } finally { lock.unlock() }
            try { writeBatch(from, n, buf) } catch (e: IOException) { /* disk trouble: the memory log keeps working */ }
            lock.lock(); try { written = from + n } finally { lock.unlock() }
        }
        runCatching { segOut?.fd?.sync(); segOut?.close() }
    }

    private fun writeBatch(from: Long, n: Int, buf: ByteArray) {
        var done = 0
        while (done < n) {
            if (segOut == null || segStart < 0 || segCount >= segmentEntries) {
                runCatching { segOut?.fd?.sync(); segOut?.close() }
                // Continue the last segment after a restart if it has room, else start a new file at this cursor.
                val last = lock.let { it.lock(); try { segments.lastEntry() } finally { it.unlock() } }
                val cont = last != null && segStart < 0 && last.key + last.value.length() / T == from && last.value.length() / T < segmentEntries
                if (cont) { segStart = last!!.key; segCount = (last.value.length() / T).toInt(); segOut = java.io.FileOutputStream(last.value, true) }
                else {
                    val f = File(dir, "bell-%016x.seg".format(from + done))
                    segStart = from + done; segCount = 0; segOut = java.io.FileOutputStream(f, true)
                    lock.lock(); try { segments[segStart] = f } finally { lock.unlock() }
                }
            }
            val take = minOf(n - done, segmentEntries - segCount)
            segOut!!.write(buf, done * T, take * T)
            segCount += take; done += take
        }
    }

    /** Drops whole old segments by age and by total size; the open segment stays. */
    fun trim() {
        lock.lock()
        try {
            val open = segStart
            val cut = now() - retentionMs
            var total = segments.values.sumOf { it.length() }
            val it = segments.entries.iterator()
            while (it.hasNext()) {
                val (start, f) = it.next()
                if (start == open || segments.size <= 1) break
                if (f.lastModified() < cut || total > maxBytes) { total -= f.length(); f.delete(); it.remove() } else break
            }
        } finally { lock.unlock() }
    }

    override fun close() {
        lock.lock(); try { closed = true; dirty.signalAll(); published.signalAll() } finally { lock.unlock() }
        writer?.join(5_000)
        timer.shutdownNow()
    }
}

/**
 * TURN relay that trusts credentials made with a shared secret (coturn `use-auth-secret`):
 * username = "<expiry unix seconds>:<random>", password = base64(HMAC-SHA1(secret, username)).
 */
class TurnConfig(val secret: ByteArray, val urls: List<String>, val ttlSec: Long = 4 * 3600)

/** Request handler for the mailbox server; one frame in, one frame out. */
class MailboxServer(
    private val store: MailboxStore,
    private val powBits: Int = MailboxProtocol.POW_BITS,
    private val turn: TurnConfig? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val credPowBits: Int = MailboxProtocol.CRED_POW_BITS,
    /** Most parked WAIT requests at once (each costs one connection thread; use virtual threads for thousands). */
    private val maxWaits: Int = MailboxProtocol.MAX_WAITS,
    /** The Swell Bell tag log; null = this mailbox does not offer Bell (op 0x18 and tagged PUTs get `0xFF`, like a pre-1.3 server). */
    private val bell: BellLog? = null,
) {
    private val usedCredNonces = ConcurrentHashMap<Long, Long>() // nonce -> minute
    private var credMinute = 0L
    private var credCount = 0
    private var peekMinute = 0L
    private var peekCount = 0

    /** One parked WAIT. Its own condition, so a PUT wakes only the waiters of that queue (no thundering herd). */
    private inner class Waiter {
        @Volatile var cancelled = false
        var signalled = false // guarded by [lock]
        val cond: java.util.concurrent.locks.Condition = lock.newCondition()
    }
    // A ReentrantLock, not synchronized/wait: on Java 21 a virtual thread parked in Object.wait pins its carrier thread.
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val waiting = HashMap<String, Waiter>() // queue -> its single active waiter; guarded by [lock]
    private var activeWaiters = 0                   // distinct parked waiters; guarded by [lock]

    /** Currently parked waiters (for tests and monitoring). */
    fun waiters(): Int { lock.lock(); try { return activeWaiters } finally { lock.unlock() } }

    private fun wake(queue: String) {
        lock.lock()
        try { waiting[queue]?.let { it.signalled = true; it.cond.signal() } } finally { lock.unlock() }
    }

    /**
     * WAIT: GET for up to [MailboxProtocol.MAX_SECRETS_PER_WAIT] queues (same answer layout), but hold the answer until
     * one has an envelope or [maxWaitMs] (capped) passes. One waiter per queue (a newer WAIT covering the queue makes the
     * older one answer empty), at most [maxWaits] waiters in total (beyond that: reject, the client falls back to polling).
     */
    private fun waitFor(secrets: List<ByteArray>, maxWaitMs: Int): ByteArray {
        val queues = secrets.map { Codec.hex(MailboxProtocol.queueId(it)) }
        val me = Waiter()
        lock.lock()
        try {
            val olds = queues.mapNotNull { waiting[it] }.toSet()
            if (activeWaiters - olds.size >= maxWaits) return reject()
            olds.forEach { it.cancelled = true; it.signalled = true; it.cond.signal() }
            queues.forEach { waiting[it] = me }
            activeWaiters++
        } finally { lock.unlock() }
        try {
            val deadline = System.nanoTime() + maxWaitMs.coerceIn(0, MailboxProtocol.MAX_WAIT_MS) * 1_000_000L
            var items = ArrayList<Triple<Int, String, ByteArray>>()
            while (true) {
                lock.lock(); try { me.signalled = false } finally { lock.unlock() } // anything put after this point signals us
                items = ArrayList()
                if (!me.cancelled) {
                    var budget = MailboxProtocol.MAX_LIST_BYTES
                    for ((i, q) in queues.withIndex()) {
                        if (items.size >= MailboxProtocol.MAX_LIST || budget <= 0) break
                        store.list(q, MailboxProtocol.MAX_LIST - items.size, budget).forEach { (id, b) -> items += Triple(i, id, b); budget -= b.size }
                    }
                }
                val left = deadline - System.nanoTime()
                if (items.isNotEmpty() || me.cancelled || left <= 0) break
                lock.lock()
                try { if (!me.signalled) me.cond.awaitNanos(left) } catch (e: InterruptedException) { items = ArrayList(); break } finally { lock.unlock() }
            }
            val o = Out().u8(MailboxProtocol.R_LIST).i32(items.size)
            items.forEach { (i, id, b) -> o.i32(i).str(id).bytes(b) }
            return o.done()
        } finally {
            lock.lock()
            try { queues.forEach { if (waiting[it] === me) waiting.remove(it) }; activeWaiters-- } finally { lock.unlock() }
        }
    }

    private fun creds(minute: Long, nonce: Long): ByteArray {
        val t = turn ?: return reject()
        val m = now() / 60_000
        if (kotlin.math.abs(minute - m) > 1 || !MailboxProtocol.credPowOk(minute, nonce, credPowBits)) return reject()
        synchronized(this) {
            usedCredNonces.entries.removeIf { m - it.value > 3 }
            if (usedCredNonces.putIfAbsent(nonce, minute) != null) return reject() // replayed proof
            if (m != credMinute) { credMinute = m; credCount = 0 }
            if (++credCount > MailboxProtocol.MAX_CREDS_PER_MIN) return reject()
        }
        val expires = now() / 1000 + t.ttlSec
        val user = "$expires:${Codec.hex(Codec.random(6))}"
        val mac = javax.crypto.Mac.getInstance("HmacSHA1").apply { init(javax.crypto.spec.SecretKeySpec(t.secret, "HmacSHA1")) }
        val pass = java.util.Base64.getEncoder().encodeToString(mac.doFinal(user.toByteArray()))
        val o = Out().u8(MailboxProtocol.R_CREDS).i32(t.urls.size)
        t.urls.forEach { o.str(it) }
        return o.str(user).str(pass).i64(expires * 1000).done()
    }

    fun handle(frame: ByteArray): ByteArray = try {
        val r = In(frame)
        if (r.u8() != MailboxProtocol.VERSION) throw IOException("version")
        when (r.u8()) {
            MailboxProtocol.CREATE -> {
                val q = MailboxProtocol.queueId(r.raw(32))
                val nonce = r.i64()
                r.end()
                if (MailboxProtocol.powOk(q, nonce, powBits) && store.create(Codec.hex(q))) ok() else reject()
            }
            MailboxProtocol.PUT -> {
                val queue = Codec.hex(r.raw(32))
                val env = r.bytes(MailboxProtocol.MAX_ENVELOPE)
                // Optional trailing bell tag: exactly 12 bytes, else the whole frame is refused and nothing is stored.
                val tag = if (r.more()) r.bytes(Bell.TAG_SIZE + 1).also { if (it.size != Bell.TAG_SIZE || bell == null) throw IOException("bell tag") } else null
                r.end()
                if (store.put(queue, env)) { wake(queue); if (tag != null) bell!!.append(tag); ok() } else reject()
            }
            MailboxProtocol.BELL -> {
                val cursor = r.i64(); val ms = r.i32()
                r.end()
                bell?.listen(cursor, ms) ?: reject()
            }
            MailboxProtocol.GET -> {
                val n = r.i32()
                if (n !in 1..MailboxProtocol.MAX_SECRETS_PER_GET) throw IOException("count")
                val secrets = List(n) { r.raw(32) }
                r.end()
                val items = ArrayList<Triple<Int, String, ByteArray>>()
                var budget = MailboxProtocol.MAX_LIST_BYTES
                for ((i, s) in secrets.withIndex()) {
                    if (items.size >= MailboxProtocol.MAX_LIST || budget <= 0) break
                    store.list(Codec.hex(MailboxProtocol.queueId(s)), MailboxProtocol.MAX_LIST - items.size, budget).forEach { (id, b) ->
                        items += Triple(i, id, b); budget -= b.size
                    }
                }
                val o = Out().u8(MailboxProtocol.R_LIST).i32(items.size)
                items.forEach { (i, id, b) -> o.i32(i).str(id).bytes(b) }
                o.done()
            }
            MailboxProtocol.CREDS -> {
                val minute = r.i64(); val nonce = r.i64()
                r.end()
                creds(minute, nonce)
            }
            MailboxProtocol.WAIT -> {
                val ms = r.i32(); val n = r.i32()
                if (n !in 1..MailboxProtocol.MAX_SECRETS_PER_WAIT) throw IOException("count")
                val secrets = List(n) { r.raw(32) }
                r.end()
                waitFor(secrets, ms)
            }
            MailboxProtocol.PEEK -> {
                val queue = Codec.hex(r.raw(32))
                r.end()
                val allowed = synchronized(this) {
                    val m = now() / 60_000
                    if (m != peekMinute) { peekMinute = m; peekCount = 0 }
                    ++peekCount <= MailboxProtocol.MAX_PEEKS_PER_MIN
                }
                if (!allowed) reject() else {
                    val first = store.list(queue, 1, MailboxProtocol.MAX_ENVELOPE + 1024) // oldest first; deletes nothing
                    val o = Out().u8(MailboxProtocol.R_LIST).i32(first.size)
                    first.forEach { (id, b) -> o.i32(0).str(id).bytes(b) }
                    o.done()
                }
            }
            MailboxProtocol.ACK -> {
                val secret = r.raw(32)
                val n = r.i32()
                if (n !in 1..MailboxProtocol.MAX_LIST) throw IOException("count")
                val ids = List(n) { r.str(64) }
                r.end()
                val q = Codec.hex(MailboxProtocol.queueId(secret))
                ids.forEach { store.delete(q, it) }
                ok()
            }
            else -> reject()
        }
    } catch (e: Exception) {
        reject()
    }

    private fun ok() = Out().u8(MailboxProtocol.R_OK).done()
    private fun reject() = Out().u8(MailboxProtocol.R_REJECT).done()
}
