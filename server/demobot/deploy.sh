#!/bin/sh
# Builds the peer on the Mac, deploys the demo bot to the VPS (~/swell-demobot) and prints its demo code.
set -e
cd "$(dirname "$0")"
( cd ../../android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :peer:installDist -q )
rm -rf app && cp -R ../../android/peer/build/install/peer app
rsync -a --delete --exclude data ./ your-server:swell-demobot/
ssh your-server 'cd ~/swell-demobot && mkdir -p data && sudo -n chown 10003:10003 data 2>/dev/null || chmod 777 data; docker compose up -d --build 2>&1 | tail -2'
for i in $(seq 1 60); do
  C=$(ssh your-server 'cd ~/swell-demobot && docker compose logs --no-log-prefix demobot 2>/dev/null | grep -o "DEMO_CODE swell:[A-Za-z0-9_-]*" | tail -1' || true)
  [ -n "$C" ] && { echo "$C"; exit 0; }
  sleep 5
done
echo "no demo code yet; check: ssh your-server 'cd ~/swell-demobot && docker compose logs --tail 30'"
