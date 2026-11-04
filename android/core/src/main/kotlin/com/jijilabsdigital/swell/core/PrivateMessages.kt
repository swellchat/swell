package com.jijilabsdigital.swell.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Messages of chats in private mode. They live in this object only: nothing here ever touches a
 * [Kv], a file or a cache, so a wipe (or the process ending) leaves nothing behind. Attachment bytes
 * stay in memory and are overwritten with zeros on a wipe (best effort; the JVM may have copied them).
 */
class PrivateMessages(private val now: () -> Long = System::currentTimeMillis) {
    class Entry(val message: Message, @Volatile var status: Int)

    private val chats = ConcurrentHashMap<String, MutableList<Entry>>()
    private val activity = ConcurrentHashMap<String, Long>()

    fun add(contactId: String, m: Message, status: Int) {
        synchronized(this) {
            val l = chats.getOrPut(contactId) { ArrayList() }
            if (l.none { it.message.id == m.id }) l += Entry(m, status)
        }
        touch(contactId)
    }

    fun setStatus(contactId: String, id: String, status: Int) { chats[contactId]?.firstOrNull { it.message.id == id }?.status = status }

    fun remove(contactId: String, id: String) = synchronized(this) {
        chats[contactId]?.let { l -> l.filter { it.message.id == id }.forEach { zero(it); l.remove(it) } }
    }

    fun messages(contactId: String): List<Entry> = synchronized(this) { chats[contactId]?.toList() ?: emptyList() }

    /** Bytes of a private attachment, or null. */
    fun file(fileId: String): ByteArray? = synchronized(this) {
        chats.values.asSequence().flatten().firstNotNullOfOrNull { e -> e.message.file?.takeIf { it.id == fileId }?.data }
    }

    fun has(contactId: String) = synchronized(this) { chats[contactId]?.isNotEmpty() == true }

    fun touch(contactId: String) { activity[contactId] = now() }

    /** Chats with private messages that saw no activity for [idleMs]. */
    fun idleChats(idleMs: Long): List<String> = synchronized(this) {
        chats.filter { it.value.isNotEmpty() }.keys.filter { now() - (activity[it] ?: 0L) >= idleMs }
    }

    fun wipe(contactId: String) = synchronized(this) {
        chats.remove(contactId)?.forEach { zero(it) }
        activity.remove(contactId)
    }

    fun wipeAll() = synchronized(this) {
        chats.values.flatten().forEach { zero(it) }
        chats.clear(); activity.clear()
    }

    private fun zero(e: Entry) { e.message.file?.data?.fill(0) }
}
