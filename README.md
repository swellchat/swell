<p align="center"><img src="docs/brand/logo.png" width="220" alt="Swell"></p>

# Swell

A private messenger for Android and iPhone. No phone number, no email, no account on our servers.

> **Status:** early. Swell has had an internal security review. It has **not** been independently audited, and builds are not yet reproducible. Do not rely on it where your safety depends on it.

## How it works

- **Identity:** keys on your phone. You add people with a one-time invite link or QR code.
- **Encryption:** [libsignal](https://github.com/signalapp/libsignal) (PQXDH, Double Ratchet, SPQR). Swell adds no custom cryptography.
- **Network:** all traffic goes over Tor onion services. Android can deliver directly, phone to phone. iPhone always uses a mailbox.
- **Mailbox:** holds encrypted messages for a queue until they are fetched. It has no accounts. The operator can still see timing and sizes per queue, so Swell tries to make queues hard to link: see [docs/DESIGN-SWELL-LANES.md](docs/DESIGN-SWELL-LANES.md).
- **On the phone:** the database is encrypted. There are no analytics, ads, crash reporting or contact upload.

Wire format: [docs/PROTOCOL.md](docs/PROTOCOL.md). Rules every change must follow: [docs/PRIVACY-RULES.md](docs/PRIVACY-RULES.md).

## Build

**Android and mailbox** (JDK 17):

```sh
cd android
./gradlew :app:assembleDebug
./gradlew :mailbox:installDist
```

**iPhone** (Xcode, Rust, XcodeGen):

```sh
ios/SwellSignal/scripts/setup-libsignal.sh   # libsignal v0.86.5 at ios/vendor/libsignal
cd ios/Swell && xcodegen
```

Then open `Swell.xcodeproj`, set your own signing team and build. Tor comes in as a dynamic framework from `ios/TorDynamic`.

**Run your own mailbox:** see `server/mailbox`. Set your own host in `deploy.sh`.

Store builds are signed with keys we hold, so your own build will carry a different signature.

## Tags

Each released version has a tag, for example `android-1.1.2`.

## Security

Report problems as described in [SECURITY.md](SECURITY.md).

## License

GNU Affero General Public License, version 3. See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

Made by Jiji Labs (PT JIJI LABS DIGITAL).
