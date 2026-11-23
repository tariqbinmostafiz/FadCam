#!/system/bin/sh
# FadCam Guardian - restarts FadCam's RecordingService if the app was killed (or the phone
# rebooted) while a recording was in progress.
#
# How it decides: FadCam stores is_recording_in_progress=true in app_prefs.xml while recording
# and sets it to false on a clean stop. Flag true + process dead = interrupted session.
#
# Kill-switch:  touch /data/local/tmp/fadcam_guardian.disable   (remove file to re-enable)
# Log:          /data/local/tmp/fadcam_guardian.log
# Known limit:  a manual Force Stop from Settings also leaves the flag true -> it will resume.
#               Use the kill-switch (or stop recording in-app first) if you do not want that.

LOG=/data/local/tmp/fadcam_guardian.log
DISABLE=/data/local/tmp/fadcam_guardian.disable
PKGS="com.fadcam com.fadcam.beta com.fadcam.pro com.fadcam.proplus"
SERVICE=com.fadcam.services.RecordingService
ACTION=ACTION_START_RECORDING
POLL=15            # seconds between checks
NEED_STRIKES=2     # consecutive "interrupted" observations before restarting
MAX_RESTARTS=3     # per 10 minutes (crash-loop guard)
WINDOW=600

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

# keep the log small
[ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 200000 ] && : > "$LOG"

while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 5; done
sleep 25
log "guardian started"

strikes=0
restarts=0
window_start=$(date +%s)

while true; do
  sleep "$POLL"
  [ -f "$DISABLE" ] && { strikes=0; continue; }

  now=$(date +%s)
  if [ $((now - window_start)) -gt $WINDOW ]; then window_start=$now; restarts=0; fi

  found=""
  for pkg in $PKGS; do
    xml="/data/data/$pkg/shared_prefs/app_prefs.xml"
    [ -f "$xml" ] || continue
    if grep -q 'name="is_recording_in_progress" value="true"' "$xml"; then
      if [ -z "$(pidof "$pkg")" ]; then found="$pkg"; break; fi
    fi
  done

  if [ -z "$found" ]; then strikes=0; continue; fi

  strikes=$((strikes + 1))
  [ "$strikes" -lt "$NEED_STRIKES" ] && continue

  if [ "$restarts" -ge "$MAX_RESTARTS" ]; then
    log "restart limit reached, waiting for next window"
    continue
  fi

  log "interrupted recording detected for $found -> starting service"
  out=$(am start-foreground-service -n "$found/$SERVICE" -a "$ACTION" 2>&1)
  log "am: $out"
  restarts=$((restarts + 1))
  strikes=0
done
