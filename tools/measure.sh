#!/usr/bin/env bash
# Measures Zero Launcher on a real device over adb, optionally next to another home app.
#
# Everything is measured from outside the app (am, dumpsys, /proc, logcat). The only thing the
# launcher contributes is its optional timing log, which this script switches on with
#   adb shell setprop log.tag.ZeroLauncher D
# and off again at the end.
#
# Usage:
#   tools/measure.sh [-s SERIAL] [-a APK] [-c OTHER_HOME_PACKAGE] [-n RUNS] [-i IDLE_SECONDS]
#                    [-l LONG_RUN_ROUNDS]
#     -a APK   install this APK first (adb install -r) and report its size
#     -c PKG   measure this home app the same way for comparison (e.g. the stock launcher,
#              com.android.launcher3 / com.google.android.apps.nexuslauncher / ...)
#     -n RUNS  cold starts per app (default 5)
#     -i SEC   idle window for the CPU measurement and the "stable" RAM sample (default 60)
#     -l N     rounds of swipes + app launches for the long-run memory check (default 30)
#
# Before running: unlock the device, keep the screen on (Developer options > Stay awake) and
# make sure Zero Launcher has more than one page of apps for the page-switch numbers.
# The report is printed and saved to build/measure-<date>.md.
set -u -o pipefail

PKG=io.github.plpp555.zerolauncher
RUNS=5
IDLE=60
ROUNDS=30
APK=""
OTHER=""
ADB=(adb)
NAP_SCALE="${ZL_NAP_SCALE:-1}" # 0 only for dry runs of this script against a fake adb

while getopts "s:a:c:n:i:l:" o; do
    case "$o" in
        s) ADB=(adb -s "$OPTARG") ;;
        a) APK="$OPTARG" ;;
        c) OTHER="$OPTARG" ;;
        n) RUNS="$OPTARG" ;;
        i) IDLE="$OPTARG" ;;
        l) ROUNDS="$OPTARG" ;;
        *) sed -n '2,24p' "$0"; exit 2 ;;
    esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/build"
REPORT="$ROOT/build/measure-$(date +%Y%m%d-%H%M%S).md"

sh_() { "${ADB[@]}" shell "$@" 2>/dev/null | tr -d '\r'; }
nap() { [ "$NAP_SCALE" = 0 ] || sleep "$1"; }
out() { echo "$*" | tee -a "$REPORT"; }
log() { echo ">> $*" >&2; }

# median / min / max / mean of the numbers on stdin
stats() {
    sort -n | awk '{a[NR]=$1; s+=$1} END {
        if (NR == 0) { print "n/a"; exit }
        m = (NR % 2) ? a[(NR+1)/2] : (a[NR/2] + a[NR/2+1]) / 2;
        printf "median %s / min %s / max %s / mean %.1f (n=%d)", m, a[1], a[NR], s/NR, NR }'
}

wake() { sh_ input keyevent KEYCODE_WAKEUP >/dev/null; }

go_home() {
    local pkg="$1"
    sh_ am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -p "$pkg"
}

pid_of() {
    local p
    p="$(sh_ pidof "$1" | awk '{print $1}')"
    [ -n "$p" ] || p="$(sh_ ps -A 2>/dev/null | awk -v n="$1" '$NF==n {print $2; exit}')"
    [ -n "$p" ] || p="$(sh_ ps | awk -v n="$1" '$NF==n {print $2; exit}')"
    echo "$p"
}

# Prints "TOTAL_PSS_KB JAVA NATIVE GRAPHICS" (KB) for a package from dumpsys meminfo.
meminfo() {
    sh_ dumpsys meminfo "$1" | awk '
        /TOTAL PSS:/ { for (i = 1; i <= NF; i++) if ($i == "PSS:") total = $(i+1) }
        /^ *TOTAL +[0-9]/ { if (total == "") total = $2 }
        /^ *Java Heap:/ { java = $3 }
        /^ *Native Heap:/ { native = $3 }
        /^ *Graphics:/ { gfx = $2 }
        END { printf "%s %s %s %s\n", (total == "" ? "?" : total), (java == "" ? "?" : java),
              (native == "" ? "?" : native), (gfx == "" ? "?" : gfx) }'
}

cpu_ticks() {
    sh_ cat "/proc/$1/stat" | awk '{print $14 + $15}'
}

