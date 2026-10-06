#!/usr/bin/env bash
#
# End-to-end check of the order-events pipeline on the docker compose stack: places real orders
# through order-app and checks that a filled order is published to order-events and turned into
# the right reporting row by the ETL's consumer, and that a validation rejection publishes
# nothing. The checks are in reporting-etl/integration/check_order_events.py.
#
# Kafka has no host port, so the checks run in a one-off reporting-etl container on the compose
# network. The reporting database is not checked.
#
# Usage, from anywhere in the repo:
#   scripts/test-order-events.sh            # start (or reuse) the stack, then check
#   scripts/test-order-events.sh --fresh    # docker compose down -v first: a clean database
#   scripts/test-order-events.sh --down     # tear the stack down afterwards
#
# Needs docker with the compose plugin and curl. JWT_SECRET comes from .env, as for compose.

set -euo pipefail

cd "$(dirname "$0")/.."

FRESH=false
DOWN=false
for arg in "$@"; do
    case "$arg" in
        --fresh) FRESH=true ;;
        --down) DOWN=true ;;
        -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $arg (see --help)" >&2; exit 2 ;;
    esac
done

# Everything but the frontend, which the check doesn't use.
SERVICES=(db kafka kafka-init iam-app account-app market-data-app order-app reporting-etl)
HEALTH_TIMEOUT_SECONDS=600

for tool in docker curl; do
    command -v "$tool" >/dev/null || { echo "'$tool' was not found on PATH." >&2; exit 2; }
done
docker compose version >/dev/null 2>&1 || { echo "The docker compose plugin is not installed." >&2; exit 2; }

dump_logs() {
    echo
    echo "---- last 50 lines of order-app and reporting-etl ----"
    docker compose logs --no-color --tail 50 order-app reporting-etl || true
}

if $FRESH; then
    echo "==> Removing the stack and its volumes"
    docker compose down -v
fi

echo "==> Starting ${SERVICES[*]}"
docker compose up -d --build "${SERVICES[@]}"

echo "==> Waiting for the services to report healthy (a first start generates price history)"
deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
for entry in iam-app:${IAM_PORT:-8081} account-app:${ACCOUNT_PORT:-8082} \
             market-data-app:${MARKETDATA_PORT:-8083} order-app:${ORDER_PORT:-8084}; do
    name=${entry%%:*}
    port=${entry##*:}
    until curl -fsS "http://localhost:${port}/actuator/health" >/dev/null 2>&1; do
        if ((SECONDS > deadline)); then
            echo "$name is not healthy on port $port after ${HEALTH_TIMEOUT_SECONDS}s." >&2
            docker compose logs --no-color --tail 50 "$name" || true
            exit 1
        fi
        sleep 3
    done
    echo "    $name is up"
done

container_of() { docker compose ps -a -q "$1"; }

kafka_init_exit=$(docker inspect -f '{{.State.ExitCode}}' "$(container_of kafka-init)")
if [[ "$kafka_init_exit" != "0" ]]; then
    echo "kafka-init exited $kafka_init_exit, so order-events may not exist." >&2
    docker compose logs --no-color kafka-init || true
    exit 1
fi
echo "    kafka-init created the topics"

if [[ "$(docker inspect -f '{{.State.Running}}' "$(container_of reporting-etl)")" != "true" ]]; then
    echo "reporting-etl is not running." >&2
    dump_logs
    exit 1
fi
echo "    reporting-etl is running"

echo "==> Running the order-events checks"
status=0
docker compose run --rm --no-deps -T \
    --entrypoint python \
    -e PYTHONPATH=/app \
    -v "$PWD/reporting-etl/integration:/integration:ro" \
    reporting-etl /integration/check_order_events.py || status=$?

if ((status != 0)); then
    dump_logs
    echo
    if $DOWN; then
        echo "FAILED"
    else
        echo "FAILED - the stack is left running; inspect it with docker compose logs."
    fi
else
    echo
    echo "PASSED"
fi

if $DOWN; then
    echo "==> Stopping the stack"
    docker compose down
fi
exit "$status"
