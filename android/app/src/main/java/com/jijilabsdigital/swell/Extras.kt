package com.jijilabsdigital.swell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jijilabsdigital.swell.core.Codec
import com.jijilabsdigital.swell.core.LinkPreview
import com.jijilabsdigital.swell.core.Socks5
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.util.ArrayDeque
import javax.net.ssl.SSLSocketFactory

/**
 * Debug log for "Export debug log": a short ring of event lines. It never holds message text, names, contact
 * identifiers, onion addresses, invite codes or keys: lines are filtered and every long token is removed.
 */
object DebugLog {
    private val lines = ArrayDeque<String>()
    private val SKIP = Regex("^(ONION|INVITE|RECV|SENT|MAILBOX|CONTACT|PHOTO|AVATAR)")
    private val TOKEN = Regex("[A-Za-z0-9_+/=-]{20,}|swell:\\S+|\\b[0-9a-f]{8,}\\b|[a-z2-7]{16,}\\.onion|\\b[a-z2-7]{8}…")

    fun add(line: String) {
        if (SKIP.containsMatchIn(line)) return
        val clean = TOKEN.replace(line, "…").take(160)
        synchronized(lines) {
            if (lines.size >= 300) lines.removeFirst()
            lines.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + clean)
        }
    }

    /** The text people send to support: app and phone versions, counts, state, and the filtered lines. */
    fun report(): String {
        val sb = StringBuilder()
        sb.append("Swell ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MODEL}\n")
        sb.append("State: ${Swell.net.value::class.simpleName}\n")
        sb.append("Chats: ${Swell.contacts().size}\n")
        sb.append("This file holds no messages, names, contacts, keys or addresses.\n\n")
        synchronized(lines) { lines.forEach { sb.append(it).append('\n') } }
        return sb.toString()
    }
}

/** The path of our connection, for Settings > Network: the only screen that mentions it. */
object NetworkPath {
    class Hop(val label: String, val country: String? = null)

    /** You, entry (with its country), relays, and the mailbox; null when no circuit is up yet. Reads the Tor control port. */
    fun read(): List<Hop>? {
        val ctl = Swell.torControl() ?: return null
        return runCatching {
            val circuits = ctl.getInfo("circuit-status").lines().map { it.trim() }.filter { it.contains(" BUILT ") }
            // A circuit to our mailbox if there is one, else any built circuit.
            val c = circuits.firstOrNull { it.contains("PURPOSE=HS_CLIENT_REND") } ?: circuits.firstOrNull() ?: return null
            val path = c.split(' ').getOrNull(2)?.split(',')?.map { it.substringBefore('~').removePrefix("$") }.orEmpty()
            if (path.isEmpty()) return null
            val hops = ArrayList<Hop>()
            hops += Hop("You")
            path.forEachIndexed { i, fp ->
                if (i == 0) {
                    val cc = runCatching {
                        val ip = ctl.getInfo("ns/id/$fp").lines().first { it.startsWith("r ") }.split(' ')[6]
                        ctl.getInfo("ip-to-country/$ip").trim()
                    }.getOrNull()?.takeIf { it.length == 2 && it != "??" }
                    hops += Hop("Entry", cc?.let { java.util.Locale("", it).displayCountry.ifBlank { it.uppercase() } })
                } else hops += Hop("Relay")
            }
            hops += Hop("Swell mailbox")
            hops
        }.getOrNull()
    }
}

/**
 * Link previews built by the SENDER over Tor (wire section 5c): the page and its image are fetched through our own SOCKS
 * port, never directly, and never by the receiver. Everything fails quietly: no preview, the message still goes.
 */
object Previews {
    private val URL = Regex("https://[^\\s<>\"']{4,2000}")
    private const val MAX_HTML = 256 * 1024
    private const val MAX_IMAGE = 400 * 1024

    fun firstUrl(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';')

