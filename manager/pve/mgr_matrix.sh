#!/bin/bash
# The Manager on every server of the 15.1 jars: for each target a fresh server with the jar of Ultra Fast Pregen and the
# Manager, a look at the web page, and a pregen of 500 x 500 blocks started and followed only through the page's API.
# Usage: [RELEASED_15=<folder of the 15 jars>] [ALSO=<another jar>] [OUT=<file>] mgr_matrix.sh <manager jar> [target ...]
# One line per target in results/mgr-matrix.txt; each server's log stays in logs/mgr-m-<target>.log
set -u
C="$HOME/compat"; cd "$C" || exit 1
MGR="$1"; shift
PORT=25571; WEB=25580; TOKEN=mgrtesttoken00000000
J="$C/jars-151-final"
ALL="fabric-1.20 fabric-1.20.1 fabric-1.20.2 fabric-1.20.3 fabric-1.20.4 fabric-1.20.5 fabric-1.20.6
forge-1.20 forge-1.20.1 forge-1.20.2 forge-1.20.3 forge-1.20.4
fabric-1.21 fabric-1.21.1 neoforge-1.21 neoforge-1.21.1
fabric-26.1 fabric-26.1.1 fabric-26.1.2 fabric-26.2 neoforge-26.1.2 neoforge-26.2
craftbukkit-26.2 spigot-26.2 paper-26.2 folia-26.2 sponge-26.2"
TARGETS="${*:-$ALL}"
OUT="${OUT:-$C/results/mgr-matrix.txt}"

ufp_jar() {
  if [ -n "${RELEASED_15:-}" ]; then
    case "$1" in
      sponge-*) echo "$RELEASED_15/ultrafastpregen-sponge-26.2-15.jar" ;;
      craftbukkit-*|spigot-*|paper-*|folia-*) echo "$RELEASED_15/ultrafastpregen-bukkit-26.2-15.jar" ;;
      *) echo "$RELEASED_15/ultrafastpregen-$1-15.jar" ;;
    esac
    return
  fi
  case "$1" in
    fabric-1.20*) echo "$J/ultrafastpregen-fabric-1.20.x-15.1.jar" ;;
    forge-*) echo "$J/ultrafastpregen-forge-1.20-1.20.4-15.1.jar" ;;
    fabric-1.21*) echo "$J/ultrafastpregen-fabric-1.21-1.21.1-15.1.jar" ;;
    neoforge-1.21*) echo "$J/ultrafastpregen-neoforge-1.21-1.21.1-15.1.jar" ;;
    fabric-26*) echo "$J/ultrafastpregen-fabric-26.1-26.2-15.1.jar" ;;
    neoforge-26*) echo "$J/ultrafastpregen-neoforge-26.1.2-26.2-15.1.jar" ;;
    sponge-*) echo "$C/jars-151-final/ultrafastpregen-sponge-26.2-15.1.jar" ;;
    *) echo "$C/jars-151-final/ultrafastpregen-bukkit-26.2-15.1.jar" ;;
  esac
}

api() { curl -s -m 10 -H "Authorization: Bearer $TOKEN" "$@"; }
field() { python3 -I -c "
import json, sys
try:
    data = json.load(sys.stdin)
except ValueError:
    print('no-json'); sys.exit()
for key in sys.argv[1].split('.'):
    data = data.get(key) if isinstance(data, dict) else None
print(data)" "$1"; }

for T in $TARGETS; do
  echo "[$(date +%T)] $T"
  START=$(PORT=$PORT WEB=$WEB TOKEN=$TOKEN JVM="-Xms1G -Xmx3G" ./mgr_srv.sh m start "$T" "$(ufp_jar "$T")" "$MGR" ${ALSO:-} 2>&1 | tail -1)
  PAGE=$(curl -s -m 10 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$WEB/")
  ANON=$(curl -s -m 10 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$WEB/api/info")
  VERSION=$(api "http://127.0.0.1:$WEB/api/info" | field ufp.version)
  # UFP's API is there once its mod has started; a plugin's is a moment after the server is ready.
  STARTED=
  for i in 1 2 3 4 5 6 7 8 9 10; do
    STARTED=$(api -d "dimension=minecraft:overworld&shape=square&x=100000&z=100000&radius=250" "http://127.0.0.1:$WEB/api/pregen/start" | field started)
    [ "$STARTED" = True ] && break
    sleep 2
  done
  STATE=none; DONE=; TOTAL=; SPEED=
  for i in $(seq 1 120); do
    POLL=$(api "http://127.0.0.1:$WEB/api/poll")
    STATE=$(echo "$POLL" | field ufp.status.state)
    case "$STATE" in COMPLETED|FAILED|STOPPED) break ;; esac
    sleep 1
  done
  DONE=$(echo "$POLL" | field ufp.status.done); TOTAL=$(echo "$POLL" | field ufp.status.total)
  SPEED=$(echo "$POLL" | field ufp.status.chunksPerSecond | cut -d. -f1)
  REGIONS=$(api "http://127.0.0.1:$WEB/api/map/regions?dimension=minecraft:overworld" | python3 -I -c "
import json, sys
try:
    print(sum(r[2] for r in json.load(sys.stdin)['regions'] if r[0] > 100))
except ValueError:
    print('no-json')")
  PICTURE=$(api -o /dev/null -w '%{http_code}:%{size_download}' "http://127.0.0.1:$WEB/api/map/terrain?dimension=minecraft:overworld&x=195&z=195")
  FINISHED=$(sleep 1; api "http://127.0.0.1:$WEB/api/poll" | python3 -I -c "
import json, sys
try:
    print(len(json.load(sys.stdin)['ufp']['finished']))
except (ValueError, KeyError, TypeError):
    print('no-json')")
  LOG="$C/logs/mgr-m.log"
  ERRORS=$(grep -E ' ERROR|/ERROR\]|Exception|NoSuchMethodError|NoSuchFieldError|AbstractMethodError' "$LOG" | grep -vc 'sculk_patch_deep_dark')
  cp -f "$LOG" "$C/logs/mgr-m-$T.log" 2>/dev/null
  ./mgr_srv.sh m stop > /dev/null 2>&1
  printf '%-18s page=%s anon=%s ufp=%s start=%s state=%s chunks=%s/%s cps=%s on_disk=%s terrain=%s finished=%s errors=%s | %s\n' \
    "$T" "$PAGE" "$ANON" "$VERSION" "$STARTED" "$STATE" "$DONE" "$TOTAL" "$SPEED" "$REGIONS" "$PICTURE" "$FINISHED" "$ERRORS" "$START" | tee -a "$OUT"
done
echo "MATRIX_DONE"
