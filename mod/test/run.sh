#!/usr/bin/env bash
# Runs the mod's tests on a plain JVM (no device, no Android SDK):
#   ModTests     tile URL templates, received-marker classifier, marker uids, sizes
#   ImportTests  XML hardening, KML/GPX/CoT parsers, data package edge cases,
#                ATAK map source conversion, the local-network rule
#   E2ETests     data packages end to end against a mock TAK server (mutual TLS)
#                and a plain http server
#
# Needs: build.sh run once (it downloads the tools), JDK 17+, python3, openssl.
# Everything the tests use is generated under mod/work/test; nothing real is needed.
set -euo pipefail

MOD="$(cd "$(dirname "$0")/.." && pwd)"
TEST="$MOD/test"
TOOLS="$MOD/work/tools"
WORK="$MOD/work/test"
HTTPS_PORT=${HTTPS_PORT:-8443}   # the app's fixed TAK Server API port
HTTP_PORT=${HTTP_PORT:-18080}

log() { printf '\n==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

[ -s "$TOOLS/osmdroid.jar" ] && [ -s "$TOOLS/android-35.jar" ] || die "run mod/build.sh once first (it fetches the tools)"
for cmd in java javac python3 openssl curl; do command -v "$cmd" >/dev/null || die "'$cmd' is not installed"; done

# XML pull parser and org.json for the JVM (on Android they are part of the platform).
KXML_URL=https://repo1.maven.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.jar
KXML_SHA256=f264dd9f79a1fde10ce5ecc53221eff24be4c9331c830b7d52f2f08a7b633de2
JSON_URL=https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
JSON_SHA256=3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed
fetch() {
    local url=$1 dest=$2 sha=$3
    if [ ! -s "$dest" ]; then
        echo "downloading $(basename "$dest")"
        curl -fsSL --retry 5 --retry-delay 3 --retry-all-errors -o "$dest.part" "$url" && mv "$dest.part" "$dest"
    fi
    echo "$sha  $dest" | sha256sum -c --quiet - || die "checksum mismatch for $dest (delete it to re-download)"
}
fetch "$KXML_URL" "$TOOLS/kxml2.jar" "$KXML_SHA256"
fetch "$JSON_URL" "$TOOLS/json.jar" "$JSON_SHA256"

log "Compiling"
rm -rf "$WORK/classes" "$WORK/modstubs" "$WORK/teststubs" "$WORK/tests"
mkdir -p "$WORK/classes" "$WORK/modstubs" "$WORK/teststubs" "$WORK/tests"
JAVAC=(javac --release 8 -proc:none -nowarn -encoding UTF-8)
ANDROID="$TOOLS/android-35.jar:$TOOLS/osmdroid.jar"
quiet() { grep -v '^warning\|UnsupportedAppUsage\|^Note:\|warnings$\|^$' || true; }
"${JAVAC[@]}" -cp "$ANDROID" -d "$WORK/modstubs" $(find "$MOD/stubs" -name '*.java') 2>&1 | quiet
"${JAVAC[@]}" -cp "$ANDROID:$WORK/modstubs" -d "$WORK/classes" $(find "$MOD/src" -name '*.java') 2>&1 | quiet
[ -s "$WORK/classes/com/taklite/client/mod/ModUtil.class" ] || die "mod sources did not compile"
"${JAVAC[@]}" -d "$WORK/teststubs" $(find "$TEST/stubs" -name '*.java') 2>&1 | quiet
"${JAVAC[@]}" -cp "$WORK/classes:$WORK/modstubs:$WORK/teststubs:$ANDROID" -d "$WORK/tests" $(find "$TEST/src" -name '*.java') 2>&1 | quiet
[ -s "$WORK/tests/com/taklite/client/mod/ModTests.class" ] || die "tests did not compile"

log "Generating fixtures and certificates"
python3 -I "$TEST/mock/make_fixtures.py" "$WORK/fixtures" "$TEST/fixtures"
[ -s "$WORK/certs/client.p12" ] || bash "$TEST/mock/make_certs.sh" "$WORK/certs"
rm -rf "$WORK/fixtures/certs" && cp -r "$WORK/certs" "$WORK/fixtures/certs"

# Test stand-ins first, so their MainActivity/SettingsActivity win over the compile-time stubs.
CP="$WORK/teststubs:$WORK/tests:$WORK/classes:$WORK/modstubs:$TOOLS/osmdroid.jar:$TOOLS/kxml2.jar:$TOOLS/json.jar"
JAVA=(java -Xss512k -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote -Dtaklite.test.dir="$WORK/fixtures")
status=0

log "ModTests"
"${JAVA[@]}" -cp "$CP" com.taklite.client.mod.ModTests | grep -v '^D/\|^I/' || status=1

log "ImportTests"
"${JAVA[@]}" -cp "$CP" com.taklite.client.mod.imports.ImportTests "$WORK/fixtures" | grep -v '^D/\|^I/' || status=1

log "E2ETests (mock TAK server on $HTTPS_PORT, http on $HTTP_PORT)"
python3 -I "$TEST/mock/tak_server.py" "$WORK/fixtures" "$HTTPS_PORT" > "$WORK/tak_server.log" 2>&1 &
TAK_PID=$!
python3 -I "$TEST/mock/plain_http.py" "$WORK/fixtures" "$HTTP_PORT" > "$WORK/plain_http.log" 2>&1 &
HTTP_PID=$!
trap 'kill $TAK_PID $HTTP_PID 2>/dev/null || true' EXIT
sleep 1.5
kill -0 $TAK_PID 2>/dev/null || { cat "$WORK/tak_server.log"; die "mock TAK server did not start"; }
"${JAVA[@]}" -cp "$CP" com.taklite.client.mod.imports.E2ETests "$WORK/fixtures" "$HTTPS_PORT" "$HTTP_PORT" \
    | grep -v '^D/\|^I/Imports\|^I/DataPackages: Imported\|^I/DataPackages: Downloaded' || status=1

if [ $status -eq 0 ]; then log "All test suites passed"; else log "SOME TESTS FAILED"; fi
exit $status
