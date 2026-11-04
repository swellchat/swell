# Swell Wire Protocol v2

Version 2 (1.1 additions, see section 5a) — 2026-11-04. Source of truth: `android/core/src/main/kotlin/com/jijilabsdigital/swell/core/` (`Node.kt`, `Mailbox.kt`, `Wire.kt`, `Invite.kt`, `Onion.kt`). An iOS client must implement this document exactly. Use LibSignalClient (Swift), which is the same Rust library as on Android.

## 1. Encoding

- **Integers:** big-endian. `u8`, `i32`, `i64`.
- **`bytes`:** `i32 length` followed by the data.
- **`str`:** `bytes` holding UTF-8.
- **`raw(n)`:** exactly n bytes with no length prefix.
- **Frame on a stream:** `i32 length` followed by the payload. The payload is 1 to 327,680 bytes (320 KiB).
- **Streams:** a stream (Tor stream or TCP) carries request/response pairs in order. The client may send several requests over one stream.
- **Server timeouts:** the server answers each frame. It closes the stream after 30 s idle, or 10 min in total.
- **Padding:** every plaintext encrypted with libsignal is padded first.
  - Layout: `i32 length` + data + zero bytes, up to the next size bucket.
  - Buckets: 1 KiB, 4 KiB, 16 KiB, 64 KiB, 256 KiB.
- **Forward compatibility:**
  - A reader ignores unknown trailing fields in inner messages.
  - A reader ignores unknown inner message types and still answers `R_OK`.
  - Outer frame types and mailbox operations are strict: anything unknown gets `R_REJECT`.

## 2. Identity and keys

- **Account entropy:** 16 random bytes, shown as a BIP-39 phrase of 12 words.
- **Identity key:** `ECPrivateKey(HKDF-SHA256(entropy, info="swell identity v1", salt=32 zero bytes, len=32))`.
- **Sessions:** libsignal PQXDH with a Kyber-1024 one-time prekey, then the Double Ratchet.
  - Protocol address = `(hex(channelId), deviceId 1)`.
  - A changed identity on a known channel is never trusted.
- **Safety number:** `NumericFingerprintGenerator(5200)`, version 2. Each side passes its own identity key as the stable id.

## 3. Invite (QR / link), version 2

`"swell:" + base64url_nopad( u8 2 | str onion | raw(32) secret | bytes identityKey(33) | raw(32) authKey )`

| Field | Meaning |
|-------|---------|
| `onion` | 56-char v3 address, without ".onion" |
| `secret` | Fetches the prekey bundle; works once; expires after 24 h |
| `authKey` | x25519 private key that opens the inviter's onion service until the invite is used, plus 10 min after |

## 4. Peer frames (to `<onion>.onion:80`)

Every frame starts with `u8 PROTOCOL=2 | u8 type`.

| Type | Body | Answer |
|------|------|--------|
| 1 GET_BUNDLE | `raw32 secret` | `u8 0x81, bytes bundle` or `0xFF` |
| 2 HELLO | `raw32 secret, raw16 channelId, bytes PreKeySignalMessage` | `u8 0x80, bytes SignalMessage(I_STATE)` or `0xFF` |
| 3 MSG | `raw16 channelId, u8 ciphertextType (2 = whisper, 3 = prekey), bytes ciphertext` | `0x80` or `0xFF` |
| 4 PING | (empty) | `0x80` (keeps a stream warm) |

**Bundle layout:** `i32 registrationId, i32 preKeyId, bytes preKeyPub, i32 signedPreKeyId, bytes signedPreKeyPub, bytes signedPreKeySig, bytes identityKey, i32 kyberId, bytes kyberPub, bytes kyberSig`.

**Rejection:** every refusal is the same single byte `0xFF`, so a refusal reveals nothing about the reason.

## 5. Inner messages (plaintext inside the ratchet)

| Type | Body |
|------|------|
| 1 HELLO | `str onion ("" = mailbox-only peer), str name, str mailboxOnion, bytes queueId(0 or 32), bytes authKey(32), MSG` |
| 2 TEXT | `MSG` |
| 3 SETTINGS | `i32 disappearSeconds` |
| 5 STATE | `str name, str mailboxOnion, bytes queueId, str onion ("" = mailbox-only), bytes authKey` |
| 6 CALL | `u8 op (1 offer, 2 answer, 3 hangup, 4 busy), raw16 callId, str sdp` (live only; never via mailbox) |
| 7 FILE_PART | `raw16 fileId, i32 index, bytes data (≤ 240 KiB; ≤ 48 KiB if it may go via mailbox)` |
| 8 FILE | `MSG, raw16 fileId, str name, str mime, i64 size (≤ 5,000,000), raw32 sha256, i32 parts` |

