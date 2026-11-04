package com.jijilabsdigital.swell.core

import java.io.File
import kotlin.test.Test

/**
 * Writes cross-platform golden vectors to build/golden-vectors.json (or the path in SWELL_GOLDEN_OUT).
 * The iOS package (ios/SwellCore) checks its output against ios/SwellCore/Tests/SwellCoreTests/Resources/golden.json.
 * Everything is deterministic: fixed inputs only.
 */
class GoldenVectors {
    private fun h(b: ByteArray) = Codec.hex(b)
    private fun seq(n: Int, start: Int) = ByteArray(n) { (start + it).toByte() }

    @Test
    fun write() {
        val sb = StringBuilder("{\n")
        fun kv(k: String, v: String, last: Boolean = false) { sb.append("  \"$k\": \"$v\"${if (last) "" else ","}\n") }

        // Out encoding
        val out = Out().u8(2).i32(-2).i64(0x0102030405060708L).bytes(seq(5, 1)).str("héllo").raw(seq(3, 9)).done()
        kv("out_mixed", h(out))

        // Padding
        for (n in listOf(0, 1, 1020, 1021, 4092, 4093, 16380, 65532, 65533, 262140)) {
            val data = ByteArray(n) { (it % 251).toByte() }
            val p = Padding.pad(data)
            kv("pad_${n}_size", p.size.toString())
            kv("pad_${n}_head", h(p.copyOf(minOf(p.size, 16))))
        }

        // Invite
        val onion = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx"
        val inv = Invite(onion, seq(32, 0), seq(33, 100), seq(32, 200)).encode()
        kv("invite", inv)

        // Queue id and PoW
        val secret = seq(32, 7)
        val q = MailboxProtocol.queueId(secret)
        kv("queue_secret", h(secret))
        kv("queue_id", h(q))
        val nonce = MailboxProtocol.solvePow(q, 12)
        kv("pow12_nonce", nonce.toString())
        kv("create_frame_pow12", h(Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.CREATE).raw(secret).i64(nonce).done()))
        val minute = 29_000_000L
        val cn = MailboxProtocol.solveCredPow(minute, 10)
        kv("cred_minute", minute.toString())
        kv("cred10_nonce", cn.toString())
        kv("creds_frame_cred10", h(Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.CREDS).i64(minute).i64(cn).done()))

        // Recovery phrase and identity
        val entropy = seq(16, 1)
        kv("entropy", h(entropy))
        kv("phrase", RecoveryPhrase.fromEntropy(entropy).joinToString(" "))
        val priv = Hkdf.sha256(entropy, "swell identity v1".toByteArray(), 32)
        kv("identity_priv", h(priv))
        val pub = org.signal.libsignal.protocol.ecc.ECPrivateKey(priv).getPublicKey().serialize()
        kv("identity_pub", h(pub))
        val entropy0 = ByteArray(16)
        kv("phrase_zero", RecoveryPhrase.fromEntropy(entropy0).joinToString(" "))
        kv("identity_pub_zero", h(org.signal.libsignal.protocol.ecc.ECPrivateKey(Hkdf.sha256(entropy0, "swell identity v1".toByteArray(), 32)).getPublicKey().serialize()), last = true)
        sb.append("}\n")
        val path = System.getenv("SWELL_GOLDEN_OUT") ?: "build/golden-vectors.json"
        File(path).also { it.parentFile?.mkdirs() }.writeText(sb.toString())
    }
}
