package com.jijilabsdigital.swell.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Mailbox op 0x16 PEEK (docs/IOS-PLAN.md section 10): read the oldest envelope by queue id, delete nothing. */
class PeekTest {
    private fun srv(now: () -> Long = System::currentTimeMillis) = MailboxServer(MemoryMailboxStore(), powBits = 8, now = now)
    private fun MailboxServer.create(secret: ByteArray) {
        val q = MailboxProtocol.queueId(secret)
        assertEquals(0x80, In(handle(Out().u8(2).u8(MailboxProtocol.CREATE).raw(secret).i64(MailboxProtocol.solvePow(q, 8)).done())).u8())
    }
    private fun MailboxServer.put(q: ByteArray, b: ByteArray) =
        assertEquals(0x80, In(handle(Out().u8(2).u8(MailboxProtocol.PUT).raw(q).bytes(b).done())).u8())
    private fun MailboxServer.peek(q: ByteArray) = handle(Out().u8(2).u8(MailboxProtocol.PEEK).raw(q).done())

    @Test
    fun peekReturnsOldestAndDeletesNothing() {
        val s = srv(); val secret = Codec.random(32); val q = MailboxProtocol.queueId(secret)
        s.create(secret)
        s.put(q, byteArrayOf(1, 1, 1)); s.put(q, byteArrayOf(2, 2, 2))
        repeat(3) {
            val r = In(s.peek(q))
            assertEquals(MailboxProtocol.R_LIST, r.u8()); assertEquals(1, r.i32())
            assertEquals(0, r.i32()); r.str(64); assertContentEquals(byteArrayOf(1, 1, 1), r.bytes(1024)); r.end()
        }
        val g = In(s.handle(Out().u8(2).u8(MailboxProtocol.GET).i32(1).raw(secret).done()))
        assertEquals(0x82, g.u8()); assertEquals(2, g.i32(), "PEEK left both envelopes in place")
    }

    @Test
    fun unknownQueueIsAnEmptyListNotAReject() {
        val r = In(srv().peek(Codec.random(32)))
        assertEquals(MailboxProtocol.R_LIST, r.u8()); assertEquals(0, r.i32()); r.end()
    }

    @Test
    fun peekIsRateLimitedAndResetsEachMinute() {
        var t = 1_000_000_000_000L
        val s = srv { t }
        val q = Codec.random(32)
        repeat(MailboxProtocol.MAX_PEEKS_PER_MIN) { assertEquals(MailboxProtocol.R_LIST, In(s.peek(q)).u8()) }
        assertEquals(0xFF, In(s.peek(q)).u8(), "601st PEEK in one minute")
        t += 61_000
        assertEquals(MailboxProtocol.R_LIST, In(s.peek(q)).u8())
    }

    @Test
    fun malformedPeekIsRejectedAndOldOpsStillWork() {
        val s = srv()
        assertEquals(0xFF, In(s.handle(byteArrayOf(2, 0x16, 1, 2, 3))).u8())
        assertEquals(0xFF, In(s.handle(Out().u8(2).u8(MailboxProtocol.PEEK).raw(Codec.random(32)).u8(0).done())).u8(), "trailing bytes")
        assertEquals(0x11, MailboxProtocol.PUT); assertEquals(0x12, MailboxProtocol.GET); assertEquals(0x13, MailboxProtocol.ACK)
        assertEquals(0x14, MailboxProtocol.CREATE); assertEquals(0x15, MailboxProtocol.CREDS); assertEquals(0x16, MailboxProtocol.PEEK)
    }
}

/** Mailbox op 0x17 WAIT: long poll for one queue. */
class WaitTest {
    private val srv = MailboxServer(MemoryMailboxStore(), powBits = 8)
    private fun create(secret: ByteArray) {
        val q = MailboxProtocol.queueId(secret)
        assertEquals(0x80, In(srv.handle(Out().u8(2).u8(MailboxProtocol.CREATE).raw(secret).i64(MailboxProtocol.solvePow(q, 8)).done())).u8())
    }
    private fun put(q: ByteArray, b: ByteArray) = srv.handle(Out().u8(2).u8(MailboxProtocol.PUT).raw(q).bytes(b).done())
    private fun waitReq(vararg secret: ByteArray, ms: Int) = Out().u8(2).u8(MailboxProtocol.WAIT).i32(ms).i32(secret.size).also { o -> secret.forEach { o.raw(it) } }.done()
    private fun count(resp: ByteArray): Int { val r = In(resp); assertEquals(MailboxProtocol.R_LIST, r.u8()); return r.i32() }

