package com.jijilabsdigital.swell.mailbox

import com.jijilabsdigital.swell.core.FileMailboxStore
import com.jijilabsdigital.swell.core.ScaledInboundServer
import com.jijilabsdigital.swell.core.MailboxProtocol
import com.jijilabsdigital.swell.core.MailboxServer
import com.jijilabsdigital.swell.core.TurnConfig
import java.io.File
import java.util.concurrent.ThreadFactory
import kotlin.concurrent.thread

/**
 * A factory of virtual threads on Java 21+, else daemon platform threads. Reflection keeps this module (and core, which
 * Android shares) compiling on Java 17: `Thread.ofVirtual().factory()`.
 */
fun threadFactory(): ThreadFactory = runCatching {
    val builder = Thread::class.java.getMethod("ofVirtual").invoke(null)
    // Call through the public interface Thread.Builder: the implementation class is not accessible.
    Class.forName("java.lang.Thread\$Builder").getMethod("factory").invoke(builder) as ThreadFactory
}.getOrElse { ThreadFactory { r -> Thread(r).apply { isDaemon = true } } }

/**
 * Swell mailbox server. Listens on 127.0.0.1 only; tor publishes it as an onion service.
 * Env: MAILBOX_DIR (default /data/queues), MAILBOX_PORT (default 7070),
 * TURN_SECRET_FILE (default /data/turn-secret, shared with the coturn relay), TURN_URLS (comma separated),
 * MAILBOX_MAX_CONNECTIONS (default 6000), MAILBOX_MAX_WAITS (default 4000), MAILBOX_BACKLOG (default 1024).
 * Logs only counts, never queue ids.
 */
fun main() {
    val dir = File(System.getenv("MAILBOX_DIR") ?: "/data/queues")
    val port = (System.getenv("MAILBOX_PORT") ?: "7070").toInt()
    val maxConnections = (System.getenv("MAILBOX_MAX_CONNECTIONS") ?: "6000").toInt()
    val maxWaits = (System.getenv("MAILBOX_MAX_WAITS") ?: MailboxProtocol.MAX_WAITS.toString()).toInt()
    val backlog = (System.getenv("MAILBOX_BACKLOG") ?: "1024").toInt()
    val store = FileMailboxStore(dir)
    val secret = File(System.getenv("TURN_SECRET_FILE") ?: "/data/turn-secret").takeIf { it.canRead() }?.readText()?.trim()
    val urls = (System.getenv("TURN_URLS") ?: "turn:203.0.113.10:3478?transport=udp").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val turn = secret?.takeIf { it.isNotEmpty() }?.let { TurnConfig(it.toByteArray(), urls) }
    val server = MailboxServer(store, turn = turn, maxWaits = maxWaits)
    val factory = threadFactory()
    ScaledInboundServer(port, server::handle, maxConnections, factory, backlog).start()
    println("swell-mailbox listening on 127.0.0.1:$port, dir=$dir, call relay credentials ${if (turn != null) "on" else "off"}, " +
        "connections<=$maxConnections waits<=$maxWaits threads=${if (Thread.currentThread().javaClass.name.contains("Virtual") || factory.newThread {}.isVirtual()) "virtual" else "platform"}")
    thread(name = "purge", isDaemon = false) {
        while (true) {
            runCatching { store.purge(System.currentTimeMillis() - MailboxProtocol.TTL_MS) }
            Thread.sleep(3600_000)
        }
    }
}

private fun Thread.isVirtual(): Boolean = runCatching { Thread::class.java.getMethod("isVirtual").invoke(this) as Boolean }.getOrDefault(false)
