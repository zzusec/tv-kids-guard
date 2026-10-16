#!/system/bin/sh
# TV Kid Guard - on-device enforcement loop.
#
# Runs on the TV as the shell user via adb. Nothing is installed, so there is no icon to tap
# and no app to uninstall. It only ever acts on the listed video apps: it never touches the
# launcher, the HDMI inputs or live TV, so the television keeps working as a television.
#
# State and config live in $DIR and survive reboots; the running process does not, which is
# why the host re-launches it after a reboot.

DIR=${TVGUARD_DIR:-/data/local/tmp/tvguard}
CONFIG="$DIR/config"
STATE="$DIR/state"
CMD="$DIR/cmd"
PIDFILE="$DIR/daemon.pid"
LOG="$DIR/log"

mkdir -p "$DIR" 2>/dev/null

log() {
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG"
    # Keep the log from growing without bound on a TV with little storage.
    if [ "$(wc -l < "$LOG" 2>/dev/null || echo 0)" -gt 2000 ]; then
        tail -n 500 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
    fi
}

# ---------------------------------------------------------------- defaults

LIMIT_MINUTES=30
BONUS_MINUTES=0
POLL_SECONDS=2
ENABLED=1
APPS="org.smarttube.stable com.gitvdemo.video com.github.mytv.android top.yogiczy.slcs.tv com.aesq.yh"

# ------------------------------------------------------------------- state

DAY=""
USED=0
UNLOCKED=0

# Dates are compared as YYYYMMDD numbers: no shell string operators, and a TV whose clock has
# fallen back to 1970 cannot hand out a fresh allowance by power-cycling.
today_number() {
    date +%Y%m%d 2>/dev/null | tr -d '\r'
}

# The config file is written by the host and only ever read here. If the daemon wrote it back
# from memory it would overwrite a limit the parent had just changed.
load_config() {
    [ -f "$CONFIG" ] || return 0
    local v
    v=$(sed -n 's/^LIMIT_MINUTES=\([0-9]\{1,4\}\)$/\1/p' "$CONFIG" | tail -1)
    [ -n "$v" ] && LIMIT_MINUTES="$v"
    v=$(sed -n 's/^POLL_SECONDS=\([0-9]\{1,3\}\)$/\1/p' "$CONFIG" | tail -1)
    [ -n "$v" ] && POLL_SECONDS="$v"
    v=$(sed -n 's/^APPS=//p' "$CONFIG" | tail -1)
    [ -n "$v" ] && APPS="$v"
}

load_state() {
    [ -f "$STATE" ] || return 0
    local v
    v=$(sed -n 's/^DAY=\([0-9]\{8\}\)$/\1/p' "$STATE" | tail -1);   [ -n "$v" ] && DAY="$v"
    v=$(sed -n 's/^USED=\([0-9]*\)$/\1/p' "$STATE" | tail -1);      [ -n "$v" ] && USED="$v"
    v=$(sed -n 's/^UNLOCKED=\([01]\)$/\1/p' "$STATE" | tail -1);    [ -n "$v" ] && UNLOCKED="$v"
    v=$(sed -n 's/^BONUS_MINUTES=\([0-9]\{1,4\}\)$/\1/p' "$STATE" | tail -1); [ -n "$v" ] && BONUS_MINUTES="$v"
    v=$(sed -n 's/^ENABLED=\([01]\)$/\1/p' "$STATE" | tail -1);     [ -n "$v" ] && ENABLED="$v"
}

save_state() {
    printf 'DAY=%s\nUSED=%s\nUNLOCKED=%s\nBONUS_MINUTES=%s\nENABLED=%s\n' \
        "$DAY" "$USED" "$UNLOCKED" "$BONUS_MINUTES" "$ENABLED" > "$STATE.tmp" \
        && mv "$STATE.tmp" "$STATE"
}

# ------------------------------------------------------------- observation

uptime_seconds() {
    # Monotonic, so a wrong TV clock cannot roll the counter back mid-session.
    cut -d. -f1 /proc/uptime 2>/dev/null || echo 0
}

screen_is_on() {
    dumpsys power 2>/dev/null | grep -m1 'Display Power' | grep -q 'state=ON'
}

