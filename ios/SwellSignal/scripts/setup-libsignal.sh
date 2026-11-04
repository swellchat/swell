#!/bin/sh
# One-time setup for ios/SwellSignal: libsignal v0.86.5 (same as Android) as a local Swift package.
#   - checks it out at ios/vendor/libsignal (git-ignored; set LIBSIGNAL_DIR to reuse an existing checkout)
#   - builds libsignal_ffi for macOS (debug) so `swift test` can link; needs rustup + protoc
#   - downloads the iOS prebuilt libsignal_ffi.a files used by the Xcode app
set -eu
TAG=v0.86.5
HERE=$(cd "$(dirname "$0")/.." && pwd)
DIR=${LIBSIGNAL_DIR:-$HERE/../vendor/libsignal}
mkdir -p "$HERE/../vendor"
if [ ! -d "$DIR/.git" ]; then git clone --depth 1 --branch "$TAG" https://github.com/signalapp/libsignal.git "$DIR"; fi
[ -L "$HERE/../vendor/libsignal" ] || [ -d "$HERE/../vendor/libsignal" ] || ln -s "$DIR" "$HERE/../vendor/libsignal"
(cd "$DIR" && PATH="/opt/homebrew/opt/rustup/bin:$PATH" cargo build -p libsignal-ffi --features libsignal-bridge-testing)
ARCH=libsignal-client-ios-build-$TAG.tar.gz
SUM=a3df979aa39b307ac4ef0f3fd59536daab5d6b3f8257c7aefe5f0470625f2582
if [ ! -f "$DIR/target/aarch64-apple-ios/release/libsignal_ffi.a" ]; then
  T=$(mktemp -d); curl -sL "https://build-artifacts.signal.org/libraries/$ARCH" -o "$T/$ARCH"
  echo "$SUM  $T/$ARCH" | shasum -a 256 -c - >/dev/null
  tar xzf "$T/$ARCH" -C "$DIR"; rm -rf "$T"
fi
echo "libsignal ready: $DIR"
