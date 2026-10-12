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
#   scripts/test-order-events.sh --fresh    # down -v first: a clean database, light history
#   scripts/test-order-events.sh --down     # tear the stack down afterwards
#
# Needs docker and docker-compose. JWT_SECRET comes from .env, as for compose.

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

command -v docker >/dev/null || { echo "'docker' was not found on PATH." >&2; exit 2; }
docker-compose version >/dev/null 2>&1 || { echo "docker-compose is not installed." >&2; exit 2; }

dump_logs() {
    echo
    echo "---- last 50 lines of order-app and reporting-etl ----"
    docker-compose logs --no-color --tail 50 order-app reporting-etl || true
}

container_of() { docker-compose ps -a -q "$1"; }

if $FRESH; then
    echo "==> Removing the stack and its volumes"
    docker-compose down -v
    # A fresh database makes market-data generate its price history before it quotes anything;
    # the default is a year of it, which takes many minutes. The check only needs live quotes,
    # so ask for CI's token amount unless a value is already set.
    export MARKETDATA_BACKFILL_TIERS="${MARKETDATA_BACKFILL_TIERS:-86400:7,3600:1}"
    echo "    price history backfill: $MARKETDATA_BACKFILL_TIERS"
fi

echo "==> Starting ${SERVICES[*]}"
docker-compose up -d --build "${SERVICES[@]}"

# Probed from inside each container, as the Jenkinsfile does, so host port mappings, .env
# overrides and proxies can't get in the way. A container that exits or keeps restarting fails
# the wait straight away instead of running out the clock.
echo "==> Waiting for the services to report healthy (up to ${HEALTH_TIMEOUT_SECONDS}s)"
declare -A HEALTH_PORTS=([iam-app]=8081 [account-app]=8082 [market-data-app]=8083 [order-app]=8084)
pending=(iam-app account-app market-data-app order-app)
started=$SECONDS
last_report=$SECONDS
while ((${#pending[@]} > 0)); do
    still=()
    for name in "${pending[@]}"; do
        container=$(container_of "$name")
        state=$(docker inspect -f '{{.State.Status}} {{.State.Restarting}}' "$container" 2>/dev/null || echo "missing false")
        if [[ "$state" != "running false" ]]; then
            echo "$name is not running (state: $state)." >&2
            docker-compose logs --no-color --tail 50 "$name" || true
            exit 1
        fi
        if docker-compose exec -T "$name" wget -q -O /dev/null "http://localhost:${HEALTH_PORTS[$name]}/actuator/health" >/dev/null 2>&1; then
            echo "    $name is up"
        else
            still+=("$name")
        fi
    done
    pending=("${still[@]+"${still[@]}"}")
    ((${#pending[@]} == 0)) && break
    if ((SECONDS - started > HEALTH_TIMEOUT_SECONDS)); then
        echo "Not healthy after ${HEALTH_TIMEOUT_SECONDS}s: ${pending[*]}" >&2
        for name in "${pending[@]}"; do docker-compose logs --no-color --tail 50 "$name" || true; done
        exit 1
    fi
    if ((SECONDS - last_report >= 30)); then
        last_report=$SECONDS
        echo "    still waiting on ${pending[*]} ($((SECONDS - started))s)"
    fi
    sleep 3
done

kafka_init_exit=$(docker inspect -f '{{.State.ExitCode}}' "$(container_of kafka-init)")
if [[ "$kafka_init_exit" != "0" ]]; then
    echo "kafka-init exited $kafka_init_exit, so order-events may not exist." >&2
    docker-compose logs --no-color kafka-init || true
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
docker-compose run --rm --no-deps -T \
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
    docker-compose down
fi
exit "$status"
