#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
VENDOR_DIRECTORY="${ROOT}/sdk/jvm/vendor/apache-httpcomponents"
TESTS=(
  "${VENDOR_DIRECTORY}/vendor-lock.test.js"
  "${VENDOR_DIRECTORY}/relocation.test.js"
  "${VENDOR_DIRECTORY}"/patch-*.test.js
)

if [[ ! -f "${TESTS[0]}" ]]; then
  echo "::error::missing HTTP engine vendor contract test: ${TESTS[0]}" >&2
  exit 1
fi

if [[ -n "${REPOST_HTTP_ENGINE_ARCHIVE_DIR:-}" ]]; then
  if [[ ! -d "${REPOST_HTTP_ENGINE_ARCHIVE_DIR}" ]]; then
    echo "::error::archive directory does not exist: ${REPOST_HTTP_ENGINE_ARCHIVE_DIR}" >&2
    exit 1
  fi
  env REPOST_HTTP_ENGINE_ARCHIVE_DIR="${REPOST_HTTP_ENGINE_ARCHIVE_DIR}" \
    node --test "${TESTS[@]}"
else
  node --test "${TESTS[@]}"
fi
