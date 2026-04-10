#!/usr/bin/env bash
# Fetches the latest stable SeleniumHQ/selenium release (no pre-releases) and
# writes "<version>-SNAPSHOT" into build.gradle.kts line matching: version = "..."
set -euo pipefail

REPO="SeleniumHQ/selenium"
BUILD_FILE="$(cd "$(dirname "$0")/../.." && pwd)/build.gradle.kts"

latest_selenium_version() {
  local json
  json=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest")
  # tag_name is e.g. "selenium-4.42.0" — strip the "selenium-" prefix
  echo "$json" | grep -m1 '"tag_name"' | sed 's/.*"tag_name": *"selenium-\([^"]*\)".*/\1/'
}

VERSION="$(latest_selenium_version)"

if [[ -z "$VERSION" ]]; then
  echo "ERROR: could not determine latest Selenium version" >&2
  exit 1
fi

SNAPSHOT="${VERSION}-SNAPSHOT"

sed -i.bak "s/^version = \".*\"/version = \"${SNAPSHOT}\"/" "$BUILD_FILE"
rm -f "${BUILD_FILE}.bak"

echo "Updated build.gradle.kts → version = \"${SNAPSHOT}\""
