#!/usr/bin/env bash
# Installs FreeTV on the TV and gives it the HOME button.
#   ./install.sh            the release APK (checksum-verified) when one is published, else build from source
#   ./install.sh --build    build from source (needs a JDK and the Android SDK)
#   ./install.sh --undo     hand HOME back to Amazon's launcher
# Pick the device with ADB_SERIAL when more than one is connected.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# shellcheck disable=SC1091
source version.env
APK_URL=https://github.com/centsandcode/FreeTV/releases/download/v$VERSION/freetv.apk

PKG=tv.freetv.home
GUARD=$PKG/$PKG.Guard
MEDIA=$PKG/$PKG.Media
# launchers that took HOME the same way before: their service is swapped for ours
OLD_GUARDS=(
  tv.dumbtv.home/tv.dumbtv.home.Guard
  com.spocky.projengmenu/com.spocky.projengmenu.services.ProjectivyAccessibilityService
)
ADB=${ADB:-adb}

adb_() { "$ADB" ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }
sh_() { adb_ shell "$@" </dev/null; }
installed() { sh_ pm path "$1" 2>/dev/null | grep -q '^package:'; }

mode=release
[[ -z ${APK_SHA256:-} ]] && mode=build
case ${1:-} in
  "") ;;
  --build) mode=build ;;
  --undo) mode=undo ;;
  *) echo "usage: $0 [--build | --undo]" >&2; exit 2 ;;
esac

command -v "$ADB" >/dev/null || { echo "error: adb not found — install Android platform-tools" >&2; exit 1; }
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

# Puts $1 (or nothing) in place of any launcher's service, keeping every other service.
set_guard() {
  local cur drop=(-e "$GUARD" -e '')
  for g in "${OLD_GUARDS[@]}"; do drop+=(-e "$g"); done
  cur=$(sh_ settings get secure enabled_accessibility_services | tr -d '\r')
  [[ $cur == null ]] && cur=
  cur=$(printf '%s' "$cur" | tr ':' '\n' | { grep -vxF "${drop[@]}" || true; } | paste -sd: -)
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

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

if [[ $mode == undo ]]; then
  set_futurity off
  set_guard ""
  sh_ cmd notification disallow_listener "$MEDIA" >/dev/null 2>&1 || true
  sh_ am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
  echo "HOME handed back to Amazon's launcher (FreeTV stays installed: adb uninstall $PKG)"
  exit 0
fi

if [[ $mode == build ]]; then
  ./build.sh
  APK=build/freetv.apk
else
  mkdir -p build
  APK=build/freetv-$VERSION.apk
  if [[ ! -f $APK || $(sha256 "$APK") != "$APK_SHA256" ]]; then
    echo "downloading FreeTV $VERSION"
    curl -fL --progress-bar -o "$APK.part" "$APK_URL" || { echo "error: could not download $APK_URL" >&2; exit 1; }
    mv "$APK.part" "$APK"
  fi
  got=$(sha256 "$APK")
  [[ $got == "$APK_SHA256" ]] || { echo "error: checksum mismatch for $APK (got $got) — not installing" >&2; exit 1; }
  echo "checksum ok"
fi

out=$(adb_ install -r "$APK" 2>&1 | tail -1 || true)
case $out in
  *Success*) ;;
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
    echo "error: the installed FreeTV was signed with a different key (release vs your own build)." >&2
    echo "  back up your settings:  adb pull /sdcard/Android/data/$PKG/files/home.conf" >&2
    echo "  then remove it:         adb uninstall $PKG    and run this again" >&2
    exit 1 ;;
  *) echo "error: adb install failed: $out" >&2; exit 1 ;;
esac
echo "installed $PKG"

# Coming from dumbtv home: bring its settings and wallpapers along, once.
OLD=/sdcard/Android/data/tv.dumbtv.home/files NEW=/sdcard/Android/data/$PKG/files
if installed tv.dumbtv.home && ! sh_ test -f "$NEW/home.conf"; then
  sh_ "mkdir -p $NEW && cp -r $OLD/. $NEW/" && echo "copied dumbtv home settings (uninstall it when happy: adb uninstall tv.dumbtv.home)"
fi

sh_ dumpsys deviceidle whitelist +$PKG >/dev/null
sh_ cmd package set-home-activity $PKG/.Home >/dev/null 2>&1 || true
set_futurity on
set_guard $GUARD
# Now playing reads the active media session; Android hands it only to an enabled notification listener.
sh_ cmd notification allow_listener "$MEDIA" >/dev/null 2>&1 || true
sh_ am start -W -n $PKG/.Home >/dev/null

sleep 2
printf 'accessibility: %s\n' "$(sh_ settings get secure enabled_accessibility_services)"
printf 'front window:  %s\n' "$(sh_ dumpsys window | grep -m1 mCurrentFocus | sed 's/.* u0 //; s/}$//')"
