package com.jijilabsdigital.swell.core

import net.freehaven.tor.control.TorControlConnection
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import java.io.IOException
import java.util.Base64

/**
 * Tor onion services as the node needs them (audit H-02):
 * our own services are published with client authorization, so only contacts and holders of an
 * open invite can even find them; keys for reaching other people's services are registered here.
 */
interface OnionHost {
    /** Publishes a new onion service that only holders of [clients] (x25519 public keys) can reach. Returns (address, private key). */
    fun create(clients: List<ByteArray>): Pair<String, String>
    /** Publishes [onion] again with exactly these clients. */
    fun publish(onion: String, key: String, clients: List<ByteArray>)
    fun unpublish(onion: String)
    /** Client side: the x25519 private key for reaching [onion]; null forgets it. */
    fun clientAuth(onion: String, privateKey: ByteArray?)
}

/** x25519 key pairs for onion client authorization. */
object AuthKey {
    fun generate(): ByteArray = ECKeyPair.generate().privateKey.serialize()
    fun publicOf(privateKey: ByteArray): ByteArray = ECPrivateKey(privateKey).getPublicKey().publicKeyBytes
}

/** RFC 4648 base32 without padding (Tor's encoding for client-auth public keys). */
object Base32 {
    private const val A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    fun encode(b: ByteArray): String {
        val sb = StringBuilder()
        var buf = 0; var bits = 0
        for (x in b) {
            buf = (buf shl 8) or (x.toInt() and 0xFF); bits += 8
            while (bits >= 5) { sb.append(A[(buf ushr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(A[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }
}

/** [OnionHost] over a Tor control connection (Android TorService and the Mac test peer). */
class TorControlHost(
    private val ctl: TorControlConnection,
    private val localPort: Int,
    private val virtualPort: Int = Node.PORT,
    private val log: (String) -> Unit = {},
) : OnionHost {
    private val send = TorControlConnection::class.java
        .getDeclaredMethod("sendAndWaitForResponse", String::class.java, String::class.java)
        .apply { isAccessible = true }

    private fun addOnion(key: String, clients: List<ByteArray>): Map<String, String> {
        // With nobody authorized, a random key keeps the service closed instead of open to all.
        val auth = clients.ifEmpty { listOf(AuthKey.publicOf(AuthKey.generate())) }
        val cmd = StringBuilder("ADD_ONION ").append(key)
            .append(" Flags=V3Auth MaxStreams=64")
            .append(" Port=").append(virtualPort).append(",127.0.0.1:").append(localPort)
        auth.forEach { cmd.append(" ClientAuthV3=").append(Base32.encode(it)) }
        cmd.append("\r\n")
        val lines = synchronized(ctl) { send.invoke(ctl, cmd.toString(), null) as List<*> }
        val out = HashMap<String, String>()
        for (l in lines) {
            val msg = l!!.javaClass.getDeclaredField("msg").apply { isAccessible = true }.get(l) as String
            val i = msg.indexOf('=')
            if (i > 0) out[msg.substring(0, i)] = msg.substring(i + 1)
        }
        return out
    }

    override fun create(clients: List<ByteArray>): Pair<String, String> {
        val r = addOnion("NEW:ED25519-V3", clients)
        val onion = r["ServiceID"] ?: throw IOException("tor: no ServiceID")
        val key = r["PrivateKey"] ?: throw IOException("tor: no PrivateKey")
        return onion to key
    }

    override fun publish(onion: String, key: String, clients: List<ByteArray>) {
        runCatching { synchronized(ctl) { ctl.delOnion(onion) } } // not published yet is fine
        addOnion(key, clients)
        log("published ${onion.take(8)}… for ${clients.size} keys")
    }

    override fun unpublish(onion: String) { runCatching { synchronized(ctl) { ctl.delOnion(onion) } } }

    override fun clientAuth(onion: String, privateKey: ByteArray?) {
        synchronized(ctl) {
            if (privateKey == null) runCatching { ctl.onionClientAuthRemove(onion) }
            else ctl.onionClientAuthAdd(onion, Base64.getEncoder().encodeToString(privateKey)) // jtorctl adds "x25519:"
        }
    }
}
