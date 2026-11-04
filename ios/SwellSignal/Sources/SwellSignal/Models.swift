import Foundation
import SwellCore

/// A contact, stored as `contact/<channelId hex>`. Layout matches Kotlin `Contact` v4 where it can; extra
/// iOS fields (unread, last activity) come after and are ignored by a reader that does not know them.
public struct Contact: Identifiable, Equatable, Sendable {
    public var channelId: [UInt8]
    public var onion: String            // "" = mailbox-only peer
    public var identityKey: [UInt8]
    public var name: String
    public var verified = false
    public var blocked = false
    public var expireSec: Int32 = 0
    public var createdAt: Int64
    public var peerMailbox = ""
    public var peerQueue: [UInt8] = []
    public var myQueueSecret: [UInt8]
    public var queueReady = false
    public var myAuth: [UInt8]
    public var peerAuth: [UInt8] = []
    public var unread: Int32 = 0
    public var lastAt: Int64
    public var lastText = ""
    /// Features the contact announced (bit 0 = CAP_V11: replies, reactions, edit/delete, receipts, typing, voice, photo).
    public var caps: Int32 = 0
    /// Name chosen on this phone; wins over the name the contact gave.
    public var nickname = ""
    /// Created by redeeming a mailbox invite and still waiting for the other person's answer (STATE).
    public var waiting = false
    /// Made by a mailbox invite: we owe them our queue and a STATE until `handshakeDone`.
    public var fromInvite = false
    public var handshakeDone = true

    public var id: String { Codec.hex(channelId) }
    public var hasMailbox: Bool { !peerMailbox.isEmpty && peerQueue.count == 32 }
    public var supportsV11: Bool { caps & 1 != 0 }
    public var displayName: String { !nickname.isEmpty ? nickname : name.isEmpty ? "Contact \(id.prefix(6))" : name }

    public func encode() -> [UInt8] {
        Out().u8(4).raw(channelId).str(onion).bytes(identityKey).str(name)
            .u8(verified ? 1 : 0).u8(blocked ? 1 : 0).i32(expireSec).i64(createdAt)
            .str(peerMailbox).bytes(peerQueue).raw(myQueueSecret).u8(queueReady ? 1 : 0).u8(0)
            .raw(myAuth).bytes(peerAuth)
            .i32(unread).i64(lastAt).str(lastText).i32(caps).str(nickname).u8(waiting ? 1 : 0).u8((fromInvite ? 1 : 0) | (handshakeDone ? 2 : 0)).done()
    }

    public static func decode(_ b: [UInt8]) throws -> Contact {
        let r = In(b)
        guard try r.u8() == 4 else { throw SwellError.format("contact version") }
        var c = Contact(channelId: try r.raw(16), onion: try r.str(max: 64), identityKey: try r.bytes(max: 64), name: try r.str(),
                        createdAt: 0, myQueueSecret: [], myAuth: [], lastAt: 0)
        c.verified = try r.u8() == 1; c.blocked = try r.u8() == 1; c.expireSec = try r.i32(); c.createdAt = try r.i64()
        c.peerMailbox = try r.str(max: 64); c.peerQueue = try r.bytes(max: 32); c.myQueueSecret = try r.raw(32)
        c.queueReady = try r.u8() == 1; _ = try r.u8()
        c.myAuth = try r.raw(32); c.peerAuth = try r.bytes(max: 32)
        if r.remaining > 0 { c.unread = try r.i32(); c.lastAt = try r.i64(); c.lastText = try r.str() }
        if r.remaining > 0 { c.caps = try r.i32(); c.nickname = try r.str(max: 256); c.waiting = try r.u8() == 1 }
        if r.remaining > 0 { let f = try r.u8(); c.fromInvite = f & 1 != 0; c.handshakeDone = f & 2 != 0 }
        return c
    }
}

public struct ChatMessage: Identifiable, Equatable, Sendable {
    public enum Status: Int, Sendable { case sending = 0, sentDirect = 1, queued = 2, failed = 3, received = 4 }
    public var id: String               // hex of the 16-byte message id
    public var contactId: String
    public var text: String
    public var incoming: Bool
    public var sentAt: Int64            // millis
    public var status: Status
    /// Millis after which the message is hidden and deleted (0 = never).
    public var expiresAt: Int64 = 0
    /// Hex id of the message this one answers ("" = none).
    public var replyTo = ""
    public var edited = false
    public var deleted = false
    /// Outgoing: the contact's phone confirmed it (receipt). Incoming: we sent the delivered receipt.
    public var delivered = false
    /// Outgoing: the contact read it (receipt). Incoming: we sent the read receipt.
    public var read = false
    public var myReaction = ""
    public var theirReaction = ""
    /// Voice note length in ms (0 = not a voice note) and the id of its audio file.
    public var voiceMs: Int32 = 0
    public var fileId = ""
    /// Attached photo or file: name, MIME type and size in bytes (empty/0 for old voice notes and text).
    public var fileName = ""
    public var fileMime = ""
    public var fileSize: Int64 = 0
    /// When this phone got an incoming message (the edit/delete window runs from here).
    public var receivedAt: Int64 = 0

