#!/usr/bin/env bash
# Build the containerstore bundle and deploy app + a `local` search chain to a
# running Vespa (data persists). Injects the chain into a TEMP copy of vespa/app
# so the tracked services.xml stays clean. Requires a running `vespa` container.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; app="$here/../../vespa/app"
bash "$here/build-bundle.sh"
tmp="$(mktemp -d)"; cp -r "$app"/. "$tmp/"
mkdir -p "$tmp/components"; cp "$here/containerstore.jar" "$tmp/components/"
python3 - "$tmp/services.xml" <<'PY'
import sys; p=sys.argv[1]; s=open(p).read()
s=s.replace("<search />",
 '<search>\n      <chain id="local" inherits="vespa">\n'
 '        <searcher id="com.vitorpamplona.quartz.eventstore.container.LocalStoreSearcher" '
 'bundle="containerstore" />\n'
 '        <searcher id="com.vitorpamplona.quartz.eventstore.container.InsertSpikeSearcher" '
 'bundle="containerstore" />\n'
 '        <searcher id="com.vitorpamplona.quartz.eventstore.container.StoreInsertSpikeSearcher" '
 'bundle="containerstore" />\n'
 '      </chain>\n    </search>')
open(p,"w").write(s)
PY
(cd "$tmp" && zip -rq /tmp/containerstore-app.zip .)
curl -s --noproxy '*' -H "Content-Type: application/zip" --data-binary @/tmp/containerstore-app.zip \
  http://localhost:19071/application/v2/tenant/default/prepareandactivate \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('deploy:',d.get('message','?')[:200])"
