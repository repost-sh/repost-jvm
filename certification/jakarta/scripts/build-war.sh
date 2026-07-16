#!/usr/bin/env bash
set -euo pipefail

[[ $# -ge 2 && $# -le 3 ]] || {
  echo "usage: $0 plugin|cli OUTPUT_WAR [skinny]" >&2
  exit 2
}
mode=$1
output=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
packaging=${3:-war}
[[ "$mode" == plugin || "$mode" == cli ]] || { echo "mode must be plugin or cli" >&2; exit 2; }
[[ "$packaging" == war || "$packaging" == skinny ]] || {
  echo "packaging must be skinny when supplied" >&2
  exit 2
}
jakarta=$(cd "$(dirname "$0")/.." && pwd)
jvm=$(cd "$jakarta/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

one() {
  local pattern=$1
  local found
  found=$(find "$HOME/.gradle/caches/modules-2/files-2.1" -path "$pattern" -name '*.jar' -print | sort | tail -1)
  [[ -n "$found" ]] || { echo "missing cached dependency: $pattern" >&2; exit 1; }
  printf '%s' "$found"
}

client=$(find "$jvm/repost-client/build/libs" -name 'repost-client-*.jar' -print | sort | tail -1)
cdi=$(find "$jvm/repost-client-cdi/build/libs" -name 'repost-client-cdi-*.jar' -print | sort | tail -1)
[[ -n "$client" && -n "$cdi" ]] || { echo "build repost-client and repost-client-cdi jars first" >&2; exit 1; }
cdi_api=$(one '*jakarta.enterprise.cdi-api*')
inject_api=$(one '*jakarta.inject-api*')
lang_model=$(one '*jakarta.enterprise.lang-model*')
mp_api=$(one '*microprofile-config-api*')
jspecify=$(one '*jspecify*')
jackson=$(one '*com.fasterxml.jackson.core/jackson-core/2.21.2*')

mkdir -p "$work/classes/META-INF" "$work/war/WEB-INF/classes" "$work/war/WEB-INF/lib"
sources=("$jakarta/generated-glue-fixture/common")
sources+=("$jakarta/generated-glue-fixture/$mode")
find "${sources[@]}" -name '*.java' -print0 | xargs -0 javac --release 17 \
  -cp "$client:$cdi_api:$inject_api:$lang_model:$mp_api:$jspecify" -d "$work/classes"
cp -R "$work/classes/." "$work/war/WEB-INF/classes/"
if [[ "$mode" == plugin ]]; then
  mkdir -p "$work/war/WEB-INF/classes/META-INF/services"
  cp "$jakarta/generated-glue-fixture/plugin/META-INF/services/"* \
    "$work/war/WEB-INF/classes/META-INF/services/"
fi
printf '<beans bean-discovery-mode="annotated" version="4.0"/>\n' \
  > "$work/war/WEB-INF/beans.xml"
printf '%s\n' \
  'repost.client.api-key=certification-key' \
  'repost.client.base-uri=https://127.0.0.1:9443' \
  'repost.certification=container' \
  > "$work/war/WEB-INF/classes/META-INF/microprofile-config.properties"
if [[ "$packaging" == war ]]; then
  cp "$client" "$cdi" "$jackson" "$work/war/WEB-INF/lib/"
fi
jar --create --file "$output" -C "$work/war" .
printf 'PASS mode=%s packaging=%s war=%s\n' "$mode" "$packaging" "$output"