foreground_package() {
    local line
    line=$(dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|mFocusedActivity')
    printf '%s\n' "$line" | grep -o '[A-Za-z0-9_.]*/[A-Za-z0-9_.$]*' | head -1 | cut -d/ -f1
}

is_watched() {
    local pkg="$1" app
    [ -n "$pkg" ] || return 1
    for app in $APPS; do
        [ "$pkg" = "$app" ] && return 0
    done
    return 1
}

# ---------------------------------------------------------------- actions

kick_back() {
    # Stop playback and return to the launcher. The app stays installed, so the home screen
    # keeps its normal layout and the HDMI inputs stay visible.
    am force-stop "$1" >/dev/null 2>&1
    input keyevent KEYCODE_HOME >/dev/null 2>&1
    local now
    now=$(uptime_seconds)
    if [ $((now - LAST_KICK_LOG)) -ge 30 ]; then
        LAST_KICK_LOG=$now
        log "quota reached at ${USED}s of $(( (LIMIT_MINUTES + BONUS_MINUTES) * 60 ))s; sent $1 home"
    fi
}

apply_command() {
    [ -f "$CMD" ] || return 0
    local line arg
    while IFS= read -r line; do
        case "$line" in
            unlock)   UNLOCKED=1 ;;
            lock)     UNLOCKED=0 ;;
            reset)    USED=0; UNLOCKED=0 ;;
            enable)   ENABLED=1 ;;
            disable)  ENABLED=0 ;;
            bonus*)   arg=$(printf '%s' "$line" | sed -n 's/^bonus \([0-9]\{1,4\}\)$/\1/p'); [ -n "$arg" ] && BONUS_MINUTES="$arg" ;;
            stop)     rm -f "$CMD"; save_state; log 'stopped by command'; exit 0 ;;
            *)        log "ignored command: $line"; continue ;;
        esac
        log "command: $line"
    done < "$CMD"
    rm -f "$CMD"
    save_state
}

# ------------------------------------------------------------------- main

if [ -f "$PIDFILE" ]; then
    OLD=$(cat "$PIDFILE" 2>/dev/null | tr -d '\r')
    if [ -n "$OLD" ] && [ -d "/proc/$OLD" ]; then
        log "another daemon (pid $OLD) is running; exiting"
        exit 0
    fi
fi
echo $$ > "$PIDFILE"

load_config
load_state
[ -n "$DAY" ] || DAY=$(today_number)
save_state
log "started pid $$ limit=${LIMIT_MINUTES}m bonus=${BONUS_MINUTES}m enabled=${ENABLED} apps=[$APPS]"

LAST_UPTIME=$(uptime_seconds)
LAST_SAVE=$LAST_UPTIME
LAST_KICK_LOG=0
LOOPS=0

while true; do
    apply_command
    # Re-read the config about every 10 seconds rather than every poll.
    LOOPS=$((LOOPS + 1))
    if [ $((LOOPS % 5)) -eq 0 ]; then
        load_config
    fi

    NOW_UPTIME=$(uptime_seconds)
    DELTA=$((NOW_UPTIME - LAST_UPTIME))
    LAST_UPTIME=$NOW_UPTIME
    # A long gap means the loop was not running; never bill that time to the child.
    [ "$DELTA" -lt 0 ] && DELTA=0
    [ "$DELTA" -gt $((POLL_SECONDS * 3)) ] && DELTA=$POLL_SECONDS

    TODAY=$(today_number)
    if [ -n "$TODAY" ] && [ "$TODAY" -gt "$DAY" ]; then
        log "new day $TODAY (was $DAY); resetting"
        DAY="$TODAY"; USED=0; UNLOCKED=0
        save_state
    fi

    if [ "$ENABLED" = "1" ]; then
        FG=$(foreground_package)
        if [ "$UNLOCKED" != "1" ] && screen_is_on && is_watched "$FG"; then
            USED=$((USED + DELTA))
            if [ "$USED" -ge $(( (LIMIT_MINUTES + BONUS_MINUTES) * 60 )) ]; then
                kick_back "$FG"
            fi
        fi
    fi

    if [ $((NOW_UPTIME - LAST_SAVE)) -ge 5 ]; then
        save_state
        LAST_SAVE=$NOW_UPTIME
    fi

    sleep "$POLL_SECONDS"
done
