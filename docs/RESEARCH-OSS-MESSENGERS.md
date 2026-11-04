# Research: open-source messengers and the Swell upgrade plan

Version 1.0, 2026-11-04. Research only; no app code changed. Method: read public repos, protocol docs and blog posts (no code copied, none will be).

**Evidence tags.** `[V]` = opened and read during this research (URL given). `[K]` = from prior knowledge, not re-opened; verify before quoting outside this repo. Anything about Swell is tagged **shipped** (in the Android 1.0 code, which is still in Google review, so "shipped" means "in the code", not "public"), **iOS-wip** (in progress), or **planned** (design only, no code).

**Licenses seen** `[V]` via the GitHub API: libsignal AGPL-3.0, simplexmq AGPL-3.0, simplex-chat AGPL-3.0, Session iOS and libsession-util GPL-3.0, Session push server AGPL-3.0, Quiet GPL-3.0, vodozemac Apache-2.0, OpenMLS MIT. **Rule: ideas and published specs only; never copy AGPL/GPL code.** Using libsignal as a dependency is already how Swell is built (AGPL, matching Swell's own AGPL licence). Permissive libraries (OpenMLS MIT, vodozemac Apache-2.0) may be linked.

## 0. Findings that change the plan

1. **Swell pins libsignal 0.86.5, which has no SPQR.** `rust/protocol/src/triple_ratchet.rs` is absent at tag v0.86.5 and v0.91.0 and present from v0.93.0 `[V: raw.githubusercontent.com/signalapp/libsignal/<tag>/rust/protocol/src/triple_ratchet.rs, HTTP 404 vs 200]`. Latest tag seen is v0.106.1 `[V: api.github.com/repos/signalapp/libsignal/releases]`. The pin is in `android/core/build.gradle.kts:17` and `ios/SwellSignal/Package.swift:5`. [COMPARISON.md](COMPARISON.md) lists "PQXDH + SPQR (libsignal)" as a target; today Swell has PQXDH (post-quantum at session start) plus the classic Double Ratchet. Do not claim a post-quantum ratchet until the pin is bumped and tested.
2. libsignal's v0.100 release notes say SPQR is now required for all sessions (`requirePqRatio` removed) `[V: RELEASE_NOTES.md at v0.100.0]`. A bump therefore changes wire behaviour and must be tested against old clients (section 2, upgrade U4).
3. Swell's backup file (Argon2id passphrase + AEAD) is designed in [ARCHITECTURE.md](ARCHITECTURE.md) section 7 but I found no Argon2 code under `android/*/src`. Treat as **planned** until confirmed.

## 1. What each project does best

### 1.1 Per-project notes (areas as asked)

**Signal** (Signal-iOS, libsignal; AGPL)
- *Push:* Signal-iOS ships a Notification Service Extension target (`SignalNSE/`) `[V: github.com/signalapp/Signal-iOS/tree/main/SignalNSE]`. Details of what the server pushes are `[K]`: a contentless wake-up, then the NSE fetches. Server sees device token and IP.
- *Multi-device:* linked devices via QR provisioning, each device has own keys `[K]`; sync-start and attachment backfill described in `signal.org/blog/a-synchronized-start-for-linked-devices/`, `signal.org/blog/backup-improvements/` `[V: listed on blog index]`.
- *Backup:* "Secure Backups": one `AccountEntropyPool` backs a Backup Key; archive is an encrypted series of length-delimited protos (`rust/message-backup/README.md`, `rust/account-keys/src/lib.rs`) `[V]`. Free tier keeps text; paid tier holds media up to 100 GB; on-device backups use the same cross-platform format `[V: signal.org/blog/backup-improvements/]`.
- *Groups:* anonymous credentials (`rust/zkgroup`) and sender keys (`rust/protocol/src/sender_keys.rs`, `group_cipher.rs`) `[V: directory listing]`; design in `signal.org/blog/signal-private-group-system/` `[V: 200]`. Group state is stored encrypted on the server.
- *Calls:* RingRTC; group calls with an end-to-end encrypted SFU `[V: blog titles "group-calls", "how-to-build-encrypted-group-calls", "call-links"]`; "always relay" option `[K]`.
- *Anti-spam:* phone number + registration checks + message requests `[K]`. Usernames and QR/links exist so a number is not needed to start a chat (`rust/usernames`) `[V: directory]`.
- *Metadata:* sealed sender (`rust/protocol/src/sealed_sender.rs`, `signal.org/blog/sealed-sender/`) `[V]`; server still sees IPs.
- *Tor perf:* not applicable (direct TLS).
- *Onboarding:* phone number, SMS code, PIN. Fast, but it is the thing Swell rejects.
- *Reproducible/audits:* Android reproducible since 3.15.0 (2016) `[V: Signal-Android/reproducible-builds/README.md]`; PQXDH and SPQR have published formal analysis (`signal.org/blog/pqxdh/`, `eprint.iacr.org/2025/078`, SPQR README "Formal Verification" section) `[V: URLs 200; SPQR README]`. iOS reproducibility: not found, `[K]` no.
- *Best idea to learn from:* **the Triple Ratchet** (Double Ratchet + ML-KEM "braid" using erasure-coded chunks so large KEM keys travel in small pieces) `[V: SparsePostQuantumRatchet/README.md, signal.org/blog/spqr/]`, and **a recovery secret that also derives the backup key**.

**Session** (session-ios, session-android, libsession-util; GPL)
- *Push:* opt-in "Fast mode". The client makes an **onion request** to the push server (SPNS) with a device ID, an encryption key and a signed swarm-subscription signature. SPNS subscribes at the user's swarm nodes, then pushes an **encrypted** payload (message plus hash) via APNs/FCM. APNs sees only an opaque blob `[V: github.com/session-foundation/session-push-notification-server README]`. Leak: SPNS knows which accounts opted in and when they get mail; the user must trust it with a swarm subscription.
- *Multi-device:* none; recovery phrase restores identity and config (config sync via `libsession-util`) `[K]`.
- *Backup:* phrase restores account, not history `[K]`.
- *Groups:* new closed-group design in `libsession-util` with group keys and admin signing `[K]`; Protocol V2 (Dec 2025) re-adds forward secrecy and post-quantum protection `[V: getsession.org/session-protocol-v2, title and date; body not rendered by curl]`.
- *Calls:* beta, peer-to-peer WebRTC, so the peer can see an IP (see COMPARISON.md) `[K]`.
- *Anti-spam:* swarm-level; message requests for unknown senders `[K]`. A permanent public Session ID invites spam (the exact problem Swell avoids).
- *Metadata:* onion requests over service nodes; swarms replicate a user's inbox on several nodes `[K]`.
- *Performance:* 3-hop onion requests over HTTP-like calls, no Tor circuit building; fast. `[K]`
- *Onboarding:* ID generated in one tap; phrase can be saved later `[K]`.
- *Best idea:* **replicated inbox (swarm) + oblivious push**: the push server never sees plaintext.

**SimpleX Chat** (simplexmq, simplex-chat; AGPL)
- *Push:* iOS notifications run through a separate **Notification Router (NTF)** that registers an APNs token, then subscribes to SMP queues. APNs payload is e2e encrypted between NTF router and the device. NTF can learn the device token and counts, but not queue addresses or content; the SMP router can learn which messages trigger a push and the NTF router's IP `[V: simplexmq/protocol/push-notifications.md, "Threat Model"]`. Messages sent without the notification flag leak nothing to NTF `[V]`.
- *Multi-device:* none for chats; link mobile to desktop over a quantum-resistant protocol (v5.4) `[V: blog 20231125]`. This is a remote-control model, not independent devices.
- *Backup:* database export/import with passphrase `[V: blog 20220711 title]`.
- *Groups:* group built from pairwise connections between members, no group server; large public groups with moderation added in 2025 `[V: blog 20250114 title]`.
- *Calls:* WebRTC audio/video, relay option `[V: blog 6.1 "better calls"]`.
- *Anti-spam:* no IDs, so no spam by address; one-time links plus optional contact "business" and moderation features `[K]`.
- *Metadata:* unidirectional queues, queue rotation, fixed 16 KB blocks, 2-hop "private routing" so the destination router cannot see the sender IP `[V: protocol/overview-tjr.md lines 33, 153, 168, 177]`. Incognito profiles per contact `[K]`.
- *Files:* XFTP, chunked, encrypted, stored on separate file routers `[V: protocol/xftp.md]`.
- *Performance:* own routers, fast. Tor on iOS is optional and uses Orbot `[V: APPSTORE-4.3-MARKET.md cites simplex.chat/docs/guide/app-settings.html]`.
- *Audits:* Trail of Bits 2022 and Oct 2024 `[V: blog 20221108, blog 20241014]`.
- *Best idea:* **separate the "wake" party from the "storage" party and give the wake party nothing but a token.**

**Briar** (code.briarproject.org; GPL) `[K unless noted]`
- Tor + Bluetooth + Wi-Fi sync; no central server; Android only. Contact add by QR face to face or link. Private groups, forums, blogs.
- **Briar Mailbox**: a user-run always-on device (for example an old phone or Pi) to raise reachability and cut battery drain `[V: code.briarproject.org/briar/briar-mailbox README]`. This is the closest published match to Swell's mailbox idea, but user-owned instead of operator-owned.
- Reproducible builds and F-Droid distribution `[K]`; audits `[K, unverified]`.
- *Best idea:* **user-owned mailbox hardware** and offline transports (Bluetooth/Wi-Fi) for blackouts.

**Cwtch** (Open Privacy; Tor onion peers) `[K unless noted]`
- Peer-to-peer over Tor onion services. Groups use "untrusted servers" and a shared random Group Key `[V: docs.cwtch.im/docs/groups/introduction]`; weaker than pairwise ratchets (no post-compromise security for the group key, `[K]`). No iOS app (see APPSTORE-4.3-MARKET.md).
- *Best idea:* the server learns as little as possible from a hosted group relay, and group servers are swappable. Not a model for crypto.

**Delta Chat / chatmail** (chatmail/core, Rust) 
- Uses email relays ("chatmail") with Autocrypt/SecureJoin and a security-audited rPGP; **multi-device setup over ephemeral P2P with Iroh**; a P2P group-membership protocol that needs no server state `[V: github.com/chatmail/core README]`.
- Push: relays notify via a notification proxy `[K]`.
- Onboarding: instant account on a chatmail relay, no setup `[K]`.
- *Best idea:* **"add second device" as a direct transfer between the two devices, no cloud** (fits Swell's V2.md 2.3).

**Element X / Matrix** (matrix-rust-sdk, vodozemac Apache-2.0, OpenMLS MIT)
- vodozemac: pure Rust Olm (Double Ratchet) and Megolm `[V: github.com/matrix-org/vodozemac README]`. Megolm = a sender-key group ratchet; the shipped Matrix group crypto, not MLS `[K]`.
- Multi-device: cross-signing and key backup with a recovery key `[K]`. Push: gateway receives an event id only and the client fetches `[K]`; this "event-id-only" push is the cheapest leak-minimising form.
- Calls: Element Call on a SFU `[K]`. Audits: vodozemac audited by Least Authority `[K]`.
- *Best idea:* **recovery key + cross-signing UX for multi-device**; the id-only push pattern.

**Threema** (libthreema; Threema Android is AGPL)
- No phone/email needed; random Threema ID and QR trust levels `[K]`. Multi-device through a "mediator" server `[K]` (threema-ch/app-remote-protocol is the desktop-link spec `[V: org repo list]`).
- `github.com/threema-ch/libthreema` returned 404 on 2026-11-04 `[V]`; not readable by me, so no claims about it.
- *Best idea:* **a trust ladder in the contact UI** (unverified / verified in person), cheap and clear.

**Berty / Wesh** (berty/berty, berty/weshnet; licence field "NOASSERTION")
- Wesh protocol: groups with shared metadata over OrbitDB/IPFS; BLE and mDNS so no internet is needed `[V: berty README]`. README warns the app is not hardened `[V: line 96]`.
- *Best idea:* proximity transport (BLE) for first contact in places with no network.

**Quiet** (TryQuiet/quiet; GPL-3.0)
- Team chat synced over Tor with OrbitDB, no server; iOS app exists on TestFlight **without notifications**; iOS notifications are on the roadmap "with a service Apple requires to be centralized" `[V: TryQuiet/quiet README lines 46-79]`.
- *Lesson:* even a Tor-first project says plainly that push needs a central piece on iOS. Swell should say so too.

### 1.2 Comparison matrix: Swell vs each app

Cells are one-line summaries. Swell column is honest status.

| Area | Swell | Signal | Session | SimpleX | Briar | Cwtch | Delta Chat | Element X | Threema | Berty | Quiet |
|---|---|---|---|---|---|---|---|---|---|---|---|
| iOS push, metadata | **planned**: contentless APNs relay, token sent over Tor; iOS-wip is foreground-only [IOS-PLAN §4] | NSE, server sees token+IP [K] | SPNS, opt-in, encrypted payload [V] | NTF router, e2e payload, no queue address [V] | no iOS | no iOS | notif. proxy [K] | event-id-only gateway [K] | APNs push [K] | push not core [K] | none yet [V] |
| Multi-device | **planned** (V2.md 2.3), none today | linked devices, QR [K] | none | mobile<->desktop link only [V] | none | none | Iroh P2P device add [V] | cross-signing [K] | mediator [K] | multi-device group [K] | team members only |
| Backup / restore | phrase restores identity **shipped**; file backup **planned** | Secure Backups, AEP-derived key [V] | phrase, no history [K] | passphrase DB export [V] | no cloud [K] | profile export [K] | backup transfer [K] | recovery key [K] | Threema Safe [K] | export [K] | none |
| Groups | **planned** (MLS in V2.md) | sender keys + zk creds [V] | closed groups [K] | pairwise mesh [V] | creator-run private groups [K] | shared key via server [V] | P2P membership [V] | Megolm [K] | fan-out [K] | OrbitDB group [V] | community DB [V] |
| Calls | **planned** (CALLS.md, relay-only) | RingRTC, SFU [V] | beta P2P [K] | WebRTC [V] | none | none | webxdc realtime [V] | Element Call [K] | group calls [K] | none | none |
| Anti-spam without ID | **shipped**: one-time invites, client-auth onion, PoW on mailbox [PROTOCOL §3,6] | phone+requests [K] | message requests [K] | no addresses [K] | contact add only [K] | invite only [K] | encrypted-only filter [K] | server rules [K] | contact approval [K] | contact approval [K] | invite link |
| Metadata protection | **shipped**: Tor, rotating onion, padded buckets, no accounts | sealed sender [V] | onion requests [K] | private routing, 16 KB blocks [V] | Tor [K] | Tor [K] | email metadata visible | homeserver sees all [K] | server sees ID [K] | libp2p exposes peers [K] | Tor [V] |
| Performance on Tor | Tor required; p95 targets in ROADMAP, **not yet measured on iOS** | n/a | no Tor | Tor optional | slow, design accepts it | slow, accepts it | n/a | n/a | n/a | n/a | slow sync [K] |
| Onboarding | phrase first, paste code; QR not on iOS yet (4.3 audit) | phone number | tap, ID at once | open link/QR | QR in person | paste onion address | instant account | server picker | ID + QR | QR | invite link |
| Reproducible / audits | reproducible hashes **planned**; no audit yet | Android repro [V]; formal work [V] | audit [K] | Trail of Bits x2 [V] | repro [K] | [K, unverified] | rPGP audited [V] | vodozemac audited [K] | audits [K] | [K, unverified] | none known |

## 2. Where Swell can be best-in-class

Ranking basis in section 4. Effort: S ≤ 1 week, M = 2-4 weeks, L = 5+ weeks (guesses, one developer plus agents).

### U1. iOS push that leaks less than Session or SimpleX (sealed push)

**What the others leak.** Session's SPNS knows which accounts opted in and holds a swarm subscription; payload is encrypted but carries message metadata `[V]`. SimpleX's NTF router learns the device token and per-queue notification counts, and the SMP router learns which messages trigger pushes `[V]`.

**Design sketch (guess: it works on paper; unproven on device).**
1. At registration the phone gets an APNs token. Over Tor, with a fresh circuit and no stable ID, it sends `(apnsToken, pushKey)` to the relay and gets back a random `notifyTag`. The relay stores only `notifyTag -> apnsToken, pushKey`.
2. For each queue the phone gives the **mailbox** only `(notifyTag, pushKey)` (not the APNs token). The queue secret never goes to the relay.
3. On PUT, the mailbox seals the stored envelope under `pushKey` (AEAD), pads to one fixed size (about 3 KB, because the APNs payload limit is 4 KB `[K]`) and posts it to the relay over Tor. The relay forwards the blob to APNs untouched.
4. The Notification Service Extension decrypts the blob, then runs the Signal ratchet **locally** (shared Keychain access group and file-protected DB). No Tor is needed, so the roughly 24 MB extension memory limit (IOS-PLAN §3) is not a problem. It shows "New message" or, if the user opts in, the contact name. Messages that do not fit fall back to "open Swell to fetch".
5. The app dedupes by message id when it later does a normal GET + ACK.
6. Optional cover traffic: the relay sends dummy pushes at random times so APNs and the relay cannot count real messages. Cost: battery and Apple throttling (guess); needs measuring.

**Resulting exposure (design claim):** relay sees token, tag, time, blob size (constant); mailbox sees queue, tag, time, but not the APNs token; Apple sees time and fixed size. Linking queue to device needs the mailbox and relay operators to collude, so require **different operators** (same rule as CALLS.md) and make the relay self-hostable. Beats SimpleX/Session on: no account-level subscription, no queue id at the relay, no per-message metadata in the payload.

**Risks:** NSE needs the sealed-sender-style mailbox change (new op), concurrent DB access between app and NSE, and Apple may suppress pushes that show nothing. Dropping a notification silently needs the `com.apple.developer.usernotifications.filtering` entitlement `[K]`, which needs Apple approval. SimpleX's threat model assumes the client can filter pushes `[V]`.
**Effort:** L. **Inspired by:** SimpleX NTF split `[V]`, Session encrypted payload `[V]`, Matrix id-only push `[K]`. **License:** design only; relay and mailbox ops are new Swell code.

### U2. Multi-device with one identity, per-device sessions

**Today:** protocol address is `(hex(channelId), 1)`; deviceId is fixed (PROTOCOL §2).
**Sketch:**
1. Primary shows a QR with a one-time link secret and its onion or mailbox queue. The new device connects directly (Tor) or through a mailbox and sends its own prekey bundle, authenticated by the secret. This is Delta Chat's "device add over direct P2P" idea `[V]`.
2. The new device gets deviceId 2..4 under the **same identity key** (derivable from the phrase, PROTOCOL §2). It never receives ratchet state; each contact opens a separate session to it.
3. STATE (PROTOCOL §5) carries a list of device endpoints; senders encrypt once per device (libsignal supports multiple device ids per address) and PUT into each device's queue.
4. Contacts see a "new device" event, as V2.md already states. Safety numbers stay valid because the identity key is the same.
5. History: encrypted transfer from the primary over the same direct link (Signal's "synchronized start" idea `[V: blog title]`), or from a backup (U3).
**Effort:** L. **Inspired by:** Signal linked devices `[K]`, Delta Chat Iroh `[V]`, SimpleX link-quantum `[V]`. **License:** design ideas. **Caveat:** a stolen secondary device holds the identity key; offer "lock device" and remote unlink.

### U3. Encrypted backup that cannot desync ratchets

**Sketch:**
1. Backup key = `HKDF(entropy, info="swell backup v1")` from the same 12-word phrase, so the user keeps one secret (Signal derives the Backup Key from one `AccountEntropyPool` `[V]`). Optional extra passphrase through Argon2id for a second factor.
2. Archive = length-delimited records (contacts, per-contact reconnect secret, settings, messages, small media), chunked, padded, AEAD per chunk, with a random header. Format modelled on Signal's "encrypted series of length-delimited protos" `[V]`.
3. **Do not back up ratchet state.** Restoring an old ratchet state can re-use keys or desync. After restore the app opens fresh sessions (same identity key, so the "changed identity never trusted" rule still holds); contacts accept a new session from an unchanged identity. Needs a rendezvous: a reconnect secret per contact, kept in the backup, used to request a new bundle from the contact's queue.
4. Destination: user's Files/iCloud Drive/Dropbox/NAS (ciphertext only) or on-device. Nothing runs on a Swell server, so no cost and nothing to subpoena.
**Effort:** M (format and restore) plus S for the reconnect step. **Inspired by:** Signal Secure Backups `[V]`, SimpleX DB export `[V]`. **License:** design only; do not reuse `backup.proto`.

### U4. Post-quantum ratchet (SPQR / Triple Ratchet) via libsignal bump

**Sketch:** bump libsignal 0.86.5 to a version containing `triple_ratchet.rs` (first seen at v0.93.0, so choose a current tag such as 0.106.x after reading its release notes) on Android and iOS together; update the interop fixtures (`interop/fixtures`) and `tools/interop-gen.sh`; add a version byte so old and new sessions coexist for a defined window (V2.md plans 180 days for protocol upgrades). Because v0.100 made SPQR mandatory for all sessions `[V]`, test old-to-new session establishment and mailbox messages in flight across an upgrade. Measure bandwidth: the ML-KEM braid adds chunks to messages, which matters on Tor and for the 48 KiB mailbox part size (PROTOCOL §5 FILE_PART). Do not hand-roll; the Rust crate is the reference `[V: SparsePostQuantumRatchet README]`.
**Effort:** M (guess; the risk is Swift/Kotlin API drift, 20 minor versions). **Inspired by:** Signal SPQR `[V]`. **License:** AGPL libsignal already used as a dependency; do not copy source into Swell.

### U5. Groups: sender keys now, MLS only when ordering is solved

**Options.**
- *Sender keys* (libsignal `sender_keys.rs`, `group_cipher.rs` `[V]`; Megolm is the same family `[K]`): each member distributes a sender-key chain through existing pairwise PQXDH sessions; messages fan out over pairwise queues. Cheap, works with no server, fits small groups (guess: up to 32). Weaker post-compromise security until the sender key is rotated; rekey on every member change.
- *MLS* (RFC 9420 `[V: rfc-editor.org/rfc/rfc9420]`, OpenMLS MIT `[V]`): tree-based, strong post-compromise security, good for 100+ members. Needs a **Delivery Service** to order commits; Swell has none. Possible fix: the group admin's mailbox queue is the sequencer. A post-quantum MLS ciphersuite is still a draft `[K, unverified]`, so a V2.md claim of "PQ hybrid MLS" is conditional.
**Recommendation:** ship sender-key groups ≤ 32 members in 1.2 (admin-signed membership list, rekey on change, no group ID visible to mailboxes), keep MLS as a research spike for 2.0. Scope the first group release to text and small files.
**Effort:** M (sender keys), L (MLS). **Inspired by:** Signal `[V]`, Matrix `[K]`, SimpleX pairwise mesh `[V]`, Delta Chat serverless membership `[V]`. **License:** design; libsignal dependency fine; OpenMLS MIT fine.

### U6. Anti-spam for invite-only IDs (capabilities, not puzzles)

Swell already has one-time invite secrets, onion client authorisation and mailbox PoW (PROTOCOL §3, §6) **shipped**. Gaps I see from reading the spec (my analysis):
1. **PUT is open to anyone who knows a queue id** (PROTOCOL §6: `PUT raw32 queueId`). A leaked or shared queue id lets someone fill 200 envelopes / 2 MB. Fix: queue created with a sender-auth public key; each contact gets its own write key (or its own sub-queue) and PUT must carry a signature. This is SimpleX's per-queue sender key idea `[V: simplex-messaging.md]` applied to Swell's mailbox. Revoke one contact without rotating everything.
2. **First-contact limits ("message requests")**: until accepted, a new contact may send only one small text (≤ 1 KiB), no files or calls. Session and Signal use message requests `[K]`.
3. **Invite hygiene:** show invite state (waiting, used, expired); allow "approve before chat opens"; cap pending requests; make an invite revocable.
4. **Replace home-made PoW over time:** CPU PoW disadvantages low-end phones and favours GPU attackers. Use Tor's built-in onion-service PoW defence for introduction floods `[V: spec.torproject.org/hspow-spec/ HTTP 200; details K]` plus Privacy Pass-style blind tokens already planned in V2.md 2.7.
5. Report/block already exists (Play/App Store 1.2 compliance).
**Effort:** S (message-request limits, invite states), M (write keys, tokens). **Inspired by:** SimpleX sender keys `[V]`, Signal/Session message requests `[K]`, Tor PoW `[V]`. **License:** design only.

### U7. Faster Tor path for the mailbox

(My analysis; confirm by measurement.) Run public mailboxes as **single onion services** (`HiddenServiceSingleHopMode`, non-anonymous server side), because a mailbox does not need to hide its own location; this shortens the rendezvous path. Keep one warm circuit per active mailbox, fetch one queue per request with isolation as today (PROTOCOL §6), and measure p50/p95/p99 as ROADMAP demands. Compare with SimpleX's fixed 16 KB blocks (`overview-tjr.md` line 168 `[V]`): Swell's frame buckets go up to 256 KiB, so small messages could use a smaller first bucket.
**Effort:** S to M. **License:** none (Tor config).

### U8. Onboarding that is faster than every Tor app

Universal link `https://<swell-domain>/i#<code>`: the invite sits in the URL **fragment**, which browsers do not send to the server, so the web host never sees it. The app opens straight to "Add contact" with the code filled in. Add QR (camera) and a share sheet. A "trust ladder" like Threema's (unverified, verified in person) `[K]` ties into safety numbers.
**Effort:** S to M. **Inspired by:** SimpleX one-time links `[V]`, Threema trust levels `[K]`, Delta Chat SecureJoin `[V]`. **License:** none.

## 3. Anti-4.3 positioning (iOS)

Evidence base: [APPSTORE-4.3-MARKET.md](APPSTORE-4.3-MARKET.md) rates risk moderate-to-high; closest listing Hushy (one-time invites, Tor, no phone/email, PQ), then Rún and SimpleX. Apple's wording: 4.3(b) targets apps that are indistinguishable from what exists; it does not name messengers `[V: per that audit]`. I cannot predict an outcome.

**What visibly makes Swell different (only claim what the build does)**
1. **Mailbox you can see.** A screen "Your mailbox": how many encrypted messages are waiting, when they expire (14 days), a "Fetch now" button over Tor. No other app in the audit shows this.
2. **Per-contact rotating channels.** On each contact page show "Channel changes in N days" and the current address age. Make the contact flow the product: one-time code, "approve this person" request card, then a private channel.
3. **Network line.** A permanent honest status ("Connecting to Tor", "Mailbox reached 12 s ago"). It also helps reviewers who wait for Tor.
4. **Own art direction.** Night-ocean pixel art, wave icon, Pixelify Sans. This is the strongest visual separator in the audit. Keep it consistent from icon to empty states.
5. **Safety number screen with a plain-language "what we can and cannot see" card** (the published exposure matrix, ARCHITECTURE §8).
6. **Sealed push and multi-device** once built (U1, U2): screenshots of "device list" and "notifications reveal nothing" are hard to copy.

**First-run experience**
- Screen 1: the pitch in one sentence: "Add contacts by invitation. Messages travel through Tor. Open Swell on iPhone to receive messages waiting in your mailbox."
- Create identity in one tap, write down the 12 words (confirm 3 of them), name optional.
- Land on an empty chat list with a big "Add contact" button and a visible demo contact for review (IOS-PLAN §8).
- No account, no permissions asked up front; ask for notifications only after the first contact exists, with a plain explanation.

**Contact flow (must work end to end before submission)**
iPhone creates a code (needs the v3 mailbox invite, IOS-PLAN §6 M7), shows QR plus share link, the other side scans or pastes, request card appears, accept, safety-number prompt. The audit flags paste-only add-contact and missing report/disappearing-message UI as 2.1/2.3/1.2 risks; fix those first.

**Screenshots (real screens only)**
1. Welcome with the one-sentence pitch. 2. Create/scan invite QR. 3. Chat with network line. 4. Your mailbox screen. 5. Safety number and exposure card. 6. (Later) Linked devices. Headlines from the audit work; add "Mailbox: messages wait up to 14 days".

**What NOT to imitate**
- Do not copy SimpleX's blue geometry, Session's green-on-dark, Signal's blue bubble, or Hushy's feature-list ordering/copy.
- Do not use superlatives: "first", "only", "unbreakable", "anonymous", "untraceable" (PRIVACY-RULES 15). Do not name competitors in the listing.
- No Tor-browser-style onion iconography copied from other Tor apps; no phone-book import; no "Session ID"-style copyable public handle.
- Do not hide that iOS receives only when opened or via the optional push. Say it in-app and in review notes.
- Do not submit with fake or incomplete flows (QR, report, disappearing messages shown but not working).
- Keep Jiji Labs' other chat apps out of the listing, assets and strings (IOS-PLAN §7).

## 4. Proposed roadmap

Score = user value (1-5) x differentiation (1-5) / effort (S=1, M=2, L=4). Numbers are my judgement, not measurement.

| Item | Value | Diff. | Effort | Score |
|---|---|---|---|---|
| U6a message requests + invite states | 4 | 3 | S=1 | 12.0 |
| U8 invite link in URL fragment + QR + share | 5 | 3 | S=1 (use 1.5) | 10.0 |
| U6b per-contact write keys on mailbox | 4 | 4 | M=2 | 8.0 |
| U4 libsignal bump + SPQR | 3 | 5 | M=2 | 7.5 |
| U3 encrypted backup (phrase-derived) | 5 | 3 | M=2 | 7.5 |
| U7 single-onion mailbox + latency measurement | 3 | 3 | S-M=1.5 | 6.0 |
| U1 sealed push for iOS | 5 | 5 | L=4 | 6.25 |
| U5 sender-key groups (≤ 32) | 5 | 3 | M=2 | 7.5 |
| U2 multi-device | 4 | 4 | L=4 | 4.0 |
| MLS groups, group calls | 3 | 4 | L=4+ | below 3 |

### 1.1 (Android hotfix line and iOS TestFlight to first App Store build)
- iOS: finish the contact flow (create code, QR, report, disappearing timer) and the "Your mailbox" screen; the 4.3 audit says these gaps are the biggest risk.
- U8 invite links with fragment; U6a message requests and invite states.
- Pin and document the libsignal version; run U4 as a spike (build, interop tests, bandwidth); do **not** advertise SPQR until it passes.
- Measure Tor latency on iPhone and Pixel with p50/p95/p99 (ROADMAP targets).
- Reproducible-build hashes for Android published (ROADMAP phase 4).

### 1.2
- U4 shipped (SPQR via bumped libsignal), with a compatibility window.
- U3 encrypted backup and restore with reconnect.
- U6b write keys on the mailbox; Tor PoW defence on the mailbox onion service; U7.
- U1 sealed push (opt-in, off by default, with disclosure text first). It is long; start with the relay and mailbox ops in 1.1 behind a flag if the owner approves the privacy disclosure (IOS-PLAN §9 item 3).
- U5 sender-key groups up to 32 members.

### 2.0
- U2 multi-device and history sync.
- Voice calls per CALLS.md (relay-only, two operators), then 1:1 video.
- MLS research spike resolved: adopt if ordering via admin mailbox and a PQ ciphersuite are available, else keep sender keys.
- Blind tokens (V2.md 2.7), Nym mode, run-your-own node.
- Independent audit, published before any claim of security superiority (COMPARISON.md already requires this).

## 5. Honest limits of this research

- I read protocol docs and READMEs; I did not run any of the apps, build their code or measure anything. Competitor feature claims are their published statements or my memory (`[K]`).
- Several GitHub pages (Cwtch docs, Threema, libthreema, Briar blog posts) were not reachable or not rendered; those rows are lower confidence.
- All Swell upgrade designs are untested sketches. Effort and score figures are guesses.
- The App Store audit is an assessment, not a prediction of Apple's decision.

## Sources

- libsignal: https://github.com/signalapp/libsignal (`rust/protocol/src/{triple_ratchet,sender_keys,group_cipher,sealed_sender}.rs`, `rust/{message-backup,account-keys,zkgroup,usernames,device-transfer}`, `RELEASE_NOTES.md`)
- SPQR: https://github.com/signalapp/SparsePostQuantumRatchet ; https://signal.org/blog/spqr/ ; https://signal.org/blog/pqxdh/ ; https://eprint.iacr.org/2025/078
- Signal: https://signal.org/blog/signal-private-group-system/ ; https://signal.org/blog/sealed-sender/ ; https://signal.org/blog/backup-improvements/ ; https://github.com/signalapp/Signal-Android/tree/main/reproducible-builds ; https://github.com/signalapp/Signal-iOS/tree/main/SignalNSE
- SimpleX: https://github.com/simplex-chat/simplexmq/tree/stable/protocol (`push-notifications.md`, `simplex-messaging.md`, `xftp.md`, `overview-tjr.md`) ; https://simplex.chat/blog/ (20221108, 20231125, 20241014, 20250114)
- Session: https://github.com/session-foundation/session-push-notification-server ; https://github.com/session-foundation/session-ios ; https://github.com/session-foundation/libsession-util ; https://getsession.org/session-protocol-v2 ; https://www.privacyguides.org/news/2025/12/03/session-messenger-adds-pfs-pqe-and-other-improvements/
- Briar: https://code.briarproject.org/briar/briar-mailbox ; https://briarproject.org/how-it-works/
- Cwtch: https://docs.cwtch.im/docs/groups/introduction
- Delta Chat / chatmail: https://github.com/chatmail/core
- Matrix: https://github.com/matrix-org/vodozemac ; https://github.com/openmls/openmls ; https://www.rfc-editor.org/rfc/rfc9420
- Threema: https://github.com/threema-ch/app-remote-protocol ; https://github.com/threema-ch/threema-android
- Berty: https://github.com/berty/berty ; https://github.com/berty/weshnet
- Quiet: https://github.com/TryQuiet/quiet
- Tor onion-service PoW: https://spec.torproject.org/hspow-spec/
- Swell: [PROTOCOL.md](PROTOCOL.md), [COMPARISON.md](COMPARISON.md), [IOS-PLAN.md](IOS-PLAN.md), [ROADMAP.md](ROADMAP.md), [V2.md](V2.md), [CALLS.md](CALLS.md), [APPSTORE-4.3-MARKET.md](APPSTORE-4.3-MARKET.md)
