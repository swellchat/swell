#!/bin/sh
# Starts as root only to fix ownership, then runs Tor and the mailbox as two different users,
# so a bug in the mailbox cannot read the onion service key (audit M-08).
set -e
mkdir -p /data/queues /data/tor/data /data/tor/hs
chown -R 10002:10002 /data/tor && chmod 700 /data/tor /data/tor/data /data/tor/hs
chown -R 10001:10001 /data/queues && chmod 700 /data/queues
chmod 711 /data
setpriv --reuid=10002 --regid=10002 --clear-groups tor -f /etc/tor/swell-torrc &
TOR=$!
setpriv --reuid=10001 --regid=10001 --clear-groups /opt/mailbox/bin/mailbox &
APP=$!
# Exit (and let Docker restart us) if either process dies. /proc, not kill -0: with cap_drop ALL, root may not signal other users.
while [ -d /proc/$TOR ] && [ -d /proc/$APP ]; do sleep 5; done
exit 1
