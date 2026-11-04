#!/bin/sh
# Regenerates interop/fixtures (Kotlin <-> Swift libsignal transcripts). Normal test runs only verify;
# this alternates the two sides so each appends its next step. Needs JDK 17 and the libsignal setup
# (ios/SwellSignal/scripts/setup-libsignal.sh). Existing transcripts are deleted first.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/rustup/bin:$PATH"
export SWELL_INTEROP_GEN=1
rm -f "$ROOT"/interop/fixtures/*.txt
for round in 1 2 3 4 5 6; do
  (cd "$ROOT/android" && ./gradlew :core:test --tests '*InteropTest*' -q --rerun-tasks >/dev/null)
  (cd "$ROOT/ios/SwellSignal" && swift test -q 2>&1 | grep -E "error|failed" || true)
done
unset SWELL_INTEROP_GEN
(cd "$ROOT/android" && ./gradlew :core:test --tests '*InteropTest*' -q --rerun-tasks)
(cd "$ROOT/ios/SwellSignal" && swift test 2>&1 | tail -3)
