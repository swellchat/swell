package com.jijilabsdigital.swell.core

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Cross-platform crypto interop (docs/IOS-PLAN.md section 5). No network.
 *
 * `interop/fixtures/<direction>.txt` is a transcript of 5 steps. The sender of a step stores the
 * ciphertext, the plaintext it expects the receiver to see, and a snapshot of its own key store
 * after sending. A receiver replays: it decrypts the golden ciphertext and compares the plaintext.
 * A sender replays by loading its snapshot. The Swift tests (ios/SwellSignal) read the same files.
 *
 * Normal runs only verify. `SWELL_INTEROP_GEN=1` (see tools/interop-gen.sh) lets each side append
 * the next step when it is its turn.
 */
class InteropTest {
    private val gen = System.getenv("SWELL_INTEROP_GEN") == "1"
    private val dir = File(System.getenv("SWELL_INTEROP_DIR") ?: "../../interop/fixtures")

    private class TestKv : Kv {
        val m = ConcurrentHashMap<String, ByteArray>()
        override fun get(ns: String, key: String) = m["$ns\u0000$key"]
        override fun put(ns: String, key: String, value: ByteArray) { m["$ns\u0000$key"] = value }
        override fun delete(ns: String, key: String) { m.remove("$ns\u0000$key") }
        override fun list(ns: String) = m.entries.filter { it.key.startsWith("$ns\u0000") }.map { it.key.substringAfter('\u0000') to it.value }
        override fun deleteAll() = m.clear()
        fun dump(): ByteArray {
            val keys = m.keys.sorted()
            val o = Out().i32(keys.size)
            keys.forEach { k -> o.str(k.substringBefore('\u0000')).str(k.substringAfter('\u0000')).bytes(m[k]!!) }
            return o.done()
        }
        companion object {
            fun load(b: ByteArray): TestKv {
                val r = In(b); val kv = TestKv()
                repeat(r.i32()) { kv.put(r.str(), r.str(), r.bytes()) }
                r.end(); return kv
            }
        }
    }

    private enum class Kind { BUNDLE, HELLO, STATE, TEXT }
    private val kinds = listOf(Kind.BUNDLE, Kind.HELLO, Kind.STATE, Kind.TEXT, Kind.TEXT)

    private fun account(who: String) = if (who == "K") Account(ByteArray(16) { 0x4B }, 1234) else Account(ByteArray(16) { 0x53 }, 5678)
    private val mailbox = "m".repeat(56)

    // ---- plaintexts (identical constants in the Swift test)
    private fun msg(o: Out, text: String) = o.raw(ByteArray(16) { 0x11 }).i64(1_700_000_000_000L).i32(0).str(text)
    private fun plain(kind: Kind, sender: String, step: Int): ByteArray {
        val name = if (sender == "K") "Kotlin Peer" else "Swift Peer"
        val onion = if (sender == "K") "k".repeat(56) else "" // an iPhone is mailbox-only
        return when (kind) {
            Kind.HELLO -> Out().u8(1).str(onion).str(name).str(mailbox).bytes(ByteArray(32) { 0x22 }).bytes(ByteArray(32) { 0x33 })
                .also { msg(it, "hello from $name") }.done()
            Kind.STATE -> Out().u8(5).str(name).str(mailbox).bytes(ByteArray(32) { 0x44 }).str(onion).bytes(ByteArray(32) { 0x55 }).done()
            Kind.TEXT -> Out().u8(2).also { msg(it, "step $step from $name é世") }.done()
            Kind.BUNDLE -> error("no plaintext")
        }
    }

