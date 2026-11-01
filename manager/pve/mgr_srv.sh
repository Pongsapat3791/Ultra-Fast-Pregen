#!/bin/bash
# A test server for the Manager on the PVE box, next to whatever else runs there: its own tmux session, ports and low
# priority, so other tests are not disturbed.
# Usage: [PORT=25571] [WEB=25580] [TOKEN=...] [CONF="k=v k=v"] [LINK="k=v k=v"] [JVM="-Xmx3G"] [SEED=42]
#          mgr_srv.sh <instance> start <target> <jar> [jar...]
#        mgr_srv.sh <instance> again     (starts what stop KEEP=1 left; the earlier log is kept as .log.1)
#        mgr_srv.sh <instance> cmd <console command>
#        mgr_srv.sh <instance> log [lines] [regex]
#        mgr_srv.sh <instance> stop      (KEEP=1 keeps the folder)
# CONF goes into ultrafastpregen.conf, LINK into ufplink-network.conf. The Manager listens on 0.0.0.0:$WEB.
set -u
C="$HOME/compat"
I="$1"; shift
SESSION="mgr-$I"
RUN="$C/run/mgr-$I"
LOG="$C/logs/mgr-$I.log"
J25="$HOME/jdk25/jdk-25.0.4.1+1/bin/java"
JVM="${JVM:--Xms1G -Xmx3G}"
alive() { tmux has-session -t "$SESSION" 2>/dev/null; }
launch() {
  tmux new-session -d -s "$SESSION" -c "$RUN" "nice -n 10 $CMD > '$LOG' 2>&1"
  for i in $(seq 1 240); do
    grep -qE 'Done \([0-9.,]+s\)!' "$LOG" 2>/dev/null && { echo "ready after ${i} s: $1"; exit 0; }
    alive || { echo "server died"; tail -5 "$LOG"; exit 1; }
    sleep 1
  done
  echo "server did not start"; tail -5 "$LOG"; exit 1
}

case "${1:-}" in
start)
  TARGET="$2"; shift 2
  if alive; then echo "session $SESSION already exists"; exit 2; fi
  SKEL="$C/skel/$TARGET"
  [ -d "$SKEL" ] || { echo "missing skeleton $TARGET"; exit 2; }
  rm -rf "$RUN" "$LOG" "$LOG.1"
  CONF_FILE="$RUN/config/ultrafastpregen.conf"
  MGR_DIR="$RUN/config"
  LINK_DIR="$RUN/config"
  case "$TARGET" in
    spigot-*|craftbukkit-*|paper-*|folia-*|sponge-*)
      case "$TARGET" in
        spigot-*) JAR=spigot.jar; LINKS="bundler" ;;
        craftbukkit-*) JAR=craftbukkit.jar; LINKS="bundler" ;;
        paper-*) JAR=paper.jar; LINKS="cache libraries versions" ;;
        folia-*) JAR=folia.jar; LINKS="cache libraries versions" ;;
        sponge-*) JAR=sponge.jar; LINKS="libraries" ;;
      esac
      DIR=plugins
      case "$TARGET" in
        sponge-*) DIR=mods ;;
        *) CONF_FILE="$RUN/plugins/UltraFastPregen/ultrafastpregen.conf"; MGR_DIR="$RUN/plugins/UltraFastPregenManager"
           LINK_DIR="$RUN/plugins/UltraFastPregenLink" ;;
      esac
      mkdir -p "$RUN/$DIR"
      for d in $LINKS; do cp -al "$SKEL/$d" "$RUN/$d"; done
      ln "$SKEL/$JAR" "$RUN/$JAR"
      for f in "$SKEL"/*.yml "$SKEL"/*.json; do [ -f "$f" ] && cp "$f" "$RUN/"; done
      [ -d "$SKEL/config" ] && cp -r "$SKEL/config" "$RUN/config"
      CMD="$J25 $JVM -jar $JAR nogui"
      ;;
    *)
      DIR=mods
      CMD=$(JVM="$JVM" "$C/launch_cmd.sh" "$TARGET") || { echo "no launch command"; exit 2; }
      cp -al "$SKEL" "$RUN"
      rm -rf "$RUN/mods" "$RUN/config" "$RUN/world" "$RUN/logs"
      mkdir -p "$RUN/mods" "$RUN/config"
      ;;
  esac
  for jar in "$@"; do cp "$jar" "$RUN/$DIR/" || exit 2; done
  if [ -n "${CONF:-}" ]; then
    mkdir -p "$(dirname "$CONF_FILE")"
    for line in $CONF; do echo "$line"; done > "$CONF_FILE"
  fi
  mkdir -p "$MGR_DIR"
  printf '%s\n' "enabled=true" "bind=0.0.0.0" "port=${WEB:-25580}" "token=${TOKEN:-mgrtesttoken00000000}" > "$MGR_DIR/ufpmanager.conf"
  if [ -n "${LINK:-}" ]; then
    mkdir -p "$LINK_DIR"
    for line in $LINK; do echo "$line"; done > "$LINK_DIR/ufplink-network.conf"
  fi
  echo "eula=true" > "$RUN/eula.txt"
  printf '%s\n' "server-port=${PORT:-25571}" "level-name=world" "level-seed=${SEED:-42}" "online-mode=false" "view-distance=${VIEW:-4}" \
    "simulation-distance=4" "spawn-protection=0" "allow-flight=true" "max-players=4" "enable-rcon=false" \
    "enable-query=false" "enforce-secure-profile=false" "motd=ufp manager test" > "$RUN/server.properties"
  echo "$CMD" > "$RUN/.ufp-launch"
  launch "$TARGET"
  ;;
again)
  if alive; then echo "session $SESSION already exists"; exit 2; fi
  [ -f "$RUN/.ufp-launch" ] || { echo "nothing to start in $RUN"; exit 2; }
  CMD=$(cat "$RUN/.ufp-launch")
  mv "$LOG" "$LOG.1"
  launch "again"
  ;;
cmd)
  alive || { echo "no server"; exit 1; }
  tmux send-keys -t "$SESSION" "$2" Enter
  ;;
log)
  if [ -n "${3:-}" ]; then grep -E "$3" "$LOG" | tail -n "${2:-40}"; else tail -n "${2:-40}" "$LOG"; fi
  ;;
stop)
  if alive; then
    tmux send-keys -t "$SESSION" "stop" Enter
    for i in $(seq 1 60); do alive || break; sleep 1; done
    alive && { tmux kill-session -t "$SESSION" 2>/dev/null; echo "server had to be killed"; }
  fi
  [ "${KEEP:-0}" = 1 ] || rm -rf "$RUN"
  echo "stopped"
  ;;
*)
  sed -n '2,9p' "$0"; exit 2
  ;;
esac
