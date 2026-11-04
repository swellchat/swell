package com.jijilabsdigital.swell.mailbox

import com.jijilabsdigital.swell.core.Codec
import com.jijilabsdigital.swell.core.In
import com.jijilabsdigital.swell.core.MailboxProtocol
import com.jijilabsdigital.swell.core.Out
import com.jijilabsdigital.swell.core.Wire
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Load test for a running mailbox (see docs/RESEARCH-SPEED.md): N simulated phones each park one WAIT on their own queue
 * over a real TCP connection, then a sample of them gets a PUT and we measure the time until the WAIT answers.
 *
 *   java -cp ... com.jijilabsdigital.swell.mailbox.LoadToolKt <port> <queueDir> [waiters=1000] [puts=200] [queuesPerWait=1]
 *
 * <queueDir> is the mailbox's MAILBOX_DIR: queues are created as directories there (CREATE needs proof of work).
 * Prints one line per metric: PARKED, ERRORS, LAT_MS p50/p95/p99/max.
 */
fun main(args: Array<String>) {
    val port = args[0].toInt()
    val dir = File(args[1])
    val n = args.getOrNull(2)?.toInt() ?: 1000
    val puts = args.getOrNull(3)?.toInt() ?: 200
    val per = args.getOrNull(4)?.toInt() ?: 1
    val secrets = List(n * per) { Codec.random(32) }
    secrets.forEach { File(dir, Codec.hex(MailboxProtocol.queueId(it))).mkdirs() }
    val loop = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    val parked = CountDownLatch(n)
    val answered = ConcurrentHashMap<Int, Long>() // waiter -> nanoTime of the answer
    val putAt = ConcurrentHashMap<Int, Long>()
    val errors = AtomicInteger()
    val done = CountDownLatch(puts)
    val sockets = ArrayList<Socket>()
    val factory = threadFactory()
    for (w in 0 until n) {
        if (w % 10 == 9) Thread.sleep(8) // phones do not all connect in the same millisecond (and macOS caps the accept backlog at 128)
        factory.newThread {
            try {
                val s = Socket(loop, port).also { synchronized(sockets) { sockets += it } }
                val mine = secrets.subList(w * per, (w + 1) * per)
                val req = Out().u8(2).u8(MailboxProtocol.WAIT).i32(25_000).i32(per).also { o -> mine.forEach { o.raw(it) } }.done()
                Wire.write(s.getOutputStream(), req)
                parked.countDown()
                s.soTimeout = 40_000
                val resp = In(Wire.read(s.getInputStream()))
                val kind = resp.u8()
                if (kind != MailboxProtocol.R_LIST) errors.incrementAndGet()
                else if (resp.i32() > 0) { answered[w] = System.nanoTime(); done.countDown() }
            } catch (e: Exception) { if (errors.incrementAndGet() <= 3) println("ERR $e"); parked.countDown() }
        }.start()
    }
    parked.await(60, TimeUnit.SECONDS)
    Thread.sleep(3_000) // let the server park them all
    println("PARKED $n waiters on $per queue(s) each; errors so far ${errors.get()}")
    val targets = (0 until n).shuffled().take(puts)
    targets.forEachIndexed { i, w ->
        factory.newThread {
            runCatching {
                Socket(loop, port).use { s ->
                    val q = MailboxProtocol.queueId(secrets[w * per + (per - 1)])
                    putAt[w] = System.nanoTime()
                    Wire.write(s.getOutputStream(), Out().u8(2).u8(MailboxProtocol.PUT).raw(q).bytes(ByteArray(300)).done())
                    Wire.read(s.getInputStream())
                }
            }.onFailure { errors.incrementAndGet() }
        }.start()
        if (i % 20 == 19) Thread.sleep(100) // about 200 PUTs per second
    }
    done.await(30, TimeUnit.SECONDS)
    val lat = targets.mapNotNull { w -> answered[w]?.let { (it - putAt[w]!!) / 1_000_000.0 } }.sorted()
    fun p(q: Double) = if (lat.isEmpty()) -1.0 else lat[((lat.size - 1) * q).toInt()]
    println("ANSWERED ${lat.size}/$puts errors ${errors.get()}")
    println("LAT_MS p50=%.1f p95=%.1f p99=%.1f max=%.1f".format(p(0.5), p(0.95), p(0.99), lat.lastOrNull() ?: -1.0))
    synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
    System.exit(0)
}
