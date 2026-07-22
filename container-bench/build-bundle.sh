#!/usr/bin/env bash
# Build the in-container BenchSearcher OSGi bundle, version-matched to a running
# `vespa` docker container. Produces container-bench/relaybench.jar.
# Requires: a running container named `vespa` (docker), JDK 17+ locally.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; lib="$here/lib"; out="$here/out"
mkdir -p "$lib" "$out"; rm -f "$out"/relay/bench/*.class 2>/dev/null || true

VER="$(docker exec vespa sh -c 'ls /opt/vespa/lib/jars/ | grep -oE "container-disc-jar.*" >/dev/null; cat /opt/vespa/lib/jars/../../share/vespa/version 2>/dev/null' 2>/dev/null || true)"
[ -z "${VER:-}" ] && VER="$(docker exec vespa sh -c 'vespa version 2>/dev/null | grep -oE "[0-9]+\.[0-9]+\.[0-9]+" | head -1')"
echo "Vespa version: ${VER:-unknown}"

# version-matched container jars from the running node
for j in container-search-and-docproc-jar-with-dependencies.jar jdisc_core-jar-with-dependencies.jar vespajlib.jar container-disc-jar-with-dependencies.jar; do
  docker cp "vespa:/opt/vespa/lib/jars/$j" "$lib/" >/dev/null
done
unzip -o -j "$lib/container-search-and-docproc-jar-with-dependencies.jar" dependencies/container-search.jar -d "$lib/" >/dev/null

# plain compile jars from Maven Central (version-matched)
BASE=https://repo1.maven.org/maven2/com/yahoo/vespa
for art in component config-lib vespalog; do
  curl -s --http1.1 -o "$lib/$art.jar" "$BASE/$art/$VER/$art-$VER.jar" || true
done

CP="$(ls "$lib"/*.jar | paste -sd:)"
javac --release 17 -cp "$CP" -d "$out" "$here/src/relay/bench/BenchSearcher.java"
echo "compiled (class version: $(python3 -c "import struct;f=open('$out/relay/bench/BenchSearcher.class','rb');f.read(6);print(struct.unpack('>H',f.read(2))[0])"))"

# OSGi bundle (manifest 72-byte-wrapped; DynamicImport ends the import whack-a-mole)
python3 - "$here" <<'PY'
import zipfile,sys
here=sys.argv[1]
def wrap(h):
    b=h.encode(); L=[b[:72]]; r=b[72:]
    while r: L.append(b' '+r[:71]); r=r[71:]
    return b'\r\n'.join(L)
H=["Manifest-Version: 1.0","Bundle-ManifestVersion: 2","Bundle-Name: relaybench",
   "Bundle-SymbolicName: relaybench","Bundle-Version: 1.0.0","DynamicImport-Package: *",
   "Import-Package: com.yahoo.search,com.yahoo.search.searchchain,com.yahoo.search.result,com.yahoo.search.query,com.yahoo.processing,com.yahoo.processing.request,com.yahoo.processing.execution,com.yahoo.component,com.yahoo.component.chain,com.yahoo.component.chain.dependencies"]
man=b'\r\n'.join(wrap(x) for x in H)+b'\r\n\r\n'
z=zipfile.ZipFile(here+"/relaybench.jar","w",zipfile.ZIP_DEFLATED)
z.writestr("META-INF/MANIFEST.MF",man)
z.write(here+"/out/relay/bench/BenchSearcher.class","relay/bench/BenchSearcher.class")
z.close(); print("built relaybench.jar")
PY