# ------------------------------------------------------------------ device

"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "no device (adb get-state failed)" >&2; exit 1; }
: > "$REPORT"
out "# Zero Launcher measurement $(date '+%Y-%m-%d %H:%M')"
out ""
out "| device | value |"
out "|---|---|"
out "| model | $(sh_ getprop ro.product.manufacturer) $(sh_ getprop ro.product.model) |"
out "| Android | $(sh_ getprop ro.build.version.release) (API $(sh_ getprop ro.build.version.sdk)) |"
out "| ABI | $(sh_ getprop ro.product.cpu.abi) |"
out "| RAM | $(sh_ cat /proc/meminfo | awk '/MemTotal/ {printf "%d MB", $2/1024}') |"
out "| CPU cores | $(sh_ cat /proc/cpuinfo | grep -c '^processor') |"
HZ="$(sh_ getconf CLK_TCK)"
case "$HZ" in ''|*[!0-9]*) HZ=100 ;; esac

if [ -n "$APK" ]; then
    log "installing $APK"
    "${ADB[@]}" install -r "$APK" >/dev/null || { echo "install failed" >&2; exit 1; }
    out ""
    out "APK size: $(wc -c < "$APK" | tr -d ' ') bytes ($APK)"
fi
APK_PATH="$(sh_ pm path "$PKG" | sed -n 's/^package://p' | head -1)"
[ -n "$APK_PATH" ] || { echo "$PKG is not installed" >&2; exit 1; }
out "Installed APK: $(sh_ stat -c %s "$APK_PATH") bytes"

sh_ setprop log.tag.ZeroLauncher D

# ------------------------------------------------------------------ per-app measurements

measure_app() {
    local pkg="$1" ours="$2"
    local i t fd first
    local cold="" fully="" firstdraw=""
    log "[$pkg] cold starts"
    for i in $(seq 1 "$RUNS"); do
        wake
        # Put another app in front so killing the launcher does not restart it by itself.
        sh_ am start -W -a android.settings.SETTINGS >/dev/null
        nap 1
        sh_ am force-stop "$pkg"
        nap 2
        sh_ logcat -c
        t="$(go_home "$pkg" | awk -F': *' '/TotalTime/ {print $2}')"
        [ -n "$t" ] && cold="$cold $t"
        nap 3
        # "Fully drawn <pkg>/<activity>: +234ms" or "+1s234ms"
        fd="$(sh_ logcat -d | grep "Fully drawn $pkg" | tail -1 | grep -o '+[0-9s]*ms' | awk '{
            v = substr($1, 2, length($1) - 3); n = split(v, p, "s");
            print (n == 2 ? p[1] * 1000 + p[2] : v) }')"
        [ -n "$fd" ] && fully="$fully $fd"
        if [ "$ours" = 1 ]; then
            first="$(sh_ logcat -d -s ZeroLauncher:D | sed -n 's/.*first_list_draw=\([0-9]*\)ms.*/\1/p' | tail -1)"
            [ -n "$first" ] && firstdraw="$firstdraw $first"
        fi
    done
    nap 5
    local after_start stable pid t0 t1 ticks cpu
    after_start="$(meminfo "$pkg")"
    log "[$pkg] idle ${IDLE}s (CPU + stable RAM)"
    wake
    go_home "$pkg" >/dev/null
    nap 2
    pid="$(pid_of "$pkg")"
    t0="$(cpu_ticks "$pid")"
    nap "$IDLE"
    t1="$(cpu_ticks "$pid")"
    stable="$(meminfo "$pkg")"
    if [ -n "$t0" ] && [ -n "$t1" ]; then
        ticks=$((t1 - t0))
        cpu="$(awk -v t="$ticks" -v hz="$HZ" -v s="$IDLE" 'BEGIN { printf "%.3f%% of one core (%d ticks in %ds)", t * 100 / (hz * s), t, s }')"
    else
        cpu="n/a (could not read /proc/$pid/stat)"
    fi

    out ""
    out "## $pkg"
    out ""
    out "| metric | result |"
    out "|---|---|"
    out "| cold start, am start -W TotalTime (ms) | $(echo "$cold" | tr ' ' '\n' | grep . | stats) |"
    out "| cold start, \"Fully drawn\" (ms) | $(echo "$fully" | tr ' ' '\n' | grep . | stats) |"
    [ "$ours" = 1 ] && out "| onCreate → first frame with the app list (ms) | $(echo "$firstdraw" | tr ' ' '\n' | grep . | stats) |"
    set -- $after_start
    out "| PSS ~5 s after start (KB) | total $1 / Java $2 / native $3 / graphics $4 |"
    set -- $stable
    out "| PSS after ${IDLE} s idle (KB) | total $1 / Java $2 / native $3 / graphics $4 |"
    out "| CPU while idle on the home screen | $cpu |"
}

