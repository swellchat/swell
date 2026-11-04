package com.jijilabsdigital.swell.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Swell Bell: op 0x18, tagged PUT, the tag log (docs/PROTOCOL.md 6a). */
class BellTest {
    private fun hex(s: String) = Codec.unhex(s)
    private fun tag(i: Int) = ByteArray(12) { (i + it).toByte() }.also { it[0] = (i shr 8).toByte(); it[1] = i.toByte() }

    /** Paced like real traffic, so the disk writer never falls a whole (tiny test) ring behind. */
    private fun fill(log: BellLog, n: Int) = repeat(n) { log.append(tag(it)); if (it % 4 == 3) Thread.sleep(5) }

    private class Reply(val cursor: Long, val lost: Boolean, val tags: List<ByteArray>, val raw: ByteArray)
    private fun parse(b: ByteArray): Reply {
        val r = In(b)
        assertEquals(MailboxProtocol.R_BELL, r.u8())
        val c = r.i64(); val lost = r.u8() == 1; val n = r.i32()
        val tags = List(n) { r.raw(12) }
        r.end()
        return Reply(c, lost, tags, b)
    }
    private fun MailboxServer.bell(cursor: Long, ms: Int = 0) = handle(Out().u8(2).u8(MailboxProtocol.BELL).i64(cursor).i32(ms).done())
    private fun MailboxServer.create(secret: ByteArray) {
        val q = MailboxProtocol.queueId(secret)
        assertEquals(0x80, In(handle(Out().u8(2).u8(MailboxProtocol.CREATE).raw(secret).i64(MailboxProtocol.solvePow(q, 8)).done())).u8())
    }
    private fun MailboxServer.put(q: ByteArray, env: ByteArray, tag: ByteArray? = null): Int {
        val o = Out().u8(2).u8(MailboxProtocol.PUT).raw(q).bytes(env)
        if (tag != null) o.bytes(tag)
        return In(handle(o.done())).u8()
    }
    private fun server(log: BellLog?) = MailboxServer(MemoryMailboxStore(), powBits = 8, bell = log)

    @Test
    fun tagVectors() {
        val k1 = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val k2 = ByteArray(32) { 0x42 }
        assertContentEquals(hex("00000000a86acbcbc29b8fa8"), Bell.tag(k1, hex("00000000")))
        assertContentEquals(hex("01020304e3ba74ad60769167"), Bell.tag(k1, hex("01020304")))
        assertContentEquals(hex("deadbeef2759079ac61b2bf6"), Bell.tag(k2, hex("deadbeef")))
        assertTrue(Bell.matches(k1, hex("01020304e3ba74ad60769167")))
        assertFalse(Bell.matches(k2, hex("01020304e3ba74ad60769167")))
        assertFalse(Bell.matches(k1, hex("01020304e3ba74ad60769168")))
        assertFalse(Bell.matches(k1, ByteArray(11)))
        val t = Bell.tag(k2)
        assertEquals(listOf(1), Bell.matching(listOf(k1, k2, Bell.newKey()), t))
    }

    @Test
    fun cursorFromNowThenTagsThenNothing() {
        val log = BellLog(null, coalesceMs = 0)
        val s = server(log); val sec = Codec.random(32); val q = MailboxProtocol.queueId(sec); s.create(sec)
        val now = parse(s.bell(-1))
        assertEquals(0L, now.cursor); assertFalse(now.lost); assertEquals(0, now.tags.size)
        assertEquals(0x80, s.put(q, byteArrayOf(1), tag(1)))
        assertEquals(0x80, s.put(q, byteArrayOf(2), tag(2)))
        val a = parse(s.bell(0))
        assertEquals(2L, a.cursor); assertFalse(a.lost)
        assertContentEquals(tag(1), a.tags[0]); assertContentEquals(tag(2), a.tags[1])
        val again = parse(s.bell(2, 0)) // at the end, no wait
        assertEquals(2L, again.cursor); assertEquals(0, again.tags.size)
        log.close()
    }

    @Test
    fun identicalBytesForTheSameCursor() {
        val log = BellLog(null, coalesceMs = 0)
        repeat(5) { log.append(tag(it)) }
        assertContentEquals(log.listen(0, 0), log.listen(0, 0))
        assertContentEquals(log.listen(2, 0), log.listen(2, 0))
        log.close()
    }

