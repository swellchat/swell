package com.jijilabsdigital.swell.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64

/** Length-prefixed frames on a stream. One request frame and one response frame per connection. */
object Wire {
    const val MAX_FRAME = 320 * 1024

    fun write(out: OutputStream, payload: ByteArray) {
        require(payload.size in 1..MAX_FRAME) { "frame size" }
        val d = DataOutputStream(out)
        d.writeInt(payload.size)
        d.write(payload)
        d.flush()
    }

    fun read(input: InputStream): ByteArray {
        val d = DataInputStream(input)
        val n = d.readInt()
        if (n !in 1..MAX_FRAME) throw IOException("bad frame size")
        return ByteArray(n).also { d.readFully(it) }
    }

    /**
     * Reads one frame with an overall deadline (not just per read), so a peer that trickles
     * bytes cannot hold the connection open.
     */
    fun read(s: Socket, totalMs: Long): ByteArray {
        val deadline = System.currentTimeMillis() + totalMs
        val input = s.getInputStream()
        fun fill(buf: ByteArray) {
            var off = 0
            while (off < buf.size) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw java.net.SocketTimeoutException("frame deadline")
                s.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val r = input.read(buf, off, buf.size - off)
                if (r < 0) throw java.io.EOFException()
                off += r
            }
        }
        val len = ByteArray(4).also { fill(it) }
        val n = ByteBuffer.wrap(len).int
        if (n !in 1..MAX_FRAME) throw IOException("bad frame size")
        return ByteArray(n).also { fill(it) }
    }
}

/** Small binary writer. Byte arrays and strings carry a 4-byte length. */
class Out {
    private val bos = ByteArrayOutputStream()
    private val d = DataOutputStream(bos)
    fun u8(v: Int) = apply { d.writeByte(v) }
    fun i32(v: Int) = apply { d.writeInt(v) }
    fun i64(v: Long) = apply { d.writeLong(v) }
    fun raw(b: ByteArray) = apply { d.write(b) }
    fun bytes(b: ByteArray) = apply { d.writeInt(b.size); d.write(b) }
    fun str(s: String) = bytes(s.toByteArray(Charsets.UTF_8))
    fun done(): ByteArray = bos.toByteArray()
}

/** Matching reader. Every read is bounded; trailing bytes are an error ([end]). */
class In(b: ByteArray) {
    private val d = DataInputStream(ByteArrayInputStream(b))
    fun u8(): Int = d.readUnsignedByte()
    fun i32(): Int = d.readInt()
    fun i64(): Long = d.readLong()
    fun raw(n: Int): ByteArray = ByteArray(n).also { d.readFully(it) }
    fun bytes(max: Int = Wire.MAX_FRAME): ByteArray {
        val n = d.readInt()
        if (n !in 0..max) throw IOException("field size")
        return raw(n)
    }
    fun str(max: Int = 64 * 1024): String = String(bytes(max), Charsets.UTF_8)
    fun end() { if (d.available() != 0) throw IOException("trailing bytes") }
    /** True while unread bytes remain (optional trailing fields added by later versions). */
    fun more(): Boolean = d.available() > 0
}

/** Plaintext is padded to a size bucket before encryption so length leaks less. */
object Padding {
    private val buckets = intArrayOf(1024, 4096, 16384, 65536, 262144)

    fun pad(b: ByteArray): ByteArray {
        val need = b.size + 4
        val size = buckets.firstOrNull { it >= need } ?: throw IllegalArgumentException("message too large")
        return ByteBuffer.allocate(size).putInt(b.size).put(b).array()
    }

    fun unpad(p: ByteArray): ByteArray {
        if (p.size < 4) throw IOException("padding")
        val n = ByteBuffer.wrap(p).int
        if (n < 0 || n > p.size - 4) throw IOException("padding")
        return p.copyOfRange(4, 4 + n)
    }
}

/** Minimal SOCKS5 client with remote name resolution (needed for .onion). */
object Socks5 {
    /**
     * [isolation]: when set, sent as the SOCKS username. Tor (IsolateSOCKSAuth, on by default) then
     * uses a separate circuit, so requests with different values cannot be linked by the path.
     */
    fun connect(socksPort: Int, host: String, port: Int, timeoutMs: Int = 60_000, isolation: String? = null): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress("127.0.0.1", socksPort), 10_000)
            s.soTimeout = timeoutMs
            val o = s.getOutputStream()
            val i = DataInputStream(s.getInputStream())
            if (isolation == null) {
                o.write(byteArrayOf(5, 1, 0)); o.flush()
                val g = ByteArray(2).also { i.readFully(it) }
                if (g[0].toInt() != 5 || g[1].toInt() != 0) throw IOException("socks auth refused")
            } else {
                o.write(byteArrayOf(5, 1, 2)); o.flush()
                val g = ByteArray(2).also { i.readFully(it) }
                if (g[0].toInt() != 5 || g[1].toInt() != 2) throw IOException("socks auth refused")
                val u = isolation.toByteArray(Charsets.US_ASCII).copyOf(minOf(isolation.length, 255))
                o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(1, 'x'.code.toByte())); o.flush()
                val a = ByteArray(2).also { i.readFully(it) }
                if (a[1].toInt() != 0) throw IOException("socks auth refused")
            }
            val h = host.toByteArray(Charsets.US_ASCII)
            require(h.size in 1..255)
            o.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf((port shr 8).toByte(), port.toByte()))
            o.flush()
            val r = ByteArray(4).also { i.readFully(it) }
            if (r[1].toInt() != 0) throw IOException("socks connect failed: code ${r[1]}")
            when (r[3].toInt()) {
                1 -> i.readFully(ByteArray(6))
                4 -> i.readFully(ByteArray(18))
                3 -> i.readFully(ByteArray(i.readUnsignedByte() + 2))
                else -> throw IOException("socks reply")
            }
            return s
        } catch (e: Exception) {
            s.close()
            throw e
        }
    }
}

object Codec {
    private val rnd = SecureRandom()
    fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    fun randomId(): Int = rnd.nextInt(0x7FFF_FFF0) + 1
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
}