    fun build(url: String, socksPort: Int): LinkPreview? = runCatching {
        val html = String(get(url, socksPort, MAX_HTML) ?: return null, Charsets.UTF_8)
        fun meta(prop: String): String? =
            Regex("<meta[^>]+(?:property|name)=[\"']$prop[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                ?: Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']$prop[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        val title = (meta("og:title") ?: Regex("<title[^>]*>([^<]{1,300})</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1))?.let(::unescape)?.trim() ?: return null
        val host = URI(url).host ?: return null
        val site = meta("og:site_name")?.let(::unescape)?.trim()?.ifBlank { null } ?: host
        val thumb = meta("og:image")?.let { img -> runCatching { thumbnail(URI(url).resolve(unescape(img)).toString(), socksPort) }.getOrNull() } ?: ByteArray(0)
        LinkPreview(url, title.take(LinkPreview.MAX_TITLE), site.take(LinkPreview.MAX_SITE), thumb)
    }.getOrNull()

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun thumbnail(url: String, socksPort: Int): ByteArray? {
        if (!url.startsWith("https://")) return null
        val bytes = get(url, socksPort, MAX_IMAGE) ?: return null
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= 240) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, 240f / maxOf(bmp.width, bmp.height))
        val out = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        var q = 75
        while (true) {
            val j = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray() // re-encoding drops EXIF
            if (j.size <= LinkPreview.MAX_THUMB) return j
            if (q <= 25) return null
            q -= 15
        }
    }

    /** One HTTPS GET over Tor (own circuit), following up to 3 https redirects; the body is cut at [max] bytes. */
    private fun get(url: String, socksPort: Int, max: Int, redirects: Int = 3): ByteArray? {
        val u = URI(url)
        if (u.scheme != "https" || u.host == null) return null
        val port = if (u.port > 0) u.port else 443
        val raw = Socks5.connect(socksPort, u.host, port, timeoutMs = 10_000, isolation = "preview-" + Codec.hex(Codec.random(6)))
        val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, u.host, port, true) as javax.net.ssl.SSLSocket
        try {
            s.soTimeout = 10_000
            s.startHandshake()
            val path = (u.rawPath.ifEmpty { "/" }) + (u.rawQuery?.let { "?$it" } ?: "")
            s.getOutputStream().write(
                ("GET $path HTTP/1.1\r\nHost: ${u.host}\r\nUser-Agent: Mozilla/5.0 (compatible; LinkPreview)\r\nAccept: text/html,image/*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            s.getOutputStream().flush()
            val input = s.getInputStream()
            val head = readHeaders(input) ?: return null
            val status = head.first().split(' ').getOrNull(1)?.toIntOrNull() ?: return null
            val headers = head.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
            if (status in 300..399) {
                val loc = headers["location"] ?: return null
                return if (redirects > 0) get(u.resolve(loc).toString(), socksPort, max, redirects - 1) else null
            }
            if (status != 200) return null
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < max) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            return if (headers["transfer-encoding"]?.contains("chunked") == true) dechunk(out.toByteArray(), max) else out.toByteArray()
        } finally { runCatching { s.close() } }
    }

    private fun readHeaders(i: InputStream): List<String>? {
        val sb = StringBuilder()
        while (sb.length < 16_384) { val c = i.read(); if (c < 0) return null; sb.append(c.toChar()); if (sb.endsWith("\r\n\r\n")) break }
        return sb.toString().split("\r\n").filter { it.isNotEmpty() }
    }

    private fun dechunk(b: ByteArray, max: Int): ByteArray {
        val out = ByteArrayOutputStream(); var p = 0
        while (p < b.size && out.size() < max) {
            val e = String(b, p, minOf(12, b.size - p), Charsets.ISO_8859_1).indexOf("\r\n").takeIf { it > 0 } ?: break
            val n = String(b, p, e, Charsets.ISO_8859_1).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (n == 0) break
            p += e + 2
            out.write(b, p, minOf(n, b.size - p)); p += n + 2
        }
        return out.toByteArray()
    }
}