    @Test
    fun parkedListenerWakesAndAllGetTheSameAnswer() {
        val log = BellLog(null, coalesceMs = 50)
        val results = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        val started = CountDownLatch(20)
        val ts = List(20) { Thread { started.countDown(); results += log.listen(0, 5_000)!! }.apply { start() } }
        started.await(); Thread.sleep(200)
        assertEquals(20, log.parked())
        val t0 = System.nanoTime()
        log.append(tag(7))
        ts.forEach { it.join(3_000) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(20, results.size)
        assertTrue(ms in 40..1_000, "wake after the coalescing delay, was $ms ms")
        assertEquals(1, results.map { Codec.hex(it) }.toSet().size)
        assertEquals(0, log.parked())
        log.close()
    }

    @Test
    fun burstIsCoalescedIntoOneWake() {
        val log = BellLog(null, coalesceMs = 150)
        var reply: ByteArray? = null
        val t = Thread { reply = log.listen(0, 5_000) }.apply { start() }
        Thread.sleep(100)
        repeat(10) { log.append(tag(it)); Thread.sleep(5) }
        t.join(3_000)
        assertEquals(10, parse(reply!!).tags.size, "ten tags in one answer")
        log.close()
    }

    @Test
    fun timeoutAnswersEmptyWithSameCursor() {
        val log = BellLog(null)
        val t0 = System.nanoTime()
        val r = parse(log.listen(0, 300)!!)
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 250)
        assertEquals(0L, r.cursor); assertEquals(0, r.tags.size); assertFalse(r.lost)
        log.close()
    }

    @Test
    fun cursorTooOldAndCursorFromTheFutureAreLost() {
        val log = BellLog(null, coalesceMs = 0, ringSize = 8, segmentEntries = 8)
        repeat(20) { log.append(tag(it)) }
        val old = parse(log.listen(0, 0)!!)
        assertTrue(old.lost); assertEquals(20L, old.cursor); assertEquals(0, old.tags.size)
        val edge = parse(log.listen(12, 0)!!) // oldest kept = 12
        assertFalse(edge.lost); assertEquals(8, edge.tags.size); assertContentEquals(tag(12), edge.tags[0])
        val future = parse(log.listen(500, 0)!!)
        assertTrue(future.lost); assertEquals(20L, future.cursor)
        log.close()
    }

    @Test
    fun repliesAreCappedAndChain() {
        val log = BellLog(null, coalesceMs = 0)
        repeat(Bell.MAX_TAGS_PER_REPLY + 10) { log.append(tag(it)) }
        val a = parse(log.listen(0, 0)!!)
        assertEquals(Bell.MAX_TAGS_PER_REPLY, a.tags.size); assertEquals(Bell.MAX_TAGS_PER_REPLY.toLong(), a.cursor)
        val b = parse(log.listen(a.cursor, 0)!!)
        assertEquals(10, b.tags.size); assertContentEquals(tag(Bell.MAX_TAGS_PER_REPLY), b.tags[0])
        log.close()
    }

    @Test
    fun wrongTagSizeRejectsTheFrameAndStoresNothing() {
        val log = BellLog(null, coalesceMs = 0)
        val s = server(log); val sec = Codec.random(32); val q = MailboxProtocol.queueId(sec); s.create(sec)
        for (bad in listOf(0, 1, 11, 13, 32)) assertEquals(0xFF, s.put(q, byteArrayOf(9), ByteArray(bad)), "size $bad")
        // trailing junk after a good tag
        val junk = handle(s, Out().u8(2).u8(MailboxProtocol.PUT).raw(q).bytes(byteArrayOf(9)).bytes(tag(1)).u8(0).done())
        assertEquals(0xFF, In(junk).u8())
        val g = In(s.handle(Out().u8(2).u8(MailboxProtocol.GET).i32(1).raw(sec).done()))
        g.u8(); assertEquals(0, g.i32(), "nothing stored")
        assertEquals(0L, log.end())
        log.close()
    }
    private fun handle(s: MailboxServer, f: ByteArray) = s.handle(f)

    @Test
    fun tagOnlyAfterTheEnvelopeIsStored() {
        val log = BellLog(null, coalesceMs = 0)
        val s = server(log)
        assertEquals(0xFF, s.put(Codec.random(32), byteArrayOf(1), tag(1))) // no such queue
        assertEquals(0L, log.end())
        log.close()
    }

    @Test
    fun serverWithoutBellActsLikeAnOldServer() {
        val s = server(null); val sec = Codec.random(32); val q = MailboxProtocol.queueId(sec); s.create(sec)
        assertEquals(0xFF, In(s.bell(-1)).u8())
        assertEquals(0xFF, s.put(q, byteArrayOf(1), tag(1)))
        assertEquals(0x80, s.put(q, byteArrayOf(1)))
    }