- **HELLO and STATE trailing field (1.1):** both end with `i32 caps` (HELLO: after `MSG`; STATE: after `authKey`). Bit 0 (`CAP_V11`, value 1) means "I understand inner types 9–13 and the trailing fields of section 5a". A peer that sends no `caps` (1.0) has caps = 0, and a 1.1 node sends it nothing new. Further bits (section 5b): bit 1 `CAP_VIEW_ONCE` (value 2), bit 2 `CAP_PRIVATE_MODE` (value 4), bit 3 `CAP_LINK_PREVIEW` (value 8, section 5c). A node announces the bits it implements (Android 1.2: 15). A sender uses a feature only if the contact announced its bit; a reader ignores bits it does not know.
- **`MSG` layout:** `raw16 messageId, i64 sentAtMillis, i32 expireSec (ignored by the receiver; the chat timer applies), str text`.
- **STATE:** sent after HELLO (as the HELLO answer), and whenever name, mailbox, address or key changes.
- **Receiver rule:** a receiver uses `authKey` as its client-auth key for the sender's `onion`.

## 5a. Inner messages added in 1.1 (types 9–13, trailing fields)

All of these are backwards compatible: a 1.0 reader ignores unknown inner types (answers `R_OK`) and ignores trailing bytes after `TEXT`, `HELLO`, `STATE`. **Exception:** the 1.0 reader of `FILE` (type 8) calls `end()` and refuses a `FILE` with trailing bytes. So a sender adds the `FILE` trailing fields **only if the contact announced `CAP_V11`** (see section 5) and leaves them out otherwise. Likewise, types 9–13 are only worth sending to a contact with `CAP_V11` (a 1.0 reader would accept and ignore them, but each costs a round trip or a mailbox slot). Messages are identified by `messageId` (the 16 bytes of `MSG.messageId`, hex on screen).

| Type | Body |
|------|------|
| 2 TEXT (extended) | `MSG, [bytes replyTo]`. `replyTo` is empty or exactly 16 bytes: the `messageId` of the message this one answers. Omit the field when it is not a reply. |
| 8 FILE (extended) | `MSG, raw16 fileId, str name, str mime, i64 size, raw32 sha256, i32 parts, [bytes replyTo, i32 durationMs]`. Both trailing fields come together (`replyTo` may be empty). `durationMs` is 0 for non-audio and 1..180000 for a voice note. |
| 9 REACTION | `raw16 messageId, str emoji, u8 add (1 = add or replace, 0 = remove)` |
| 10 MODIFY | `u8 op (1 = delete for everyone, 2 = edit), raw16 messageId, str text` |
| 11 RECEIPT | `u8 kind (1 = delivered, 2 = read, 3 = opened), i32 n (0..64), raw16 messageId × n` (kind 3: section 5b) |
| 12 TYPING | `u8 on (1 = typing, 0 = stopped)` |
| 13 AVATAR | `raw32 sha256, bytes jpeg` (empty `jpeg` = photo removed) |
| 14 CONTROL | `u8 op (1 = private mode on, 2 = off, 3 = wipe)` (section 5b) |

**Voice notes:** an ordinary FILE (parts via FILE_PART, then FILE) with `mime = "audio/mp4"` (AAC-LC in an MP4/M4A container, mono, about 32 kbps, 16 kHz), at most 180 s, and `durationMs` as the trailing field. Without `durationMs` (sent to a 1.0 contact) the receiver derives the length from the file. The file name is `voice-<unixMillis>.m4a`.

**REACTION:** `emoji` is one string of 1..16 bytes of UTF-8 with no control characters (one emoji, possibly several code points). Each person has at most one reaction per message: an add replaces that person's earlier one; `add = 0` removes it (the emoji field names the one being removed). A reaction for an unknown `messageId` is ignored. Our quick set: 👍 ❤️ 😂 😮 😢 🙏.

