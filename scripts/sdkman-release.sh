#!/usr/bin/env bash
# =============================================================================
# sdkman-release.sh — Announce a new Kobol release to the SDKMAN! vendor API.
#
# Usage (typically called from the GitHub Actions release workflow):
#   VERSION=0.1.0 SDKMAN_KEY=<key> SDKMAN_TOKEN=<token> ./scripts/sdkman-release.sh
#
# Prerequisites:
#   - SDKMAN vendor API credentials (apply at https://vendors.sdkman.io)
#   - The release ZIP/tar.gz files must already be published to GitHub Releases
#
# Environment variables:
#   VERSION        — release version, e.g. 0.1.0
#   SDKMAN_KEY     — SDKMAN vendor API key
#   SDKMAN_TOKEN   — SDKMAN vendor API token
#   GITHUB_REPO    — GitHub repository slug, default: kobol-lang/kobol
#   DEFAULT_PLATFORM — set to "LINUX_64" to make this version the default (optional)
# =============================================================================
set -euo pipefail

VERSION="${VERSION:?VERSION env var is required}"
SDKMAN_KEY="${SDKMAN_KEY:?SDKMAN_KEY env var is required}"
SDKMAN_TOKEN="${SDKMAN_TOKEN:?SDKMAN_TOKEN env var is required}"
GITHUB_REPO="${GITHUB_REPO:-kobol-lang/kobol}"
BASE_URL="https://github.com/${GITHUB_REPO}/releases/download/v${VERSION}"
API="https://vendors.sdkman.io/2"
CANDIDATE="kobol"

announce() {
  local platform="$1"
  local asset="$2"
  local url="${BASE_URL}/${asset}"

  echo "Announcing ${platform} → ${url}"
  curl --fail --silent --show-error \
    -X POST "${API}/release" \
    -H "Consumer-Key: ${SDKMAN_KEY}" \
    -H "Consumer-Token: ${SDKMAN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{
      \"candidate\": \"${CANDIDATE}\",
      \"version\": \"${VERSION}\",
      \"platform\": \"${platform}\",
      \"url\": \"${url}\"
    }"
  echo
}

# Register all platform variants
announce "MAC_ARM64"  "kobol-macos-arm64.tar.gz"
announce "MAC_OSX"    "kobol-macos-x86_64.tar.gz"
announce "LINUX_64"   "kobol-linux-x86_64.tar.gz"
announce "LINUX_ARM64" "kobol-linux-aarch64.tar.gz"
announce "WINDOWS_64" "kobol-windows-x86_64.zip"

# Optionally set this version as the default / latest
if [[ "${DEFAULT_PLATFORM:-}" == "LINUX_64" ]]; then
  echo "Setting ${VERSION} as default..."
  curl --fail --silent --show-error \
    -X PUT "${API}/default/${CANDIDATE}/${VERSION}" \
    -H "Consumer-Key: ${SDKMAN_KEY}" \
    -H "Consumer-Token: ${SDKMAN_TOKEN}"
  echo
fi

echo "SDKMAN release ${VERSION} announced successfully."
