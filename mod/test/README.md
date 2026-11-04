# Tests

```
bash mod/test/run.sh
```

Runs on a plain JVM in about a minute. Needs `mod/build.sh` to have run once
(it fetches the tools), a JDK 17+, python3 and openssl. Everything the tests
use is generated under `mod/work/test`: a synthetic data package (KML, GPX,
CoT marker and shape), a map pack built from `fixtures/mapsources`, the
manifest edge cases that real packages showed, throwaway certificates, and a
mock TAK server. No real server, certificate or data is involved.

| Suite | What it proves |
|---|---|
| `ModTests` | Tile URL templates (`{z}/{x}/{y}`, `{-y}`, `{q}` quadkeys against Bing's documented example, `{s:a,b,c}` rotation); the received-marker classifier on TAK Aware markers, ATAK position reports, chat and drawings; stable marker uids; size formatting |
| `ImportTests` | XML hardening (entity declarations anywhere before the root, 1 MB prolog cap, nesting depth, truncated input); KML styles, HTML descriptions, ATAK's duplicate label placemarks; GPX colours; CoT shapes, circles, routes, control messages; data package manifests with backslashes, wrong paths, no extension, KMZ named `.kml`, nothing drawable, zips nested too deep; ATAK map source conversion and the skipped WMS / multi-layer / non-Mercator kinds; the local-network rule for http shares; server list parsing |
| `E2ETests` | Through the same path a received `b-f-t-r` CoT takes, against a mock TAK Server API with mutual TLS: https imports on its own, repeats are ignored, a wrong SHA-256 is refused, an unreachable advertised host falls back to the configured server, internet http and ftp are refused, local http waits for the user, redirects aren't followed, a package tapped twice is downloaded once and both taps are told, a map pack becomes map sources, hide and delete |

What the suites can't cover, because it needs Android: drawing on the map,
dialogs, the tile cache (SQLite) and osmdroid downloads, the hooks into the
stock app. Those are checked by decompiling the built APK (the hooked methods
read as intended) and on a phone; the README's test list covers that.

`test/stubs` holds small JVM stand-ins for the few Android classes the import
code touches (a `Context` with a folder and in-memory preferences, a `Handler`
that runs posted work at once, `Log` to stdout). They are never packaged.
