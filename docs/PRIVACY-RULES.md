# Privacy Rules

Hard rules. A change that breaks one is not merged.

## Data

1. No server-side account, profile, contact list, social graph or message log.
2. No phone number, email, username or device identifier is ever requested or sent.
3. No contact-book access (`READ_CONTACTS`), no SMS, no call log, no location.
4. All network traffic goes through Tor. No direct connection to any host, including Jiji Labs.
5. Mailbox stores only encrypted envelopes, deletes on fetch or after 14 days, and keeps no access logs.
6. Report inbox stores reports for 90 days, then deletes them, unless law requires longer.

## Code and dependencies

7. No analytics, advertising, attribution, crash-reporting or Firebase SDKs. No Google Play Services dependency.
8. Cryptography only from libsignal and the platform (Keystore, SQLCipher). No custom primitives.
9. Every new dependency is reviewed for network calls and listed in `DEPENDENCIES.md` with its reason.
10. Logs never contain keys, plaintext, safety numbers, onion addresses or queue IDs. Release builds log nothing to logcat.
11. Builds are reproducible; release APK hashes are published.

## App defaults

12. Read receipts and typing indicators off; screenshot blocking on; notification previews hidden.
13. `android:allowBackup="false"`; no cloud backup.
14. Disappearing messages available per chat.

## Free

15a. Swell is free for everyone: no price, in-app purchases, donations, subscriptions or ads. No feature is ever paid.

## Claims

15. Never claim "untraceable", "anonymous", "unhackable", "100% secure" or "more secure than Signal".
16. Every privacy claim in the store listing or website must point to a section of this repo or a published test.
