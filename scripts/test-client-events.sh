#!/usr/bin/env bash
# Development/test stack only: creates a real client and funded account, retained for inspection.
# Existing databases must first apply scripts/migrate-reporting-clients.sql.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose version >/dev/null
export CLIENT_REGISTRATION_EVENTS_ENABLED=true
trap 'status=$?; if ((status != 0)); then docker compose logs --no-color --tail 80 iam-app user-etl kafka-init || true; fi; exit "$status"' EXIT

docker compose up -d --build db kafka kafka-init account-app iam-app user-etl
for service in iam-app account-app; do
    port=8081
    [[ "$service" == account-app ]] && port=8082
    ready=false
    for ((attempt=0; attempt<120; attempt++)); do
        if docker compose exec -T "$service" wget -q -O /dev/null "http://localhost:$port/actuator/health"; then
            ready=true
            break
        fi
        sleep 2
    done
    $ready || { echo "$service did not become healthy" >&2; exit 1; }
done

docker compose run --rm --no-deps -T --entrypoint python \
    -e PYTHONPATH=/app \
    -v "$PWD/reporting-etl/integration:/integration:ro" \
    user-etl /integration/check_client_events.py
