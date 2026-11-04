package com.jijilabsdigital.swell.peer

import com.jijilabsdigital.swell.core.Account
import com.jijilabsdigital.swell.core.Bell
import com.jijilabsdigital.swell.core.Codec
import com.jijilabsdigital.swell.core.In
import com.jijilabsdigital.swell.core.MailboxProtocol
import com.jijilabsdigital.swell.core.Out
import com.jijilabsdigital.swell.core.MemoryKv
import com.jijilabsdigital.swell.core.Message
import com.jijilabsdigital.swell.core.CallSignal
import com.jijilabsdigital.swell.core.Contact
import com.jijilabsdigital.swell.core.TurnCredentials
import com.jijilabsdigital.swell.core.Dialer
import com.jijilabsdigital.swell.core.InboundServer
import com.jijilabsdigital.swell.core.Invite
import com.jijilabsdigital.swell.core.Node
import com.jijilabsdigital.swell.core.NodeEvents
import com.jijilabsdigital.swell.core.OnionHost
import com.jijilabsdigital.swell.core.Socks5
import com.jijilabsdigital.swell.core.TorControlHost
import net.freehaven.tor.control.TorControlConnection
import java.net.Socket
import java.util.Base64
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Test peer for the Mac: runs its own tor with an onion service and speaks the Swell protocol.
 *
 *   peer invite [--wait S]              print an invite, echo every message back
 *   peer accept <invite> <text> [--wait S]  redeem an invite, send text, print replies
 *
 * stdin commands for Swell Bell (docs/PROTOCOL.md 6a): `bellkey` sends a fresh BELLKEY to the newest contact,
 * `bell` listens on our mailbox (op 0x18) and fetches on a match, `bellstop` stops it. Tags are added to PUTs to
 * contacts that sent us a BELLKEY.
 *
 * Lines starting with INVITE / CONTACT / RECV / SENT / READY are meant for scripts.
 */
fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: usage()
    val wait = args.indexOf("--wait").takeIf { it >= 0 }?.let { args[it + 1].toLong() } ?: 600L
    val torBin = System.getenv("TOR") ?: listOf("/opt/homebrew/bin/tor", "/usr/bin/tor").first { File(it).exists() }

    // Message ids seen, so stdin commands can say "last" (newest incoming) or "mine" (newest we sent).
    val incomingIds = java.util.concurrent.CopyOnWriteArrayList<String>()
    val outgoingIds = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun pick(w: String, list: List<String>): String = when (w) {
        "last", "mine" -> list.last()
        else -> list.first { it.startsWith(w) }
    }
    lateinit var kv: com.jijilabsdigital.swell.core.Kv
    val events = object : NodeEvents {
        override fun onReaction(contact: Contact, messageId: String, emoji: String, add: Boolean) =
            println("REACTION ${contact.short} ${messageId.take(8)} $emoji ${if (add) "add" else "remove"}")
        override fun onModify(contact: Contact, op: Int, messageId: String, text: String) =
            println("MODIFY ${contact.short} ${messageId.take(8)} ${if (op == 1) "delete" else "edit"} $text")
        override fun onReceipt(contact: Contact, kind: Int, messageIds: List<String>) =
            println("RECEIPT ${contact.short} ${when (kind) { 1 -> "delivered"; 3 -> "opened"; else -> "read" }} ${messageIds.joinToString(",") { it.take(8) }}")
        override fun onControl(contact: Contact, op: Int) = println("CONTROL ${contact.short} ${when (op) { 1 -> "private-on"; 2 -> "private-off"; else -> "wipe" }}")
        override fun onTyping(contact: Contact, typing: Boolean) = println("TYPING ${contact.short} $typing")
        override fun onAvatar(contact: Contact, jpeg: ByteArray?) = println("AVATAR ${contact.short} ${jpeg?.size ?: "removed"}")
        override fun log(line: String) = println("LOG $line")
        override fun onBellKey(contact: Contact, key: ByteArray) {
            kv.put("bell", "out.${contact.id}", key)
            println("BELLKEY got ${Codec.hex(key).take(8)} from ${contact.short}")
        }
        override fun onContact(contact: Contact) =
            println("CONTACT ${contact.short} onion=${contact.onion.take(8)}… key=${Codec.hex(contact.identityKey.serialize()).take(16)}")
        override fun onMessage(contact: Contact, message: Message) =
            run {
                (if (message.incoming) incomingIds else outgoingIds) += message.id
                val extra = (message.replyTo?.let { " reply=${it.take(8)}" } ?: "") + (if (message.durationMs > 0) " dur=${message.durationMs}" else "") +
                    (if (message.viewOnce) " viewonce" else "") + (if (message.isPrivate) " private" else "") + (if (message.timerSec > 0) " timer=${message.timerSec}" else "")
                println((if (message.incoming) "RECV" else "SENT") + " ${contact.short} id=${message.id.take(8)}$extra ${message.text}")
            }
    }

    val dataDir = System.getenv("DATA_DIR")?.let { File(it).apply { mkdirs() } }
    val dir = dataDir ?: Files.createTempDirectory("swell-peer").toFile()
    kv = if (dataDir != null) FileKv(File(dataDir, "state.bin")) else MemoryKv()
    val account = Account.load(kv) ?: Account.create().also { it.save(kv) }
    val bot = mode == "bot"
    val socksPort = ServerSocket(0).use { it.localPort }
    val controlPort = ServerSocket(0).use { it.localPort }
    val tor = startTor(torBin, dir, socksPort, controlPort)
    val ctl = TorControlConnection(Socket("127.0.0.1", controlPort)).apply {
        launchThread(true)
        authenticate(File(dir, "data/control_auth_cookie").readBytes())
    }
    lateinit var server: InboundServer
    lateinit var node: Node
    val call = CallBridge { node }
    node = Node(
        kv,
        account,
        object : NodeEvents by events {
            override fun onNewContactFromInvite(contact: Contact) {
                if (bot) thread { runCatching {
                    Thread.sleep(3_000)
                    node.sendText(contact, "Hi! I'm the Swell demo contact. Send me a message or a photo and I'll send it back. I keep nothing: chats with me are deleted after 3 days.")
                } }
            }
            override fun onMessage(contact: Contact, message: Message) {
                if (bot && message.incoming) {
                    println("BOT message from ${contact.short}")
                    thread { runCatching {
                        val f = message.file
                        if (f?.data != null) node.sendFile(contact, f.name, f.mime, f.data!!, "Echo: your ${if (f.mime.startsWith("image/")) "photo" else "file"} came through end-to-end encrypted.")
                        else node.sendText(contact, "Echo: ${message.text}")
                    }.onFailure { println("LOG echo failed: ${it.message}") } }
                    return
                }
                events.onMessage(contact, message)
                message.file?.data?.takeIf { message.incoming }?.let { d ->
                    val out = File(System.getenv("RECV_DIR") ?: ".", "recv-" + message.file!!.name).apply { writeBytes(d) }
                    println("RECVFILE ${message.file!!.name} ${d.size} B mime=${message.file!!.mime} saved=${out.path}")
                }
                if (message.incoming && "--echo" in args) thread { runCatching { node.sendText(contact, "echo: ${message.text}") } }
            }
            override fun onCall(contact: Contact, signal: CallSignal) {
                println("CALL ${contact.short} op=${signal.op} id=${signal.callId.take(8)} sdp=${signal.sdp.length}")
                thread { runCatching { call.onSignal(contact, signal) }.onFailure { println("LOG call failed: ${it.message}") } }
            }
        },
        Dialer { onion, port, iso -> Socks5.connect(socksPort, "$onion.onion", port, timeoutMs = 35_000, isolation = iso) },
        HostProxy(ctl) { server.port },
        rotateMs = if (bot) 0 else Node.ROTATE_MS,
    )
    server = InboundServer(node, events).also { it.start() }
    node.extraCaps = Bell.CAP_BELL
    // Tag PUTs to contacts that sent a BELLKEY, but only if the mailbox answers BELL (an old one rejects the extra field).
    val bellMailboxOk = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    node.bellKeyOf = { c ->
        kv.get("bell", "out.${c.id}")?.takeIf { c.peerMailbox.isNotEmpty() && bellMailboxOk.getOrPut(c.peerMailbox) {
            runCatching { bellAsk(socksPort, c.peerMailbox, -1, 0)?.lost != null }.getOrDefault(false) } }
    }
    var bellRun = false
    args.indexOf("--mailbox").takeIf { it >= 0 }?.let { node.mailboxOnion = args[it + 1].removeSuffix(".onion") }
    args.indexOf("--name").takeIf { it >= 0 }?.let { node.profileName = args[it + 1] }
    node.start()
    println("READY onion=${node.onion}")
    // Commands on stdin: "send <text>" to the newest contact, "poll" to fetch the mailbox.
    thread(isDaemon = true) {
        System.`in`.bufferedReader().forEachLine { line ->
            runCatching {
                when {
                    line.startsWith("send ") -> node.sendText(node.contacts().last(), line.removePrefix("send "))
                    // 1.1 commands. <id> = id prefix, "last" (newest from the phone) or "mine" (newest we sent).
                    line.startsWith("reply ") -> line.removePrefix("reply ").split(" ", limit = 2).let { (w, t) ->
                        node.sendText(node.contacts().last(), t, pick(w, incomingIds + outgoingIds)) }
                    line.startsWith("react ") -> line.removePrefix("react ").split(" ", limit = 2).let { (w, e) ->
                        node.sendReaction(node.contacts().last(), pick(w, incomingIds), e, true) }
                    line.startsWith("unreact ") -> line.removePrefix("unreact ").split(" ", limit = 2).let { (w, e) ->
                        node.sendReaction(node.contacts().last(), pick(w, incomingIds), e, false) }
                    line.startsWith("edit ") -> line.removePrefix("edit ").split(" ", limit = 2).let { (w, t) ->
                        node.sendModify(node.contacts().last(), com.jijilabsdigital.swell.core.Modify.EDIT, pick(w, outgoingIds), t) }
                    line.startsWith("delete ") -> node.sendModify(node.contacts().last(), com.jijilabsdigital.swell.core.Modify.DELETE, pick(line.removePrefix("delete "), outgoingIds))
                    line.startsWith("avatar ") -> line.removePrefix("avatar ").let { f ->
                        // "avatar none" removes the photo; otherwise a JPEG of at most 40 KB.
                        val b = if (f == "none") null else File(f).readBytes()
                        node.avatar = b; node.sendAvatar(node.contacts().last(), b)
                    }
                    line == "receipts on" -> node.receiptsEnabled = true
                    line == "receipts off" -> node.receiptsEnabled = false
                    line == "typing on" -> node.typingEnabled = true
                    line == "typing off" -> node.typingEnabled = false
                    line.startsWith("typing now") -> node.sendTyping(node.contacts().last(), line.endsWith("stop").not())
                    line.startsWith("readall") -> node.sendReceipt(node.contacts().last(), com.jijilabsdigital.swell.core.Receipt.READ, incomingIds.toList())
                    line.startsWith("voice ") -> line.removePrefix("voice ").split(" ").let { p ->
                        val f = File(p[0])
                        node.sendFile(node.contacts().last(), f.name, "audio/mp4", f.readBytes(), "", durationMs = p.getOrNull(1)?.toInt() ?: 0)
                        println("FILESENT ${f.name} ${f.length()} B")
                    }
                    // 1.2: view once, per-message timer, private mode
                    line.startsWith("viewonce ") -> File(line.removePrefix("viewonce ")).let { f ->
                        node.sendFile(node.contacts().last(), f.name, if (f.name.endsWith(".mp4")) "video/mp4" else "image/jpeg", f.readBytes(), "", flags = Node.FLAG_VIEW_ONCE)
                        println("FILESENT ${f.name} viewonce")
                    }
                    line.startsWith("timer ") -> line.removePrefix("timer ").split(" ", limit = 2).let { (sec, t) -> node.sendText(node.contacts().last(), t, timerSec = sec.toInt()) }
                    line.startsWith("timerfile ") -> line.removePrefix("timerfile ").split(" ", limit = 2).let { (sec, f) ->
                        File(f).let { node.sendFile(node.contacts().last(), it.name, "image/jpeg", it.readBytes(), "", timerSec = sec.toInt()) } }
                    line == "private on" -> node.setPrivateMode(node.contacts().last(), true)
                    line == "private off" -> node.setPrivateMode(node.contacts().last(), false)
                    line == "wipe" -> node.sendWipe(node.contacts().last())
                    line.startsWith("psend ") -> node.sendText(node.contacts().last(), line.removePrefix("psend "), flags = Node.FLAG_PRIVATE)
                    line.startsWith("pfile ") -> File(line.removePrefix("pfile ")).let { f ->
                        node.sendFile(node.contacts().last(), f.name, "image/jpeg", f.readBytes(), "", flags = Node.FLAG_PRIVATE)
                        println("FILESENT ${f.name} private")
                    }
                    line.startsWith("opened ") -> node.sendOpened(node.contacts().last(), pick(line.removePrefix("opened "), incomingIds))
                    line == "bellkey" -> node.contacts().last().let { c ->
                        val key = Bell.newKey()
                        kv.put("bell", "in.${c.id}", key)
                        node.sendBellKey(c, key)
                        println("BELLKEY sent ${Codec.hex(key).take(8)} to ${c.short}")
                    }
                    line == "bell" -> if (!bellRun) {
                        bellRun = true
                        thread(isDaemon = true, name = "bell") {
                            var cursor = -1L
                            while (bellRun) {
                                val r = runCatching { bellAsk(socksPort, node.mailboxOnion, cursor, 25_000) }.onFailure { println("LOG bell: ${it.message}"); Thread.sleep(3_000) }.getOrNull() ?: continue
                                val keys = kv.list("bell").filter { it.first.startsWith("in.") }.map { it.first.removePrefix("in.") to it.second }
                                var hits = 0
                                r.tags.forEach { t ->
                                    keys.filter { Bell.matches(it.second, t) }.forEach { (id, _) ->
                                        hits++; println("BELLHIT ${node.contacts().firstOrNull { it.id == id }?.short ?: id.take(8)}")
                                    }
                                }
                                if (cursor == -1L || r.tags.isNotEmpty() || r.lost) println("BELL cursor=${r.cursor} tags=${r.tags.size} lost=${if (r.lost) 1 else 0} hits=$hits")
                                cursor = r.cursor
                                if (hits > 0 || r.lost) runCatching { println("POLLED ${node.pollMailbox()}") }.onFailure { println("LOG bell fetch: ${it.message}") }
                            }
                        }
                    }
                    line == "bellstop" -> bellRun = false
                    line == "poll" -> println("POLLED ${node.pollMailbox()}")
                    line.startsWith("file ") -> {
                        val f = File(line.removePrefix("file "))
                        val t0 = System.nanoTime()
                        node.sendFile(node.contacts().last(), f.name, if (f.name.endsWith(".jpg")) "image/jpeg" else "application/octet-stream", f.readBytes(), "file from Mac")
                        println("FILESENT ${f.name} ${f.length()} B in ${(System.nanoTime() - t0) / 1_000_000} ms")
                    }
                    line == "creds" -> node.turnCredentials().let { println("CREDS ${it.urls} user=${it.username.take(12)}… until=${it.expiresAt}") }
                    line == "call" -> call.dial(node.contacts().last())
                    line == "hangup" -> call.hangup()
                }
            }.onFailure { println("LOG command failed: ${it.message}") }
        }
    }

    when (mode) {
        "bot" -> {
            // One reusable code, kept across restarts (the address never rotates for the bot).
            val code = kv.get("bot", "code")?.let { String(it) } ?: node.createInvite(reusable = true).encode().also { kv.put("bot", "code", it.toByteArray()) }
            println("DEMO_CODE $code")
            thread(isDaemon = true, name = "bot-maintain") {
                while (true) {
                    Thread.sleep(3600_000)
                    runCatching {
                        node.contacts().filter { System.currentTimeMillis() - it.createdAt > 3 * 86400_000L }.forEach { node.delete(it) }
                        node.maintain()
                    }
                }
            }
            while (true) Thread.sleep(Long.MAX_VALUE)
        }
        "invite" -> println("INVITE ${node.createInvite().encode()}")
        "accept" -> {
            val invite = Invite.decode(args.getOrNull(1) ?: usage())
            val text = args.getOrNull(2) ?: "hello from the Mac peer"
            val t0 = System.nanoTime()
            node.acceptInvite(invite, text)
            println("LOG handshake+first message took ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        "report-setup", "reports" -> {
            val mb = args.indexOf("--mailbox").takeIf { it >= 0 }?.let { args[it + 1].removeSuffix(".onion") } ?: usage()
            reportInbox(mode, mb, socksPort)
            tor.destroy(); exitProcess(0)
        }
        else -> usage()
    }
    Thread.sleep(wait * 1000)
    tor.destroy()
    tor.waitFor(5, TimeUnit.SECONDS)
    if (dataDir == null) dir.deleteRecursively()
    exitProcess(0)
}

