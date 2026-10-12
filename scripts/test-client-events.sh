#!/usr/bin/env bash
# Development/test stack only: creates a real client and funded account, retained for inspection.
# Existing databases must first apply scripts/migrate-reporting-clients.sql.
set -euo pipefail
cd "$(dirname "$0")/.."
# Compose v2 is the "docker compose" plugin on most installs, but some have only the standalone
# "docker-compose". Use whichever this machine has.
if docker compose version >/dev/null 2>&1; then
    compose() { docker compose "$@"; }
else
    docker-compose version >/dev/null
    compose() { docker-compose "$@"; }
fi
export CLIENT_REGISTRATION_EVENTS_ENABLED=true
trap 'status=$?; if ((status != 0)); then compose logs --no-color --tail 80 iam-app user-etl kafka-init || true; fi; exit "$status"' EXIT

# mailpit: IAM opens the client only once the link it emails is followed, and the check reads it there.
compose up -d --build db kafka kafka-init mailpit account-app iam-app user-etl
for service in iam-app account-app; do
    port=8081
    [[ "$service" == account-app ]] && port=8082
    ready=false
    for ((attempt=0; attempt<120; attempt++)); do
        if compose exec -T "$service" wget -q -O /dev/null "http://localhost:$port/actuator/health"; then
            ready=true
            break
        fi
        sleep 2
    done
    $ready || { echo "$service did not become healthy" >&2; exit 1; }
done

compose run --rm --no-deps -T --entrypoint python \
    -e PYTHONPATH=/app \
    -v "$PWD/reporting-etl/integration:/integration:ro" \
    user-etl /integration/check_client_events.py
