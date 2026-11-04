# Swell v1 Architecture

Version 3.0 — 2026-10-24. Supersedes the mixnet and voice sections of [whitepaper-v2.1.md](whitepaper-v2.1.md) for v1.
Threat model, security goals (G-01…G-06) and risk register of the whitepaper still apply.

## 1. Principles

1. **Hold nothing.** The operator (Jiji Labs) must not be able to read, list, link or hand over users, contacts or messages.
2. **Reuse proven parts.** libsignal for cryptography, Tor for transport. No custom cipher, ratchet or anonymity network.
3. **Fail closed.** Invalid, replayed or unauthenticated data is dropped without a crash or an oracle.
4. **Say what leaks.** Every mode has a published exposure matrix ([§8](#8-exposure-matrix)).

## 2. Components

| Component | Where | Role |
|-----------|-------|------|
| App | User's phone | Identity keys, sessions, plaintext, UI. Only place plaintext exists. |
| Tor (Arti or tor-android) | Inside the app | Onion service for receiving; circuits for sending; bundled Snowflake/obfs4 bridges. |
| Mailbox (optional) | Jiji Labs or self-hosted, onion service only | Stores encrypted envelopes for offline contacts. TTL 14 days, size quota. |
| Report inbox | Jiji Labs | Receives only messages a user chooses to report. |
| Call relays (v1.5) | Two operators, public VPS | Forward encrypted call audio; see [CALLS.md](CALLS.md). |

There is no account server, no key directory, no contact discovery service and no push server.

## 3. Identity

- On first launch the app generates a libsignal identity key pair and a 12-word recovery phrase that derives it.
- No phone number, email or username exists anywhere.
- The **safety number** (fingerprint of both identity keys) is shown per contact; QR verification marks a contact as verified.
- An identity-key change for a contact blocks sending until the user accepts it (high-severity warning).

## 4. Contact model

1. **Invite.** Alice creates a one-time invite: a link or QR holding her current onion address, a one-time pre-key bundle and an invite secret. Expiry: 24 h. Use: once.
2. **Handshake.** Bob's app connects to Alice's onion address over Tor and runs PQXDH with the bundle, bound to the invite secret.
3. **Pairwise channel.** Each contact pair gets its own onion client-authorization key and mailbox queue ID. Other contacts cannot reach or recognize the channel.
4. **Rotation.** Onion address and queue IDs rotate on a schedule; the next values are sent inside the encrypted session.
5. **Optional public address.** A user may publish a long-lived address (e.g. in a bio) that only accepts contact requests; accepted requests move to a pairwise channel. The address can be deleted at any time.
6. **Used-invite warning.** If an invite was already redeemed, the inviter sees who redeemed it (safety number) and is prompted to verify.

## 5. Message flow

1. App encrypts with the libsignal session (Double Ratchet + SPQR), pads to a size bucket (1, 4, 16, 64 KiB; attachments chunked into 64 KiB), and wraps in a versioned envelope with expiry.
2. If the contact's onion service is reachable, send directly over Tor.
3. Otherwise put the envelope in the contact's mailbox queue (if both sides enabled a mailbox).
4. Receiver pulls the queue over Tor, verifies, decrypts, advances the ratchet with crash-safe state write, deletes the envelope from the mailbox.
5. Read receipts and typing indicators: off by default.

## 6. Running in the background (Android)

- A foreground service keeps Tor and the onion service alive while the user allows it. Type: `remoteMessaging` (preferred) or `specialUse` with a Play Console justification. Do not use `dataSync` (Android 15 caps it at 6 h per 24 h).
- When the service is off or killed, a `WorkManager` job polls the mailbox periodically.
- No FCM. Optional UnifiedPush distributor for wake-ups, off by default and disclosed.
- Do not request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in v1; show a guide to system settings instead.

## 7. Storage, backup, recovery

- SQLCipher database; key wrapped by Android Keystore (StrongBox when available).
- Screenshot blocking (`FLAG_SECURE`) and hidden notification previews: on by default.
- Encrypted backup file (Argon2id passphrase + AEAD), saved where the user chooses. `android:allowBackup="false"`; no Google cloud backup.
- Recovery restores identity from the phrase; messages restore only from a backup file.
- Delete identity: wipes keys, database and Tor state, notifies contacts that the identity is gone.

## 8. Exposure matrix

| Observer | Can see | Cannot see |
|----------|---------|------------|
| Local network / ISP | That Tor is used; traffic volume and timing | Contacts, content |
| Tor relays | Encrypted circuits | Content; both ends of a conversation (unless a global observer correlates timing) |
| Mailbox | Queue ID, envelope size bucket, store/fetch time | Identity, IP, content, which queues belong to the same person |
| UnifiedPush distributor (if enabled) | Wake-up time for an endpoint | Sender, content |
| Contact | Messages you send, your identity key and safety number | Your IP, your other contacts |
| Jiji Labs | Reports users choose to send; mailbox data above if it runs the mailbox | Everything else |
| Google Play | Install and update of the app | Anything inside the app |

## 9. Abuse handling without data

- Block: local, immediate, and the blocked contact's channel keys are discarded.
- Report: user selects messages; the app sends them with the reported contact's safety number to the report inbox over Tor. Nothing else leaves the device.
- Invite-only contact model plus per-queue rate limits at the mailbox limit spam.

## 10. Later (not v1)

- Groups: MLS (RFC 9420) via OpenMLS, with a group-metadata design.
- Voice (v1.5): see [CALLS.md](CALLS.md). Dual relay with different operators, E2EE media, signaling over the Tor channel.
- Optional Nym mixnet transport behind the same transport interface.
- iOS: blocked by background limits; needs a mailbox-first design.