    @Test
    fun answersImmediatelyWhenMailIsWaiting() {
        val secret = Codec.random(32); create(secret); put(MailboxProtocol.queueId(secret), byteArrayOf(1))
        val t = System.nanoTime()
        assertEquals(1, count(srv.handle(waitReq(secret, ms = 20_000))))
        assertTrue((System.nanoTime() - t) / 1_000_000 < 2_000)
    }

    @Test
    fun emptyAtTimeoutAndWakesOnPut() {
        val secret = Codec.random(32); create(secret)
        val t0 = System.nanoTime()
        assertEquals(0, count(srv.handle(waitReq(secret, ms = 300))))
        assertTrue((System.nanoTime() - t0) / 1_000_000 in 250..2_000)
        var got = -1; var tookMs = 0L
        val th = Thread { val t = System.nanoTime(); got = count(srv.handle(waitReq(secret, ms = 20_000))); tookMs = (System.nanoTime() - t) / 1_000_000 }
        th.start(); Thread.sleep(300)
        put(MailboxProtocol.queueId(secret), byteArrayOf(7))
        th.join(5_000)
        assertEquals(1, got); assertTrue(tookMs in 250..3_000, "woken by PUT, not by the 20 s timeout ($tookMs ms)")
        // WAIT deletes nothing
        assertEquals(1, In(srv.handle(Out().u8(2).u8(MailboxProtocol.GET).i32(1).raw(secret).done())).also { it.u8() }.i32())
    }

    @Test
    fun newerWaitReplacesOlderAndWaitNeedsTheSecret() {
        val secret = Codec.random(32); create(secret)
        var first = -1
        val th = Thread { first = count(srv.handle(waitReq(secret, ms = 20_000))) }
        th.start(); Thread.sleep(200)
        val t = Thread { count(srv.handle(waitReq(secret, ms = 600))) }
        t.start(); th.join(3_000)
        assertEquals(0, first, "replaced waiter answers empty")
        t.join(3_000)
        // knowing only the queue id wakes nothing and sees nothing
        val q = MailboxProtocol.queueId(secret); put(q, byteArrayOf(9))
        assertEquals(0, count(srv.handle(waitReq(q, ms = 100))))
    }

    @Test
    fun globalCapRejectsAndCapsDuration() {
        val srv = MailboxServer(MemoryMailboxStore(), powBits = 8, maxWaits = 48) // the cap is configurable
        val threads = (1..48).map { Thread { srv.handle(waitReq(Codec.random(32), ms = 1_500)) }.also { it.start() } }
        Thread.sleep(400)
        assertEquals(48, srv.waiters())
        assertEquals(0xFF, In(srv.handle(waitReq(Codec.random(32), ms = 100))).u8(), "over the global cap")
        threads.forEach { it.join(5_000) }
        assertEquals(0, count(srv.handle(waitReq(Codec.random(32), ms = -5))), "negative wait is clamped")
        assertEquals(0xFF, In(srv.handle(byteArrayOf(2, 0x17, 1))).u8())
    }
}

class WaitManyTest {
    private val srv = MailboxServer(MemoryMailboxStore(), powBits = 8)
    @Test
    fun oneWaitCoversSeveralQueuesAndReportsTheIndex() {
        val ss = List(3) { Codec.random(32) }
        ss.forEach { s -> val q = MailboxProtocol.queueId(s); srv.handle(Out().u8(2).u8(MailboxProtocol.CREATE).raw(s).i64(MailboxProtocol.solvePow(q, 8)).done()) }
        val req = Out().u8(2).u8(MailboxProtocol.WAIT).i32(20_000).i32(3).also { o -> ss.forEach { o.raw(it) } }.done()
        var resp = ByteArray(0)
        val th = Thread { resp = srv.handle(req) }; th.start(); Thread.sleep(200)
        srv.handle(Out().u8(2).u8(MailboxProtocol.PUT).raw(MailboxProtocol.queueId(ss[2])).bytes(byteArrayOf(5)).done())
        th.join(5_000)
        val r = In(resp); assertEquals(MailboxProtocol.R_LIST, r.u8()); assertEquals(1, r.i32()); assertEquals(2, r.i32())
        // 64 queues per WAIT (mailbox 1.2); 65 is refused; GET stays at 8.
        fun wait(n: Int) = srv.handle(Out().u8(2).u8(MailboxProtocol.WAIT).i32(10).i32(n).also { o -> repeat(n) { o.raw(ss[0]) } }.done())
        assertEquals(MailboxProtocol.R_LIST, In(wait(9)).u8()); assertEquals(MailboxProtocol.R_LIST, In(wait(64)).u8())
        assertEquals(0xFF, In(wait(65)).u8(), "more than 64 queues")
        assertEquals(0xFF, In(srv.handle(Out().u8(2).u8(MailboxProtocol.GET).i32(9).also { o -> repeat(9) { o.raw(ss[0]) } }.done())).u8(), "GET still takes 8")
    }
}
