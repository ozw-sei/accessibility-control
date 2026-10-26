#!/usr/bin/env bash
# Lock the tag → versionName / versionCode mapping used by the release workflow.
set -euo pipefail

script="$(cd "$(dirname "$0")" && pwd)/release-version.sh"

expect() {
  local tag="$1" name="$2" code="$3" pre="$4"
  local out got_name got_code got_pre
  out="$("$script" "$tag")"
  got_name="$(printf '%s\n' "$out" | sed -n 's/^version_name=//p')"
  got_code="$(printf '%s\n' "$out" | sed -n 's/^version_code=//p')"
  got_pre="$(printf '%s\n' "$out" | sed -n 's/^prerelease=//p')"
  if [[ "$got_name" != "$name" || "$got_code" != "$code" || "$got_pre" != "$pre" ]]; then
    echo "FAIL $tag" >&2
    echo "  expected name=$name code=$code pre=$pre" >&2
    echo "  got:" >&2
    printf '%s\n' "$out" >&2
    exit 1
  fi
  echo "ok $tag -> $name $code prerelease=$pre"
}

expect_fail() {
  local tag="$1"
  if "$script" "$tag" >/dev/null 2>&1; then
    echo "FAIL $tag should have been rejected" >&2
    exit 1
  fi
  echo "ok reject $tag"
}

expect v1.2.3 1.2.3 1020399 false
expect v1.2.3-beta.4 1.2.3-beta.4 1020304 true
expect v0.1.5 0.1.5 10599 false
expect v10.0.0 10.0.0 10000099 false
expect v1.2.3-beta.08 1.2.3-beta.08 1020308 true
expect v1.2.3-beta.1 1.2.3-beta.1 1020301 true
expect v1.2.3-beta.98 1.2.3-beta.98 1020398 true
expect v0.0.0 0.0.0 99 false
expect v0.0.0-beta.1 0.0.0-beta.1 1 true
expect v2099.99.99 2099.99.99 2099999999 false
expect v2099.0.0 2099.0.0 2099000099 false
expect v1.99.99 1.99.99 1999999 false

expect_fail v1.2
expect_fail v1.2.3-beta
expect_fail v1.2.3-rc.1
expect_fail v1.2.3-beta.0
expect_fail v1.2.3-beta.00
expect_fail v0.0.0-beta.0
expect_fail v1.2.3-beta.99
expect_fail v1.2.3-beta.100
expect_fail v2100.0.0
expect_fail v1.100.0
expect_fail v1.2.100
expect_fail v9999999999.0.0
expect_fail 1.2.3
expect_fail V1.2.3

echo "all release version checks passed"
