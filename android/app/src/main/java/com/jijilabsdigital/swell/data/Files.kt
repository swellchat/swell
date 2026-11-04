package com.jijilabsdigital.swell.data

import android.content.Context
import com.jijilabsdigital.swell.core.Codec
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Photos and files on the phone: one AES-256-GCM file per attachment in app-private storage.
 * The key lives in the encrypted database, so files are unreadable without it.
 */
object Files {
    private lateinit var dir: File
    private lateinit var key: ByteArray
    private lateinit var db: Db

    fun init(context: Context, db: Db) {
        this.db = db
        dir = File(context.filesDir, "att").apply { mkdirs() }
        key = db.get("app", "fileKey") ?: Codec.random(32).also { db.put("app", "fileKey", it) }
    }

    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-zA-Z_-]{1,64}")))
        return File(dir, id)
    }

    /** A view-once file has its own random key, kept only until the file is deleted (deleting the key alone already destroys it). */
    private fun keyFor(id: String): ByteArray = db.get(ONCE_NS, id) ?: key

    /** Saves a view-once file under its own key. */
    fun saveOnce(id: String, data: ByteArray) {
        db.put(ONCE_NS, id, Codec.random(32))
        save(id, data)
    }

    fun save(id: String, data: ByteArray) {
        val nonce = Codec.random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyFor(id), "AES"), GCMParameterSpec(128, nonce)) }
        val tmp = File(dir, ".$id.tmp")
        tmp.writeBytes(nonce + c.doFinal(data))
        tmp.renameTo(file(id))
    }

    fun load(id: String): ByteArray? {
        val f = file(id)
        if (!f.exists()) return null
        val b = f.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(keyFor(id), "AES"), GCMParameterSpec(128, b, 0, 12)) }
        return runCatching { c.doFinal(b, 12, b.size - 12) }.getOrNull()
    }

    fun delete(id: String) { runCatching { file(id).delete() }; runCatching { db.delete(ONCE_NS, id) } }

    private const val ONCE_NS = "vokey"

    fun deleteAll() { if (::dir.isInitialized) dir.deleteRecursively() }
}
