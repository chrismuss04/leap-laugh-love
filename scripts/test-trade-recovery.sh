#!/usr/bin/env bash
# Start an isolated durable backend stack; optionally run a recovery test command.
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"
command -v docker >/dev/null
command -v python3 >/dev/null
docker compose version >/dev/null
docker info >/dev/null

# Ignore deployment .env settings and never reuse a caller-selected project/volume.
active_run="$repo_root/.recovery-results/active-run"
cleanup_only=false
if [[ "${1:-}" == '--cleanup' ]]; then
    [[ -f "$active_run" ]] || exit 0
    run_id=$(cat "$active_run")
    [[ "$run_id" =~ ^[a-f0-9]{32}$ ]] || { echo "Invalid recovery run marker; refusing cleanup."; exit 1; }
    cleanup_only=true
else
    [[ ! -f "$active_run" ]] || { echo "An earlier run needs cleanup: bash scripts/test-trade-recovery.sh --cleanup"; exit 1; }
    run_id=$(python3 -c 'import uuid; print(uuid.uuid4().hex)')
fi
export COMPOSE_PROJECT="leap-recovery-$run_id"
export COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT"
export DB_VOLUME_NAME="${COMPOSE_PROJECT}_db_data"
export DB_VOLUME_EXTERNAL=false
# CI has already built this commit's service images; RECOVERY_IMAGE_TAG reuses them instead of
# building all four again. Those images belong to the caller, so cleanup leaves them alone.
# Without it (local runs) the images are built here under a tag only this run uses.
prebuilt_tag="${RECOVERY_IMAGE_TAG:-}"
unset RECOVERY_IMAGE_TAG
if [[ -n "$prebuilt_tag" ]]; then
    [[ "$prebuilt_tag" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || { echo "Invalid RECOVERY_IMAGE_TAG."; exit 1; }
    export IMAGE_TAG="$prebuilt_tag"
else
    export IMAGE_TAG="$COMPOSE_PROJECT"
fi
export DB_PASSWORD="recovery-$run_id"
export JWT_SECRET="recovery-test-only-$run_id-$run_id"
export DB_PORT=0 IAM_PORT=0 ACCOUNT_PORT=0 ORDER_PORT=0 MARKETDATA_PORT=0 FRONTEND_PORT=0
export MARKETDATA_BACKFILL_ENABLED=false
unset COMPOSE_FILE COMPOSE_PROFILES
unset RECOVERY_ACCOUNT_SERVICE_BASE_URL
compose=(docker compose --env-file /dev/null -p "$COMPOSE_PROJECT"
    -f "$repo_root/docker-compose.yml" -f "$repo_root/docker-compose.e2e.yml")
export RECOVERY_PROJECT="$COMPOSE_PROJECT"
export RECOVERY_REPO_ROOT="$repo_root"
export RECOVERY_RESULTS_DIR="$repo_root/.recovery-results/$run_id"
mkdir -p "$RECOVERY_RESULTS_DIR"

# Validate the resolved storage before allowing any container creation or cleanup.
"${compose[@]}" config --format json | python3 -c '
import json, os, sys
c = json.load(sys.stdin)
v = c["volumes"]["db_data"]
assert v["name"] == os.environ["DB_VOLUME_NAME"] and not v.get("external", False)
mount = next(m for m in c["services"]["db"]["volumes"] if m["target"] == "/var/lib/postgresql/data")
assert mount["type"] == "volume" and mount["source"] == "db_data"
assert c["services"]["db"]["command"] == ["postgres", "-c", "fsync=on", "-c", "synchronous_commit=on", "-c", "full_page_writes=on"]
'

cleanup() {
    result=$?
    trap - EXIT INT TERM
    "${compose[@]}" ps -a >> "$RECOVERY_RESULTS_DIR/containers.txt" 2>&1 || true
    "${compose[@]}" logs --no-color --tail=300 >> "$RECOVERY_RESULTS_DIR/stack.log" 2>&1 || true
    docker logs "${COMPOSE_PROJECT}-fault-proxy" >> "$RECOVERY_RESULTS_DIR/fault-proxy.log" 2>&1 || true
    docker rm -f "${COMPOSE_PROJECT}-fault-proxy" >/dev/null 2>&1 || true
    if ! "${compose[@]}" down -v --remove-orphans; then
        echo "Cleanup failed for $COMPOSE_PROJECT; inspect its resources."
        result=1
    else
        rm -f -- "$active_run"
    fi
    if [[ -z "$prebuilt_tag" ]]; then
        for service in iam-app account-app order-app market-data-app; do
            docker image rm "$service:$IMAGE_TAG" >/dev/null 2>&1 || true
        done
    fi
    echo "Recovery environment results: $RECOVERY_RESULTS_DIR"
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ "$cleanup_only" == true ]]; then
    exit 0 # EXIT trap performs the same scoped cleanup after a Jenkins interruption.
fi
printf '%s\n' "$run_id" > "$active_run"
echo "Starting isolated recovery environment: $COMPOSE_PROJECT"
"${compose[@]}" up -d --wait --wait-timeout 180 db

# Check both settings and schema completion (pg_isready alone can precede seeding).
ready=false
for ((attempt=0; attempt<90; attempt++)); do
    settings=$("${compose[@]}" exec -T db psql -U paysprint -d paysprint -At -v ON_ERROR_STOP=1 -c \
        "SELECT current_setting('fsync'), current_setting('synchronous_commit'), current_setting('full_page_writes') WHERE to_regclass('trading.orders') IS NOT NULL AND to_regclass('trading.executions') IS NOT NULL AND to_regclass('trading.cash_ledger') IS NOT NULL AND to_regclass('trading.positions') IS NOT NULL;" 2>/dev/null || true)
    if [[ "$settings" == 'on|on|on' ]]; then ready=true; break; fi
    sleep 2
done
[[ "$ready" == true ]] || { echo "Durable database/schema readiness check failed."; exit 1; }
printf '%s\n' "$settings" > "$RECOVERY_RESULTS_DIR/durability.txt"

if [[ -n "$prebuilt_tag" ]]; then
    for service in iam-app account-app order-app market-data-app; do
        docker image inspect "$service:$IMAGE_TAG" >/dev/null 2>&1 \
            || { echo "Prebuilt image $service:$IMAGE_TAG not found."; exit 1; }
    done
    "${compose[@]}" up -d --no-build iam-app account-app order-app market-data-app
else
    "${compose[@]}" up -d --build iam-app account-app order-app market-data-app
fi
for pair in iam-app:8081 account-app:8082 order-app:8084 market-data-app:8083; do
    service=${pair%:*}
    port=${pair#*:}
    ready=false
    for ((attempt=0; attempt<90; attempt++)); do
        if "${compose[@]}" exec -T "$service" wget -q -O /dev/null "http://localhost:$port/actuator/health"; then
            ready=true
            break
        fi
        sleep 2
    done
    [[ "$ready" == true ]] || { echo "$service did not become healthy."; exit 1; }
done

# Host-side tests use these URLs; Docker-side tests can use RECOVERY_PROJECT_default.
for pair in IAM:8081 ACCOUNT:8082 ORDER:8084 MARKETDATA:8083; do
    name=${pair%:*}
    port=${pair#*:}
    case "$name" in
        IAM) service=iam-app ;;
        ACCOUNT) service=account-app ;;
        ORDER) service=order-app ;;
        MARKETDATA) service=market-data-app ;;
    esac
    binding=$("${compose[@]}" port "$service" "$port" | head -n 1)
    export "RECOVERY_${name}_URL=http://127.0.0.1:${binding##*:}"
done

echo "Durable backend environment is ready."
if (( $# )); then
    "$@"
else
    echo "Environment smoke check passed; pass the test:recovery command to run failure scenarios."
fi
