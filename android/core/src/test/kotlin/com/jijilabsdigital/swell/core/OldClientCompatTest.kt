package com.jijilabsdigital.swell.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Swell 1.0's inner-message reader, copied from the 1.0 sources (unknown types ignored, trailing
 * bytes ignored except where 1.0 called end()). The 1.1 frames must pass through it unharmed.
 */
class OldClientCompatTest {
    private class Old {
        val texts = mutableListOf<String>()
        var settings = -1
        var states = 0
        var files = 0
        var ignored = 0
        private fun msg(r: In): String { r.raw(16); r.i64(); r.i32(); return r.str() }
        fun inner(b: ByteArray) {
            val r = In(b)
            when (r.u8()) {
                2 -> texts += msg(r)
                3 -> settings = r.i32()
                5 -> { r.str(256); r.str(64); r.bytes(32); r.str(64); r.bytes(32); states++ }
                7 -> { r.raw(16); r.i32(); r.bytes(); r.end() }
                8 -> { msg(r); r.raw(16); r.str(400); r.str(100); r.i64(); r.raw(32); r.i32(); r.end(); files++ }
                1 -> { r.str(64); r.str(256); r.str(64); r.bytes(32); r.bytes(32); msg(r) }
                else -> ignored++
            }
        }
    }

    private val id = ByteArray(16) { 7 }
    private fun msg(o: Out, text: String) = o.raw(id).i64(1L).i32(0).str(text)

    @Test
    fun newFramesAreIgnoredOrReadByOldClients() {
        val old = Old()
        // TEXT with a trailing reply id
        old.inner(msg(Out().u8(2), "quoted").bytes(ByteArray(16) { 9 }).done())
        assertEquals(listOf("quoted"), old.texts)
        // Types 9, 10, 11, 12 are unknown to 1.0
        old.inner(Out().u8(9).raw(id).str("👍").u8(1).done())
        old.inner(Out().u8(10).u8(2).raw(id).str("edited").done())
        old.inner(Out().u8(11).u8(2).i32(1).raw(id).done())
        old.inner(Out().u8(12).u8(1).done())
        old.inner(Out().u8(13).raw(ByteArray(32)).bytes(ByteArray(10)).done())
        assertEquals(5, old.ignored)
        // STATE with trailing caps (and anything after it)
        old.inner(Out().u8(5).str("n").str("").bytes(ByteArray(0)).str("").bytes(ByteArray(32)).i32(1).i32(99).done())
        assertEquals(1, old.states)
        // HELLO with trailing caps
        old.inner(msg(Out().u8(1).str("").str("n").str("").bytes(ByteArray(0)).bytes(ByteArray(32)), "hi").i32(1).done())
        // A FILE without extras (what a 1.1 node sends to a contact without CAP_V11)
        old.inner(msg(Out().u8(8), "").raw(id).str("a.m4a").str("audio/mp4").i64(5).raw(ByteArray(32)).i32(1).done())
        assertEquals(1, old.files)
    }

    @Test
    fun oldFileReaderRefusesTrailingBytes_soTheyAreOnlySentWithTheCapability() {
        val withExtras = msg(Out().u8(8), "").raw(id).str("a.m4a").str("audio/mp4").i64(5).raw(ByteArray(32)).i32(1)
            .bytes(ByteArray(0)).i32(1000).done()
        assertFailsWith<IOException> { Old().inner(withExtras) }
        // The 1.1 reader accepts both forms and unknown further fields.
        assertTrue(Contact.decode(Contact(ByteArray(16), "", org.signal.libsignal.protocol.IdentityKey(
            org.signal.libsignal.protocol.ecc.ECKeyPair.generate().publicKey), "x").encode()).caps == 0)
    }
}
