#!/usr/bin/env bash
# Map a release tag to Gradle -PversionName / -PversionCode.
#
# versionName = tag without the leading "v"
# versionCode = X*1000000 + Y*10000 + Z*100 + (N for -beta.N, 99 for a final release)
#
# Final releases are vX.Y.Z. Prereleases are vX.Y.Z-beta.N with N in 0..98
# so the +N slot does not collide with the final release's +99.
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

# 10# keeps values such as 08 in base 10 (bash arithmetic would treat them as octal).
x=$((10#$x))
y=$((10#$y))
z=$((10#$z))
n=$((10#$n))

if [[ "$prerelease" == true && "$n" -ge 99 ]]; then
  echo "error: beta N must be 0..98 (99 is reserved for the final release versionCode)" >&2
  exit 1
fi

version_code=$((x * 1000000 + y * 10000 + z * 100 + n))
if (( version_code > 2147483647 )); then
  echo "error: versionCode ${version_code} exceeds Android's signed 32-bit max" >&2
  exit 1
fi

printf 'version_name=%s\n' "${tag#v}"
printf 'version_code=%s\n' "$version_code"
printf 'prerelease=%s\n' "$prerelease"