private class BellAnswer(val cursor: Long, val lost: Boolean, val tags: List<ByteArray>)

/** One BELL request to [mailbox] over its own Tor stream; null if the mailbox refuses (older than 1.3). */
private fun bellAsk(socksPort: Int, mailbox: String, cursor: Long, waitMs: Int): BellAnswer? =
    Socks5.connect(socksPort, "${mailbox.removeSuffix(".onion")}.onion", Node.PORT, isolation = "bell").use { s ->
        com.jijilabsdigital.swell.core.Wire.write(s.getOutputStream(), Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.BELL).i64(cursor).i32(waitMs).done())
        val r = In(com.jijilabsdigital.swell.core.Wire.read(s, 40_000))
        if (r.u8() != MailboxProtocol.R_BELL) return null
        val c = r.i64(); val lost = r.u8() == 1
        BellAnswer(c, lost, List(r.i32()) { r.raw(Bell.TAG_SIZE) })
    }

/** Defers to a [TorControlHost] built once the inbound server's port is known. */
private class HostProxy(val ctl: TorControlConnection, val port: () -> Int) : OnionHost {
    private val real by lazy { TorControlHost(ctl, port()) { println("LOG $it") } }
    override fun create(clients: List<ByteArray>) = real.create(clients)
    override fun publish(onion: String, key: String, clients: List<ByteArray>) = real.publish(onion, key, clients)
    override fun unpublish(onion: String) = real.unpublish(onion)
    override fun clientAuth(onion: String, privateKey: ByteArray?) = real.clientAuth(onion, privateKey)
}

