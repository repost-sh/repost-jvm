#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 2 ]] || { echo "usage: $0 CDI_JAR WAR" >&2; exit 2; }
cdi_jar=$1
war=$2
root=$(cd "$(dirname "$0")/.." && pwd)

for archive in "$cdi_jar" "$war"; do
  [[ -f "$archive" ]] || { echo "missing archive: $archive" >&2; exit 1; }
done

if unzip -Z1 "$war" | grep -Eqi 'WEB-INF/lib/(jakarta\.(enterprise|inject)|microprofile-config|jackson-databind|logback|slf4j-(simple|jdk14)|wildfly|openliberty|payara)'; then
  echo "forbidden server/API/implementation dependency in WAR" >&2
  exit 1
fi
if unzip -p "$cdi_jar" | strings | grep -Eq 'Class\.forName|java/lang/reflect|ServiceLoader'; then
  echo "reflection or Repost-owned service lookup in CDI archive" >&2
  exit 1
fi

grep -Fq 'return OrdersClientFactory.INSTANCE.create(runtime);' \
  "$root/generated-glue-fixture/cli/certification/glue/ExplicitClientProducers.java"
grep -Fq 'return KotlinCatalogClientFactory.INSTANCE.create(runtime);' \
  "$root/generated-glue-fixture/cli/certification/glue/ExplicitClientProducers.java"
printf 'PASS archive=%s war=%s reflection=absent cli-producers=exact\n' "$cdi_jar" "$war"
