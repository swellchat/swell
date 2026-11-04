# Swell Bell: receiving without telling the server who is listening (design, 2026-11-04)

Owner goal: Swell's own idea, not a copy, with speed and privacy together, and low battery and data use. Status: design, not built. Latency and battery figures below are estimates until measured.

Honesty on novelty: the idea of detecting messages from a public stream is related to academic work (fuzzy message detection, oblivious message retrieval, private signaling). A search on 2026-11-04 found no deployed messenger using this simple form over Tor mailboxes, but that is not proof. Do not claim "first" publicly. Get outside review before any claim.

## The idea
Today a client names its queues to wait on them, so the server can group them. With the bell, nobody names a queue while listening.

1. Sender adds a short random-looking tag to each PUT: `tag = nonce(4) ‖ HMAC-SHA256(bellKey, nonce)[0..8]`, 12 bytes. `bellKey` is per contact, sent inside the encrypted session.
2. Mailbox appends the tag to one public log.
3. Every online client holds one anonymous stream and receives all new tags. Every client gets the same bytes.
4. The client checks tags against its own bell keys, on the phone. On a match, it fetches only that queue, on a stream used for nothing else.

The server learns nothing from listening. Grouping can happen only at fetch time, and each queue is fetched on its own stream.

## Why it can also save battery and data
- One listening stream instead of 4 receive lanes, so fewer Tor circuits to keep alive. Foreground total: 1 bell + 2 spare + 1 open chat, about 4 circuits instead of about 7.
- No polling of every queue. After being away, the client reads the log from its saved cursor and fetches only queues with mail.
- 12 bytes per message, shared among all listeners. At 1 message per second that is about 43 KB per hour per listener in tag bytes alone. That figure is misleading, see "Known costs and limits".
- Background: nothing runs until a content-free push wakes the app, then one log read. iOS has no background listening.
- Bell stream only in the foreground. Replies are batched for 100 ms, so several messages wake the radio once.

## Known costs and limits (found while building the server, 2026-11-04)
- **Data cost grows with global traffic.** Each wake costs a request and a reply, and over Tor each occupies at least one 514-byte cell, so about 1 KB per wake. At 1 message per second that is about 3.6 MB per hour per listener on the wire, not 43 KB (calculated, not measured). At 10 messages per second the load test measured 1.0 MB per hour at app level on loopback without Tor, and the wire figure could reach about 36 MB per hour. This fails the data and battery target as designed. Clients must not ship Bell until wakes are capped, for example with a longer or client-chosen batching interval, and the result is measured on a phone.
- **The operator is not blinded.** The mailbox operator sees which queue gets which tag in the same PUT. Bell hides queue grouping from listeners and from outside readers of the log, not from the mailbox operator.
- **Cursor reuse can link circuits.** A client that reconnects with a rare stale cursor can be linked to its earlier circuit. Up-to-date listeners share cursors, so the anonymity set is large only when traffic is busy. The safer client behaviour is to reset to -1 and do one catch-up fetch.
- **Added latency.** The 100 ms batching adds 0 to 100 ms to the first message.
- **Replay.** Anyone who sees a tag can replay it. The effect is at most one useless fetch.

Status: server committed and load-tested on loopback (4,000 listeners, 10 PUT/s, 0 errors, p50 103 ms, p99 165 ms, no Tor). Not deployed. No client advertises `CAP_BELL`.

## Speed
- Sender PUT on a warm stream: about 0.5 s (measured 0.48 s median for a request).
- Bell to receiver plus fetch on a spare stream: first message about 1.2 s in total (estimate).
- While a chat is on screen, that one queue also gets its own WAIT on its own stream, about 0.75 s (estimate). No queue is ever bundled with another.

## Wire
- Caps bit 16 = CAP_BELL. Inner type 15 BELLKEY: `raw32 key`, sent on connect and rotated with the queue. Contacts without the cap stay on lanes.
- PUT gets an optional trailing `bytes bell` (12 bytes). Send it only after the mailbox answered a BELL request; old servers reject trailing bytes.
- Op 0x18 BELL: `i64 cursor (-1 = from now), i32 maxWaitMs (≤ 25000)` → `u8 0x84, i64 newCursor, u8 lost, i32 n, raw12 × n`. No authentication. `lost = 1` means the cursor is older than the log; the client then polls each queue once, staggered.
- The log keeps a tag as long as messages stay (14 days), capped by size, and survives restarts.

## Limits
- Timing is still visible: a PUT into queue A followed by a fetch of A. Add a random delay of 0–300 ms before fetch; it weakens the link but does not remove it.
- The app-open catch-up could fetch several queues at once. Stagger the fetches, up to about 30 s for chats not on screen.
- Anyone can read the public log, which shows total message volume on that mailbox. Tags themselves reveal nothing without a bell key.
- A malicious sender can flood tags. PUT rate limits apply; the log is capped.

## Tests before calling this real
Two phones over Tor: delivery median of 20 messages, compared with lanes. Battery and data per hour in the foreground, compared with lanes. Load test: 4,000 listeners at 10 messages per second.

## Sources
- Oblivious Message Retrieval: https://eprint.iacr.org/2021/1256.pdf
- Fuzzy Message Detection: https://www.researchgate.net/publication/356201154_Fuzzy_Message_Detection
- PerfOMR: https://www.usenix.org/system/files/sec24fall-prepub-824-liu-zeyu.pdf
