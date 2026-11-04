package com.jijilabsdigital.swell.core

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Nodes over loopback TCP; a fake Tor maps onion names to local ports and enforces client auth. */
class NodeTest {
    private val loop = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    /** onion -> (port, authorized client public keys as hex; null = open service like the mailbox). */
    private val services = ConcurrentHashMap<String, Pair<Int, Set<String>?>>()

    private class Rec : NodeEvents {
        val got = CopyOnWriteArrayList<String>()
        val calls = CopyOnWriteArrayList<CallSignal>()
        val files = CopyOnWriteArrayList<Attachment>()
        val msgs = CopyOnWriteArrayList<Message>()
        val reactions = CopyOnWriteArrayList<String>()
        val modifies = CopyOnWriteArrayList<String>()
        val receipts = CopyOnWriteArrayList<String>()
        val typing = CopyOnWriteArrayList<Boolean>()
        val avatars = CopyOnWriteArrayList<Int>() // size of the JPEG, -1 = removed
        val controls = CopyOnWriteArrayList<Int>()
        override fun onControl(contact: Contact, op: Int) { controls += op }
        override fun onAvatar(contact: Contact, jpeg: ByteArray?) { avatars += jpeg?.size ?: -1 }
        override fun onReaction(contact: Contact, messageId: String, emoji: String, add: Boolean) { reactions += "$messageId $emoji $add" }
        override fun onModify(contact: Contact, op: Int, messageId: String, text: String) { modifies += "$op $messageId $text" }
        override fun onReceipt(contact: Contact, kind: Int, messageIds: List<String>) { receipts += "$kind ${messageIds.joinToString(",")}" }
        override fun onTyping(contact: Contact, typing: Boolean) { this.typing += typing }
        override fun onMessage(contact: Contact, message: Message) {
            if (message.incoming) msgs += message
            if (message.incoming) got += message.text
            if (message.incoming) message.file?.let { files += it }
        }
        override fun onCall(contact: Contact, signal: CallSignal) { calls += signal }
    }

    private val mailboxOnion = "mailbox".padEnd(56, 'm')
    private val mailboxStore = MemoryMailboxStore()
    private val turnSecret = "test-secret".toByteArray()
    init {
        val srv = InboundServerAt(0, MailboxServer(mailboxStore, powBits = 8, turn = TurnConfig(turnSecret, listOf("turn:203.0.113.1:3478?transport=udp")), credPowBits = 8)::handle) {}
        srv.start()
        services[mailboxOnion] = srv.port to null
    }

    private fun onionName(seed: String) = seed.map { if (it in 'a'..'z' || it in '2'..'7') it else 'q' }.joinToString("").padEnd(56, 'a').take(56)

    /** Fake Tor for one peer: its client keys and the services it publishes. */
    private inner class FakeHost(val seed: String, val port: () -> Int) : OnionHost {
        val clientKeys = ConcurrentHashMap<String, ByteArray>()
        private var n = 0
        private fun keys(c: List<ByteArray>) = c.map { Codec.hex(it) }.toSet()
        override fun create(clients: List<ByteArray>): Pair<String, String> {
            val onion = onionName(if (n++ == 0) seed else "$seed${"x".repeat(n)}")
            services[onion] = port() to keys(clients)
            return onion to "key-$onion"
        }
        override fun publish(onion: String, key: String, clients: List<ByteArray>) { services[onion] = port() to keys(clients) }
        override fun unpublish(onion: String) { services.remove(onion) }
        override fun clientAuth(onion: String, privateKey: ByteArray?) {
            if (privateKey == null) clientKeys.remove(onion) else clientKeys[onion] = privateKey
        }
        val dialer = Dialer { onion, _, _ ->
            val (port, allowed) = services[onion] ?: throw IOException("offline")
            if (allowed != null) {
                val mine = clientKeys[onion] ?: throw IOException("no client authorization")
                if (Codec.hex(AuthKey.publicOf(mine)) !in allowed) throw IOException("client authorization refused")
            }
            Socket(loop, port)
        }
    }

    private inner class Peer(
        val name: String, val kv: Kv = MemoryKv(), account: Account? = null, mailbox: Boolean = false,
        clock: () -> Long = System::currentTimeMillis,
    ) {
        val rec = Rec()
        val account = account ?: Account.load(kv) ?: Account.create().also { it.save(kv) }
        lateinit var server: InboundServer
        val host = FakeHost(name) { server.port }
        val node = Node(kv, this.account, rec, host.dialer, host, now = clock, powBits = 8, pollJitterMs = 0, publishDelayMs = 0, retryDelayMs = 5)
        init {
            server = InboundServer(node, rec).also { it.start() }
            node.profileName = name
            if (mailbox) node.mailboxOnion = mailboxOnion
            node.start()
        }
        fun stop() { server.close(); services.entries.removeIf { it.value.first == server.port } }
    }

    private fun waitFor(cond: () -> Boolean) {
        repeat(200) { if (cond()) return; Thread.sleep(20) }
    }

    @Test
    fun inviteHandshakeAndConversation() {
        val a = Peer("alice"); val b = Peer("bob")
        val invite = Invite.decode(a.node.createInvite().encode())
        val bobsAlice = b.node.acceptInvite(invite, "hi alice")
        waitFor { a.rec.got.isNotEmpty() }
        assertEquals(listOf("hi alice"), a.rec.got)
        b.node.sendText(bobsAlice, "second")
        val alicesBob = a.node.contacts().single()
        assertEquals("bob", alicesBob.name)
        a.node.sendText(alicesBob, "hi bob")
        b.node.sendText(bobsAlice, "third")
        waitFor { a.rec.got.size == 3 && b.rec.got.size == 1 }
        assertEquals(listOf("hi alice", "second", "third"), a.rec.got)
        assertEquals(listOf("hi bob"), b.rec.got)
        waitFor { bobsAlice.name == "alice" }
        assertEquals("alice", bobsAlice.name, "profile reply names the contact")
    }

