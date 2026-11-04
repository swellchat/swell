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

- **HELLO and STATE trailing field (1.1):** both end with `i32 caps` (HELLO: after `MSG`; STATE: after `authKey`). Bit 0 (`CAP_V11`, value 1) means "I understand inner types 9–13 and the trailing fields of section 5a". A peer that sends no `caps` (1.0) has caps = 0, and a 1.1 node sends it nothing new.
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
| 11 RECEIPT | `u8 kind (1 = delivered, 2 = read), i32 n (0..64), raw16 messageId × n` |
| 12 TYPING | `u8 on (1 = typing, 0 = stopped)` |
| 13 AVATAR | `raw32 sha256, bytes jpeg` (empty `jpeg` = photo removed) |

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

**Rate limits:** REACTION, MODIFY, RECEIPT, TYPING and AVATAR from one contact are limited to 90 per minute together (the existing `allowInbound` check with its own bucket); beyond that they are dropped silently (the frame is still answered `R_OK`). The pre-decryption limit per contact is 120 + 90 frames a minute, so these cannot starve texts.


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
| 0x17 WAIT | `i32 maxWaitMs` (clamped to 0..25000), `i32 n (1..8), raw32 secret × n`. Long poll | Like GET for those queues (same item layout and index, nothing deleted; ACK as usual), sent as soon as one queue has mail, or empty at the timeout. One waiter per queue (a newer WAIT covering a queue makes the older one answer empty); at most 48 waiters server-wide, beyond that `0xFF` |

**Envelope:** a complete peer `MSG` frame (section 4), stored as is.

**Limits:**
- Per queue: 200 envelopes and 2 MB.
- TTL: 14 days.
- A queue nobody fetched for 14 days is deleted.

**Foreground clients (iOS)** keep one stream open to the mailbox and loop WAIT (25 s) on it, using the same stream for PUT/ACK; on `0xFF` for 0x17 (older server) they fall back to polling GET every 12 s. Both ops are new in mailbox 1.1; an old server answers `0xFF` to them.

**Client polling rule:** fetch one queue per request, each over its own Tor circuit (SOCKS username = random isolation tag).

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
