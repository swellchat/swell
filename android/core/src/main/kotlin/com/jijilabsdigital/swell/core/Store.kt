package com.jijilabsdigital.swell.core

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Namespaced key-value storage. Android backs it with an SQLCipher table; tests and the Mac peer
 * use [MemoryKv]. Values are opaque bytes; everything secret lives only here.
 */
interface Kv {
    fun get(ns: String, key: String): ByteArray?
    fun put(ns: String, key: String, value: ByteArray)
    fun delete(ns: String, key: String)
    fun list(ns: String): List<Pair<String, ByteArray>>
    fun deleteAll()
    /** Runs [block] atomically (all writes or none). */
    fun <T> tx(block: () -> T): T = synchronized(this) { block() }
}

class MemoryKv : Kv {
    private val m = ConcurrentHashMap<String, ByteArray>()
    override fun get(ns: String, key: String) = m["$ns\u0000$key"]
    override fun put(ns: String, key: String, value: ByteArray) { m["$ns\u0000$key"] = value }
    override fun delete(ns: String, key: String) { m.remove("$ns\u0000$key") }
    override fun list(ns: String) =
        m.entries.filter { it.key.startsWith("$ns\u0000") }.map { it.key.substringAfter('\u0000') to it.value }
    override fun deleteAll() = m.clear()
}

/** BIP-39 English, 12 words = 128-bit entropy + 4-bit checksum. */
object RecoveryPhrase {
    private val words: List<String> by lazy {
        val list = RecoveryPhrase::class.java.getResourceAsStream("/bip39-english.txt")!!
            .bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        check(list.size == 2048) { "wordlist" }
        list
    }
    private val index by lazy { words.withIndex().associate { it.value to it.index } }

    fun fromEntropy(e: ByteArray): List<String> {
        require(e.size == 16)
        val cs = (sha256(e)[0].toInt() and 0xFF) ushr 4
        val bi = BigInteger(1, e).shiftLeft(4).or(BigInteger.valueOf(cs.toLong()))
        return (0 until 12).map { i -> words[bi.shiftRight(11 * (11 - i)).and(BigInteger.valueOf(0x7FF)).toInt()] }
    }

    /** Throws [IOException] with a plain message for unknown words or a bad checksum. */
    fun toEntropy(phrase: List<String>): ByteArray {
        val ws = phrase.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (ws.size != 12) throw IOException("The recovery phrase has 12 words.")
        var bi = BigInteger.ZERO
        for (w in ws) bi = bi.shiftLeft(11).or(BigInteger.valueOf((index[w] ?: throw IOException("Unknown word: $w")).toLong()))
        val cs = bi.and(BigInteger.valueOf(0xF)).toInt()
        val raw = bi.shiftRight(4).toByteArray()
        val e = ByteArray(16)
        val src = if (raw.size > 16) raw.copyOfRange(raw.size - 16, raw.size) else raw
        System.arraycopy(src, 0, e, 16 - src.size, src.size)
        if (cs != ((sha256(e)[0].toInt() and 0xFF) ushr 4)) throw IOException("The recovery phrase is not correct.")
        return e
    }

    fun parse(text: String): List<String> = text.split(Regex("[\\s,]+")).filter { it.isNotBlank() }

    fun isWord(w: String) = index.containsKey(w.trim().lowercase())
}

fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

object Hkdf {
    fun sha256(ikm: ByteArray, info: ByteArray, len: Int, salt: ByteArray = ByteArray(32)): ByteArray {
        val prk = hmac(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < len) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t); i++
        }
        return out.toByteArray().copyOf(len)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}

/** The user's identity: derived only from the 12-word phrase, so the phrase restores it anywhere. */
class Account(val entropy: ByteArray, val registrationId: Int) {
    val identity: IdentityKeyPair by lazy {
        val priv = ECPrivateKey(Hkdf.sha256(entropy, "swell identity v1".toByteArray(), 32))
        IdentityKeyPair(IdentityKey(priv.getPublicKey()), priv)
    }
    val phrase: List<String> get() = RecoveryPhrase.fromEntropy(entropy)

    fun save(kv: Kv) = kv.put(NS, "account", Out().u8(1).raw(entropy).i32(registrationId).done())