**MODIFY:** only the sender of the target message may change it, and only within 24 hours.
- The receiver ignores a MODIFY when the target is not found, was not sent by this contact (a contact can never touch your own messages), is older than 24 h + 5 min slack, or has `op` unknown.
- The age is measured from `sentAt` for the sender and from the local receipt time for the receiver.
- `op = 1`: the receiver replaces the message with "This message was deleted", removes its text, reactions and attached file, and keeps the tombstone. `text` is empty.
- `op = 2`: the receiver replaces the text and marks the message "edited". Only text messages (no attached file) can be edited. `text` is the new full text (non-empty).
- Quotes (`replyTo`) of a deleted message show "Original message unavailable"; of an edited one, the new text.

**RECEIPT:** *delivered* is sent when the message is stored; *read* when the chat showing it is open and the user has seen it. Receipts may use the mailbox. Both ends must have receipts enabled: **a node with receipts off neither sends a RECEIPT nor processes one it receives** (Signal's rule), and the setting is off by default.

**TYPING:** **direct path only, never through the mailbox** (a sender never PUTs it; a receiver drops a copy that arrives from the mailbox). A sender sends it only over a stream that is already open, `on = 1` at most every 5 s while the user types and `on = 0` after 3 s idle or when the message is sent. The receiver shows "typing…" and clears it after 8 s without a refresh. Same rule as RECEIPT: off means neither sent nor shown; off by default.

**AVATAR (profile photo):** `sha256` is the SHA-256 of `jpeg` (of the empty string for a removal). `jpeg` is a 256x256 JPEG, quality about 80, EXIF stripped, **at most 40960 bytes** (so one frame fits a mailbox PUT). The receiver ignores (answers `R_OK`, stores nothing) a frame whose `jpeg` is over 40960 bytes or whose hash does not match. A node sends AVATAR to every non-blocked contact with `CAP_V11` when the photo changes, and to a new contact right after the handshake (only if it has a photo). Photos go only to existing contacts, inside the ratchet. The receiver stores the photo encrypted at rest and shows it in the chat list, chat header and contact info, with initials as fallback. A **nickname** a user gives a contact is local only and never sent. Blocked contacts' frames are dropped before decryption like every other type.

**Mailbox invite v3 (Android redeems since 1.1):** `swell:` + base64url(`u8 3 | str mailboxOnion | raw32 queueId | raw32 sealKey | bytes identityKey`). The redeemer PEEKs (0x16) the queue, takes the first envelope `u8 2 | raw16 zero | u8 0 | bytes sealed`, with `sealed = raw12 nonce | AES-256-GCM(sealKey, bundle, aad = queueId)`, and checks the bundle's identity key equals the invite's. It then PUTs a peer HELLO frame (`u8 2 | u8 2 | raw32 sealKey | raw16 channelId | bytes PreKeySignalMessage`) into the same queue; the inviter answers with STATE through the redeemer's mailbox. The nonce-first layout of `sealed` is what the Kotlin reference (`MailboxInvite.seal/open`) does; the iOS inviter must match it.

**Invite link (Android 1.1 and iOS):** `https://jijilabsdigital.com/swell/i#<invite code>`; the fragment is the complete invite code (`swell:...`; the prefix may be omitted when reading). The fragment never reaches the web server. Android registers it as an App Link and opens straight to the "Connect?" confirmation.

**Deferred invite (clipboard, Android 1.2):** the web page `https://jijilabsdigital.com/swell/i` (the page behind the invite link) copies the **complete link**, `https://jijilabsdigital.com/swell/i#swell:...`, to the clipboard when the person taps "Get Swell" (before sending them to the store). After install, on the first open and on the Add-contact screen, the app reads the clipboard **only at those two moments**, and if the text matches `^https://jijilabsdigital\.com/swell/i#(swell:)?[A-Za-z0-9_-]+$` it offers "Connect with the person who invited you?". Accepting redeems the code as usual; after use (accept or dismiss) the app clears the clipboard if it still holds that text. Nothing else is read from the clipboard.

**Rate limits:** REACTION, MODIFY, RECEIPT, TYPING and AVATAR from one contact are limited to 90 per minute together (the existing `allowInbound` check with its own bucket); beyond that they are dropped silently (the frame is still answered `R_OK`). The pre-decryption limit per contact is 120 + 90 frames a minute, so these cannot starve texts.


## 5b. View once, per-message timer, private mode (Android 1.2; types 14, flags, caps bits 1 and 2)

All of this is backwards compatible in the same way as 5a: a reader ignores unknown trailing bytes after `TEXT` and `FILE`, ignores unknown inner types, and a sender uses a feature only after the contact announced the matching `caps` bit. Where a contact lacks the bit, the sender's app disables the option and says "Update needed".

**Caps bits.** `CAP_V11 = 1`, `CAP_VIEW_ONCE = 2` (view once and the per-message timer), `CAP_PRIVATE_MODE = 4`.

**Trailing fields.**

| Type | Body |
|------|------|
| 2 TEXT | `MSG, bytes replyTo, u8 flags, i32 timerSec`. `replyTo` is empty when not a reply. A sender adds `flags` and `timerSec` (together, after `replyTo`) only when `flags != 0` or `timerSec != 0`. |
| 8 FILE | `MSG, raw16 fileId, str name, str mime, i64 size, raw32 sha256, i32 parts, bytes replyTo, i32 durationMs, u8 flags, i32 timerSec`. The first six trailing fields are as in 5a; `flags` and `timerSec` follow `durationMs` and are added under the same rule. A 1.0 reader refuses trailing bytes on FILE, which is why everything here needs the contact's `CAP_V11` as well. |

- **`flags`** (`u8`): bit 0 = view once; bit 1 = private; bits 2..7 reserved, sent as 0, ignored on receipt.
- **`timerSec`** (`i32`): 0 = none, otherwise 1..2,592,000 (30 days). Values outside are treated as 0.
- **View once (bit 0)** is only valid on a FILE whose `mime` starts with `image/` or `video/`. A receiver ignores bit 0 on any other message (it is shown as an ordinary message). The sender sends it only to contacts with `CAP_VIEW_ONCE`.

**View once, receiver.**
- It shows a blurred placeholder ("View-once photo"), never the content, and never builds a thumbnail, notification preview, gallery copy or backup of it. A notification says only "View-once photo".
- The viewer is full screen with the platform's screenshot block (Android `FLAG_SECURE`). The user can open it once. When the viewer closes, the app deletes the file and every key for it at once, and sends `RECEIPT kind 3` (opened) for the `messageId`.
- No-trace rule: the receiver's row disappears 60 s after the viewer closes, with no placeholder left. It is removed from the database, files, caches, the search index and notifications.
- An item nobody opened expires and is removed after 14 days from receipt.

**View once, sender.** The sender keeps no viewable copy: the picked file is not stored. Its row shows "View-once photo" with the send/delivery status. When `RECEIPT kind 3` arrives, the row disappears 60 s later (no placeholder, purged from everywhere). If it never arrives, the row expires after 14 days.

**RECEIPT kind 3 (opened).** Same layout as kinds 1 and 2 (`u8 kind, i32 n, raw16 messageId × n`). Unlike kinds 1 and 2 it is sent and processed **even when the receipts setting is off**, because the feature cannot work without it (the app tells the person so when they open the photo). It is only acted on for outgoing view-once messages of that contact; for any other `messageId` it is ignored. It may travel through the mailbox.

**Per-message timer (`timerSec > 0`).**
- Receiver: the timer starts when the message is first shown on screen (chat open, message in view). The message is then deleted `timerSec` seconds later, with no trace (as above). A message never shown is removed after 14 days.
- Sender: starts the same countdown when the read receipt (`RECEIPT kind 2`, or kind 3 for a view-once message) arrives. If receipts are off (no kind 2 arrives), the sender falls back to the chat's disappearing timer (type 3 SETTINGS), and keeps the message if there is none.
- The per-chat timer remains the default for messages without `timerSec`. If both apply, the sooner one wins.
- The composer offers Off / 10 s / 1 min / 1 h / 1 day.

**Private mode (per chat).**
- `CONTROL` (type 14): `u8 op`. `1` = private mode on, `2` = off, `3` = wipe. Sent only to contacts with `CAP_PRIVATE_MODE`. **Like any message: direct first, the mailbox as the fallback**, and a receiver accepts CONTROL from either path (an iPhone has no direct route, so for it the mailbox is the only one). A CONTROL that arrives late (from the mailbox) is still applied: `op 3` and `op 2` wipe whatever private messages are in RAM at that moment. Unknown `op` is ignored. The same rate bucket as TYPING applies.
- A message with `flags` bit 1 is a private message. Private messages take the same two paths. While the contact is offline the (still end-to-end encrypted) envelope waits in their mailbox queue, as for any message (at most 14 days, deleted when fetched and acknowledged); the receiver accepts a private message from the mailbox and keeps it in RAM only, exactly as if it had arrived direct. File parts above 48 KiB are direct only, as for any file, so a large private photo needs the contact online. The send fails only if neither path works.
- The receiver keeps private messages (text, photos, voice, file parts and the decrypted media) **in RAM only**: never in the database, files, cache, search index, backups, the chat-list preview or a notification. File parts of a contact that is in private mode are also held in RAM until the FILE arrives. The notification text is "New private message", with no sender and no content.
- Both sides show the banner "Private mode · nothing is saved" and a distinct darker look, and block screenshots while it is on.
- `op 1` from the contact puts our side in private mode too (the mode is shared by the chat); a private message that arrives while we are not in the mode (we missed op 1, or restarted) turns it on and is kept in RAM.
- `op 3` or `op 2` from the contact: delete every private message of that chat from RAM (both directions), `op 2` also leaves the mode.
- A node sends `op 3` (and wipes its own copy) when its user leaves the chat, the app goes to the background, the screen locks, or the chat is idle for 5 minutes (no message sent or received). Turning the mode off sends `op 2`, which also wipes. If the process dies, the messages are simply gone; the mode flag is not stored either.
- Delivery/read receipts, reactions, edits and replies are not used in private mode.

## 5c. Link previews (Android 1.2; caps bit 3)

`CAP_LINK_PREVIEW = 8`. Off by default on both sides. The **sender** builds the preview (the receiver never fetches anything), so the receiver's address is never exposed to the linked site.

- **Wire:** one more trailing field on `TEXT`, after `timerSec`: `TEXT = MSG, bytes replyTo, u8 flags, i32 timerSec, bytes preview`. A sender that adds `preview` always writes `replyTo`, `flags` and `timerSec` first (zeros if unused). Only sent to contacts with `CAP_LINK_PREVIEW`, and only when the sender turned link previews on.
- **`preview` layout** (the bytes of): `u8 version (1), str url (≤ 2048 bytes, https only), str title (≤ 200 bytes), str site (≤ 100 bytes, host or site name), bytes thumbJpeg (≤ 24,576 bytes, may be empty; JPEG, longest side ≤ 240 px, EXIF stripped)`.
- **Receiver:** shows a card (thumbnail, title, site) under the text only if `url` appears in the message text (otherwise the card is dropped), `url` is `https://`, and sizes are within the limits; anything else is ignored and the text shows normally. The card is inside the message, so it follows that message's rules (timer, view once never applies, private mode: previews are not sent in private mode). Tapping the card opens the browser after a "Open this link?" confirmation. The receiver never fetches the page or the image.
- **Sender fetch:** the sender's app downloads the page (HTML up to 256 KiB, 10 s) and the `og:image` (up to 400 KiB) **over its own Tor SOCKS port**, never directly, then re-encodes the image. If anything fails, the message is sent without a preview.
- **Receipts for the setting:** the user-facing setting is "Link previews" (Settings › Privacy). It only affects what this phone sends; what arrives is always shown.

## 6. Mailbox (onion service, port 80)

Frames start with `u8 VERSION=2 | u8 op`. Queue id = `SHA-256("swell-queue" || secret32)`.

| Op | Body | Answer |
|----|------|--------|
| 0x14 CREATE | `raw32 secret, i64 nonce`; proof of work: `SHA-256("swell-pow" ‖ queueId ‖ i64 nonce)` has ≥ 22 leading zero bits | `0x80` / `0xFF` |
| 0x11 PUT | `raw32 queueId, bytes envelope (≤ 80 KiB)`; the queue must already exist | `0x80` / `0xFF` |
| 0x12 GET | `i32 n (1..8), raw32 secret × n` | `u8 0x82, i32 count, (i32 index, str id, bytes envelope) × count` |
| 0x13 ACK | `raw32 secret, i32 n, str id × n` | `0x80` |
| 0x15 CREDS | `i64 minute, i64 nonce`; `SHA-256("swell-cred" ‖ i64 minute ‖ i64 nonce)` has ≥ 18 leading zero bits; minute = now/60000 ± 1; each nonce works once | `u8 0x83, i32 n, str url × n, str username, str password, i64 expiresAtMillis` |
| 0x16 PEEK | `raw32 queueId` (no secret). Reads, deletes nothing. Used by v3 mailbox invites. At most 600 per minute per server | `u8 0x82, i32 count (0 or 1), (i32 0, str id, bytes envelope)`: the oldest envelope; unknown queue = empty list; over the limit = `0xFF` |
| 0x17 WAIT | `i32 maxWaitMs` (clamped to 0..25000), `i32 n (1..64), raw32 secret × n`. Long poll | Like GET for those queues (same item layout and index, nothing deleted; ACK as usual), sent as soon as one queue has mail, or empty at the timeout. One waiter per queue (a newer WAIT covering a queue makes the older one answer empty); server-wide waiter cap is configurable (default 4,000 on the Jiji Labs mailbox), beyond that `0xFF`. **Compatibility:** mailboxes older than 1.2 accept `n` up to 8 only and answer `0xFF` to more; a client that gets `0xFF` for `n > 8` retries once with its 8 newest queues and keeps using 8 for that session. Items for the first queues come first (`MAX_LIST` 50 items and 192 KiB per answer overall, so a very busy account sees the rest on the next WAIT or an ACK-then-WAIT). |

**Envelope:** a complete peer `MSG` frame (section 4), stored as is.

**Limits:**
- Per queue: 200 envelopes and 2 MB.
- TTL: 14 days.
- A queue nobody fetched for 14 days is deleted.

**Foreground clients (iOS, Android)** keep one stream open to the mailbox and loop WAIT (25 s) on it, using the same stream for PUT/ACK; on `0xFF` for 0x17 (older server) they fall back to polling GET every 12 s. Both ops are new in mailbox 1.1 (64 queues per WAIT: 1.2); an old server answers `0xFF` to them.

**Client polling rule:** fetch one queue per request, each over its own Tor circuit (SOCKS username = random isolation tag).

## 6a. Swell Bell (mailbox 1.3; caps bit 16)

Design and reasons: `DESIGN-SWELL-BELL.md`. A listener names no queue: it reads one public log of 12-byte tags and checks them against its own bell keys. Servers older than 1.3 answer `0xFF` to op 0x18 and to a PUT with a trailing tag.

**Capability and key.** `CAP_BELL = 65536` (bit 16, `1 << 16`) in the `caps` of HELLO/STATE. Inner type **15 BELLKEY** (inside the ratchet, any path): `raw32 key`. The *receiver* of a queue generates a random `bellKey` per contact and sends it to that contact; the contact (the sender) uses it to tag PUTs into the receiver's queue. A new BELLKEY replaces the old one (send it again when the queue rotates). A reader ignores trailing bytes; a reader that lacks the cap ignores the type (answers `R_OK`). Send BELLKEY only to contacts with `CAP_BELL`.

**Tag (12 bytes).** `tag = nonce(4) || HMAC-SHA256(bellKey, nonce)[0..8]`, `nonce` = 4 random bytes. **Match:** for a tag `t` and a key `k`, `t[4..12] == HMAC-SHA256(k, t[0..4])[0..8]` (compare in constant time). A tag may be replayed by anyone who saw it; the cost is one useless fetch, so a client must treat a match only as "fetch that queue".

**Test vectors** (hex):

| bellKey | nonce | tag |
|---------|-------|-----|
| `000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f` | `00000000` | `00000000a86acbcbc29b8fa8` |
| `000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f` | `01020304` | `01020304e3ba74ad60769167` |
| `4242424242424242424242424242424242424242424242424242424242424242` | `deadbeef` | `deadbeef2759079ac61b2bf6` |

The first tag does not match the key `4242...42`; the second does not match if its last byte is changed.

**PUT with a tag (op 0x11, optional trailing field).** `raw32 queueId, bytes envelope, bytes bell`. `bell` must be exactly 12 bytes; any other length, or any other trailing byte, rejects the whole frame (`0xFF`) and stores nothing. The server stores the envelope first; only when that succeeded it appends the tag to the log, then answers `0x80`. If the PUT fails (no such queue, full) no tag is logged. A PUT without `bell` works exactly as before and logs nothing. Send `bell` only to a mailbox that has answered op 0x18 (a successful BELL request) in this session or earlier; old servers reject the trailing field.

**Op 0x18 BELL.** Frame: `u8 2, u8 0x18, i64 cursor, i32 maxWaitMs`. No authentication, no queue, no secret.
Answer: `u8 0x84, i64 newCursor, u8 lost, i32 n, raw12 x n`.

- A cursor is the number of the next tag to read; the first tag ever logged is number 0, numbers only grow, and they survive server restarts (the log is stored on disk).
- `cursor = -1` means "from now": answered at once with `n = 0`, `newCursor` = the current end of the log. Store `newCursor` and use it in the next request.
- `cursor == end of log`: the server parks the request for up to `maxWaitMs` (clamped to 0..25000) and answers as soon as new tags are published, or with `n = 0` and the same cursor at the timeout. Loop immediately after every answer.
- **Publishing is batched.** The server publishes new tags to listeners at most every 100 ms (trailing edge: a PUT starts a 100 ms timer; tags appended before it fires go out together). So one message reaches listeners 0 to 100 ms after the PUT, and a burst wakes a listener once.
- `n` is at most 2048 per answer (24,576 bytes). If more are waiting, `newCursor = cursor + n` and the next request is answered at once.
- All listeners with the same `cursor` receive identical bytes.
- `cursor` older than the oldest kept tag, or **newer than the end of the log** (the server lost its log): `lost = 1, n = 0, newCursor` = end of log. The client must then fetch every queue once (staggered, as in the design) and continue from `newCursor`.
- Otherwise `lost = 0`. `cursor < -1`, a malformed frame or a server at its listener cap (default 5,000 parked BELL requests) answers `0xFF`; on a cap `0xFF` the client waits a random 5 to 30 s before retrying, or falls back to lanes.
- Retention: tags are kept up to 14 days and at most `MAILBOX_BELL_MAX_MB` (default 192 MiB, about 16.7 million tags, because a tag takes 12 bytes on disk). The oldest 3 MiB segments are dropped first.
- Privacy: the server keeps no per-listener state besides the open socket, logs no cursor, and the log has no queue ids. A listener that reconnects with the cursor it was last given shares that cursor with every listener that is up to date, because answers end only at publish points.

**Mac test peer** (`android/peer`): sends BELLKEY on request and tags PUTs; stdin commands `bellkey` (send a fresh BELLKEY to the newest contact), `bell` (listen on our own mailbox with op 0x18) and `bellstop`. The peer announces `CAP_BELL`. It tags a PUT only if it holds a BELLKEY from that contact and the contact's mailbox answered a BELL probe. Output lines: `BELLKEY sent <8 hex> to <contact>`, `BELLKEY got <8 hex> from <contact>`, `BELL cursor=<n> tags=<n> lost=<0|1> hits=<n>` (first answer, and every answer with tags or `lost`), `BELLHIT <contact>`; after a hit or `lost` it fetches with `POLLED <n>`.

## 7. Onion client authorization

- Our onion service is published with `ClientAuthV3` = the x25519 public key of every non-blocked contact's `authKey`, of every open invite, and of 3 spare invite keys.
- Deleting or blocking a contact moves the service to a new address. The old address stays up for 2 days with the revoked key removed.
- The address also moves every 30 days.
- Every move is announced to contacts with STATE.

## 8. iOS interoperability plan

iOS cannot keep an onion service running in the background, so an iPhone is a **mailbox-only peer**.

**Already in v2 (Android 1.0):**
- Android accepts HELLO and STATE with `onion = ""` when a mailbox is given. It sends to such contacts only through their mailbox.
- Unknown inner types and trailing fields are ignored, so new features never break old clients.

**For the iOS release (v3 additions, backwards compatible):**

1. **Mailbox invites**, so an iPhone can create a code.
   - The invite carries `mailboxOnion + inviteQueueSecret` instead of an onion.
   - The bundle is stored sealed in that queue (PUT by the inviter).
   - The redeemer GETs it with a read-only "peek" op (new op 0x16), then PUTs its HELLO into the same queue.
   - The inviter polls the queue, answers with STATE through the redeemer's mailbox, and deletes the queue.
2. **Push wake-up.** An optional per-queue "notify" token goes to a contentless APNs relay, so the iPhone wakes when an envelope arrives. The relay sees only "queue X has mail" and an APNs token. Disclose this in the privacy policy.
3. **iOS Tor client.** The iPhone uses Tor as a client in the foreground (Arti or Tor.framework). It reaches Android onion services directly while open; otherwise it uses mailboxes.
4. **Interop test.** Reuse the Mac test peer (`android/peer`) as the reference implementation for iOS tests.
