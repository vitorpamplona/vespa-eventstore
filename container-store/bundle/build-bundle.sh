#!/usr/bin/env bash
# Assemble the :container-store OSGi bundle: the in-container store read path
# (LocalStoreSearcher + VespaLocalEventIndex) plus its full Java-17 closure
# (store, vespa, Quartz @ jvmTarget=17, kotlin stdlib/coroutines/serialization,
# okhttp, ...). Produces container-store/bundle/containerstore.jar.
#
# The closure is embedded as jars under dependencies/ and wired with an OSGi
# Bundle-ClassPath (the recipe proven by the quartz-in-OSGi spike). Container
# APIs (com.yahoo.search.*) resolve via DynamicImport-Package: *.
#
# Requires: JDK 17+, and the dependencies/ dir populated (module jars built with
# `./gradlew :container-store:jar :store:jar :vespa:jar` + third-party closure).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
deps="$here/dependencies"
[ -d "$deps" ] || { echo "missing $deps — stage the closure first" >&2; exit 1; }

# Refresh the three in-repo module jars so the bundle always ships current code.
root="$here/../.."
( cd "$root" && ./gradlew -q :container-store:jar :store:jar :vespa:jar )
cp "$root/container-store/build/libs/container-store.jar" "$deps/"
cp "$root/store/build/libs/store.jar"                     "$deps/"
cp "$root/vespa/build/libs/vespa-1.0.0.jar"               "$deps/"

python3 - "$here" <<'PY'
import os, zipfile, sys
here = sys.argv[1]
deps = os.path.join(here, "dependencies")
jars = sorted(f for f in os.listdir(deps) if f.endswith(".jar"))
cp = ["."] + [f"dependencies/{j}" for j in jars]

def wrap(h):
    b = h.encode(); out = [b[:72]]; r = b[72:]
    while r: out.append(b" " + r[:71]); r = r[71:]
    return b"\r\n".join(out)

H = [
    "Manifest-Version: 1.0",
    "Bundle-ManifestVersion: 2",
    "Bundle-Name: containerstore",
    "Bundle-SymbolicName: containerstore",
    "Bundle-Version: 1.0.0",
    # Container APIs are provided by the platform; embedded closure resolves
    # from Bundle-ClassPath. DynamicImport ends the import whack-a-mole.
    "DynamicImport-Package: *",
    "Import-Package: com.yahoo.search,com.yahoo.search.searchchain,"
    "com.yahoo.search.result,com.yahoo.search.query,com.yahoo.processing,"
    "com.yahoo.processing.request,com.yahoo.processing.execution,"
    "com.yahoo.component,com.yahoo.component.chain,"
    "com.yahoo.component.chain.dependencies",
    "Bundle-ClassPath: " + ",".join(cp),
]
man = b"\r\n".join(wrap(x) for x in H) + b"\r\n\r\n"

out = os.path.join(here, "containerstore.jar")
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("META-INF/MANIFEST.MF", man)
    for j in jars:
        z.write(os.path.join(deps, j), f"dependencies/{j}")
mb = os.path.getsize(out) / 1e6
print(f"built containerstore.jar ({len(jars)} embedded jars, {mb:.1f} MB)")
PY
