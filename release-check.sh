#!/usr/bin/env bash
# Checks that the launcher version, the built APK and the GitHub release all
# agree. Run this before publishing or recording instructions, and in CI.
#
#   ./launcher/release-check.sh            local build (if present) + remote release
#   ./launcher/release-check.sh --local    only launcher/build/dumbtv-home-<VERSION>.apk
#   ./launcher/release-check.sh --remote   only the GitHub release
#   ./launcher/release-check.sh --manifest only launcher/version.env (used by CI)
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
REPO=https://github.com/humblemuzzu/dumbtv

mode=${1:-all}
case $mode in
  all|--local|--remote|--manifest) ;;
  *) echo "usage: $0 [--local|--remote|--manifest]" >&2; exit 2 ;;
esac
[[ $mode == all ]] && mode=both

fail=0
ok()   { printf '  ok    %s\n' "$*"; }
bad()  { printf '  FAIL  %s\n' "$*"; fail=1; }

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

# --- version.env ------------------------------------------------------------
[[ -f version.env ]] || { echo "error: launcher/version.env missing" >&2; exit 1; }
# shellcheck disable=SC1091
source version.env
echo "version $VERSION"

[[ $VERSION =~ ^[0-9]+\.[0-9]+$ ]] || bad "VERSION '$VERSION' is not <major>.<minor>"
[[ $APK_SHA256 =~ ^[0-9a-f]{64}$ ]] || bad "APK_SHA256 is not a 64-char lowercase hex digest"
grep -q 'version.env' install.sh || bad "install.sh does not source version.env"
grep -q 'version.env' build.sh   || bad "build.sh does not source version.env"

[[ $mode == --manifest ]] && { [[ $fail == 0 ]] && echo "manifest consistent"; exit $fail; }

# --- local build ------------------------------------------------------------
if [[ $mode == both || $mode == --local ]]; then
  APK=build/dumbtv-home-$VERSION.apk
  if [[ -f $APK ]]; then
    got=$(sha256 "$APK")
    [[ $got == "$APK_SHA256" ]] && ok "$APK matches" || bad "$APK is $got, version.env says $APK_SHA256 — rebuild and update the hash"
  else
    [[ $mode == --local ]] && bad "$APK not built (run ./build.sh)" || echo "  note  $APK not built, skipping local check"
  fi
fi

# --- remote release ---------------------------------------------------------
if [[ $mode == both || $mode == --remote ]]; then
  URL=$REPO/releases/download/launcher-v$VERSION/dumbtv-home.apk
  code=$(curl -sIL -o /dev/null -w '%{http_code}' "$URL" 2>/dev/null || echo 000)
  if [[ $code == 200 ]]; then
    tmp=$(mktemp -t dumbtv-release.XXXXXX)
    if curl -fsSL -o "$tmp" "$URL"; then
      got=$(sha256 "$tmp")
      [[ $got == "$APK_SHA256" ]] && ok "release launcher-v$VERSION matches" \
        || bad "release APK is $got, version.env says $APK_SHA256 — re-upload the build"
    else
      bad "could not download $URL"
    fi
    rm -f "$tmp"
  else
    bad "release launcher-v$VERSION not reachable ($code): $URL"
  fi
fi

if [[ $fail == 0 ]]; then
  echo "release consistent"
else
  echo
  echo "fix: edit launcher/version.env, ./build.sh, upload the APK to tag launcher-v\$VERSION,"
  echo "     then run this again. The tag, the hash and the APK must be one commit."
fi
exit $fail
