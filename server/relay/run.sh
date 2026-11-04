#!/bin/sh
# Starts coturn with the shared secret read from a file (never on the command line of the host).
SECRET=$(cat /run/swell/secret)
exec turnserver -c /etc/coturn/turnserver.conf --static-auth-secret="$SECRET"
