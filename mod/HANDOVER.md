# Handover: what this change adds to TAK Lite 1.5.0, and how to fold it into the source

This is for the upstream author. Everything here was built on top of the official
1.5.0 release APK because the source for 1.5.0 wasn't published: `build.sh` decodes
the APK, applies `patches/taklite-1.5.0.patch` (small hooks), compiles `src/` into a
third dex and signs the result. None of your code was rewritten; the hooks only call
out into the new classes and return. With the source in hand, each hook becomes one
Java line and the `src/` classes drop in as they are.

`README.md` next to this file describes every feature from the user's side, with the
settings and the on-phone test list. This document is the developer's side.

## What's added

| Feature | Where |
|---|---|
| Custom XYZ map sources (TileServer GL and any `{z}/{x}/{y}`, `{-y}`, `{q}` quadkey, `{s:a,b,c}` URL), picker on tap of the layers button, TileServer `styles.json` import | `mod/CustomMapSources`, `TemplateTileSource` |
| Offline: cache size setting, Clear Cache, download of the visible area for a custom source (zoom cap 18) | `mod/TileCache`, `AreaDownloader` |
| Data packages: received over TAK (`b-f-t-r`), downloaded from the server list, or imported as files; KML/KMZ/GPX/CoT drawn on the map; ATAK map source XML becomes map sources | `mod/imports/*` |
| Markers from other clients classified as markers (not people), shown with their 2525 symbol, kept until deleted (ATAK behaviour) | `mod/RemoteMarkers` |
| Stable marker uids (a re-send updates instead of duplicating); sent markers carry `<archive/>` and a 1-year stale so other clients keep them | `mod/MarkerIds` |
| Move on the marker radial menu | `mod/MarkerMove` |
| Settings: Show Emergency Button, Send Marker Upon Placement, Clear All Markers | `mod/MapOptions`, `MarkerSend` |

## Hook map: where each call goes in the source

The patch adds 34 call sites. In Java they are:

| Stock method | Add | Purpose |
|---|---|---|
| `MainActivity.onCreate`, right after osmdroid `Configuration` is loaded, before `setContentView` | `CustomMapSources.beforeMapCreated(this)` | cache size must be set before the MapView exists |
| `MainActivity.onCreate`, end | `CustomMapSources.onMainActivityCreated(this)` | map picker on tap (replaces the stock `cycleMapType` listener) and long-press of the layers button, toolbar Data Packages button, imported layers |
| `MainActivity.onResume`, end | `CustomMapSources.onMainActivityResumed(this)` | redraw after Settings changes, cache trim, Emergency button, queued clears |
| `MainActivity.applyMapType(int)`, start | `String n = CustomMapSources.applyMapType(this, mapView, type); if (n != null) return n;` | custom types come after the four built-ins |
| `MainActivity.cycleMapType` | `% CustomMapSources.getMapTypeCount(this)` instead of `% 4` | no longer reached from the button (see below), kept so the method stays correct |
| `MainActivity.showRadialMenu(DroppedMarker)`, after the view is created | `MarkerMove.attach(this, activeRadialMenu, marker)` | the Move slice |
| `RadialMenuView` | a fourth slice for markers, icon `MarkerMove.radialIcon(ctx)`, runs the `modMove` Runnable | |
| `MainActivity.placeMarker`, end | `MarkerSend.onPlaced(this, marker)` | send on placement if the setting is on |
| Edit-marker dialog, the SAVE button, after the marker is updated | `MarkerSend.onEdited(this, marker)` | with the setting on, a saved edit is sent at once; the SEND button is untouched |
| Both stock `sendMarker` call sites (radial Send, edit Save), just before the call | `MarkerIds.beforeSend(marker)` | names the marker for the uid below |
| `TakManager.sendMarker`, where the random uid is made, and between `CotBuilder.buildMarker` and `client.sendMessage` | `uid = MarkerIds.uidForSend(this.uid, randomUid)`; `cot = MarkerIds.outgoing(cot)` | stable uid; the CoT gets `<archive/>` and a stale time a year out (stock: 10 min), so TAK Aware/ATAK keep the marker until deleted. Unrecognised CoT goes out unchanged. In the source, do it in `CotBuilder.buildMarker` |
| `TakManager.onCotReceived`, start | `Imports.onCotReceived(cot); RemoteMarkers.observe(cot);` | package shares; marker classification |
| `TakManager.processCoT`, right after `CotParser.parse` | `RemoteMarkers.adjust(user)` | stand-in iconset path so the stock code files it as a marker |
| `TakManager.sendAlert` / `cancelAlert`, start | `MapOptions.onAlertChanged()` | a hidden Emergency button must show while an alert is active |
| `TakUser.isStale()` / `isExpired()`, start | `if (RemoteMarkers.keep(this)) return false;` | received markers don't grey out or expire |
| Remote-marker delete (radial), where `takUserMarkers.remove(uid)` happens | `RemoteMarkers.onDeleted(uid)` | also drop it from `TakManager`, or it redraws |
| `SettingsActivity.onCreate`, end | `CustomMapSources.onSettingsCreated(this)` | wires the Map Sources button and the new switches |
| `TakLocationService.onCreate` | `Imports.init(this)` | packages arrive while only the service runs |