    @Test
    fun stateSurvivesRestart() {
        val akv = MemoryKv()
        var a = Peer("alice5", akv)
        val b = Peer("bob5")
        val inviteForLater = a.node.createInvite()
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "before restart")
        waitFor { a.rec.got.size == 1 }
        a.stop()
        a = Peer("alice5", akv) // same storage, new process
        assertEquals(1, a.node.contacts().size)
        b.node.sendText(bobsAlice, "after restart")
        waitFor { a.rec.got.size == 1 }
        assertEquals(listOf("after restart"), a.rec.got)
        a.node.sendText(a.node.contacts().single(), "reply")
        waitFor { b.rec.got.size == 1 }
        assertEquals(listOf("reply"), b.rec.got)
        // An invite created before the restart still works.
        val c = Peer("carol5")
        c.node.acceptInvite(inviteForLater, "late")
        waitFor { a.node.contacts().size == 2 }
        assertEquals(2, a.node.contacts().size)
    }

    @Test
    fun inviteIsSingleUse() {
        val a = Peer("alice2"); val b = Peer("bob2"); val c = Peer("carol2")
        val invite = a.node.createInvite()
        b.node.acceptInvite(invite, "first")
        assertFailsWith<IOException> { c.node.acceptInvite(invite, "second") }
        assertEquals(1, a.node.contacts().size)
    }

    @Test
    fun ownInviteIsRefused() {
        val a = Peer("alice6")
        assertFailsWith<IOException> { a.node.acceptInvite(a.node.createInvite(), "me") }
    }

    @Test
    fun wrongIdentityInInviteIsRefused() {
        val a = Peer("alice3"); val b = Peer("bob3")
        val real = a.node.createInvite()
        val forged = Invite(real.onion, real.secret, Account.create().identity.publicKey.serialize(), real.authKey)
        assertFailsWith<IOException> { b.node.acceptInvite(forged, "x") }
    }

    @Test
    fun blockedContactIsDropped() {
        val a = Peer("alice7"); val b = Peer("bob7")
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "hello")
        waitFor { a.rec.got.size == 1 }
        a.node.setBlocked(a.node.contacts().single(), true)
        assertFailsWith<IOException> { b.node.sendText(bobsAlice, "spam") } // refused like a stranger, never decrypted
        Thread.sleep(200)
        assertEquals(listOf("hello"), a.rec.got)
        assertFailsWith<IOException> { a.node.sendText(a.node.contacts().single(), "x") }
        a.node.setBlocked(a.node.contacts().single(), false)
        waitFor { bobsAlice.onion == a.node.onion } // the new address arrives after unblocking
        b.node.sendText(bobsAlice, "ok again")
        waitFor { a.rec.got.size == 2 }
        assertEquals("ok again", a.rec.got.last())
    }

    @Test
    fun disappearingTimerIsShared() {
        val a = Peer("alice8"); val b = Peer("bob8")
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "hi")
        waitFor { a.node.contacts().isNotEmpty() }
        b.node.setDisappearing(bobsAlice, 3600)
        waitFor { a.node.contacts().single().expireSec == 3600 }
        assertEquals(3600, a.node.contacts().single().expireSec)
    }

    @Test
    fun safetyNumbersMatchAndQrVerifies() {
        val a = Peer("alice9"); val b = Peer("bob9")
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "hi")
        waitFor { a.node.contacts().isNotEmpty() }
        val alicesBob = a.node.contacts().single()
        assertEquals(
            a.node.safetyNumber(alicesBob).displayableFingerprint.displayText,
            b.node.safetyNumber(bobsAlice).displayableFingerprint.displayText,
        )
        val bobsQr = b.node.safetyNumber(bobsAlice).scannableFingerprint.serialized
        assertTrue(a.node.verifyScanned(alicesBob, bobsQr))
        assertTrue(alicesBob.verified)
        val stranger = Peer("eve9")
        val c = stranger.node.acceptInvite(a.node.createInvite(), "x")
        assertFalse(b.node.verifyScanned(bobsAlice, stranger.node.safetyNumber(c).scannableFingerprint.serialized))
    }

    @Test
    fun offlineMessagesWaitInMailbox() {
        val a = Peer("alice10", mailbox = true)
        val bkv = MemoryKv()
        var b = Peer("bob10", bkv, mailbox = true)
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "hi")
        waitFor { a.node.contacts().isNotEmpty() && bobsAlice.peerQueue.size == 32 }
        val alicesBob = a.node.contacts().single()
        assertEquals(32, alicesBob.peerQueue.size, "Alice knows Bob's queue")
        assertEquals(32, bobsAlice.peerQueue.size, "Bob knows Alice's queue")

        b.stop() // Bob's phone goes offline
        a.node.sendText(alicesBob, "while you were away 1")
        a.node.sendText(alicesBob, "while you were away 2")

        b = Peer("bob10", bkv, mailbox = true) // back online
        assertEquals(2, b.node.pollMailbox())
        assertEquals(listOf("while you were away 1", "while you were away 2"), b.rec.got)
        assertEquals(0, b.node.pollMailbox(), "acknowledged envelopes are deleted")

        // And the other way round.
        a.stop()
        b.node.sendText(b.node.contacts().single(), "reply via mailbox")
    }

    @Test
    fun mailboxQueuesNeedTheSecretAndWork() {
        val srv = MailboxServer(MemoryMailboxStore(), powBits = 8)
        val secret = Codec.random(32)
        val q = MailboxProtocol.queueId(secret)
        fun put() = In(srv.handle(Out().u8(2).u8(MailboxProtocol.PUT).raw(q).bytes(byteArrayOf(1, 2, 3)).done())).u8()
        assertEquals(0xFF, put(), "no PUT into a queue that was never created")
        var bad = 0L; while (MailboxProtocol.powOk(q, bad, 8)) bad++
        assertEquals(0xFF, In(srv.handle(Out().u8(2).u8(MailboxProtocol.CREATE).raw(secret).i64(bad).done())).u8(), "CREATE needs work")
        val nonce = MailboxProtocol.solvePow(q, 8)
        assertEquals(0x80, In(srv.handle(Out().u8(2).u8(MailboxProtocol.CREATE).raw(secret).i64(nonce).done())).u8())
        assertEquals(0x80, put())
        // Knowing the queue id is not enough to read it.
        val wrong = In(srv.handle(Out().u8(2).u8(MailboxProtocol.GET).i32(1).raw(q).done()))
        assertEquals(0x82, wrong.u8()); assertEquals(0, wrong.i32())
        val right = In(srv.handle(Out().u8(2).u8(MailboxProtocol.GET).i32(1).raw(secret).done()))
        assertEquals(0x82, right.u8()); assertEquals(1, right.i32())
        assertEquals(0xFF, In(srv.handle(byteArrayOf(2, 0x11, 0))).u8())
    }

    @Test
    fun slowSendersAreCutOff() {
        val srv = InboundServer({ it }, {}, maxConnections = 2, deadlineMs = 300)
        srv.start()
        val hogs = List(2) { Socket(loop, srv.port).also { s -> s.getOutputStream().write(byteArrayOf(0, 0)) } } // never finish
        Thread.sleep(1200)
        // Both slots were released after the deadline, so a normal request is served again.
        Socket(loop, srv.port).use { s ->
            Wire.write(s.getOutputStream(), byteArrayOf(7))
            assertTrue(Wire.read(s.getInputStream()).contentEquals(byteArrayOf(7)))
        }
        hogs.forEach { runCatching { it.close() } }
        srv.close()
    }

    @Test
    fun namesAreCleaned() {
        assertEquals("Eve", Node.cleanName("  \u202EEve\u0000 "))
        assertEquals(64, Node.cleanName("x".repeat(100)).length)
    }

    @Test
    fun fileMailboxQuotaAndPurge() {
        val dir = kotlin.io.path.createTempDirectory("mb").toFile()
        var t = 1_000L
        val store = FileMailboxStore(dir, now = { t }, minFree = 0)
        val q = Codec.hex(MailboxProtocol.queueId(Codec.random(32)))
        assertTrue(store.create(q))
        repeat(MailboxProtocol.MAX_PER_QUEUE) { assertTrue(store.put(q, byteArrayOf(it.toByte()))) }
        assertFalse(store.put(q, byteArrayOf(1)), "queue full")
        val first = store.list(q, 1).single()
        store.delete(q, first.first)
        assertTrue(store.put(q, byteArrayOf(9)))
        store.purge(System.currentTimeMillis() + 1)
        assertEquals(0, store.list(q, 10).size)
        dir.deleteRecursively()
    }

    @Test
    fun fileMailboxHasWholeServerLimits() {
        val dir = kotlin.io.path.createTempDirectory("mb2").toFile()
        val store = FileMailboxStore(dir, maxTotal = 1000, minFree = 0)
        val q1 = Codec.hex(MailboxProtocol.queueId(Codec.random(32)))
        assertTrue(store.create(q1))
        assertTrue(store.put(q1, ByteArray(600)))
        assertFalse(store.put(q1, ByteArray(600)), "server byte cap")
        store.delete(q1, store.list(q1, 1).single().first)
        assertEquals(0, store.totalBytes())
        // New queues per minute are capped (random queue ids cannot flood the disk).
        val fast = FileMailboxStore(kotlin.io.path.createTempDirectory("mb3").toFile(), now = { 0L }, minFree = 0)
        repeat(MailboxProtocol.MAX_NEW_QUEUES_PER_MIN) { assertTrue(fast.create(Codec.hex(Codec.random(32)))) }
        assertFalse(fast.create(Codec.hex(Codec.random(32))))
        dir.deleteRecursively()
    }

    @Test
    fun garbageIsRejectedWithoutCrash() {
        val a = Peer("alice4")
        assertEquals(0xFF, In(a.node.handle(byteArrayOf(1, 3, 1, 2, 3))).u8())
        assertEquals(0xFF, In(a.node.handle(byteArrayOf(9))).u8())
        assertEquals(0xFF, In(a.node.handle(byteArrayOf(2, 1))).u8())
    }

    @Test
    fun onlyKeyHoldersReachAPhone() {
        var t = System.currentTimeMillis()
        val a = Peer("alice11", clock = { t }); val b = Peer("bob11"); val eve = Peer("eve11")
        assertFailsWith<IOException>("no key, no connection") { eve.host.dialer.dial(a.node.onion, 80, null) }
        val invite = a.node.createInvite()
        val bobsAlice = b.node.acceptInvite(invite, "hi")
        waitFor { a.rec.got.size == 1 }
        // The redeemed invite key is dropped at the next daily clean-up publish; Bob's own key keeps working.
        eve.host.clientAuth(a.node.onion, invite.authKey)
        eve.host.dialer.dial(a.node.onion, 80, null).close()
        t += Node.USED_INVITE_GRACE_MS + 1000
        a.node.maintain()
        eve.host.dialer.dial(a.node.onion, 80, null).close() // no re-publish just for that
        t += Node.CLEANUP_PUBLISH_MS
        a.node.maintain()
        assertFailsWith<IOException> { eve.host.dialer.dial(a.node.onion, 80, null) }
        b.node.sendText(bobsAlice, "still here")
        waitFor { a.rec.got.size == 2 }
        assertEquals("still here", a.rec.got.last())
    }

    @Test
    fun deletingAContactMovesTheAddress() {
        val a = Peer("alice12"); val b = Peer("bob12"); val c = Peer("carol12")
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "from bob")
        val carolsAlice = c.node.acceptInvite(a.node.createInvite(), "from carol")
        waitFor { a.node.contacts().size == 2 }
        val old = a.node.onion
        a.node.delete(a.node.contacts().first { it.name == "bob12" })
        waitFor { carolsAlice.onion != old }
        assertTrue(a.node.onion != old, "new address")
        assertEquals(a.node.onion, carolsAlice.onion, "remaining contact learned it")
        c.node.sendText(carolsAlice, "after move")
        waitFor { a.rec.got.contains("after move") }
        assertTrue(a.rec.got.contains("after move"))
        // Bob's key is gone from every address, old or new.
        assertFailsWith<IOException> { b.host.dialer.dial(old, 80, null) }
        assertFailsWith<IOException> { b.node.sendText(bobsAlice, "hello?") }
    }

    @Test
    fun callSignalsGoDirectOnly() {
        val a = Peer("alice13", mailbox = true); val b = Peer("bob13", mailbox = true)
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "hi")
        waitFor { a.node.contacts().isNotEmpty() && bobsAlice.peerQueue.size == 32 }
        val id = Codec.hex(Codec.random(16))
        b.node.sendCall(bobsAlice, CallSignal(CallSignal.OFFER, id, "v=0 offer"))
        waitFor { a.rec.calls.isNotEmpty() }
        assertEquals(id, a.rec.calls.single().callId)
        assertEquals("v=0 offer", a.rec.calls.single().sdp)
        a.stop()
        assertFailsWith<IOException>("offline: no mailbox fallback for calls") { b.node.sendCall(bobsAlice, CallSignal(CallSignal.OFFER, id, "x")) }
        assertEquals(0, mailboxStore.list(Codec.hex(bobsAlice.peerQueue), 10).size)
    }

    @Test
    fun turnCredentialsAreValidAndNotReplayable() {
        val a = Peer("alice14", mailbox = true)
        val cred = a.node.turnCredentials()
        val mac = javax.crypto.Mac.getInstance("HmacSHA1").apply { init(javax.crypto.spec.SecretKeySpec(turnSecret, "HmacSHA1")) }
        assertEquals(java.util.Base64.getEncoder().encodeToString(mac.doFinal(cred.username.toByteArray())), cred.password)
        assertTrue(cred.username.substringBefore(':').toLong() * 1000 > System.currentTimeMillis())
        assertEquals(1, cred.urls.size)
        val srv = MailboxServer(MemoryMailboxStore(), turn = TurnConfig(turnSecret, listOf("turn:x")), credPowBits = 8)
        val m = System.currentTimeMillis() / 60_000
        val nonce = MailboxProtocol.solveCredPow(m, 8)
        val f = Out().u8(2).u8(MailboxProtocol.CREDS).i64(m).i64(nonce).done()
        assertEquals(MailboxProtocol.R_CREDS, In(srv.handle(f)).u8())
        assertEquals(0xFF, In(srv.handle(f)).u8(), "same proof twice")
        val old = MailboxProtocol.solveCredPow(m - 5, 8)
        assertEquals(0xFF, In(srv.handle(Out().u8(2).u8(MailboxProtocol.CREDS).i64(m - 5).i64(old).done())).u8(), "stale minute")
        assertEquals(0xFF, In(MailboxServer(MemoryMailboxStore()).handle(f)).u8(), "no relay configured")
    }

    @Test
    fun reportsAreSealedToTheInbox() {
        val a = Peer("alice15"); val b = Peer("bob15")
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "rude message")
        waitFor { a.node.contacts().isNotEmpty() }
        val inboxPriv = AuthKey.generate()
        val secret = Codec.random(32)
        val q = MailboxProtocol.queueId(secret)
        mailboxStore.create(Codec.hex(q))
        val alicesBob = a.node.contacts().single()
        a.node.report(alicesBob, "abuse", listOf(Message("m1", alicesBob.id, "rude message", true, 1L, 0)), ReportInbox(mailboxOnion, q, AuthKey.publicOf(inboxPriv)))
        val env = mailboxStore.list(Codec.hex(q), 10).single().second
        val r = In(Padding.unpad(Report.open(inboxPriv, env)))
        assertEquals(1, r.u8()); r.i64()
        assertEquals("abuse", r.str())
        assertTrue(r.bytes().contentEquals(bobsAlice.identityKey.serialize().let { b.node.identityKey.serialize() }))
        assertEquals(1, r.i32()); r.i64(); assertEquals(1, r.u8()); assertEquals("rude message", r.str())
        assertFailsWith<Exception> { Report.open(AuthKey.generate(), env) }
    }

    /** Random and mutated frames must never crash or hang either parser (Phase S fuzz item). */
    @Test
    fun fuzzParsers() {
        val a = Peer("alice16"); val b = Peer("bob16")
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "seed")
        waitFor { a.node.contacts().isNotEmpty() }
        val mb = MailboxServer(MemoryMailboxStore(), powBits = 8, turn = TurnConfig(turnSecret, listOf("turn:x")), credPowBits = 8)
        val rnd = java.util.Random(42)
        val seeds = listOf(
            Out().u8(2).u8(3).raw(bobsAlice.channelId).u8(2).bytes(Codec.random(200)).done(),
            Out().u8(2).u8(1).raw(Codec.random(32)).done(),
            Out().u8(2).u8(2).raw(Codec.random(48)).bytes(Codec.random(300)).done(),
            Out().u8(2).u8(MailboxProtocol.GET).i32(1).raw(Codec.random(32)).done(),
            Out().u8(2).u8(MailboxProtocol.ACK).raw(Codec.random(32)).i32(1).str("1-00").done(),
            Out().u8(2).u8(MailboxProtocol.CREDS).i64(1).i64(2).done(),
        )
        val t0 = System.nanoTime()
        repeat(20_000) { i ->
            val f = seeds[i % seeds.size].copyOf()
            when (rnd.nextInt(4)) {
                0 -> repeat(1 + rnd.nextInt(4)) { f[rnd.nextInt(f.size)] = rnd.nextInt(256).toByte() }
                1 -> if (f.size > 6) f[2 + rnd.nextInt(4)] = 0x7F // huge length prefixes
                2 -> {}
                else -> rnd.nextBytes(f)
            }
            val cut = if (rnd.nextBoolean()) f.copyOf(rnd.nextInt(f.size + 1)) else f
            assertTrue(a.node.handle(cut).isNotEmpty())
            assertTrue(mb.handle(cut).isNotEmpty())
        }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 60_000, "fuzz run finished in time")
    }

    @Test
    fun photosAndFilesArriveWhole() {
        val a = Peer("alice17", mailbox = true); val b = Peer("bob17", mailbox = true)
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "hi")
        waitFor { a.node.contacts().isNotEmpty() && bobsAlice.peerQueue.size == 32 }
        val photo = Codec.random(300_000)
        val progress = CopyOnWriteArrayList<Int>()
        b.node.sendFile(bobsAlice, "../../etc/IMG_\u202E1.jpg", "image/jpeg", photo, "lihat ini") { progress += it }
        waitFor { a.rec.files.isNotEmpty() }
        val f = a.rec.files.single()
        assertTrue(f.data!!.contentEquals(photo))
        assertEquals("IMG_1.jpg", f.name, "no path, no direction override")
        assertEquals("image/jpeg", f.mime)
        assertEquals("lihat ini", a.rec.got.last())
        assertEquals(100, progress.last())
        // Offline: a small file waits in the mailbox; a big one is refused instead of half-sent.
        val alicesBob = a.node.contacts().single()
        b.stop()
        a.node.sendFile(alicesBob, "note.pdf", "application/pdf", Codec.random(100_000), "")
        assertFailsWith<IOException> { a.node.sendFile(alicesBob, "big.zip", "application/zip", Codec.random(2_000_000), "") }
        assertFailsWith<IOException> { a.node.sendFile(alicesBob, "huge.bin", "application/octet-stream", ByteArray(Node.MAX_FILE + 1), "") }
        val b2 = Peer("bob17", b.kv, mailbox = true)
        b2.node.pollMailbox()
        assertEquals(listOf("note.pdf"), b2.rec.files.map { it.name })
    }

    @Test
    fun newCodesNeedNoRepublish() {
        val a = Peer("alice19")
        var publishes = 0
        val before = services[a.node.onion]!!.second!!.size
        assertEquals(Node.SPARE_INVITE_KEYS, before, "spares are published from the start")
        val inv = a.node.createInvite()
        assertTrue(Codec.hex(AuthKey.publicOf(inv.authKey)) in services[a.node.onion]!!.second!!, "the code's key is already published")
        val b = Peer("bob19")
        b.node.acceptInvite(inv, "fast")
        waitFor { a.rec.got.size == 1 }
        assertEquals(listOf("fast"), a.rec.got)
        assertEquals(before, services[a.node.onion]!!.second!!.size, "new contact used a spare key: nothing re-published")
    }

    @Test
    fun demoCodeWorksForManyPeople() {
        val bot = Peer("demobot")
        val code = Invite.decode(bot.node.createInvite(reusable = true).encode())
        val r1 = Peer("reviewer1"); val r2 = Peer("reviewer2")
        val c1 = r1.node.acceptInvite(code, "hello 1")
        val c2 = r2.node.acceptInvite(code, "hello 2")
        waitFor { bot.rec.got.size == 2 }
        assertEquals(setOf("hello 1", "hello 2"), bot.rec.got.toSet())
        bot.node.contacts().forEach { bot.node.sendText(it, "echo") }
        waitFor { r1.rec.got.size == 1 && r2.rec.got.size == 1 }
        assertEquals(listOf("echo"), r1.rec.got)
        assertEquals(listOf("echo"), r2.rec.got)
        assertTrue(c1.id != c2.id)
        // An ordinary code still works once only.
        val once = bot.node.createInvite()
        Peer("reviewer3").node.acceptInvite(once, "x")
        assertFailsWith<IOException> { Peer("reviewer4").node.acceptInvite(once, "y") }
    }

    @Test
    fun streamsAreReused() {
        val a = Peer("alice18"); val b = Peer("bob18")
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "hi")
        waitFor { a.rec.got.size == 1 }
        var dials = 0
        val counting = b.host.dialer
        val n2 = Node(b.kv, b.account, b.rec, Dialer { o, p, i -> dials++; counting.dial(o, p, i) }, b.host, powBits = 8, pollJitterMs = 0, publishDelayMs = 0, retryDelayMs = 5)
        val c = n2.contact(bobsAlice.id)!!
        n2.warm(c)
        repeat(5) { n2.sendText(c, "m$it") }
        waitFor { a.rec.got.size == 6 }
        assertEquals(6, a.rec.got.size)
        assertEquals(1, dials, "one Tor stream for the ping and five messages")
    }

    @Test
    fun paddingBuckets() {
        assertEquals(1024, Padding.pad(ByteArray(10)).size)
        assertEquals(4096, Padding.pad(ByteArray(1021)).size)
        assertEquals("abc", String(Padding.unpad(Padding.pad("abc".toByteArray()))))
    }

    // ------------------------------------------------------------ 1.1 messaging

    private fun pair(): Triple<Peer, Peer, Pair<Contact, Contact>> {
        val a = Peer("peera${Codec.hex(Codec.random(2))}"); val b = Peer("peerb${Codec.hex(Codec.random(2))}")
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "hello")
        waitFor { a.rec.got.isNotEmpty() && a.node.contacts().singleOrNull()?.supportsV11 == true && bobsAlice.supportsV11 }
        return Triple(a, b, a.node.contacts().single() to bobsAlice)
    }

    @Test
    fun capsAreExchanged() {
        val (_, _, c) = pair()
        assertTrue(c.first.supportsV11 && c.second.supportsV11)
    }

    @Test
    fun replyIdTravelsWithTextAndFile() {
        val (a, b, c) = pair()
        val first = a.rec.msgs.single()
        val m = b.node.sendText(c.second, "yes", replyTo = first.id)
        waitFor { a.rec.msgs.size == 2 }
        assertEquals(first.id, a.rec.msgs[1].replyTo)
        assertEquals(m.id, a.rec.msgs[1].id)
        b.node.sendFile(c.second, "x.bin", "application/octet-stream", ByteArray(100) { 1 }, "cap", replyTo = first.id)
        waitFor { a.rec.msgs.size == 3 }
        assertEquals(first.id, a.rec.msgs[2].replyTo)
        b.node.sendText(c.second, "plain")
        waitFor { a.rec.msgs.size == 4 }
        assertEquals(null, a.rec.msgs[3].replyTo)
    }

    @Test
    fun reactionsModifyAndEditTravel() {
        val (a, b, c) = pair()
        val id = a.rec.msgs.single().id
        a.node.sendReaction(c.first, id, "\uD83D\uDC4D", true)
        waitFor { b.rec.reactions.size == 1 }
        assertEquals(listOf("$id \uD83D\uDC4D true"), b.rec.reactions)
        a.node.sendReaction(c.first, id, "\uD83D\uDC4D", false)
        waitFor { b.rec.reactions.size == 2 }
        assertTrue(b.rec.reactions[1].endsWith("false"))
        b.node.sendModify(c.second, Modify.EDIT, id, "new text")
        waitFor { a.rec.modifies.size == 1 }
        assertEquals("2 $id new text", a.rec.modifies[0])
        b.node.sendModify(c.second, Modify.DELETE, id)
        waitFor { a.rec.modifies.size == 2 }
        assertEquals("1 $id ", a.rec.modifies[1])
        assertFailsWith<IllegalArgumentException> { a.node.sendReaction(c.first, id, "x".repeat(17), true) }
    }

    @Test
    fun modifyRules() {
        val t0 = 1_000_000_000L
        assertTrue(Modify.allowed(Modify.EDIT, true, t0, t0 + 23 * 3600_000L))
        assertFalse(Modify.allowed(Modify.EDIT, true, t0, t0 + 25 * 3600_000L), "after 24 h")
        assertFalse(Modify.allowed(Modify.DELETE, false, t0, t0 + 1000), "not the sender's message")
        assertFalse(Modify.allowed(3, true, t0, t0 + 1000), "unknown op")
    }

    @Test
    fun voiceNoteCarriesDurationAndOldPeersGetPlainFile() {
        val (a, b, c) = pair()
        val audio = ByteArray(4000) { (it % 7).toByte() }
        b.node.sendFile(c.second, "voice.m4a", "audio/mp4", audio, "", durationMs = 12_345)
        waitFor { a.rec.files.size == 1 }
        assertEquals("audio/mp4", a.rec.files[0].mime)
        assertEquals(12_345, a.rec.msgs.last().durationMs)
        // A contact that never announced features (1.0) gets no trailing bytes that its strict reader would refuse.
        c.second.caps = 0
        b.node.sendFile(c.second, "voice.m4a", "audio/mp4", audio, "", durationMs = 9_000)
        waitFor { a.rec.files.size == 2 }
        assertEquals(2, a.rec.files.size)
        assertEquals(0, a.rec.msgs.last().durationMs)
        // Reactions and the like are simply not sent to such a contact.
        b.node.sendReaction(c.second, a.rec.msgs.first().id, "\u2764", true)
        Thread.sleep(100)
        assertTrue(a.rec.reactions.isEmpty())
    }

    @Test
    fun receiptsAndTypingAreOffByDefaultAndOffMeansBothWays() {
        val (a, b, c) = pair()
        val id = a.rec.msgs.single().id
        // Both off: nothing sent, nothing shown.
        a.node.sendReceipt(c.first, Receipt.READ, listOf(id))
        a.node.sendTyping(c.first, true)
        Thread.sleep(100)
        assertTrue(b.rec.receipts.isEmpty() && b.rec.typing.isEmpty())
        // Sender on, receiver off: receiver shows nothing.
        a.node.receiptsEnabled = true; a.node.typingEnabled = true
        a.node.warm(c.first)
        a.node.sendReceipt(c.first, Receipt.READ, listOf(id))
        a.node.sendTyping(c.first, true)
        Thread.sleep(200)
        assertTrue(b.rec.receipts.isEmpty() && b.rec.typing.isEmpty(), "receiver with the setting off shows nothing")
        // Both on.
        b.node.receiptsEnabled = true; b.node.typingEnabled = true
        a.node.sendReceipt(c.first, Receipt.DELIVERED, listOf(id))
        a.node.sendTyping(c.first, true)
        waitFor { b.rec.receipts.size == 1 && b.rec.typing.size == 1 }
        assertEquals(listOf("1 $id"), b.rec.receipts)
        assertEquals(listOf(true), b.rec.typing)
    }

    @Test
    fun typingNeverUsesTheMailbox() {
        val a = Peer("peertypa", mailbox = true); val b = Peer("peertypb", mailbox = true)
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "hi")
        waitFor { a.node.contacts().singleOrNull()?.supportsV11 == true && bobsAlice.supportsV11 && bobsAlice.peerMailbox.isNotEmpty() }
        val c = a.node.contacts().single()
        a.node.typingEnabled = true
        a.node.warm(c)
        b.stop() // offline now
        runCatching { a.node.sendTyping(c, true) } // must neither throw to the mailbox nor leave anything there
        val q = MailboxProtocol.queueId(bobsAlice.myQueueSecret)
        assertEquals(0, mailboxStore.list(Codec.hex(q), 10, 1_000_000).size)
    }

    @Test
    fun floodOfEphemeralTypesIsDroppedAndTextStillArrives() {
        val (a, b, c) = pair()
        val id = a.rec.msgs.single().id
        repeat(Node.MAX_EPHEMERAL_PER_MIN + 30) { a.node.sendReaction(c.first, id, "\uD83D\uDC4D", it % 2 == 0) }
        waitFor { b.rec.reactions.size >= Node.MAX_EPHEMERAL_PER_MIN }
        Thread.sleep(200)
        assertEquals(Node.MAX_EPHEMERAL_PER_MIN, b.rec.reactions.size)
        a.node.sendText(c.first, "still here")
        waitFor { b.rec.got.contains("still here") }
        assertTrue(b.rec.got.contains("still here"))
    }


    @Test
    fun profilePhotoSetRemoveAndLimits() {
        val (a, b, c) = pair()
        val jpeg = ByteArray(30_000) { (it % 251).toByte() }
        a.node.sendAvatar(c.first, jpeg)
        waitFor { b.rec.avatars.size == 1 }
        assertEquals(30_000, b.rec.avatars[0])
        a.node.sendAvatar(c.first, null)
        waitFor { b.rec.avatars.size == 2 }
        assertEquals(-1, b.rec.avatars[1], "removal")
        // The sender refuses an oversize photo.
        assertFailsWith<IllegalArgumentException> { a.node.sendAvatar(c.first, ByteArray(Node.MAX_AVATAR + 1)) }
        // A hand-made oversize frame and one with a wrong hash are ignored by the receiver (answered OK, nothing stored).
        val big = ByteArray(Node.MAX_AVATAR + 1)
        a.node.sendInner(c.first, Out().u8(13).raw(sha256(big)).bytes(big).done())
        a.node.sendInner(c.first, Out().u8(13).raw(ByteArray(32)).bytes(jpeg).done())
        // A new contact gets our photo right after the handshake.
        a.node.avatar = jpeg
        val n = Peer("peerc${Codec.hex(Codec.random(2))}")
        val ns = n.node.acceptInvite(a.node.createInvite(), "hi")
        waitFor { n.rec.avatars.isNotEmpty() }
        assertEquals(listOf(30_000), n.rec.avatars)
        Thread.sleep(100)
        assertEquals(2, b.rec.avatars.size, "bad frames produced no event")
        // Blocked contacts' photos are dropped like everything else.
        b.node.setBlocked(c.second, true)
        runCatching { a.node.sendAvatar(c.first, jpeg) } // refused by the blocked side
        Thread.sleep(150)
        assertEquals(2, b.rec.avatars.size)
    }


    @Test
    fun redeemsAMailboxInviteMadeByAnIphone() {
        val a = Peer("peerivta", mailbox = true); val b = Peer("peerivtb", mailbox = true)
        // Stand-in for the iPhone inviter: a sealed bundle in a queue on the mailbox. Node A supplies the bundle
        // and answers the HELLO, using the same secret as the seal key.
        val inv2 = a.node.createInvite()
        val bundle = In(a.node.handle(Out().u8(2).u8(1).raw(inv2.secret).done())).also { it.u8() }.bytes()
        val q = MailboxProtocol.queueId(Codec.random(32))
        mailboxStore.create(Codec.hex(q))
        mailboxStore.put(Codec.hex(q), Out().u8(2).raw(ByteArray(16)).u8(0).bytes(MailboxInvite.seal(inv2.secret, q, bundle)).done())
        val code = MailboxInvite(mailboxOnion, q, inv2.secret, a.node.identityKey.serialize()).encode()
        assertEquals(MailboxInvite.VERSION, MailboxInvite.versionOf(code))
        val c = b.node.acceptMailboxInvite(MailboxInvite.decode(code), "hello from android")
        val envelopes = mailboxStore.list(Codec.hex(q), 10)
        assertEquals(2, envelopes.size, "bundle still there, HELLO added")
        assertEquals(0x80, a.node.handle(envelopes[1].second)[0].toInt() and 0xFF)
        waitFor { a.rec.got.contains("hello from android") && c.name == "peerivta" }
        assertEquals("peerivta", c.name)
        a.node.sendText(a.node.contacts().single(), "welcome")
        waitFor { b.rec.got.contains("welcome") }
        assertTrue(b.rec.got.contains("welcome"))
        // A tampered seal, a wrong identity and an empty queue are refused cleanly.
        val wrong = MailboxInvite(mailboxOnion, q, Codec.random(32), a.node.identityKey.serialize())
        assertFailsWith<IOException> { Peer("peerivtc", mailbox = true).node.acceptMailboxInvite(wrong, "x") }
        val empty = MailboxInvite(mailboxOnion, MailboxProtocol.queueId(Codec.random(32)), inv2.secret, a.node.identityKey.serialize())
        assertFailsWith<IOException> { Peer("peerivtd", mailbox = true).node.acceptMailboxInvite(empty, "x") }
    }


    @Test
    fun longPollReturnsAsSoonAsAMessageLands() {
        val a = Peer("peerwaita", mailbox = true); val b = Peer("peerwaitb", mailbox = true)
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "hi")
        waitFor { a.node.contacts().singleOrNull()?.queueReady == true && bobsAlice.peerMailbox.isNotEmpty() }
        a.stop() // offline: b must leave the message in the mailbox
        val t0 = System.nanoTime()
        var got = -2
        val waiter = Thread { got = a.node.waitMailbox(10_000) }.also { it.start() }
        Thread.sleep(300)
        b.node.sendText(bobsAlice, "long poll")
        waiter.join(8_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(1, got)
        assertTrue(a.rec.got.contains("long poll"))
        assertTrue(ms < 5_000, "answered when the message landed, not at the timeout ($ms ms)")
        assertEquals(0, a.node.waitMailbox(300), "empty at timeout")
    }


    @Test
    fun creatingAnInviteNeverWaitsForPublishing() {
        val kv = MemoryKv()
        var slow = false
        val host = object : OnionHost {
            override fun create(clients: List<ByteArray>) = "slowhost".padEnd(56, 'h') to "key"
            override fun publish(onion: String, key: String, clients: List<ByteArray>) { if (slow) Thread.sleep(4_000) }
            override fun unpublish(onion: String) {}
            override fun clientAuth(onion: String, privateKey: ByteArray?) {}
        }
        val node = Node(kv, Account.create().also { it.save(kv) }, object : NodeEvents {}, Dialer { _, _, _ -> throw IOException("offline") }, host,
            powBits = 8, pollJitterMs = 0, publishDelayMs = 0, retryDelayMs = 5)
        node.start()
        slow = true // from now on, publishing takes 4 s
        val t0 = System.nanoTime()
        repeat(9) { node.createInvite() } // uses up the 5 spare keys, then needs new ones
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 1_500, "9 invites took $ms ms; publishing must happen in the background")
        // The pre-built invite is simply read afterwards.
        val t1 = System.nanoTime()
        assertTrue(node.prebuildInvite() != null)
        assertTrue((System.nanoTime() - t1) / 1_000_000 < 200)
    }


    @Test
    fun linkPreviewTravelsWithTextAndIsCheckedByTheReceiver() {
        val (a, b, c) = pair()
        assertTrue(c.second.supportsLinkPreview)
        val thumb = ByteArray(5000) { it.toByte() }
        val url = "https://example.org/page"
        b.node.sendText(c.second, "look $url", preview = LinkPreview(url, "Example page", "example.org", thumb))
        waitFor { a.rec.msgs.size == 2 }
        val pv = a.rec.msgs.last().preview!!
        assertEquals("Example page", pv.title); assertEquals("example.org", pv.site); assertTrue(thumb.contentEquals(pv.thumb))
        // A preview whose link is not in the text is dropped by the sender (and would be by the receiver).
        b.node.sendText(c.second, "no link here", preview = LinkPreview(url, "x", "y", ByteArray(0)))
        waitFor { a.rec.msgs.size == 3 }
        assertEquals(null, a.rec.msgs.last().preview)
        // http and oversize are refused when decoding.
        assertEquals(null, LinkPreview.decode(LinkPreview("http://example.org/", "t", "s", ByteArray(0)).encode()))
        assertEquals(null, LinkPreview.decode(Out().u8(1).str("https://example.org/").str("t").str("s").bytes(ByteArray(LinkPreview.MAX_THUMB + 1)).done()))
        // Never to a contact without the capability, and never in private mode.
        c.second.caps = Node.CAP_V11
        b.node.sendText(c.second, "again $url", preview = LinkPreview(url, "t", "s", ByteArray(0)))
        waitFor { a.rec.msgs.size == 4 }
        assertEquals(null, a.rec.msgs.last().preview)
    }

    // ------------------------------------------------------------ 1.2: view once, timers, private mode

    /** Records every write, so a test can prove what did (not) reach storage. */
    private class RecordingKv(private val inner: Kv = MemoryKv()) : Kv by inner {
        val puts = CopyOnWriteArrayList<Triple<String, String, ByteArray>>()
        override fun put(ns: String, key: String, value: ByteArray) { puts += Triple(ns, key, value); inner.put(ns, key, value) }
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) { for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer; return true }
        return false
    }

    @Test
    fun viewOnceAndTimerTravelAsTrailingFields() {
        val (a, b, c) = pair()
        assertTrue(c.first.supportsViewOnce && c.second.supportsViewOnce && c.first.supportsPrivate)
        val photo = ByteArray(3000) { (it * 7).toByte() }
        val sent = b.node.sendFile(c.second, "p.jpg", "image/jpeg", photo, "", flags = Node.FLAG_VIEW_ONCE, timerSec = 0)
        waitFor { a.rec.files.size == 1 }
        val got = a.rec.msgs.last()
        assertTrue(got.viewOnce && !got.isPrivate)
        assertEquals(sent.id, got.id)
        assertTrue(photo.contentEquals(got.file!!.data!!))
        // A per-message timer on text and on a file.
        b.node.sendText(c.second, "tick", timerSec = 60)
        waitFor { a.rec.msgs.size == 3 }
        assertEquals(60, a.rec.msgs.last().timerSec); assertEquals(0, a.rec.msgs.last().flags)
        b.node.sendFile(c.second, "q.jpg", "image/jpeg", photo, "cap", replyTo = a.rec.msgs.first().id, timerSec = 10)
        waitFor { a.rec.files.size == 2 }
        assertEquals(10, a.rec.msgs.last().timerSec); assertEquals(a.rec.msgs.first().id, a.rec.msgs.last().replyTo)
        // View once is only for photos and videos: on any other file the flag is dropped by the sender.
        b.node.sendFile(c.second, "n.txt", "text/plain", "x".toByteArray(), "", flags = Node.FLAG_VIEW_ONCE)
        waitFor { a.rec.files.size == 3 }
        assertFalse(a.rec.msgs.last().viewOnce)
        // View once on a text is ignored by the receiver.
        b.node.sendInner(c.second, Out().u8(2).raw(Codec.random(16)).i64(1).i32(0).str("t").bytes(ByteArray(0)).u8(1).i32(0).done())
        waitFor { a.rec.msgs.size == 5 }
        assertFalse(a.rec.msgs.last().viewOnce)
    }

    @Test
    fun featuresAreRefusedForContactsThatDidNotAnnounceThem() {
        val (_, b, c) = pair()
        c.second.caps = Node.CAP_V11 // an older 1.1 app
        val e = assertFailsWith<IOException> { b.node.sendFile(c.second, "p.jpg", "image/jpeg", ByteArray(100), "", flags = Node.FLAG_VIEW_ONCE) }
        assertEquals("Update needed", e.message)
        assertEquals("Update needed", assertFailsWith<IOException> { b.node.sendText(c.second, "x", timerSec = 10) }.message)
        assertEquals("Update needed", assertFailsWith<IOException> { b.node.sendText(c.second, "x", flags = Node.FLAG_PRIVATE) }.message)
        assertEquals("Update needed", assertFailsWith<IOException> { b.node.setPrivateMode(c.second, true) }.message)
        assertFalse(b.node.isPrivate(c.second))
    }

    @Test
    fun openedReceiptIsHonouredEvenWithReceiptsOff() {
        val (a, b, c) = pair()
        assertFalse(a.node.receiptsEnabled || b.node.receiptsEnabled)
        val id = a.rec.msgs.single().id
        a.node.sendOpened(c.first, id)
        waitFor { b.rec.receipts.size == 1 }
        assertEquals(listOf("3 $id"), b.rec.receipts)
        // Reading receipts stay silent.
        a.node.sendReceipt(c.first, Receipt.READ, listOf(id))
        Thread.sleep(100)
        assertEquals(1, b.rec.receipts.size)
    }

    @Test
    fun privateModeKeepsMessagesAndFilePartsOutOfStorage() {
        val akv = RecordingKv()
        val a = Peer("privapeer", akv); val b = Peer("privbpeer")
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "hello")
        waitFor { a.rec.got.isNotEmpty() && a.node.contacts().singleOrNull()?.supportsPrivate == true && bobsAlice.supportsPrivate }
        val c = a.node.contacts().single()
        val before = akv.puts.size
        b.node.setPrivateMode(bobsAlice, true)
        waitFor { a.node.isPrivate(c) }
        assertTrue(a.node.isPrivate(c) && b.node.isPrivate(bobsAlice))
        assertEquals(listOf(Node.CONTROL_ON), a.rec.controls)
        val marker = "marker-text-4f1c9a"
        b.node.sendText(bobsAlice, marker, flags = Node.FLAG_PRIVATE)
        val photo = ByteArray(120_000) { ((it * 31) xor 0x5a).toByte() } // several parts
        b.node.sendFile(bobsAlice, "p.jpg", "image/jpeg", photo, "capt-9e77", flags = Node.FLAG_PRIVATE)
        waitFor { a.rec.files.size == 1 && a.rec.msgs.size == 3 }
        val text = a.rec.msgs.first { it.text == marker }
        assertTrue(text.isPrivate)
        assertTrue(photo.contentEquals(a.rec.msgs.last().file!!.data!!), "media arrives intact, in memory")
        // Nothing but ratchet state was written on the receiving side: no parts, no file description, no plaintext.
        val writes = akv.puts.drop(before)
        assertTrue(writes.none { it.first.startsWith("fpart.") || it.first.startsWith("fmeta.") }, "no pending file data on disk: ${writes.map { it.first }.distinct()}")
        assertTrue(writes.none { contains(it.third, marker.toByteArray()) || contains(it.third, "capt-9e77".toByteArray()) || contains(it.third, photo.copyOf(2000)) })
        // The sender's node stored nothing of it either (its own storage only gets ratchet state).
        assertTrue(b.kv.let { kv -> listOf("fpart.", "fmeta.").all { p -> kv.list(p + bobsAlice.id).isEmpty() } })
        // Wipe from the other side reaches the app; leaving the mode too.
        b.node.sendWipe(bobsAlice)
        waitFor { a.rec.controls.contains(Node.CONTROL_WIPE) }
        assertTrue(a.rec.controls.contains(Node.CONTROL_WIPE))
        b.node.setPrivateMode(bobsAlice, false)
        waitFor { !a.node.isPrivate(c) }
        assertFalse(a.node.isPrivate(c))
        assertTrue(a.rec.controls.contains(Node.CONTROL_OFF))
        // Back to normal: an ordinary message is stored the usual way and not flagged.
        b.node.sendText(bobsAlice, "plain")
        waitFor { a.rec.got.contains("plain") }
        assertFalse(a.rec.msgs.last().isPrivate)
    }

    @Test
    fun privateMessagesNeverGoThroughTheMailbox() {
        val a = Peer("privmaila", mailbox = true); val b = Peer("privmailb", mailbox = true)
        val bobsAlice = b.node.acceptInvite(a.node.createInvite(), "hi")
        waitFor { a.node.contacts().singleOrNull()?.supportsPrivate == true && bobsAlice.supportsPrivate && bobsAlice.peerMailbox.isNotEmpty() }
        val c = a.node.contacts().single()
        a.stop() // alice is offline; her mailbox exists
        val ordinary = mailboxStore
        assertFailsWith<IOException> { b.node.setPrivateMode(bobsAlice, true) }
        assertFailsWith<IOException> { b.node.sendText(bobsAlice, "secret", flags = Node.FLAG_PRIVATE) }
        // An ordinary message still falls back to the mailbox.
        b.node.sendText(bobsAlice, "ordinary")
        assertTrue(ordinary.list(Codec.hex(MailboxProtocol.queueId(c.myQueueSecret)), 10).size >= 1)
    }
}

