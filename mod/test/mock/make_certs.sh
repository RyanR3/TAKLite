#!/usr/bin/env bash
# Throwaway certificates for the mock TAK server: a CA, a server certificate and a client
# certificate, plus the PKCS12 files TAK Lite would hold after enrollment. Password "testpass".
# Usage: make_certs.sh OUTDIR
set -euo pipefail
out=$1
mkdir -p "$out"
cd "$out"
pass=testpass
q() { "$@" >/dev/null 2>&1; }
q openssl req -x509 -newkey rsa:2048 -nodes -days 30 -keyout ca.key -out ca.pem -subj "/CN=TAK Lite test CA"
q openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=localhost"
q openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out server.pem -days 30 \
    -extfile <(printf 'subjectAltName=DNS:localhost,IP:127.0.0.1')
q openssl req -newkey rsa:2048 -nodes -keyout client.key -out client.csr -subj "/CN=taklite-test-user"
q openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out client.pem -days 30
q openssl pkcs12 -export -inkey client.key -in client.pem -out client.p12 -passout pass:$pass -name client
# Java only treats a PKCS12 certificate as trusted when keytool imports it, so the trust
# store is made with keytool (openssl's -nokeys export loads as an empty trust store).
rm -f truststore.p12
q keytool -importcert -noprompt -alias ca -file ca.pem -keystore truststore.p12 -storetype PKCS12 -storepass $pass
echo "certificates written to $out"