private fun startTor(torBin: String, dir: File, socksPort: Int, controlPort: Int): Process {
    val torrc = File(dir, "torrc")
    torrc.writeText(
        """
        DataDirectory ${File(dir, "data").absolutePath}
        SocksPort 127.0.0.1:$socksPort
        ControlPort 127.0.0.1:$controlPort
        CookieAuthentication 1
        Log notice stdout
        """.trimIndent(),
    )
    val p = ProcessBuilder(torBin, "-f", torrc.absolutePath).redirectErrorStream(true).start()
    Runtime.getRuntime().addShutdownHook(Thread { p.destroy() })
    val t0 = System.nanoTime()
    val reader = p.inputStream.bufferedReader()
    while (true) {
        val line = reader.readLine() ?: error("tor exited")
        if ("Bootstrapped" in line) println("LOG tor ${line.substringAfter("Bootstrapped ").take(40)}")
        if ("Bootstrapped 100%" in line) break
    }
    println("LOG tor ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
    thread(isDaemon = true) { runCatching { reader.forEachLine { } } }
    return p
}

private fun usage(): Nothing {
    System.err.println("usage: peer invite [--wait S] | peer accept <invite> <text> [--wait S]")
    exitProcess(2)
}

/**
 * Bridges call signaling to tools/callpeer/callpeer.py (aiortc), which does the WebRTC part.
 * CALLPEER = "<python> <path to callpeer.py>".
 */
private class CallBridge(val node: () -> Node) {
    private var proc: Process? = null
    private var contact: Contact? = null
    private var callId = ""

    private fun py(): Process = proc ?: run {
        val cmd = (System.getenv("CALLPEER") ?: error("set CALLPEER")).split(" ")
        ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start().also { p ->
            proc = p
            thread(isDaemon = true) {
                p.inputStream.bufferedReader().forEachLine { line ->
                    val (op, rest) = line.substringBefore(' ') to line.substringAfter(' ')
                    when (op) {
                        "ANSWER", "OFFER" -> {
                            val sdp = String(Base64.getDecoder().decode(rest))
                            val c = contact ?: return@forEachLine
                            val t0 = System.nanoTime()
                            node().sendCall(c, CallSignal(if (op == "ANSWER") CallSignal.ANSWER else CallSignal.OFFER, callId, sdp))
                            println("LOG sent $op (${(System.nanoTime() - t0) / 1_000_000} ms over Tor)")
                        }
                        else -> println("RTC $line")
                    }
                }
            }
        }
    }

    private fun send(json: String) { py().outputStream.apply { write((json + "\n").toByteArray()); flush() } }

    private fun turn(t: TurnCredentials) =
        "{\"urls\":[${t.urls.joinToString(",") { "\"$it\"" }}],\"username\":\"${t.username}\",\"password\":\"${t.password}\"}"

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\""

    fun onSignal(c: Contact, s: CallSignal) {
        when (s.op) {
            CallSignal.OFFER -> {
                contact = c; callId = s.callId
                val t = node().turnCredentials()
                send("{\"op\":\"offer\",\"sdp\":${q(s.sdp)},\"turn\":${turn(t)}}")
            }
            CallSignal.ANSWER -> if (s.callId == callId) send("{\"op\":\"answer\",\"sdp\":${q(s.sdp)}}")
            CallSignal.HANGUP, CallSignal.BUSY -> if (s.callId == callId) send("{\"op\":\"hangup\"}")
        }
    }

    fun dial(c: Contact) {
        contact = c; callId = Codec.hex(Codec.random(16))
        send("{\"op\":\"call\",\"turn\":${turn(node().turnCredentials())}}")
    }

    fun hangup() {
        val c = contact ?: return
        runCatching { node().sendCall(c, CallSignal(CallSignal.HANGUP, callId)) }
        send("{\"op\":\"hangup\"}")
    }
}

