# Third-party notices

Swell is licensed under the GNU Affero General Public License, version 3 ([LICENSE](LICENSE)). It includes the components below, each under its own license. Versions are the ones used by the code in this repository.

## Both apps

- **libsignal** 0.86.5, Signal Messenger, LLC. AGPL-3.0-only. https://github.com/signalapp/libsignal. libsignal lists its own Rust dependencies in its `acknowledgments/` directory.
- **Tor**, The Tor Project, Inc. BSD 3-Clause. https://www.torproject.org. Tor builds also include other components such as OpenSSL and libevent, under their own licenses.

## Android

- **tor-android** 0.4.9.14 and **jtorctl** 0.4.5.7, Guardian Project. BSD 3-Clause. https://github.com/guardianproject/tor-android
- **SQLCipher for Android** 4.20.0, Zetetic LLC. BSD-style. https://github.com/sqlcipher/sqlcipher-android
- **ZXing core** 3.5.3. Apache-2.0. https://github.com/zxing/zxing
- **AndroidX and Jetpack Compose**, The Android Open Source Project. Apache-2.0.
- **Kotlin standard library and kotlinx.coroutines**, JetBrains. Apache-2.0.
- **AutoValue annotations** 1.6.3, Google. Apache-2.0.

To list every Android dependency and version:

```sh
cd android && ./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

## iOS

- **Tor.framework** 409.15.3, The iCepa Contributors. MIT. https://github.com/iCepa/Tor.framework
- **libsignal Swift bindings** from libsignal 0.86.5, as above.

## Fonts

`docs/legal/third-party/PixelifySans-OFL.txt` is the license of Pixelify Sans, used in older artwork. The current apps use system fonts.
