#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 3 ]] || { echo "usage: $0 wildfly|open-liberty|payara CONTAINER WAR" >&2; exit 2; }
runtime=$1
container=$2
war=$(cd "$(dirname "$3")" && pwd)/$(basename "$3")
[[ -f "$war" ]] || { echo "missing WAR: $war" >&2; exit 1; }

wildfly_cli=/opt/jboss/wildfly/bin/jboss-cli.sh
if [[ "$runtime" == wildfly ]] \
    && ! docker exec "$container" test -x "$wildfly_cli"; then
  wildfly_cli=/opt/wildfly/bin/jboss-cli.sh
fi

log_count() {
  docker logs "$container" 2>&1 | grep -cF "$1" || true
}

wait_for_log() {
  local marker=$1
  local before=$2
  for _ in $(seq 1 120); do
    if (( $(log_count "$marker") > before )); then
      return
    fi
    sleep 0.25
  done
  echo "timed out waiting for $marker from $container" >&2
  exit 1
}

deploy() {
  case "$runtime" in
    wildfly)
      docker cp "$war" "$container:/tmp/repost.war"
      docker exec "$container" "$wildfly_cli" --connect \
        --command='deploy /tmp/repost.war --name=repost.war --force'
      ;;
    open-liberty)
      local before
      before=$(log_count REPOST_JAKARTA_READY)
      docker cp "$war" "$container:/config/dropins/repost.war"
      wait_for_log REPOST_JAKARTA_READY "$before"
      ;;
    payara)
      docker cp "$war" "$container:/tmp/repost.war"
      docker exec "$container" asadmin --user admin \
        --passwordfile /opt/payara/passwordFile \
        deploy --force=true --name=repost /tmp/repost.war
      ;;
  esac
}

undeploy() {
  case "$runtime" in
    wildfly)
      docker exec "$container" "$wildfly_cli" --connect \
        --command='undeploy repost.war'
      ;;
    open-liberty)
      local before
      before=$(log_count REPOST_JAKARTA_STOPPED)
      docker exec "$container" rm -f /config/dropins/repost.war
      wait_for_log REPOST_JAKARTA_STOPPED "$before"
      ;;
    payara)
      docker exec "$container" asadmin --user admin \
        --passwordfile /opt/payara/passwordFile undeploy repost
      ;;
  esac
}

for cycle in $(seq 1 100); do
  deploy
  undeploy
  printf 'cycle=%d runtime=%s\n' "$cycle" "$runtime"
done

logs=$(docker logs "$container" 2>&1)
if printf '%s' "$logs" | grep -Eqi 'classloader.*leak|thread.*leak|repost.*not stopped'; then
  echo "server emitted leak warning" >&2
  exit 1
fi
printf 'PASS runtime=%s redeploys=100 leak-warnings=0\n' "$runtime"