/**
 * Jiji Labs report inbox. The key file (~/.config/swell/report-inbox, mode 600) holds the queue
 * secret and the private key; it never leaves the Mac. "report-setup" creates the queue and prints
 * the value for -Pswell.reportInbox; "reports" fetches, opens and deletes waiting reports.
 */
private fun reportInbox(mode: String, mailbox: String, socksPort: Int) {
    val f = File(System.getProperty("user.home"), ".config/swell/report-inbox")
    if (!f.exists()) {
        f.parentFile.mkdirs()
        f.writeText(Codec.hex(Codec.random(32)) + "\n" + Codec.hex(com.jijilabsdigital.swell.core.AuthKey.generate()) + "\n")
        java.nio.file.Files.setPosixFilePermissions(f.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
    }
    val (secretHex, privHex) = f.readLines().filter { it.isNotBlank() }
    val secret = Codec.unhex(secretHex); val priv = Codec.unhex(privHex)
    val q = MailboxProtocol.queueId(secret)
    fun ask(frame: ByteArray): ByteArray = Socks5.connect(socksPort, "$mailbox.onion", Node.PORT, isolation = "reports").use {
        com.jijilabsdigital.swell.core.Wire.write(it.getOutputStream(), frame); com.jijilabsdigital.swell.core.Wire.read(it, 90_000)
    }
    if (mode == "report-setup") {
        val nonce = MailboxProtocol.solvePow(q, MailboxProtocol.POW_BITS)
        val r = ask(Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.CREATE).raw(secret).i64(nonce).done())
        println(if (In(r).u8() == MailboxProtocol.R_OK) "QUEUE ready" else "QUEUE refused")
        println("REPORT_INBOX $mailbox:${Codec.hex(q)}:${Codec.hex(com.jijilabsdigital.swell.core.AuthKey.publicOf(priv))}")
        return
    }
    val r = In(ask(Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.GET).i32(1).raw(secret).done()))
    if (r.u8() != MailboxProtocol.R_LIST) { println("REPORTS refused"); return }
    val n = r.i32()
    println("REPORTS $n")
    val ids = ArrayList<String>()
    repeat(n) {
        r.i32(); val id = r.str(64); val env = r.bytes()
        ids += id
        runCatching {
            val b = In(com.jijilabsdigital.swell.core.Padding.unpad(com.jijilabsdigital.swell.core.Report.open(priv, env)))
            b.u8(); val at = b.i64(); val reason = b.str(); val key = b.bytes()
            println("--- report ${java.time.Instant.ofEpochMilli(at)}  reported key ${Codec.hex(key).take(24)}…")
            println("reason: $reason")
            repeat(b.i32()) { val t = b.i64(); val inc = b.u8() == 1; println("  [${java.time.Instant.ofEpochMilli(t)}] ${if (inc) "them" else "reporter"}: ${b.str()}") }
        }.onFailure { println("--- unreadable report: ${it.message}") }
    }
    if (ids.isNotEmpty()) ask(Out().u8(MailboxProtocol.VERSION).u8(MailboxProtocol.ACK).raw(secret).i32(ids.size).also { o -> ids.forEach { o.str(it) } }.done())
}

/** Small persistent [Kv] for the bot: the whole map, rewritten atomically on every change. */
private class FileKv(private val file: File) : com.jijilabsdigital.swell.core.Kv {
    private val m = java.util.TreeMap<String, ByteArray>()
    init {
        if (file.exists()) {
            val r = In(file.readBytes())
            repeat(r.i32()) { m[r.str()] = r.bytes() }
        }
    }
    @Synchronized private fun flush() {
        val o = Out().i32(m.size)
        m.forEach { (k, v) -> o.str(k).bytes(v) }
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(o.done())
        tmp.renameTo(file)
    }
    @Synchronized override fun get(ns: String, key: String) = m["$ns\u0000$key"]
    @Synchronized override fun put(ns: String, key: String, value: ByteArray) { m["$ns\u0000$key"] = value; flush() }
    @Synchronized override fun delete(ns: String, key: String) { if (m.remove("$ns\u0000$key") != null) flush() }
    @Synchronized override fun list(ns: String) = m.filterKeys { it.startsWith("$ns\u0000") }.map { (k, v) -> k.substringAfter('\u0000') to v }
    @Synchronized override fun deleteAll() { m.clear(); flush() }
    override fun <T> tx(block: () -> T): T = synchronized(this) { block() }
}
