package com.jijilabsdigital.swell.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateMessagesTest {
    private var t = 1_000L
    private val store = PrivateMessages { t }

    private fun msg(id: String, data: ByteArray? = null) =
        Message(id, "c1", "text-$id", true, 1, 0, data?.let { Attachment("f$id", "a.jpg", "image/jpeg", it.size.toLong(), it) }, null, 0, Node.FLAG_PRIVATE)

    @Test
    fun keepsMessagesAndWipesThemWithTheBytes() {
        val photo = ByteArray(100) { 7 }
        store.add("c1", msg("a", photo), 3)
        store.add("c1", msg("b"), 3)
        store.add("c2", msg("c"), 3)
        assertEquals(listOf("a", "b"), store.messages("c1").map { it.message.id })
        assertEquals(photo, store.file("fa"))
        store.wipe("c1")
        assertTrue(store.messages("c1").isEmpty())
        assertNull(store.file("fa"))
        assertTrue(photo.all { it == 0.toByte() }, "bytes overwritten")
        assertEquals(1, store.messages("c2").size)
        store.wipeAll()
        assertTrue(store.messages("c2").isEmpty())
    }

    @Test
    fun idleChatsAfterFiveMinutes() {
        store.add("c1", msg("a"), 3)
        t += 4 * 60_000L
        assertTrue(store.idleChats(5 * 60_000L).isEmpty())
        store.touch("c1")
        t += 4 * 60_000L
        assertTrue(store.idleChats(5 * 60_000L).isEmpty())
        t += 2 * 60_000L
        assertEquals(listOf("c1"), store.idleChats(5 * 60_000L))
    }

    @Test
    fun sourceUsesNoStorage() {
        // The class must not even reference storage types: nothing it holds can reach disk.
        val src = java.io.File("src/main/kotlin/com/jijilabsdigital/swell/core/PrivateMessages.kt").readText()
        listOf("Kv", "java.io", "FileOutput", "writeBytes", "SQLite", "Files.").forEach { assertTrue(!src.substringAfter("*/").contains(it), it) }
    }
}
