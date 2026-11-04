package com.jijilabsdigital.swell.core

import java.io.IOException

/**
 * One-time invite shown as a link or QR. It holds only the inviter's onion address, a 32-byte
 * invite secret, the inviter's identity key and a client-auth key that opens the inviter's onion
 * service until the invite is used (audit H-02). The pre-key bundle is fetched over Tor with the
 * secret, so the QR stays small and the bundle is never in a screenshot or chat log.
 */
class Invite(val onion: String, val secret: ByteArray, val identityKey: ByteArray, val authKey: ByteArray) {
    fun encode(): String =
        PREFIX + Codec.b64(Out().u8(VERSION).str(onion).raw(secret).bytes(identityKey).raw(authKey).done())

    companion object {
        const val PREFIX = "swell:"
        private const val VERSION = 2
        private val ONION = Regex("[a-z2-7]{56}")

        fun decode(text: String): Invite {
            val t = text.trim()
            if (!t.startsWith(PREFIX)) throw IOException("not a Swell invite")
            val r = In(Codec.unb64(t.removePrefix(PREFIX)))
            if (r.u8() != VERSION) throw IOException("unsupported invite version")
            val onion = r.str(64)
            if (!ONION.matches(onion)) throw IOException("bad onion address")
            val secret = r.raw(32)
            val ik = r.bytes(64)
            val auth = r.raw(32)
            r.end()
            return Invite(onion, secret, ik, auth)
        }
    }
}

/**
 * Mailbox invite (v3, made by an iPhone, which has no onion service): the code points at a queue on
 * a mailbox instead of the inviter. docs/IOS-PLAN.md section 10.
 * `"swell:" + base64url(u8 3 | str mailboxOnion | raw32 queueId | raw32 sealKey | bytes identityKey)`.
 */
class MailboxInvite(val mailbox: String, val queueId: ByteArray, val sealKey: ByteArray, val identityKey: ByteArray) {
    fun encode(): String =
        Invite.PREFIX + Codec.b64(Out().u8(VERSION).str(mailbox).raw(queueId).raw(sealKey).bytes(identityKey).done())

    companion object {
        const val VERSION = 3
        private val ONION = Regex("[a-z2-7]{56}")

        /** The version byte of an invite code, or -1 if it is not one. */
        fun versionOf(text: String): Int = runCatching {
            val t = text.trim()
            if (!t.startsWith(Invite.PREFIX)) -1 else Codec.unb64(t.removePrefix(Invite.PREFIX)).first().toInt()
        }.getOrDefault(-1)

        fun decode(text: String): MailboxInvite {
            val t = text.trim()
            if (!t.startsWith(Invite.PREFIX) || t.length > 1024) throw IOException("not a Swell invite")
            val r = In(Codec.unb64(t.removePrefix(Invite.PREFIX)))
            if (r.u8() != VERSION) throw IOException("unsupported invite version")
            val mb = r.str(64)
            if (!ONION.matches(mb)) throw IOException("bad mailbox address")
            val q = r.raw(32); val seal = r.raw(32); val ik = r.bytes(64)
            r.end()
            return MailboxInvite(mb, q, seal, ik)
        }

        /** Opens the sealed bundle in the invite queue: `raw12 nonce | AES-256-GCM(sealKey, bundle, aad = queueId)`. */
        fun open(inv: MailboxInvite, sealed: ByteArray): ByteArray {
            if (sealed.size < 12 + 16) throw IOException("sealed bundle")
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(inv.sealKey, "AES"), javax.crypto.spec.GCMParameterSpec(128, sealed, 0, 12))
            c.updateAAD(inv.queueId)
            return try { c.doFinal(sealed, 12, sealed.size - 12) } catch (e: java.security.GeneralSecurityException) { throw IOException("sealed bundle") }
        }

        /** The inverse of [open]; used by tests and the reference peer. */
        fun seal(sealKey: ByteArray, queueId: ByteArray, bundle: ByteArray): ByteArray {
            val nonce = Codec.random(12)
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(sealKey, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
            c.updateAAD(queueId)
            return nonce + c.doFinal(bundle)
        }
    }
}
