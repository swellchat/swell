# Speed research: instant open, WhatsApp-class delivery (2026-11-04)

Owner goal: the app opens instantly (no "Getting ready"), and messages between two open apps arrive in about 1 s. This file records what we learned and what we build. Measured numbers are marked; everything else is a plan.

## Measured (Deck → mailbox over Tor, 2026-11-04)
- Fresh circuit + first request: median 3.58 s before single-hop, 2.92 s after (`HiddenServiceSingleHopMode`).
- Stream open: 1.96 s. One request on an open stream: 0.84 s.
- The deployed mailbox (old core.jar) closed the stream after one frame, so every request paid the 2–3 s setup. Current code keeps it open (InboundServer multi-frame); deploy pending.
- iOS polled every 12 s and Android every 20–40 s in the foreground. This polling was the largest delay.

## Lessons from open-source messengers
- **SimpleX (SMP):** clients subscribe to many queues over one long-lived connection, and the server pushes as soon as a message lands. This is the model for our `0x17 WAIT` (≤ 8 queues per call). SimpleX also offers per-connection transport isolation for people who don't want the server to link queues. Swell follows the same idea: one stream by default, and a later "maximum privacy" option uses one circuit per queue.
- **Signal / WhatsApp:**
  - one persistent connection while foregrounded;
  - optimistic UI (the message shows at once with a clock);
  - the UI is drawn from the local DB before any network work;
  - content-free wake-up push in the background.
  Swell copies this pattern, not the code.
- **Tor conflux (traffic splitting):** lowers latency for exits. Onion-service support was deferred upstream, and Arti did not support it for onion services in 2025. Not usable for us yet; re-check each Arti release.
- **Arti (Rust Tor):** a possible replacement for the C tor on iOS later. A single library also avoids OpenSSL symbol clashes (the build-1 crash). Evaluate after build 2.
- **Briar / Cwtch:** pure peer-to-peer needs both phones online at once. Our mailbox avoids that, and is the reason iPhone works at all.

## Build list
1. [x] Single-hop onion mailbox (deployed).
2. [x] Multi-frame streams, `PEEK 0x16`, `WAIT 0x17` (code + tests; deploy pending owner OK).
3. [ ] Clients: one warm stream per mailbox in the foreground, WAIT loop, sends on the same stream, optimistic UI. iOS + Android in progress.
4. [ ] Instant open: chat list from local store in < 0.5 s; no "Getting ready" screen; Tor connects silently. iOS + Android in progress.
5. [ ] Server scale: today `InboundServer` allows 64 connections and `MAX_WAITS` = 48, which is fine for testing but not for launch. Plan:
   - mailbox on Java 21 with virtual threads (the handler blocks during WAIT, so each waiter costs only KBs);
   - a configurable cap of several thousand waiters;
   - a load test with 1,000 simulated waiters within the 256 MB container.
   Core stays Java 17 for Android: the mailbox injects its own thread factory.
6. [ ] Content-free push for iOS background delivery (APNs wake-up only, rotating tokens, no sender info).
7. [ ] Second mailbox on another provider; clients try both.
8. [ ] Latency dashboard on the Deck monitor: warm request time every 15 min, alert if over 2 s.

## Sources
- Tor GitLab, conflux for onion services deferred: https://gitlab.torproject.org/tpo/core/tor/-/issues/40716
- Arti 1.4.6 release (conflux groundwork): https://blog-7551d3.pages.torproject.net/arti_1_4_6_released/
- DarkHorse (research, lower onion-service latency): https://arxiv.org/pdf/2307.02429
