#!/bin/sh
# Builds the Swell mailbox on the Mac, copies it to the VPS (separate compose project ~/swell-mailbox,
# 0.5 CPU / 256 MB, no published ports) and (re)starts it. Prints the onion address.
set -e
cd "$(dirname "$0")"
( cd ../../android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :mailbox:installDist -q )
rm -rf app && cp -R ../../android/mailbox/build/install/mailbox app
rsync -a --delete --exclude data ./ your-server:swell-mailbox/
ssh your-server 'cd ~/swell-mailbox && mkdir -p data && docker compose up -d --build 2>&1 | tail -3'
echo "waiting for the onion address…"
for i in $(seq 1 40); do
  H=$(ssh your-server 'cd ~/swell-mailbox && docker compose exec -T mailbox cat /data/tor/hs/hostname 2>/dev/null' || true)
  # The container user owns data/ now; keep it traversable only.
  [ -n "$H" ] && { echo "MAILBOX $H"; exit 0; }
  sleep 3
done
echo "no onion address yet; check: ssh your-server 'cd ~/swell-mailbox && docker compose logs --tail 30'"