swipe_page() {
    local w="$1" h="$2" dir="$3"
    local y=$((h / 2)) a=$((w * 8 / 10)) b=$((w * 2 / 10))
    if [ "$dir" = next ]; then sh_ input swipe "$a" "$y" "$b" "$y" 120; else sh_ input swipe "$b" "$y" "$a" "$y" 120; fi
}

measure_interaction() {
    local size w h i
    size="$(sh_ wm size | awk -F': *' '/Override size/ {o=$2} /Physical size/ {p=$2} END {print (o != "" ? o : p)}')"
    w="${size%x*}"; h="${size#*x}"
    log "screen ${w}x${h}; page switching"
    wake
    go_home "$PKG" >/dev/null
    nap 2
    sh_ logcat -c
    for i in $(seq 1 10); do swipe_page "$w" "$h" next; nap 0.5; done
    for i in $(seq 1 10); do swipe_page "$w" "$h" prev; nap 0.5; done
    nap 1
    local logs
    logs="$(sh_ logcat -d -s ZeroLauncher:D)"
    out ""
    out "## Interaction ($PKG)"
    out ""
    out "| metric | result |"
    out "|---|---|"
    out "| page switch: touch-up → page drawn (ms) | $(echo "$logs" | sed -n 's/.*page_switch=\([0-9]*\)ms.*/\1/p' | stats) |"
    local draws
    draws="$(echo "$logs" | sed -n 's/.*[ :]draw=\([0-9]*\)ms.*/\1/p')"
    out "| onDraw time during swipes (ms) | $(echo "$draws" | grep . | stats) |"
    out "| draws over 16 ms (frame budget at 60 Hz) | $(echo "$draws" | grep . | awk '$1 > 16' | wc -l | tr -d ' ') |"

    log "app launches (D-pad + Enter on the current page)"
    sh_ logcat -c
    for i in $(seq 1 5); do
        wake
        go_home "$PKG" >/dev/null
        nap 2
        sh_ input keyevent KEYCODE_DPAD_DOWN
        sh_ input keyevent KEYCODE_ENTER
        nap 3
    done
    go_home "$PKG" >/dev/null
    out "| launch: key/touch event → startActivity returned (ms) | $(sh_ logcat -d -s ZeroLauncher:D | sed -n 's/.*launch_request=\([0-9]*\)ms.*/\1/p' | stats) |"

    log "long run: $ROUNDS rounds"
    local before after
    before="$(meminfo "$PKG" | awk '{print $1}')"
    for i in $(seq 1 "$ROUNDS"); do
        wake
        go_home "$PKG" >/dev/null
        swipe_page "$w" "$h" next; swipe_page "$w" "$h" next; swipe_page "$w" "$h" prev; swipe_page "$w" "$h" prev
        sh_ input keyevent KEYCODE_DPAD_DOWN
        sh_ input keyevent KEYCODE_ENTER
        nap 2
    done
    go_home "$PKG" >/dev/null
    nap 10
    after="$(meminfo "$PKG" | awk '{print $1}')"
    out "| PSS before / after $ROUNDS rounds of swipes + launches (KB) | $before / $after |"
}

measure_app "$PKG" 1
measure_interaction
[ -n "$OTHER" ] && measure_app "$OTHER" 0

sh_ setprop log.tag.ZeroLauncher '""'
go_home "$PKG" >/dev/null
out ""
out "Notes: PSS from \`dumpsys meminfo\`; CPU from utime+stime in /proc/<pid>/stat (CLK_TCK=$HZ)."
out "Zero Launcher renders in software, so \`dumpsys gfxinfo\` has no frame stats for it; the"
out "draw times above come from its own log (UI-thread time inside onDraw)."
log "report: $REPORT"