    public var hasFile: Bool { !fileId.isEmpty }
    public var isVoice: Bool { hasFile && (fileMime.isEmpty || fileMime.hasPrefix("audio/")) }
    public var isImage: Bool { hasFile && fileMime.hasPrefix("image/") }
    /// One-line description for the chat list and quotes.
    public var preview: String {
        if deleted { return "This message was deleted" }
        if isVoice { return "Voice note" }
        if hasFile { return text.isEmpty ? (isImage ? "Photo" : (fileName.isEmpty ? "File" : fileName)) : String(text.prefix(120)) }
        return String(text.prefix(120))
    }

    var key: String { String(format: "%015lld", sentAt) + id }
    /// Delivered to the contact's phone: directly, or confirmed by a receipt after it waited in their mailbox.
    public var onTheirPhone: Bool { status == .sentDirect || delivered || read }

    func encode() -> [UInt8] {
        let flags: Int = (edited ? 1 : 0) | (deleted ? 2 : 0) | (delivered ? 4 : 0) | (read ? 8 : 0)
        return Out().u8(1).str(id).str(contactId).str(text).u8(incoming ? 1 : 0).i64(sentAt).u8(status.rawValue).i64(expiresAt)
            .bytes(replyTo.count == 32 ? ((try? Codec.unhex(replyTo)) ?? []) : []).u8(flags).str(myReaction).str(theirReaction)
            .i32(voiceMs).str(fileId).i64(receivedAt).str(fileName).str(fileMime).i64(fileSize).done()
    }
    static func decode(_ b: [UInt8]) throws -> ChatMessage {
        let r = In(b)
        guard try r.u8() == 1 else { throw SwellError.format("message version") }
        var m = ChatMessage(id: try r.str(max: 64), contactId: try r.str(max: 64), text: try r.str(), incoming: try r.u8() == 1,
                            sentAt: try r.i64(), status: Status(rawValue: try r.u8()) ?? .failed, expiresAt: try r.i64())
        if r.remaining > 0 {
            let rt = try r.bytes(max: 16); m.replyTo = rt.count == 16 ? Codec.hex(rt) : ""
            let f = try r.u8(); m.edited = f & 1 != 0; m.deleted = f & 2 != 0; m.delivered = f & 4 != 0; m.read = f & 8 != 0
            m.myReaction = try r.str(max: 64); m.theirReaction = try r.str(max: 64)
            m.voiceMs = try r.i32(); m.fileId = try r.str(max: 64); m.receivedAt = try r.i64()
            if r.remaining > 0 { m.fileName = try r.str(max: 512); m.fileMime = try r.str(max: 100); m.fileSize = try r.i64() }
        }
        return m
    }
}

/// Reads and applies client authorization on our Tor (`ONION_CLIENT_AUTH_ADD/REMOVE`).
public protocol OnionAuth: Sendable {
    func setClientAuth(onion: String, privateKey: [UInt8]?) async throws
}

public struct NoOnionAuth: OnionAuth {
    public init() {}
    public func setClientAuth(onion: String, privateKey: [UInt8]?) async throws {}
}

#if DEBUG
/// Debug-only fixtures for screenshots on a device (launch argument `-swellDemo`). Not compiled into Release.
public enum DemoData {
    public static func contacts() -> [Contact] {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        func c(_ i: UInt8, _ name: String, _ last: String, _ ago: Int64, unread: Int32 = 0, verified: Bool = false) -> Contact {
            var x = Contact(channelId: [UInt8](repeating: i, count: 16), onion: "", identityKey: [], name: name, createdAt: now,
                            myQueueSecret: [], myAuth: [], lastAt: now - ago * 60_000)
            x.lastText = last; x.unread = unread; x.verified = verified; x.caps = 1; if i == 1 { x.expireSec = 86400 }
            return x
        }
        return [c(1, "Contact A", "See you at six", 3, unread: 2, verified: true),
                c(2, "Contact B", "Sent you the file", 95),
                c(3, "Contact C", "Message me when you land", 60 * 26)]
    }
    public static func messages(_ id: String) -> [ChatMessage] {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        func m(_ n: Int64, _ t: String, _ inc: Bool, _ s: ChatMessage.Status) -> ChatMessage {
            ChatMessage(id: String(format: "%032d", n), contactId: id, text: t, incoming: inc, sentAt: now - (10 - n) * 60_000, status: s)
        }
        var l = [m(1, "Are we still on for tonight?", false, .sentDirect), m(2, "Yes. Six o'clock works for me.", true, .received),
                 m(3, "Perfect. See you then.", false, .sentDirect), m(4, "See you at six", true, .received),
                 m(5, "On my way", false, .sentDirect), m(6, "Running a little late, sorry!", false, .queued), m(7, "Oops, wrong chat", true, .received)]
        l[0].read = true; l[0].delivered = true
        l[1].theirReaction = ""; l[1].myReaction = "\u{1F44D}"
        l[3].replyTo = l[0].id
        l[4].edited = true; l[4].theirReaction = "\u{2764}\u{FE0F}"
        l[6].deleted = true; l[6].text = ""
        var v = m(8, "", true, .received); v.fileId = "aa"; v.voiceMs = 14_000; l.append(v)
        return l
    }
}
#endif