Plus small accessors the new code calls, which in the source you'd replace with
direct access: `modSetMapType(int, boolean)`, `modMarkerIcon(label, cotType, iconset)`
(= `createTakMarkerIcon(label, "White", false, cotType, iconset)`), `modStopFollowing()`,
`modDismissRadialMenu()`, `modSaveDroppedMarkers()`, `modDroppedMarkers()`,
`modTakUserMarkers()`, `TakManager.modForgetUser(uid)` (= `takUsers.remove(uid)`).
`mod/stubs/` shows exactly which members the new code expects; delete it when porting.

Layout/resources: a "Manage Map Sources" button and two switches in `activity_settings`,
a "Clear All Markers" button in its title bar, a Data Packages toolbar button in
`activity_main` with `drawable/ic_mod_data_package.xml`, the `ImportActivity` entry in
the manifest (VIEW/SEND for KML, KMZ, GPX, zip, xml), and `usesCleartextTraffic="true"`
(LAN tile servers over http). The patch uses `android:tag` to find the new views
because apktool can't allocate ids; with real ids, replace `findViewWithTag`.

## Behaviour changes to stock you should know about

- **Received markers:** stock filed an incoming item as a marker only if its first
  message carried `<usericon>`; TAK Aware sends none, so its markers became people.
  `RemoteMarkers.isMarker` classifies the raw CoT instead (atom type, no `<takv>`/
  `<__group>`, and hand-placed / `<archive>` / `<usericon>`). Markers are kept until
  deleted, as in ATAK; people still grey out and expire.
- **Marker uids:** stock sent a fresh random uid per send, so every re-send duplicated
  the marker on teammates' maps. Now `UUID.nameUUIDFromBytes(deviceUid + "|" + markerKey)`.
- **Send on placement (off by default):** on, a marker is sent when placed, when an
  edit is saved and when moved. Off, nothing is sent on its own, exactly as stock:
  the radial Send and the edit dialog's SEND button remain the ways to share.
- **Sent markers are kept by other clients:** stock `buildMarker` set stale = time +
  10 min and no `<archive/>`, so TAK Aware and ATAK treated a TAK Lite marker as a
  transient and dropped it after a few minutes. `MarkerIds.outgoing` adds `<archive/>`
  after `<detail>` and sets stale a year out. PLI and alert stale times are unchanged.
- **Map button:** a tap opens the map list (as the long-press did) instead of cycling
  to the next map, which got tedious with many sources.
- **Tile cache:** osmdroid's own trimming is disabled (`setCleanupOnStart(false)`,
  GC frequency `Long.MAX_VALUE / 2`). It compares the cache *file* size with the
  limit, and SQLite files never shrink, so once over the limit it deleted a batch
  of the oldest tiles every 5 minutes, downloaded areas included. `TileCache.trim`
  uses pages-in-use instead and runs at map open/resume and after a download.
- **Icon lookups:** stock looked for a missing iconset file on every 30-second
  redraw; missing paths are swapped once for a stand-in that goes straight to the
  2525 symbol.

## Data package download rules

HTTPS (the normal server path) downloads with the enrolled client certificate and
imports on its own, SHA-256 checked. Plain http is accepted only from a private /
link-local address and only after one "Download it?" question (ATAK can send straight
from its own phone); http from anywhere else, other schemes and redirects are refused.
Inputs are hardened: no DTD internal subsets, nesting and size caps, zip bombs and
nested zips refused, `OutOfMemoryError`/`StackOverflowError` caught on import threads.

## Build and test

```
bash mod/build.sh      # official 1.5.0 APK + patch + src -> out/TAKLite-V1.5.0-custommaps.apk
bash mod/test/run.sh   # 143 JVM checks incl. an end-to-end run against a mock TAK server (mTLS)
```

The build needs no Android SDK (D8 and apksig come from the jadx jar; everything is
checksum-pinned). The signing key is generated locally and git-ignored; builds for
users must be signed with one key, so keep it.

## Open items

- `README.md` → "Battery notes for the stock code": ten battery issues found in the
  stock app during a review, with suggested fixes. They're yours to decide on; the
  biggest three are GPS kept on by the map screen while backgrounded, the 5-second
  reconnect loop with no backoff, and the 1-second socket timeout wake-up.
- `MarkerIds` passes the marker's identity to `sendMarker` through a side channel
  because the binary couldn't be changed; in the source, let `sendMarker` take the uid
  (or give `DroppedMarker` a uid field) and delete `MarkerIds.beforeSend/uidForSend`.
- Received markers aren't persisted across restarts (stock skips them when saving).
- Not handled on purpose: KML holes, ground overlays, network links, custom icon
  images; WMS and multi-layer map sources; sending packages from TAK Lite.
