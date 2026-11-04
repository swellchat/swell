package com.jijilabsdigital.swell.mailbox

import com.jijilabsdigital.swell.core.FileMailboxStore
import com.jijilabsdigital.swell.core.InboundServerAt
import com.jijilabsdigital.swell.core.MailboxProtocol
import com.jijilabsdigital.swell.core.MailboxServer
import com.jijilabsdigital.swell.core.TurnConfig
import java.io.File
import kotlin.concurrent.thread

/**
 * Swell mailbox server. Listens on 127.0.0.1 only; tor publishes it as an onion service.
 * Env: MAILBOX_DIR (default /data/queues), MAILBOX_PORT (default 7070),
 * TURN_SECRET_FILE (default /data/turn-secret, shared with the coturn relay), TURN_URLS (comma separated).
 * Logs only counts, never queue ids.
 */
fun main() {
    val dir = File(System.getenv("MAILBOX_DIR") ?: "/data/queues")
    val port = (System.getenv("MAILBOX_PORT") ?: "7070").toInt()
    val store = FileMailboxStore(dir)
    val secret = File(System.getenv("TURN_SECRET_FILE") ?: "/data/turn-secret").takeIf { it.canRead() }?.readText()?.trim()
    val urls = (System.getenv("TURN_URLS") ?: "turn:203.0.113.10:3478?transport=udp").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val turn = secret?.takeIf { it.isNotEmpty() }?.let { TurnConfig(it.toByteArray(), urls) }
    val server = MailboxServer(store, turn = turn)
    InboundServerAt(port, server::handle) { }.start()
    println("swell-mailbox listening on 127.0.0.1:$port, dir=$dir, call relay credentials ${if (turn != null) "on" else "off"}")
    thread(name = "purge", isDaemon = false) {
        while (true) {
            runCatching { store.purge(System.currentTimeMillis() - MailboxProtocol.TTL_MS) }
            Thread.sleep(3600_000)
        }
    }
}
