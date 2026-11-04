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
    if (args.getOrNull(0) == "bell") return bellLoad(args.drop(1))
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

/**
 * Swell Bell load: many listeners, each on its own TCP connection looping op BELL, while PUTs with a tag arrive at a fixed rate.
 *
 *   LoadToolKt bell <port> <queueDir> [listeners=4000] [putsPerSec=10] [seconds=60] [serverPid]
 *
 * Latency = from just before the PUT was sent until a listener's answer contained the tag (sampled on every 4th listener).
 * Also prints the server's heap used (jcmd from the tool's own JDK, so run both with the same one) and RSS (ps) when a pid is given, and the bytes one listener moved, scaled to an hour.
 */
private fun bellLoad(a: List<String>) {
    val port = a[0].toInt(); val dir = File(a[1])
    val n = a.getOrNull(2)?.toInt() ?: 4000
    val rate = a.getOrNull(3)?.toInt() ?: 10
    val secs = a.getOrNull(4)?.toInt() ?: 60
    val pid = a.getOrNull(5)
    val loop = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    val queues = List(50) { Codec.random(32) }
    queues.forEach { File(dir, Codec.hex(MailboxProtocol.queueId(it))).mkdirs() }
    val total = rate * secs
    val sentAt = LongArray(total) { Long.MAX_VALUE }
    val tags = Array(total) { Codec.random(12) }
    val index = HashMap<String, Int>().also { m -> tags.forEachIndexed { i, t -> m[Codec.hex(t)] = i } }
    val lat = LongArray(n * 256) // nanos, filled by sampled listeners
    val latN = AtomicInteger()
    val ready = CountDownLatch(n)
    val bytes = java.util.concurrent.atomic.AtomicLong()
    val errors = AtomicInteger()
    val seen = java.util.concurrent.atomic.AtomicLong()
    val stop = java.util.concurrent.atomic.AtomicBoolean()
    val sockets = ArrayList<Socket>()
    val factory = threadFactory()
    println("BELL load: $n listeners, $rate PUT/s for ${secs}s, threads=${if (factory.newThread {}.let { t -> runCatching { Thread::class.java.getMethod("isVirtual").invoke(t) as Boolean }.getOrDefault(false) }) "virtual" else "platform"}")
    for (w in 0 until n) {
        if (w % 10 == 9) Thread.sleep(6)
        factory.newThread {
            var counted = false
            try {
                val s = Socket(loop, port).also { synchronized(sockets) { sockets += it } }
                s.soTimeout = 40_000
                var cursor = -1L
                var sample = w % 4 == 0
                while (!stop.get()) {
                    val req = Out().u8(2).u8(MailboxProtocol.BELL).i64(cursor).i32(25_000).done()
                    Wire.write(s.getOutputStream(), req)
                    val resp = Wire.read(s.getInputStream())
                    val t1 = System.nanoTime()
                    bytes.addAndGet(8L + req.size + resp.size)
                    val r = In(resp)
                    if (r.u8() != MailboxProtocol.R_BELL) { errors.incrementAndGet(); break }
                    cursor = r.i64(); r.u8(); val c = r.i32()
                    if (!counted) { counted = true; ready.countDown() }
                    repeat(c) {
                        val i = index[Codec.hex(r.raw(12))]
                        if (i != null) {
                            seen.incrementAndGet()
                            if (sample && sentAt[i] != Long.MAX_VALUE) { val k = latN.getAndIncrement(); if (k < lat.size) lat[k] = t1 - sentAt[i] }
                        }
                    }
                }
            } catch (e: Exception) { if (!stop.get() && errors.incrementAndGet() <= 3) println("ERR $e") ; if (!counted) ready.countDown() }
        }.start()
    }
    ready.await(90, TimeUnit.SECONDS)
    Thread.sleep(2_000)
    println("CONNECTED ${n - ready.count.toInt()} listeners, errors ${errors.get()}")
    bytes.set(0)
    val t0 = System.nanoTime()
    val sock = Socket(loop, port)
    for (i in 0 until total) {
        val due = t0 + i * (1_000_000_000L / rate)
        while (System.nanoTime() < due) Thread.sleep(0, 200_000)
        sentAt[i] = System.nanoTime()
        Wire.write(sock.getOutputStream(), Out().u8(2).u8(MailboxProtocol.PUT).raw(MailboxProtocol.queueId(queues[i % queues.size])).bytes(ByteArray(300)).bytes(tags[i]).done())
        if (In(Wire.read(sock.getInputStream())).u8() != MailboxProtocol.R_OK) errors.incrementAndGet()
    }
    Thread.sleep(2_000)
    val elapsedS = (System.nanoTime() - t0) / 1e9
    stop.set(true)
    val k = minOf(latN.get(), lat.size)
    val l = lat.copyOf(k).sorted().map { it / 1e6 }
    fun p(q: Double) = if (l.isEmpty()) -1.0 else l[((l.size - 1) * q).toInt()]
    println("PUTS $total errors ${errors.get()} tag-deliveries seen ${seen.get()} (expected about ${total.toLong() * n})")
    println("LAT_MS p50=%.1f p95=%.1f p99=%.1f max=%.1f over %d samples".format(p(0.5), p(0.95), p(0.99), l.lastOrNull() ?: -1.0, l.size))
    val perListenerHour = bytes.get().toDouble() / n / elapsedS * 3600
    println("BYTES_PER_LISTENER_PER_HOUR %.0f (%.1f KB) at %d msg/s, measured over %.0f s".format(perListenerHour, perListenerHour / 1024, rate, elapsedS))
    if (pid != null) {
        fun run(vararg c: String) = runCatching { ProcessBuilder(*c).redirectErrorStream(true).start().inputStream.bufferedReader().readText() }.getOrDefault("")
        val rss = run("ps", "-o", "rss=", "-p", pid).trim().toLongOrNull()
        val heap = Regex("used (\\d+)K").find(run(File(System.getProperty("java.home"), "bin/jcmd").path, pid, "GC.heap_info"))?.groupValues?.get(1)?.toLongOrNull()
        println("SERVER_RSS_MB ${rss?.let { it / 1024 } ?: "?"} SERVER_HEAP_USED_MB ${heap?.let { it / 1024 } ?: "?"}")
    }
    synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
    System.exit(0)
}
