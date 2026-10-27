#!/usr/bin/env bash
# Map a release tag to Gradle -PversionName / -PversionCode.
#
# versionName = tag without the leading "v"
# versionCode = X*1000000 + Y*10000 + Z*100 + (N for -beta.N, 99 for a final release)
#
# Components: X 0..2099, Y 0..99, Z 0..99, beta N 1..98.
# versionCode must be 1..2100000000 (v0.0.0-beta.0 is 0 and is rejected).
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: release-version.sh vX.Y.Z[-beta.N]" >&2
  exit 2
fi

tag="$1"
prerelease=false

if [[ "$tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  x="${BASH_REMATCH[1]}"
  y="${BASH_REMATCH[2]}"
  z="${BASH_REMATCH[3]}"
  n=99
elif [[ "$tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)-beta\.([0-9]+)$ ]]; then
  x="${BASH_REMATCH[1]}"
  y="${BASH_REMATCH[2]}"
  z="${BASH_REMATCH[3]}"
  n="${BASH_REMATCH[4]}"
  prerelease=true
else
  echo "error: tag '${tag}' must be vX.Y.Z (normal release) or vX.Y.Z-beta.N (prerelease)" >&2
  exit 1
fi

# Assign a decimal component into the named variable.
# Digit strings longer than the maximum are rejected before bash arithmetic so they cannot wrap.
parse_component() {
  local __name="$1" raw="$2" min="$3" max="$4" label="$5"
  local stripped="${raw#"${raw%%[!0]*}"}"
  [[ -n "$stripped" ]] || stripped=0
  if (( ${#stripped} > ${#max} )); then
    echo "error: ${label} ${raw} is outside ${min}..${max}" >&2
    exit 1
  fi
  local value=$((10#$raw))
  if (( value < min || value > max )); then
    echo "error: ${label} ${value} is outside ${min}..${max}" >&2
    exit 1
  fi
  printf -v "$__name" '%s' "$value"
}

parse_component x "$x" 0 2099 X
parse_component y "$y" 0 99 Y
parse_component z "$z" 0 99 Z
if [[ "$prerelease" == true ]]; then
  parse_component n "$n" 1 98 "beta N"
else
  n=99
fi

version_code=$((x * 1000000 + y * 10000 + z * 100 + n))
if (( version_code <= 0 || version_code > 2100000000 )); then
  echo "error: versionCode ${version_code} must be 1..2100000000" >&2
  exit 1
fi

printf 'version_name=%s\n' "${tag#v}"
printf 'version_code=%s\n' "$version_code"
printf 'prerelease=%s\n' "$prerelease"
