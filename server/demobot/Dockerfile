FROM eclipse-temurin:17-jre-jammy
# Swell demo contact for app reviewers. Tor from the Tor Project repository.
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates wget gnupg \
 && wget -qO- https://deb.torproject.org/torproject.org/A3C4F0F979CAA22CDBA8F512EE8CBC9E886DDD89.asc | gpg --dearmor > /usr/share/keyrings/tor-archive-keyring.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/tor-archive-keyring.gpg] https://deb.torproject.org/torproject.org jammy main" > /etc/apt/sources.list.d/tor.list \
 && apt-get update && apt-get install -y --no-install-recommends tor deb.torproject.org-keyring \
 && apt-get purge -y wget gnupg && apt-get autoremove -y && rm -rf /var/lib/apt/lists/* \
 && useradd -r -u 10003 -M -d /nonexistent swellbot
COPY app/ /opt/peer/
USER 10003
# Native libraries are unpacked to java.io.tmpdir: on disk, not the small RAM-backed /tmp.
ENV DATA_DIR=/data TOR=/usr/bin/tor JAVA_OPTS="-Xmx112m -XX:+UseSerialGC -Djava.io.tmpdir=/data/tmp"
ENTRYPOINT ["/bin/sh", "-c", "mkdir -p /data/tmp && rm -rf /data/tmp/* && exec /opt/peer/bin/peer bot --name 'Swell Demo'"]
