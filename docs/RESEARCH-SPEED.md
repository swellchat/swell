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
5. [x] (code + load test done 2026-11-04; deploy waits for owner OK) Server scale: today `InboundServer` allows 64 connections and `MAX_WAITS` = 48, which is fine for testing but not for launch. Plan:
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

## Server scale: load test (2026-11-04, Mac, loopback, no Tor)
Tool: `android/mailbox` `LoadTool.kt` (real TCP clients, one parked WAIT each, then 200 PUTs at about 200/s; latency = PUT sent until the waiter's answer arrives). Server: the real mailbox `Main` with `-Xmx96m -XX:+UseSerialGC`, JDK 27 (virtual threads; the container image will be Java 21), file store.
- 1,000 waiters: 0 errors, 200/200 answered, latency p50 22 ms, p95 31 ms, max 32 ms; server heap about 1 MB used, 27 OS threads, process RSS grew 37 MB to 59 MB.
- 4,000 waiters (the default cap): 0 errors, 200/200, p50 12 ms, p95 27 ms, max 29 ms; heap 9 MB, 27 threads, RSS 39 MB to 83 MB (macOS RSS, includes JVM baseline).
- 1,000 waiters of 8 queues each: 0 errors, p50 18 ms, p95 31 ms; RSS 70 MB.
- Same 1,000 waiters on Java 17 platform threads: 817 OS threads (Linux pids_limit is 128, so it would not even start); latency the same.
These latencies exclude Tor (1 to 3 s per hop path in production). Caps are `MAILBOX_MAX_CONNECTIONS` (6000) and `MAILBOX_MAX_WAITS` (4000); WAIT takes up to 64 queues. The 256 MB container also holds Tor, so the practical ceiling will be Tor's memory per circuit, not the mailbox. Check after deploy with `docker stats`.
