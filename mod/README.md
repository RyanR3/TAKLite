# TAK Lite: custom maps and data import

This adds your own raster map sources and data import (TAK data packages,
KML, KMZ, GPX, CoT) to TAK Lite 1.5.0. Map sources are built for
[TileServer GL](https://github.com/maptiler/tileserver-gl) but working with any
XYZ tile server, for example:

    https://tiles.example.com/styles/mapName/512/{z}/{x}/{y}.png

## Using it

- **Settings → Map Sources → Manage Map Sources** lists your sources.
  - **Add Map Source**: a name, the tile URL with `{z}`, `{x}`, `{y}` (or
    `{-y}` for TMS), min/max zoom and tile size. Leave tile size empty to
    auto-detect it: 512 if the URL contains `/512/` or `@2x`, otherwise 256.
    A URL can also use `{q}` (a Bing-style quadkey) instead of z/x/y, and
    `{s:a,b,c}` to spread tiles over several server names.
    **Test** downloads one tile and shows what the server returned.
  - **Import from TileServer GL**: enter the server address (for example
    `http://192.168.1.10:8080`). It reads `/styles.json` and adds the styles
    you tick as `…/styles/<id>/512/{z}/{x}/{y}.png`.
  - Tap a source to edit or delete it.
- **ATAK map sources**: a data package or zip (downloaded from the server,
  sent over TAK, or imported as a file) with ATAK map source files
  (`<customMapSource>` XML, as in ATAK map packs and
  [joshuafuller/ATAK-Maps](https://github.com/joshuafuller/ATAK-Maps)) adds
  them to your Map Sources. A single `.xml` map source file works too. ATAK's
  `{$x}`, `{$y}`, `{$z}`, `{$q}`, `{$serverpart}` with `<serverParts>`, and
  `<invertYCoordinate>` are converted. A source with the same name as one you
  already have replaces it, so a re-sent map pack updates its sources. WMS and
  multi-layer sources, and projections other than Web Mercator, are skipped
  (TAK Lite draws XYZ tiles only); the log says which and why. Deleting the
  package from Data Packages leaves its map sources; delete those in Map
  Sources.
- On the map, **tap** the layers button to pick a map from a list (Street,
  Dark, Satellite, Topo and your sources; the one showing is ticked), download
  the visible area or open the source manager. Stock TAK Lite cycled to the
  next map on each tap, which meant a lot of tapping once the list grows.
  Long-press does the same as a tap.

### Offline use

Every tile you view is saved on the phone. It keeps showing when the server is
out of reach, even after it goes stale (7 days for TileServer GL, which sends
no cache headers). Stale tiles are refreshed when the server can be reached.

- **Download visible area**: tap the layers button while one of your
  own sources is showing. Choose a zoom range and you get a tile count and a
  rough size first. It downloads on 4 threads and skips tiles that are already
  saved and fresh, so running it again only fetches what's missing or stale.
  Keep the app open while it runs; the screen stays on. Built-in maps can't
  be downloaded because their providers' usage policies don't allow bulk
  downloads.
  Downloads stop at street level: standard zoom 18, about 0.6 m per pixel.
  For 512 px TileServer GL tiles that is their zoom 17, which has the same
  detail. Zoom in further and the map enlarges those tiles, offline too.
- **Cache size**: Settings → Map Sources → Manage → **Cache size** (300 MB to
  16 GB; the default is 600 MB). The cache is shared by all maps. When the
  saved tiles take up more than the limit, the oldest are removed down to 5/6
  of it. This is checked when the map opens or comes back to the screen, and
  after an area download. The limit is capped at 95% of the phone's free
  space. The cache file itself doesn't shrink when tiles are removed; new
  tiles reuse the space, and **Clear Cache** gives it back to the phone.

  (osmdroid's own trimming is turned off: it compares the file size with the
  limit, and as the file never shrinks it kept deleting the oldest tiles every
  5 minutes, including downloaded areas.)
- **Clear Cache**: Settings → Map Sources → Manage → **Clear Cache** deletes
  every saved tile for all maps, after a confirmation, and gives the space
  back to the phone. It is refused while an area download is running.

## Map options (Settings)

- **Show Emergency Button** (the switch under Role): turn off to hide the red Emergency button on
  the map for users who don't use alerts. While an alert sent from this phone
  is active (for example from the volume-key panic), the button shows anyway,
  so the alert can be cancelled.
- **Send Marker Upon Placement** (the switch under it, off by default): on,
  a marker goes to the team the moment you place it, and again when you save
  an edit or move it. Off, nothing is sent on its own: placed, edited and moved
  markers wait until you choose Send (on the marker's menu, or SEND in the
  edit dialog). Markers always keep one uid, so a re-send updates the same
  marker on teammates' maps, and it stays there until deleted (see "Shared
  markers stay on teammates' maps").
- **Clear All Markers** (the red button in the Settings title bar): after a confirmation, removes every marker from this
  phone's map at once, both your own and those received from others. It
  doesn't send anything: markers you already sent stay on teammates' maps.
  A received marker comes back if its sender sends it again.

## Shared markers stay on teammates' maps

A sent marker stays on teammates' maps until it is deleted, as ATAK's markers
do. Stock TAK Lite sent markers with a 10-minute stale time and no
`<archive/>`, so TAK Aware and ATAK treated them as passing items: they greyed
out after a few minutes and then disappeared. The mod adds `<archive/>` to every
marker sent and moves its stale time a year out; nothing else in the message
changes. Position reports and alerts keep their stock stale times.

## Moving a marker

Tap one of your dropped markers. The radial menu now has a fourth slice at the
bottom, **Move** (a circle with an arrow). Tap it, then tap the map where the
marker should go; the ✕ on the hint bar cancels. The new position is saved.
With **Send Marker Upon Placement** on (Settings), it is sent again straight
away so teammates see it move; off, you get a reminder to send it. Leaving the
map screen cancels a move in progress. Markers received from other users can't
be moved.

Stock TAK Lite sends a marker under a new random uid each time, so every
re-send added another copy on teammates' maps. Markers now keep one uid, made
from this device's TAK uid and the marker, so a re-send (Send, Edit → Send, or
a move) updates the same marker. A marker last sent by an older build will
make one more copy the first time, then update.

## Markers from other TAK clients

Stock TAK Lite decides from the first message it gets for an item whether it is
a map marker or a person: only items carrying an icon (`<usericon>`) become
markers. Some clients, TAK Aware among them, send a new marker without one, so
those markers turned up as people: plain team dots in the team list, with no
delete. The mod now looks at the CoT itself. An atom type (`a-...`) with no
client identity (`<takv>` / `<__group>`, which position reports carry) that was
placed by hand (`how="h-..."`), is kept (`<archive/>`) or has an icon is a
marker. Such markers show up as deletable markers with their type's
MIL-STD-2525 symbol. Entries already listed as people by an older build go away
when they expire or when the app restarts.

Like ATAK, received markers now stay on the map until you delete them. Stock
TAK Lite greyed a marker out at its stale time and removed it 5 minutes later
(stock TAK Lite's own markers went out with a 10-minute stale time, so this hit
every shared marker). Teammates' positions still grey out and expire as before.
Deleting a received marker removes it for good, unless its sender sends it
again. Received markers aren't saved, so they're gone after an app restart, as
before.

For troubleshooting, `adb logcat -s RemoteMarkers` shows how each incoming
item was classified. To also log the full CoT of each marker sent, run
`adb shell setprop log.tag.MarkerIds DEBUG` first and add `MarkerIds` to the
logcat command (it's off by default, as it runs for every send).

## Imported data: data packages, KML, KMZ, GPX, CoT

Everything lives under the **Data Packages** button on the map's top bar (the
box icon, as in ATAK, between the connection status and the wrench). It lists
your imports. Each row shows the package name, where it came from ("From
server · uploaded by …", "Sent over TAK by …", "Imported from a file"; long
names wrap rather than being cut off) and its item count and date.

- **The eye** at the left of a row shows or hides that package's items on the
  map, as in TAK Aware: a bright open eye is shown, a dim eye with a slash is
  hidden. Hiding deletes nothing and doesn't touch map sources that came with
  the package; the name stays readable. The eye has a 48 dp tap target and a
  screen reader label ("Show on map" / "Hide from map"). Each toggle is logged
  (`adb logcat -s DataPackages`) with the package name, new state and item count.
- **The red bin** at the right deletes the package and its items, after a
  confirmation.
- Tap a package's name to zoom to it, show or hide it, or delete it.

- **Download from Server**: lists the data packages on your TAK server (the
  same query as ATAK's data package server search,
  `/Marti/sync/search?keywords=missionpackage` on port 8443). Tap one to
  download it with your client certificate and add it to the map. Packages
  you already have are ticked.
- **Data packages sent over TAK**: when an ATAK, iTAK or WinTAK user sends you
  a data package, TAK Lite downloads it from the TAK server with your client
  certificate, checks its SHA-256 and adds its contents to the map. You get a
  notification, plus a toast if the map is on screen. If the package names a
  server address the phone can't reach, it retries with the server configured
  in TAK Lite. A re-sent package replaces the earlier copy; it's skipped while
  that copy still exists. The limit is 200 MB.

  Packages come in on their own only over https. ATAK can also send a package
  straight from its own phone over plain http. TAK Lite accepts that only from
  an address on the local network, and asks first ("Download it?"). If the
  map isn't on screen, you get a notification and the question waits until
  you open TAK Lite. An http link from anywhere else is refused, and so are
  redirects on an http download.
- **Files**: Data Packages → **Import File**, or open or share a `.kml`, `.kmz`, `.gpx`, `.cot` or data package `.zip` to TAK Lite
  from a file manager, email or chat app.
- **What's drawn**:
  - CoT drawings with vertices (freehand, rectangles, routes) as lines, or as
    areas when closed or filled.
  - Circles and ellipses as areas.
  - CoT markers with TAK Lite's MIL-STD-2525 or iconset symbols.
  - Photos attached to markers, shown when you tap them.
  - KML Placemarks: points, lines, polygons, MultiGeometry and gx:Track, with
    their Style/StyleMap colours and widths.
  - GPX waypoints, routes and tracks, with Garmin, OsmAnd or GPS Track Editor
    colours.
- **Tap** any imported item for its name, notes, length and photo.

Imports are converted once and kept in the app's private storage. They are
drawn under markers and teammates.

For safety, files with a DOCTYPE that declares entities, or with items nested
more than 500 levels deep, are refused. Real KML, GPX and CoT never need either.

Not handled: KML ground overlays, network links and custom icon images; map
tile packages (MBTiles, SQLite) inside data packages; sending data packages
from TAK Lite; the "received" acknowledgement ATAK can send back to the
sender. ATAK exports each drawing to KML twice (the shape plus a label point
called "… center"); the label point is skipped. Polygon fills are kept
translucent so they don't hide the map.

### Notes

- Plain `http://` is allowed so a server on a laptop hotspot works.
- TileServer GL draws PNG tiles only in the full version. `tileserver-gl-light`
  lists styles but answers 404 for `.png` tiles; **Test** shows this.
- If the phone is on a Wi-Fi network with no internet access (a laptop
  hotspot) and **Test** can't reach the server, turn off mobile data. Android
  may otherwise send traffic over the cellular network instead.
- The zoom level is adjusted when you switch between 256 px and 512 px sources,
  so the map keeps the same scale on screen.

## Battery notes for the stock code

A battery review found these in TAK Lite 1.5.0 itself. They are left for the
source code rather than patched into the decompiled app. None of them should
change what the user sees.

1. The map screen's own high-accuracy location request (15 s) is only removed
   in `onDestroy`; remove it in `onPause` and request it again in `onResume`.
   The service keeps sending position reports.
2. `TakClient` reconnects every 5 s forever and reloads the certificates each
   time: back off to 1–2 min, retry at once when a network comes up, and reuse
   the SSL context.
3. `setSoTimeout(1000)` wakes the read thread every second; `stopClient()`
   already closes the socket, so 0 (or 60 s) is enough.
4. One GPS fix at 0.5 m/s or more switches to moving mode (2 s, high
   accuracy). Indoor speed jitter keeps flipping a still phone; ignore speed on
   low-accuracy fixes, or require 2 moving fixes.
5. The <30 % / <15 % battery steps only apply when the movement mode changes;
   re-apply the location request when the battery crosses them.
6. The TAK user listener stays registered while paused, so marker icons are
   redrawn with the screen off; remove it in `onPause` (`onResume` redraws
   everyone).
7. `createTakMarkerIcon` decodes icon PNGs on every update; cache the
   finished icons and skip `setIcon` when nothing changed.
8. The drone video player retries every 3 s, also in the background; skip
   retries while not visible and back off.
9. Each CoT is parsed up to three times with a new parser factory; reuse the
   factory and only run the disconnect/alert parses for those types.
10. Full PLI XML and several lines per received message are logged; guard the
    logging behind a debug check.

## Testing

```
bash mod/test/run.sh
```

runs three suites on a plain JVM in about a minute: unit tests of the tile
URL templates, the received-marker classifier and marker uids; the XML
hardening, KML/GPX/CoT parsers, data package edge cases and ATAK map source
conversion; and an end-to-end run of data packages against a mock TAK Server
API (mutual TLS) plus a plain http server. Everything it uses is generated;
see `test/README.md`. What needs Android (map drawing, the tile cache, the
hooks) is checked by decompiling the built APK and on a phone.

## Building

TAK Lite's upstream repository has not published source since v1.1.2, so
`build.sh` starts from the official release APK (checksum-verified) and
applies this mod to it:

```
cd mod
./build.sh
# -> out/TAKLite-V1.5.0-custommaps.apk
```

It needs `bash`, `curl`, `unzip`, `python3`, `patch` and JDK 17+. The Android
SDK is not required. The script downloads apktool, jadx (for its d8 and apksig
libraries), osmdroid and a compile-time `android.jar` into `work/`. If
`ANDROID_HOME` is set, it uses your SDK's `android.jar` instead.

On first run it creates a signing key in `keystore/signing.p12` with a
random password, saved next to it in `keystore/signing.pass`. To use your
own key, set `KEYSTORE`, `KEY_ALIAS` and either `KEYSTORE_PASS` or
`KEYSTORE_PASS_FILE`. **Back up the key and its password.** Android only
installs an update over an app signed with the same key.

### Installing

The official app is signed with the upstream author's key, so Android won't
install this build over it. Uninstall TAK Lite first (you will need to enroll
with your TAK server again), then install the APK.

## How it works

| Path | What it is |
|---|---|
| `src/com/taklite/client/mod/` | Map sources, in Java: source storage, the osmdroid tile source and dialogs (`CustomMapSources`), area download (`AreaDownloader`) and cache size (`TileCache`); marker move (`MarkerMove`), stable marker uids and markers others keep (`MarkerIds`) and classification of received markers (`RemoteMarkers`); shared helpers (`ModUtil`) |
| `src/com/taklite/client/mod/imports/` | Imported data: parsers (`CotShapes`, `KmlParser`, `GpxParser`, `Importer` for zips and data packages), storage (`ImportStore`), map layers and dialogs (`ImportedLayers`), data package downloads (`DataPackages`) and `ImportActivity` |
| `patches/taklite-1.5.0.patch` | Changes to the decoded app: hooks in `MainActivity` (map-type count; `onCreate` before and after the map is created; `onResume`; `showRadialMenu`; the marker sends; new `modSetMapType`, `modMarkerIcon`, `modStopFollowing`, `modDismissRadialMenu`, `modSaveDroppedMarkers`), `RadialMenuView` (a fourth slice), the marker uid and the outgoing CoT (`<archive/>`, stale time) in `TakManager.sendMarker`, `SettingsActivity.onCreate`, `TakManager.onCotReceived` and `processCoT` (remote marker classification), `TakUser.isStale`/`isExpired` and the remote marker delete (keeping received markers), new `TakManager.modForgetUser` and `MainActivity.modDroppedMarkers`/`modTakUserMarkers`, the Settings screen (Emergency button and Send-on-placement switches under Role, Clear All Markers in the title bar, tighter spacing), `placeMarker` and the edit dialog's Save (send on placement), `TakManager.sendAlert`/`cancelAlert` (Emergency button re-check), and `TakLocationService.onCreate`; the Settings button, the toolbar Data Packages button and its icon; the `ImportActivity` manifest entry; `usesCleartextTraffic`; and the version name |
| `stubs/` | Compile-time stand-ins for the stock classes the mod calls (`MainActivity`, `SettingsActivity`, `TakManager`, `TakUser` and others); they are not packaged |
| `tools/SignApk.java` | Zip-aligns, signs and verifies with apksig |

The stock code is otherwise unchanged. Custom sources are stored as JSON in
the app's existing `TAKLitePrefs` (`custom_map_sources`) and use map-type
indexes after the four built-in maps, so the saved `map_type` preference keeps
working.

When upstream releases a new APK, update `APK_URL`/`APK_SHA256` in `build.sh`
and regenerate the patch against the newly decoded APK. The hooks are small,
so this is usually quick.