class RecoveryTest {
    @Test
    fun bip39Vectors() {
        assertEquals(List(11) { "abandon" } + "about", RecoveryPhrase.fromEntropy(ByteArray(16)))
        val e = Codec.unhex("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f")
        assertEquals("legal winner thank year wave sausage worth useful legal winner thank yellow".split(" "), RecoveryPhrase.fromEntropy(e))
        assertTrue(RecoveryPhrase.toEntropy(RecoveryPhrase.parse(" Legal winner thank year wave sausage worth useful legal winner thank YELLOW ")).contentEquals(e))
    }

    @Test
    fun badPhrasesAreRefused() {
        val ok = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        assertFailsWith<IOException> { RecoveryPhrase.toEntropy(RecoveryPhrase.parse(ok.replace("yellow", "year"))) }
        assertFailsWith<IOException> { RecoveryPhrase.toEntropy(RecoveryPhrase.parse(ok.replace("yellow", "notaword"))) }
        assertFailsWith<IOException> { RecoveryPhrase.toEntropy(RecoveryPhrase.parse("legal winner")) }
    }

    @Test
    fun phraseRestoresSameIdentity() {
        val a = Account.create()
        val b = Account.restore(a.phrase)
        assertTrue(a.identity.publicKey.serialize().contentEquals(b.identity.publicKey.serialize()))
        val kv = MemoryKv(); a.save(kv)
        assertTrue(Account.load(kv)!!.entropy.contentEquals(a.entropy))
    }
}
