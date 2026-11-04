package com.jijilabsdigital.swell.data

import android.content.ContentValues
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import com.jijilabsdigital.swell.core.Codec
import com.jijilabsdigital.swell.core.Kv
import com.jijilabsdigital.swell.core.Message
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The database key is 32 random bytes, stored only wrapped by an Android Keystore AES key
 * (StrongBox when the phone has it). The key never leaves the Keystore.
 */
private object KeyWrap {
    private const val ALIAS = "swell-db-wrap"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        fun gen(strongBox: Boolean): SecretKey {
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .apply { if (strongBox && android.os.Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
                .build()
            g.init(spec)
            return g.generateKey()
        }
        return try { gen(true) } catch (e: StrongBoxUnavailableException) { gen(false) } catch (e: Exception) { gen(false) }
    }

    fun dbKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("swell-keys", Context.MODE_PRIVATE)
        prefs.getString("db", null)?.let { stored ->
            val b = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
            return c.doFinal(b.copyOfRange(12, b.size))
        }
        val raw = Codec.random(32)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val wrapped = c.iv + c.doFinal(raw)
        prefs.edit().putString("db", Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit()
        return raw
    }

    fun forget(context: Context) {
        context.getSharedPreferences("swell-keys", Context.MODE_PRIVATE).edit().clear().commit()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }
}

class StoredMessage(
    val id: String,
    val contactId: String,
    val text: String,
    val incoming: Boolean,
    val at: Long,
    val expireSec: Int,
    val status: Int,
    /** Attachment (stored encrypted by [Files]); null for plain text. */
    val fileId: String? = null,
    val fileName: String? = null,
    val fileMime: String? = null,
    val fileSize: Long = 0,
    /** Id of the message this one answers (reply/quote). */
    val replyTo: String? = null,
    /** Text was replaced by an edit. */
    val edited: Boolean = false,
    /** Deleted for everyone: only a tombstone remains. */
    val deleted: Boolean = false,
    /** Voice note length in milliseconds. */
    val durationMs: Int = 0,
    /** [com.jijilabsdigital.swell.core.Node.FLAG_VIEW_ONCE] / FLAG_PRIVATE. */
    val flags: Int = 0,
    /** Per-message timer in seconds, 0 = none. */
    val timerSec: Int = 0,
    /** When the message vanishes (ms), 0 = not started. */
    val vanishAt: Long = 0,
    /** View once: the viewer was opened at this time (0 = never). */
    val openedAt: Long = 0,
    /** Outgoing: when the delivered / read receipt arrived (0 = not yet or receipts off). */
    val deliveredAt: Long = 0,
    val readAt: Long = 0,
    /** Link preview card (wire section 5c), built by the sender. */
    val preview: com.jijilabsdigital.swell.core.LinkPreview? = null,
) {
    val viewOnce: Boolean get() = flags and 1 != 0
    val isPrivate: Boolean get() = flags and 2 != 0
    val isImage: Boolean get() = fileMime?.startsWith("image/") == true
    val isVoice: Boolean get() = fileMime?.startsWith("audio/") == true

    companion object {
        const val SENDING = 0
        const val SENT = 1
        const val FAILED = 2
        const val RECEIVED = 3
        /** Outgoing: the contact's phone has stored it (needs receipts on both sides). */
        const val DELIVERED = 4
        /** Outgoing: seen by the contact. Incoming: we have shown it (read receipt handled). */
        const val READ = 5
    }
}

/** One person's reaction on a message. [who] is [Db.ME] or the contact id. */
class Reaction(val messageId: String, val who: String, val emoji: String)

/** SQLCipher database: protocol state (kv) and messages. */
class Db private constructor(private val file: File, private val db: SQLiteDatabase) : Kv {

    override fun get(ns: String, key: String): ByteArray? =
        db.rawQuery("SELECT v FROM kv WHERE ns=? AND k=?", arrayOf(ns, key)).use { if (it.moveToFirst()) it.getBlob(0) else null }