    @Test
    fun oldClientsStillWork() {
        val log = BellLog(null, coalesceMs = 0)
        val s = server(log); val sec = Codec.random(32); val q = MailboxProtocol.queueId(sec); s.create(sec)
        assertEquals(0x80, s.put(q, byteArrayOf(5, 5))) // PUT without a tag
        assertEquals(0L, log.end(), "no tag, nothing logged")
        val g = In(s.handle(Out().u8(2).u8(MailboxProtocol.GET).i32(1).raw(sec).done()))
        assertEquals(0x82, g.u8()); assertEquals(1, g.i32())
        // WAIT still wakes on a tagged and an untagged PUT
        val w = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val t = Thread { w.set(s.handle(Out().u8(2).u8(MailboxProtocol.WAIT).i32(5_000).i32(1).raw(sec).done())) }.apply { start() }
        Thread.sleep(150)
        assertEquals(0x80, s.put(q, byteArrayOf(6), tag(3)))
        t.join(3_000)
        val r = In(w.get()); assertEquals(0x82, r.u8()); assertTrue(r.i32() >= 1)
        log.close()
    }

    @Test
    fun cursorsAndTagsSurviveARestart() {
        val dir = Files.createTempDirectory("bell").toFile()
        try {
            val a = BellLog(dir, coalesceMs = 0, ringSize = 16, segmentEntries = 10)
            fill(a, 25)
            a.close()
            val b = BellLog(dir, coalesceMs = 0, ringSize = 16, segmentEntries = 10)
            assertEquals(25L, b.end())
            b.append(tag(25))
            assertEquals(26L, b.end(), "cursor keeps counting up")
            val old = parse(b.listen(3, 0)!!) // older than the ring: served from the segment files
            assertFalse(old.lost); assertEquals(23, old.tags.size); assertContentEquals(tag(3), old.tags[0]); assertContentEquals(tag(25), old.tags.last())
            b.close()
            val c = BellLog(dir, coalesceMs = 0, ringSize = 16, segmentEntries = 10)
            assertEquals(26L, c.end())
            assertContentEquals(tag(0), parse(c.listen(0, 0)!!).tags[0])
            c.close()
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun aPartialRecordAtTheEndIsDropped() {
        val dir = Files.createTempDirectory("bell").toFile()
        try {
            val a = BellLog(dir, coalesceMs = 0); fill(a, 3); a.close()
            File(dir, dir.list()!!.first { it.endsWith(".seg") }).appendBytes(byteArrayOf(1, 2, 3))
            val b = BellLog(dir, coalesceMs = 0)
            assertEquals(3L, b.end()); b.append(tag(3)); assertEquals(4L, b.end())
            assertContentEquals(tag(3), parse(b.listen(3, 0)!!).tags[0])
            b.close()
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun sizeCapDropsTheOldestSegments() {
        val dir = Files.createTempDirectory("bell").toFile()
        try {
            val log = BellLog(dir, maxBytes = 12L * 25, coalesceMs = 0, ringSize = 8, segmentEntries = 10)
            fill(log, 100)
            Thread.sleep(500); log.trim()
            assertTrue(log.diskBytes() <= 12L * 25 + 12 * 10, "disk ${log.diskBytes()}")
            assertTrue(log.oldest() > 0)
            assertTrue(parse(log.listen(0, 0)!!).lost)
            assertFalse(parse(log.listen(log.oldest(), 0)!!).lost)
            log.close()
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun ageCapDropsOldSegments() {
        val dir = Files.createTempDirectory("bell").toFile()
        var clock = System.currentTimeMillis()
        try {
            val log = BellLog(dir, coalesceMs = 0, ringSize = 64, segmentEntries = 10, now = { clock })
            fill(log, 35)
            Thread.sleep(500)
            clock += Bell.RETENTION_MS + 60_000
            dir.listFiles()!!.sortedBy { it.name }.dropLast(1).forEach { it.setLastModified(clock - Bell.RETENTION_MS - 1000) }
            log.trim()
            assertEquals(30L, log.oldest(), "only the newest segment is left")
            log.close()
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun listenerCapRejects() {
        val log = BellLog(null, maxListeners = 2)
        val ts = List(2) { Thread { log.listen(0, 1_500) }.apply { start() } }
        Thread.sleep(200)
        assertEquals(null, log.listen(0, 100))
        ts.forEach { it.join() }
        assertEquals(0, log.parked())
        log.close()
    }

    @Test
    fun appendDoesNotWaitForTheDisk() {
        val dir = Files.createTempDirectory("bell").toFile()
        try {
            val log = BellLog(dir, coalesceMs = 100)
            val t0 = System.nanoTime()
            repeat(50_000) { log.append(tag(it)) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms < 1_000, "50k appends took $ms ms")
            log.close()
        } finally { dir.deleteRecursively() }
    }
}
