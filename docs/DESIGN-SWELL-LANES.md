# Swell Lanes: faster delivery that is harder to link (design, 2026-11-04)

Owner target: Swell leads in both privacy and speed. Status: design. Nothing here is measured unless it says so. No "first" or "only" claim may be made from this document; SimpleX and others explore nearby ideas, and an outside review has not happened.

## The limit we work within
The anonymity trilemma says anonymity cannot scale better than cover traffic × latency. Swell keeps latency low and sends no cover traffic by default, so it cannot defeat a party who watches all traffic timing. Lanes reduce what the mailbox operator learns by construction, without adding delay.

## Threat: the mailbox operator
This includes us, or anyone who seizes or hacks the server. Sender and recipient IP addresses are already hidden by Tor, and contents are end-to-end encrypted. What remains is linking: which queues belong to one person, and which queues talk to each other.

## Problems in the current clients
1. iOS development code (not released): in the foreground, all mailbox calls share one stream (`Engine.mb`, lane "io"). A PUT to a contact's queue on the same stream as a WAIT on our own queues shows the operator who talks to whom. Must be fixed before TestFlight.
2. Android 1.1 and iOS: one WAIT stream carries several of our own queues, so the operator can group them as one person.
3. Android PUT opens a fresh circuit per message. It links nothing, but costs 2–9 s.

## The design
1. **Receive lanes.** Split our queues into K = 4 fixed groups by `HMAC(device key, queue id) mod K`. Each group has its own Tor circuit (isolation tag) and its own WAIT loop; ACKs use the same lane. Groups are never reshuffled, because overlap across reshuffles would link everything. Lanes open at random offsets of 0–3 s, not all at once.
2. **Send lanes.** One warm stream per active contact, with a tag never shared with another contact or with a receive lane. Closed after 120 s idle.
3. **Spare pool: the part that gives both goals.** Keep 2 already-open, unassigned streams, kept alive by a no-op request. The first message to a contact takes a spare, so it skips the path setup without sharing a stream. Refill in the background.
4. **Private Mode.** A contact in Private Mode gets its own receive lane.
5. **Background.** Fresh stream per call, as today.

## What each party learns afterwards
- Operator: at most a quarter of a person's queues can be grouped (one lane). Sends cannot be tied to a person's receive lanes by stream.
- Operator can still compare timing, for example a PUT to queue B followed by activity on queue A. Lanes do not remove this. Rotation and a second operator reduce it; cover traffic is the only strong answer.

## Later layers
- **Queue rotation:** new queue per contact every 7 days, agreed inside the encrypted session, old queue drained then dropped.
- **Second mailbox:** a contact's queue may live with another operator, so one operator sees only part of a person's traffic. It must be a truly separate operator; one company running both gives little separation.
- **Max Privacy (optional):** cover traffic in the foreground only, in the Loopix style, trading battery and data for strength.
- **Content-free push:** wake-up only, rotating tokens, no sender data.

## Cost and dependency
About 7 circuits per foregrounded user (4 receive, 1 open chat, 2 spare). The deployed mailbox allows 64 connections and 48 waiters, so the scale build (2df4177: 6000 connections, 4000 waiters in the load test defaults) must be deployed before wider testing. Battery and data use must be measured on both phones.

## Targets to measure, on real phones over Tor
- Tap to bubble: immediate (optimistic).
- First message after opening a chat: under 1 s (spare pool).
- Delivery between two open apps: median under 1 s.
- Battery in one hour of foreground use, compared with no lanes.
Measured so far: warm mailbox request median 0.48 s (Deck, 10 samples).

## Post-quantum status (corrected 2026-11-04)
libsignal 0.86.5 already runs SPQR. A runtime test on `libsignal-client` 0.86.5 (JVM) showed the `pq_ratchet` field present on all messages, 4–37 bytes per message. Source: `rust/protocol/src/ratchet.rs` starts sessions with `spqr::Version::V1` and `min_version: V0`, so fallback to no post-quantum ratchet is still allowed. Remaining work: confirm on real Android and iPhone traffic, then upgrade libsignal to a version that requires it, tested against older clients. An earlier note in RESEARCH-OSS-MESSENGERS.md said 0.86.5 had no SPQR; it looked for a file name that did not exist yet.

## Sources
- Anonymity trilemma, practical mix network design: https://bork.cs.fau.de/CCC/34C3/slides-pdf/34c3-8974-practical_mix_network_design.pdf
- Loopix: https://arxiv.org/pdf/1703.00536
- SimpleX SMP overview: https://github.com/simplex-chat/simplexmq/blob/stable/protocol/overview-tjr.md
- SimpleX private routing and isolation notes: https://www.nobsbitcoin.com/simplex-chat-v5-8/
- Separate-operator critique: https://discuss.privacyguides.net/t/simplex-community-servers-list/21443
- Signal SPQR: https://signal.org/blog/spqr/
