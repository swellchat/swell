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

    fun init(context: Context, db: Db) {
        dir = File(context.filesDir, "att").apply { mkdirs() }
        key = db.get("app", "fileKey") ?: Codec.random(32).also { db.put("app", "fileKey", it) }
    }

    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-zA-Z_-]{1,64}")))
        return File(dir, id)
    }

    fun save(id: String, data: ByteArray) {
        val nonce = Codec.random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
        val tmp = File(dir, ".$id.tmp")
        tmp.writeBytes(nonce + c.doFinal(data))
        tmp.renameTo(file(id))
    }

    fun load(id: String): ByteArray? {
        val f = file(id)
        if (!f.exists()) return null
        val b = f.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b, 0, 12)) }
        return runCatching { c.doFinal(b, 12, b.size - 12) }.getOrNull()
    }

    fun delete(id: String) { runCatching { file(id).delete() } }

    fun deleteAll() { if (::dir.isInitialized) dir.deleteRecursively() }
}