    private fun parse(path: File): MutableMap<String, String> =
        if (!path.exists()) mutableMapOf() else path.readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }.toMutableMap()

    private fun save(path: File, p: Map<String, String>) = path.writeText(p.toSortedMap().entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })

    /** Safety number of the two test identities; the Swift test must produce the same digits. */
    @Test fun safetyNumber() {
        val k = account("K").identity.publicKey; val s = account("S").identity.publicKey
        val f = org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator(5200).createFor(2, k.serialize(), k, s.serialize(), s)
        val back = org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator(5200).createFor(2, s.serialize(), s, k.serialize(), k)
        assertEquals(f.displayableFingerprint.displayText, back.displayableFingerprint.displayText, "symmetric")
        val file = File(dir, "safety.txt")
        if (gen) file.writeText(f.displayableFingerprint.displayText + "\n")
        assertEquals(file.readText().trim(), f.displayableFingerprint.displayText)
    }

    @Test fun kotlinInvites() = run("kotlin_invites", "K", "S")
    @Test fun swiftInvites() = run("swift_invites", "S", "K")

    private fun run(name: String, inviter: String, redeemer: String) {
        val file = File(dir, "$name.txt")
        val p = parse(file)
        val me = "K"
        val channel = ByteArray(16) { if (inviter == "K") 0xA1.toByte() else 0xB2.toByte() }
        val address = SignalProtocolAddress(Codec.hex(channel), 1)
        var kv = TestKv()
        var store = KvSignalStore(kv, account(me))
        fun adopt(snapshot: String) { kv = TestKv.load(Codec.unhex(snapshot)); store = KvSignalStore(kv, account(me)) }
        var bundle: ByteArray? = null

        for (i in 1..5) {
            val kind = kinds[i - 1]
            val sender = if (i % 2 == 1) inviter else redeemer
            val has = p.containsKey("step.$i.ct")
            if (sender == me) {
                if (has) { adopt(p["step.$i.kv"]!!); continue }
                if (!gen) fail("$name step $i missing; run tools/interop-gen.sh")
                val ct: ByteArray; val type: Int; val pl: ByteArray
                when (kind) {
                    Kind.BUNDLE -> { ct = makeBundle(store, kv); type = 0; pl = ByteArray(0) }
                    Kind.HELLO -> {
                        SessionBuilder(store, address).process(decodeBundle(bundle!!))
                        pl = plain(kind, me, i)
                        val c = SessionCipher(store, address).encrypt(Padding.pad(pl)); ct = c.serialize(); type = c.type
                    }
                    else -> {
                        pl = plain(kind, me, i)
                        val c = SessionCipher(store, address).encrypt(Padding.pad(pl)); ct = c.serialize(); type = c.type
                    }
                }
                p["step.$i.sender"] = me; p["step.$i.kind"] = kind.name; p["step.$i.type"] = "$type"
                p["step.$i.ct"] = Codec.hex(ct); p["step.$i.plain"] = Codec.hex(pl); p["step.$i.kv"] = Codec.hex(kv.dump())
                save(file, p)
                return // the other side's turn
            }
            if (!has) { if (gen) return else fail("$name step $i missing; run tools/interop-gen.sh") }
            val ct = Codec.unhex(p["step.$i.ct"]!!)
            if (kind == Kind.BUNDLE) {
                bundle = ct
                val b = decodeBundle(ct)
                assertContentEquals(account(sender).identity.publicKey.serialize(), b.identityKey.serialize(), "$name bundle identity derives from entropy")
                continue
            }
            val type = p["step.$i.type"]!!.toInt()
            assertEquals(if (kind == Kind.HELLO) CiphertextMessage.PREKEY_TYPE else CiphertextMessage.WHISPER_TYPE, type, "$name step $i type")
            val cipher = SessionCipher(store, address)
            val raw = if (type == CiphertextMessage.PREKEY_TYPE) cipher.decrypt(PreKeySignalMessage(ct)) else cipher.decrypt(SignalMessage(ct))
            assertContentEquals(plain(kind, sender, i), Padding.unpad(raw), "$name step $i plaintext")
            assertContentEquals(Codec.unhex(p["step.$i.plain"]!!), Padding.unpad(raw), "$name step $i golden plaintext")
        }
    }

    /** Same layout and key material as Node.createInvite (PROTOCOL.md section 4). */
    private fun makeBundle(store: KvSignalStore, kv: Kv): ByteArray {
        val idKey = store.identityKeyPair
        val spkPair = ECKeyPair.generate()
        val spk = SignedPreKeyRecord(Codec.randomId(), 1_700_000_000_000L, spkPair, idKey.privateKey.calculateSignature(spkPair.publicKey.serialize()))
        store.storeSignedPreKey(spk.id, spk)
        val pre = PreKeyRecord(Codec.randomId(), ECKeyPair.generate())
        store.storePreKey(pre.id, pre)
        val kem = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kemSig = idKey.privateKey.calculateSignature(kem.publicKey.serialize())
        val kyber = KyberPreKeyRecord(Codec.randomId(), 1_700_000_000_000L, kem, kemSig)
        store.storeKyberPreKey(kyber.id, kyber)
        return Out().i32(store.localRegistrationId)
            .i32(pre.id).bytes(pre.keyPair.publicKey.serialize())
            .i32(spk.id).bytes(spk.keyPair.publicKey.serialize()).bytes(spk.signature)
            .bytes(idKey.publicKey.serialize())
            .i32(kyber.id).bytes(kem.publicKey.serialize()).bytes(kemSig).done()
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
}