    override fun put(ns: String, key: String, value: ByteArray) {
        db.insertWithOnConflict("kv", null, ContentValues().apply { put("ns", ns); put("k", key); put("v", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun delete(ns: String, key: String) { db.delete("kv", "ns=? AND k=?", arrayOf(ns, key)) }

    override fun list(ns: String): List<Pair<String, ByteArray>> =
        db.rawQuery("SELECT k, v FROM kv WHERE ns=?", arrayOf(ns)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getBlob(1)) }
        }

    override fun deleteAll() { db.delete("kv", null, null); db.delete("messages", null, null); db.delete("reactions", null, null) }

    override fun <T> tx(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    fun addMessage(m: Message, status: Int, fileId: String? = m.file?.id) {
        db.insertWithOnConflict("messages", null, ContentValues().apply {
            put("id", m.id); put("contact", m.contactId); put("text", m.text); put("incoming", if (m.incoming) 1 else 0)
            m.replyTo?.let { put("reply_to", it) }
            if (m.flags != 0) put("flags", m.flags)
            if (m.timerSec > 0) put("timer", m.timerSec)
            m.preview?.let { put("preview", it.encode()) }
            if (m.durationMs > 0) put("duration", m.durationMs)
            m.file?.let { f -> put("file_id", fileId); put("file_name", f.name); put("file_mime", f.mime); put("file_size", f.size) }
            // Incoming messages expire by local receive time, so a wrong clock on the other phone cannot keep them.
            put("at", if (m.incoming) System.currentTimeMillis() else m.sentAt); put("expire", m.expireSec); put("status", status)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun setStatus(id: String, status: Int) {
        db.update("messages", ContentValues().apply { put("status", status) }, "id=?", arrayOf(id))
    }

    private fun row(c: android.database.Cursor) = StoredMessage(
        c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getLong(4), c.getInt(5), c.getInt(6),
        c.getString(7), c.getString(8), c.getString(9), c.getLong(10),
        c.getString(11), c.getInt(12) == 1, c.getInt(13) == 1, c.getInt(14),
        c.getInt(15), c.getInt(16), c.getLong(17), c.getLong(18), c.getLong(19), c.getLong(20),
        c.getBlob(21)?.let { com.jijilabsdigital.swell.core.LinkPreview.decode(it) },
    )

    private val cols = "id, contact, text, incoming, at, expire, status, file_id, file_name, file_mime, IFNULL(file_size, 0), " +
        "reply_to, IFNULL(edited, 0), IFNULL(deleted, 0), IFNULL(duration, 0), " +
        "IFNULL(flags, 0), IFNULL(timer, 0), IFNULL(vanish_at, 0), IFNULL(opened_at, 0), IFNULL(delivered_at, 0), IFNULL(read_at, 0), preview"

    fun messages(contactId: String): List<StoredMessage> =
        db.rawQuery("SELECT $cols FROM messages WHERE contact=? ORDER BY at, rowid", arrayOf(contactId)).use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }

    fun message(id: String): StoredMessage? =
        db.rawQuery("SELECT $cols FROM messages WHERE id=?", arrayOf(id)).use { c -> if (c.moveToFirst()) row(c) else null }

    fun lastMessages(): Map<String, StoredMessage> =
        db.rawQuery("SELECT $cols FROM messages m WHERE rowid = (SELECT rowid FROM messages WHERE contact = m.contact ORDER BY at DESC, rowid DESC LIMIT 1)", null).use { c ->
            buildMap { while (c.moveToNext()) row(c).let { put(it.contactId, it) } }
        }

    private fun fileIds(where: String, args: Array<String>): List<String> =
        db.rawQuery("SELECT file_id FROM messages WHERE file_id IS NOT NULL AND ($where)", args).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** Deletes rows and the encrypted files they point to. */
    private fun deleteWhere(where: String, args: Array<String>): Int {
        fileIds(where, args).forEach { Files.delete(it) }
        return db.delete("messages", where, args)
    }

    fun deleteMessage(id: String) { deleteWhere("id=?", arrayOf(id)); pruneReactions() }

    fun deleteMessages(contactId: String) { deleteWhere("contact=?", arrayOf(contactId)); pruneReactions() }

    // ------------------------------------------------------------ 1.1: reactions, edit, delete, receipts, search

    /** Sets (or with null removes) [who]'s reaction on a message. One reaction per person and message. */
    fun setReaction(messageId: String, who: String, emoji: String?) {
        if (emoji == null) db.delete("reactions", "msg=? AND who=?", arrayOf(messageId, who))
        else db.insertWithOnConflict("reactions", null, ContentValues().apply { put("msg", messageId); put("who", who); put("emoji", emoji) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun reactions(messageIds: Collection<String>): Map<String, List<Reaction>> {
        if (messageIds.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<Reaction>>()
        messageIds.chunked(500).forEach { part ->
            db.rawQuery("SELECT msg, who, emoji FROM reactions WHERE msg IN (${part.joinToString(",") { "?" }})", part.toTypedArray()).use { c ->
                while (c.moveToNext()) out.getOrPut(c.getString(0)) { mutableListOf() } += Reaction(c.getString(0), c.getString(1), c.getString(2))
            }
        }
        return out
    }

    private fun pruneReactions() { db.execSQL("DELETE FROM reactions WHERE msg NOT IN (SELECT id FROM messages)") }

    /** Edit for everyone: new text, marked edited. */
    fun applyEdit(id: String, text: String) {
        db.update("messages", ContentValues().apply { put("text", text); put("edited", 1) }, "id=? AND IFNULL(deleted, 0)=0", arrayOf(id))
    }

    /** Delete for everyone: text, file and reactions go; the row stays as "This message was deleted". */
    fun applyDelete(id: String) {
        fileIds("id=?", arrayOf(id)).forEach { Files.delete(it) }
        db.update("messages", ContentValues().apply {
            put("text", ""); put("deleted", 1); putNull("file_id"); putNull("file_name"); putNull("file_mime"); putNull("file_size")
            putNull("reply_to"); put("duration", 0)
        }, "id=?", arrayOf(id))
        db.delete("reactions", "msg=?", arrayOf(id))
    }

    /** Raises the status of outgoing messages (sent < delivered < read); never lowers it. */
    fun raiseStatus(ids: Collection<String>, status: Int) {
        ids.chunked(500).forEach { part ->
            val now = System.currentTimeMillis()
            val args: Array<Any?> = arrayOf(status, now, status, now, status, *part.toTypedArray())
            db.execSQL(
                "UPDATE messages SET status=?, delivered_at=IFNULL(delivered_at, ?), read_at=CASE WHEN ?=${StoredMessage.READ} THEN IFNULL(read_at, ?) ELSE read_at END " +
                    "WHERE incoming=0 AND status<? AND status IN (1,4) AND id IN (${part.joinToString(",") { "?" }})", args,
            )
        }
    }

    /** Incoming messages of a chat we have not yet marked as read. */
    fun unread(contactId: String): List<String> =
        db.rawQuery("SELECT id FROM messages WHERE contact=? AND incoming=1 AND IFNULL(flags, 0) & 1 = 0 AND status=?", arrayOf(contactId, StoredMessage.RECEIVED.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** Unread incoming messages per chat (the accent badge in the chat list). */
    fun unreadCounts(): Map<String, Int> =
        db.rawQuery("SELECT contact, COUNT(*) FROM messages WHERE incoming=1 AND status=? AND IFNULL(deleted, 0)=0 GROUP BY contact", arrayOf(StoredMessage.RECEIVED.toString())).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }

    fun markSeen(ids: Collection<String>) {
        ids.chunked(500).forEach { part ->
            val args: Array<Any?> = arrayOf(StoredMessage.READ, *part.toTypedArray())
            db.execSQL("UPDATE messages SET status=? WHERE incoming=1 AND id IN (${part.joinToString(",") { "?" }})", args)
        }
    }

    /** Messages whose text contains [query] (case-insensitive), newest first; local database only. */
    fun search(query: String, contactId: String? = null, limit: Int = 100): List<StoredMessage> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val where = "text LIKE ? ESCAPE '\\' AND IFNULL(deleted, 0)=0" + if (contactId != null) " AND contact=?" else ""
        val args = if (contactId != null) arrayOf(like, contactId) else arrayOf(like)
        return db.rawQuery("SELECT $cols FROM messages WHERE $where ORDER BY at DESC, rowid DESC LIMIT $limit", args).use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }
    }

    /**
     * Deletes disappearing messages (and their files) whose time is up: the chat timer, a started per-message
     * timer or view-once countdown, and items nobody opened for [UNOPENED_MS]. No placeholder is left. Returns how many.
     */
    fun expire(now: Long): Int {
        val n = now.toString()
        val gone = deleteWhere(
            // CAST: a text parameter compared with an arithmetic expression (no column affinity) would always win.
            "(expire > 0 AND at + expire * 1000 < CAST(?1 AS INTEGER)) OR (IFNULL(vanish_at, 0) > 0 AND vanish_at < CAST(?1 AS INTEGER)) OR " +
                "((IFNULL(flags, 0) & 1 != 0 OR IFNULL(timer, 0) > 0) AND IFNULL(vanish_at, 0) = 0 AND at + ${UNOPENED_MS} < CAST(?1 AS INTEGER))",
            arrayOf(n),
        )
        if (gone > 0) pruneReactions()
        return gone
    }

    /** Receiver: the message was shown; its per-message timer starts now (once). Returns the vanish time or 0. */
    fun startTimer(id: String, now: Long): Long = message(id)?.let { m ->
        if (m.timerSec <= 0 || m.vanishAt > 0) return 0
        val at = now + m.timerSec * 1000L
        db.update("messages", ContentValues().apply { put("vanish_at", at) }, "id=? AND IFNULL(vanish_at, 0)=0", arrayOf(id))
        at
    } ?: 0

    /** Sets the time a message vanishes (view-once countdown after closing/opening receipt). Only if not set yet. */
    fun setVanish(id: String, at: Long) {
        db.update("messages", ContentValues().apply { put("vanish_at", at) }, "id=? AND IFNULL(vanish_at, 0)=0", arrayOf(id))
    }

    /** Outgoing messages with a per-message timer whose read receipt just arrived: the countdown starts now. */
    fun startTimers(ids: Collection<String>, now: Long) {
        ids.chunked(500).forEach { part ->
            db.execSQL(
                "UPDATE messages SET vanish_at = ? + timer * 1000 WHERE incoming=0 AND IFNULL(timer, 0) > 0 AND IFNULL(vanish_at, 0)=0 AND id IN (${part.joinToString(",") { "?" }})",
                arrayOf<Any?>(now, *part.toTypedArray()),
            )
        }
    }

    /** View once opened: the file is gone for good (row stays for the last minute, without content). */
    fun markOpened(id: String, at: Long) {
        fileIds("id=?", arrayOf(id)).forEach { Files.delete(it) }
        db.update("messages", ContentValues().apply { put("opened_at", at); putNull("file_id") }, "id=?", arrayOf(id))
    }

    /** View-once items the viewer was opened for but whose countdown never started (the app died while viewing). */
    fun openedNotCounting(): List<StoredMessage> =
        db.rawQuery("SELECT $cols FROM messages WHERE IFNULL(opened_at, 0) > 0 AND IFNULL(vanish_at, 0) = 0", null).use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }

    /** Wipes the database file and its key. */
    fun destroy(context: Context) {
        Files.deleteAll()
        db.close()
        SQLiteDatabase.deleteDatabase(file)
        KeyWrap.forget(context)
    }

    companion object {
        const val ME = "me"
        const val UNOPENED_MS = 14L * 86400 * 1000
        /** Removes the database and its key without opening it (used when the key cannot be unwrapped). */
        fun wipe(context: Context) {
            java.io.File(context.filesDir, "att").deleteRecursively()
            SQLiteDatabase.deleteDatabase(context.getDatabasePath("swell.db"))
            KeyWrap.forget(context)
        }

        fun open(context: Context): Db {
            System.loadLibrary("sqlcipher")
            val file = context.getDatabasePath("swell.db").apply { parentFile?.mkdirs() }
            // Raw 256-bit key ("x'…'") so SQLCipher skips its password KDF.
            val pass = "x'${Codec.hex(KeyWrap.dbKey(context))}'".toByteArray()
            val db = SQLiteDatabase.openDatabase(file.path, pass, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null, null)
            // Deleted rows (disappearing messages, deleted chats) are overwritten, not left in free pages (audit M-05).
            db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
            db.rawQuery("PRAGMA cipher_memory_security = ON", null).use { it.moveToFirst() }
            db.execSQL("CREATE TABLE IF NOT EXISTS kv (ns TEXT NOT NULL, k TEXT NOT NULL, v BLOB NOT NULL, PRIMARY KEY (ns, k))")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, contact TEXT NOT NULL, text TEXT NOT NULL, " +
                    "incoming INTEGER NOT NULL, at INTEGER NOT NULL, expire INTEGER NOT NULL, status INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS messages_contact ON messages (contact, at)")
            // v2: attachments. ADD COLUMN fails harmlessly when the column exists.
            // v3 (1.1): reply, edited, deleted, voice length.
            // v4 (1.2): flags (view once), timer (per message), vanish_at and opened_at (countdowns).
            listOf("file_id TEXT", "file_name TEXT", "file_mime TEXT", "file_size INTEGER",
                "reply_to TEXT", "edited INTEGER", "deleted INTEGER", "duration INTEGER",
                "flags INTEGER", "timer INTEGER", "vanish_at INTEGER", "opened_at INTEGER", "delivered_at INTEGER", "read_at INTEGER", "preview BLOB").forEach { col ->
                runCatching { db.execSQL("ALTER TABLE messages ADD COLUMN $col") }
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS reactions (msg TEXT NOT NULL, who TEXT NOT NULL, emoji TEXT NOT NULL, PRIMARY KEY (msg, who))")
            return Db(file, db)
        }
    }
}
