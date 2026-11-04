FROM eclipse-temurin:21-jre-jammy
# Tor from the Tor Project repository (current stable, with onion-service proof-of-work), not Ubuntu's old package.
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates wget gnupg \
 && wget -qO- https://deb.torproject.org/torproject.org/A3C4F0F979CAA22CDBA8F512EE8CBC9E886DDD89.asc | gpg --dearmor > /usr/share/keyrings/tor-archive-keyring.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/tor-archive-keyring.gpg] https://deb.torproject.org/torproject.org jammy main" > /etc/apt/sources.list.d/tor.list \
 && apt-get update && apt-get install -y --no-install-recommends tor deb.torproject.org-keyring \
 && apt-get purge -y wget gnupg && apt-get autoremove -y && rm -rf /var/lib/apt/lists/* \
 && useradd -r -u 10001 -M -d /nonexistent swell && useradd -r -u 10002 -M -d /nonexistent swelltor
COPY app/ /opt/mailbox/
COPY torrc /etc/tor/swell-torrc
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
