#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
while IFS=$'\t' read -r lane kind source; do
  if [[ "$kind" == image ]]; then
    receipt=$(docker manifest inspect --verbose "$source" 2>&1) || {
      printf 'FAIL\t%s\t%s\t%s\n' "$lane" "$source" "$receipt"
      exit 1
    }
  else
    receipt=$(curl -fsSIL "$source" 2>&1) || {
      printf 'FAIL\t%s\t%s\t%s\n' "$lane" "$source" "$receipt"
      exit 1
    }
  fi
  printf 'PASS\t%s\t%s\t%s\n' "$lane" "$source" \
    "$(printf '%s' "$receipt" | shasum -a 256 | cut -d' ' -f1)"
done < "$root/scripts/images.tsv"
