#!/usr/bin/env bash
# Builds dumbtv home, installs it on the TV and gives it the HOME button.
#   ./launcher/install.sh          build, install, take over HOME
#   ./launcher/install.sh --undo   hand HOME back (Projectivy if installed, else Amazon)
# Pick the device with ADB_SERIAL when more than one is connected.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

PKG=tv.dumbtv.home
GUARD=$PKG/$PKG.Guard
PROJECTIVY=com.spocky.projengmenu
PROJECTIVY_GUARD=$PROJECTIVY/$PROJECTIVY.services.ProjectivyAccessibilityService

adb_() { adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }
sh_() { adb_ shell "$@" </dev/null; }
installed() { sh_ pm path "$1" 2>/dev/null | grep -q '^package:'; }

state=$(adb_ get-state 2>/dev/null || true)
[[ $state == device ]] || { echo "error: no TV connected (adb connect <tv-ip>:5555; set ADB_SERIAL if several)" >&2; exit 1; }

# Alarms normally fire no sooner than 5 s from now; the home switch needs them immediate.
set_futurity() {
  local cur
  cur=$(sh_ settings get global alarm_manager_constants | tr -d '\r')
  [[ $cur == null ]] && cur=
  cur=$(printf '%s' "$cur" | tr ',' '\n' | { grep -v '^min_futurity=' || true; } | paste -sd, -)
  if [[ $1 == on ]]; then cur=${cur:+$cur,}min_futurity=0; fi
  if [[ -n $cur ]]; then sh_ settings put global alarm_manager_constants "$cur"
  else sh_ settings delete global alarm_manager_constants >/dev/null; fi
}

# Puts $1 (or nothing) in place of either launcher's service, keeping any other service.
set_guard() {
  local cur
  cur=$(sh_ settings get secure enabled_accessibility_services | tr -d '\r')
  [[ $cur == null ]] && cur=
  cur=$(printf '%s' "$cur" | tr ':' '\n' | { grep -vxF -e "$GUARD" -e "$PROJECTIVY_GUARD" -e '' || true; } | paste -sd: -)
  if [[ -n ${1:-} ]]; then cur=${cur:+$cur:}$1; fi
  # Clearing first makes the accessibility manager rebind a service whose process died.
  sh_ settings put secure enabled_accessibility_services '""'
  sleep 1
  if [[ -n $cur ]]; then
    sh_ settings put secure enabled_accessibility_services "$cur"
    sh_ settings put secure accessibility_enabled 1
  else
    sh_ settings put secure accessibility_enabled 0
  fi
}

if [[ ${1:-} == --undo ]]; then
  set_futurity off
  if installed $PROJECTIVY; then
    set_guard $PROJECTIVY_GUARD
    sh_ am start -n $PROJECTIVY/.ui.home.MainActivity >/dev/null
    echo "HOME handed back to Projectivy"
  else
    set_guard ""
    sh_ am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
    echo "HOME handed back to Amazon's launcher"
  fi
  exit 0
fi

./build.sh
out=$(adb_ install -r build/dumbtv-home.apk 2>&1 | tail -1 || true)
[[ $out == *Success* ]] || { echo "error: adb install failed: $out" >&2; exit 1; }
echo "installed $PKG"

sh_ dumpsys deviceidle whitelist +$PKG >/dev/null
sh_ cmd package set-home-activity $PKG/.Home >/dev/null 2>&1 || true
set_futurity on
set_guard $GUARD
sh_ am start -W -n $PKG/.Home >/dev/null
if installed $PROJECTIVY; then
  sh_ am force-stop $PROJECTIVY
  echo "Projectivy stopped (still installed: ./launcher/install.sh --undo switches back)"
fi

sleep 2
printf 'accessibility: %s\n' "$(sh_ settings get secure enabled_accessibility_services)"
printf 'front window:  %s\n' "$(sh_ dumpsys window | grep -m1 mCurrentFocus | sed 's/.* u0 //; s/}$//')"
printf 'memory:        %s\n' "$(sh_ dumpsys meminfo $PKG | grep -m1 'TOTAL' | awk '{print $2 " KB PSS"}')"
