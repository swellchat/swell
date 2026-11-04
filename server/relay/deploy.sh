#!/bin/sh
# Deploys the Swell TURN relay to the VPS (~/swell-relay). Creates the shared secret once and copies it
# to ~/swell-mailbox/data/turn-secret for the credential service. Never prints the secret.
set -e
cd "$(dirname "$0")"
rsync -a compose.yml turnserver.conf run.sh your-server:swell-relay/
ssh your-server 'sh -s' <<'REMOTE'
set -e
cd ~/swell-relay
umask 077
[ -s secret ] || head -c 32 /dev/urandom | base64 > secret
chmod 644 secret
cp secret ~/swell-mailbox/data/turn-secret
chmod 644 ~/swell-mailbox/data/turn-secret
docker compose up -d 2>&1 | tail -2
sleep 3
echo "udp 3478 listeners: $(ss -ulnp | grep -c ':3478 ')"
docker compose ps --format '{{.Name}} {{.Status}}'
REMOTE
