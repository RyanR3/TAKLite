#!/usr/bin/env python3
"""Generates the test inputs (all synthetic) into a work folder:

  package.zip         a TAK data package: manifest, one CoT marker, one KML with a line
                      and a polygon, one GPX track (what ATAK sends over TAK)
  package.sha256      its SHA-256 (the mock TAK server advertises it)
  mappack.zip         a data package holding the map source XMLs from fixtures/mapsources
  edge/*.zip          the manifest edge cases that real packages showed:
      A_normal        plain package
      B_backslash     manifest paths with backslashes
      C_wrongpath     manifest path that doesn't match the zip entry
      D_nothing       manifest only, nothing drawable
      E_noext         KML content under a name without an extension
      F_kmz_named_kml a KMZ (zip) stored under a .kml name
      G_nested        a zip in a zip in a zip (must be refused)
  single/             trails.kml, track.gpx, marker.cot on their own

Usage: make_fixtures.py WORKDIR FIXTUREDIR
"""
import hashlib
import io
import os
import sys
import zipfile

work, fixtures = sys.argv[1], sys.argv[2]
os.makedirs(os.path.join(work, "edge"), exist_ok=True)
os.makedirs(os.path.join(work, "single"), exist_ok=True)

KML = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document><name>Trails</name>
<Style id="s1"><LineStyle><color>ff0000ff</color><width>4</width></LineStyle>
<PolyStyle><color>7f00ff00</color></PolyStyle></Style>
<Folder><name>Lines</name>
<Placemark><name>Ridge trail</name><styleUrl>#s1</styleUrl><description>&lt;b&gt;Steep&lt;/b&gt;&lt;br&gt;Loose rock</description>
<LineString><coordinates>10.0,50.0,0 10.01,50.01,0 10.02,50.015,0</coordinates></LineString></Placemark>
<Placemark><name>Camp</name><styleUrl>#s1</styleUrl>
<Polygon><outerBoundaryIs><LinearRing><coordinates>10.1,50.1,0 10.11,50.1,0 10.11,50.11,0 10.1,50.1,0</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
<Placemark><name>Spring</name><Point><coordinates>10.05,50.05,0</coordinates></Point></Placemark>
<Placemark id="abc center"><name>Camp center</name><Point><coordinates>10.105,50.105,0</coordinates></Point></Placemark>
<Placemark id="abc outer"><name>Camp outer</name><LineString><coordinates>10.1,50.1 10.2,50.2</coordinates></LineString></Placemark>
</Folder></Document></kml>
"""

GPX = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3">
<wpt lat="50.2" lon="10.2"><name>Gate</name><desc>Locked</desc></wpt>
<trk><name>Walk</name><extensions><gpxx:TrackExtension><gpxx:DisplayColor>DarkRed</gpxx:DisplayColor></gpxx:TrackExtension></extensions>
<trkseg><trkpt lat="50.20" lon="10.20"/><trkpt lat="50.21" lon="10.21"/><trkpt lat="50.22" lon="10.21"/></trkseg></trk>
<rte><name>Route</name><rtept lat="50.3" lon="10.3"/><rtept lat="50.31" lon="10.31"/></rte>
</gpx>
"""