    companion object {
        private const val NS = "acct"
        fun create() = Account(Codec.random(16), Codec.randomId() and 0x3FFF)
        fun restore(phrase: List<String>) = Account(RecoveryPhrase.toEntropy(phrase), Codec.randomId() and 0x3FFF)
        fun load(kv: Kv): Account? = kv.get(NS, "account")?.let {
            val r = In(it); r.u8(); val a = Account(r.raw(16), r.i32()); r.end(); a
        }
    }
}

/**
 * libsignal store over [Kv]. A changed identity key for an existing channel is never trusted:
 * messages claiming a different key on a known channel are rejected.
 */
class KvSignalStore(private val kv: Kv, private val account: Account) : SignalProtocolStore {
    private fun a(address: SignalProtocolAddress) = "${address.name}.${address.deviceId}"

    override fun getIdentityKeyPair(): IdentityKeyPair = account.identity
    override fun getLocalRegistrationId(): Int = account.registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val old = kv.get("idk", a(address))
        if (old != null && old.contentEquals(identityKey.serialize())) return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        if (old != null) return IdentityKeyStore.IdentityChange.REPLACED_EXISTING // never overwritten silently
        kv.put("idk", a(address), identityKey.serialize())
        return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val old = kv.get("idk", a(address)) ?: return true
        return old.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = kv.get("idk", a(address))?.let { IdentityKey(it) }

    fun replaceIdentity(address: SignalProtocolAddress, identityKey: IdentityKey) = kv.put("idk", a(address), identityKey.serialize())

    override fun loadPreKey(preKeyId: Int) =
        kv.get("pk", "$preKeyId")?.let { PreKeyRecord(it) } ?: throw InvalidKeyIdException("no pre-key $preKeyId")
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = kv.put("pk", "$preKeyId", record.serialize())
    override fun containsPreKey(preKeyId: Int) = kv.get("pk", "$preKeyId") != null
    /** Keys of a reusable (demo contact) invite are kept after use; all others are one-time. */
    override fun removePreKey(preKeyId: Int) { if (kv.get("keep", "pk$preKeyId") == null) kv.delete("pk", "$preKeyId") }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? = kv.get("sess", a(address))?.let { SessionRecord(it) }
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { loadSession(it) ?: throw NoSessionException(it, "no session for $it") }
    override fun getSubDeviceSessions(name: String): List<Int> =
        kv.list("sess").map { it.first }.filter { it.substringBeforeLast('.') == name }
            .map { it.substringAfterLast('.').toInt() }.filter { it != 1 }
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = kv.put("sess", a(address), record.serialize())
    override fun containsSession(address: SignalProtocolAddress) = loadSession(address)?.hasSenderChain() == true
    override fun deleteSession(address: SignalProtocolAddress) = kv.delete("sess", a(address))
    override fun deleteAllSessions(name: String) =
        kv.list("sess").map { it.first }.filter { it.substringBeforeLast('.') == name }.forEach { kv.delete("sess", it) }

    override fun loadSignedPreKey(signedPreKeyId: Int) =
        kv.get("spk", "$signedPreKeyId")?.let { SignedPreKeyRecord(it) } ?: throw InvalidKeyIdException("no signed pre-key")
    override fun loadSignedPreKeys() = kv.list("spk").map { SignedPreKeyRecord(it.second) }
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = kv.put("spk", "$signedPreKeyId", record.serialize())
    override fun containsSignedPreKey(signedPreKeyId: Int) = kv.get("spk", "$signedPreKeyId") != null
    override fun removeSignedPreKey(signedPreKeyId: Int) = kv.delete("spk", "$signedPreKeyId")

    override fun loadKyberPreKey(kyberPreKeyId: Int) =
        kv.get("kpk", "$kyberPreKeyId")?.let { KyberPreKeyRecord(it) } ?: throw InvalidKeyIdException("no kyber pre-key")
    override fun loadKyberPreKeys() = kv.list("kpk").map { KyberPreKeyRecord(it.second) }
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = kv.put("kpk", "$kyberPreKeyId", record.serialize())
    override fun containsKyberPreKey(kyberPreKeyId: Int) = kv.get("kpk", "$kyberPreKeyId") != null
    /** Swell uses one-time Kyber pre-keys (one per invite), so a used key is deleted. */
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        if (kv.get("keep", "kpk$kyberPreKeyId") == null) kv.delete("kpk", "$kyberPreKeyId")
    }

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) =
        kv.put("sk", "${a(sender)}|$distributionId", record.serialize())
    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        kv.get("sk", "${a(sender)}|$distributionId")?.let { SenderKeyRecord(it) }
}