COT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<event version="2.0" uid="marker-test-0001" type="a-h-G" how="h-g-i-g-o" time="2026-01-01T00:00:00Z" start="2026-01-01T00:00:00Z" stale="2027-01-01T00:00:00Z">
<point lat="50.5" lon="10.5" hae="100" ce="10" le="10"/>
<detail><contact callsign="Checkpoint 1"/><remarks>Test marker</remarks><color argb="-65536"/><archive/>
<usericon iconsetpath="COT_MAPPING_2525C/a-h/a-h-G"/></detail></event>
"""

COT_SHAPE = """<?xml version="1.0"?>
<event version="2.0" uid="shape-test-0002" type="u-d-f" how="h-e" time="2026-01-01T00:00:00Z" start="2026-01-01T00:00:00Z" stale="2027-01-01T00:00:00Z">
<point lat="50.6" lon="10.6" hae="0" ce="10" le="10"/>
<detail><contact callsign="Area"/><link point="50.6,10.6"/><link point="50.61,10.6"/><link point="50.61,10.61"/><link point="50.6,10.6"/>
<fillColor value="1090453504"/><strokeColor value="-16776961"/><strokeWeight value="5.0"/></detail></event>
"""


def manifest(uid, name, entries):
    items = "".join('<Content ignore="false" zipEntry="%s"><Parameter name="name" value="%s"/></Content>'
                    % (e, e.split("/")[-1]) for e in entries)
    return ('<MissionPackageManifest version="2"><Configuration><Parameter name="uid" value="%s"/>'
            '<Parameter name="name" value="%s"/></Configuration><Contents>%s</Contents></MissionPackageManifest>'
            % (uid, name, items))


def zip_bytes(files):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in files.items():
            z.writestr(name, data)
    return buf.getvalue()


def write(path, data):
    with open(os.path.join(work, path), "wb") as f:
        f.write(data if isinstance(data, bytes) else data.encode())


# The package the mock TAK server hands out.
pkg = zip_bytes({
    "MANIFEST/manifest.xml": manifest("pkg-uid-0001", "TestArea.zip",
                                      ["e1/trails.kml", "e2/track.gpx", "e3/marker.cot", "e4/shape.cot"]),
    "e1/trails.kml": KML, "e2/track.gpx": GPX, "e3/marker.cot": COT, "e4/shape.cot": COT_SHAPE,
})
write("package.zip", pkg)
write("package.sha256", hashlib.sha256(pkg).hexdigest())

# Map pack: every XML in fixtures/mapsources, as ATAK stores them.
sources = {}
for name in sorted(os.listdir(os.path.join(fixtures, "mapsources"))):
    if name.endswith(".xml"):
        with open(os.path.join(fixtures, "mapsources", name), "rb") as f:
            sources["imagery/mobile/mapsources/" + name] = f.read()
write("mappack.zip", zip_bytes(dict(
    {"MANIFEST/manifest.xml": manifest("mappack-uid-0001", "TestMapPack", sorted(sources))}, **sources)))

# Edge cases.
write("edge/A_normal.zip", zip_bytes({
    "MANIFEST/manifest.xml": manifest("u1", "Trails.zip", ["abc123/trails.kml"]), "abc123/trails.kml": KML}))
write("edge/B_backslash.zip", zip_bytes({
    "MANIFEST/manifest.xml": manifest("u2", "Trails.zip", ["abc123\\trails.kml"]), "abc123/trails.kml": KML}))
write("edge/C_wrongpath.zip", zip_bytes({
    "MANIFEST/manifest.xml": manifest("u3", "Trails.zip", ["abc123/trails.kml"]), "trails.kml": KML}))
write("edge/D_nothing.zip", zip_bytes({
    "MANIFEST/manifest.xml": manifest("u4", "Notes.zip", ["notes.txt"]), "notes.txt": "nothing to draw"}))
write("edge/E_noext.zip", zip_bytes({
    "MANIFEST/manifest.xml": manifest("u5", "Trails.zip", ["data/trails"]), "data/trails": KML}))
kmz = zip_bytes({"doc.kml": KML})
write("edge/F_kmz_named_kml.zip", zip_bytes({
    "MANIFEST/manifest.xml": manifest("u6", "Trails.zip", ["x/trails.kml"]), "x/trails.kml": kmz}))
inner = zip_bytes({"a.kml": zip_bytes({"b.kml": zip_bytes({"c.kml": KML})})})
write("edge/G_nested.zip", zip_bytes({
    "MANIFEST/manifest.xml": manifest("u7", "Nested.zip", ["n/deep.kml"]), "n/deep.kml": inner}))

write("single/trails.kml", KML)
write("single/track.gpx", GPX)
write("single/marker.cot", COT)
print("fixtures written to", work)
