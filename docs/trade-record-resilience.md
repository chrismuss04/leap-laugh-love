# Trade record resilience

## Step 1: preserve existing PostgreSQL storage

The bundled PostgreSQL stores orders, executions, cash ledger entries and holdings
in `db_data`, mounted at `/var/lib/postgresql/data`. Application replacement does
not require replacing this volume. This configuration protects storage ownership;
crash recovery and interrupted-trade tests are subsequent work.

Use Docker Compose v2 for deployments. With protection unset, the normal volume
name remains `<compose-project>_db_data`. Keep the same Compose project name across
releases. Do not use the CI overlay for a persistent deployment: it uses disposable
storage and disables PostgreSQL durability settings.

### Adopt the existing volume (Linux Docker host)

Run these commands from the existing deployment checkout, with its usual `.env`
and Compose project selection, BEFORE stopping or changing the deployment:

```bash
docker compose version
docker compose ps -a db
db_container=$(docker compose ps -a -q db)
test -n "$db_container" || { echo "No database container found; check the Compose project."; exit 1; }
docker inspect "$db_container" --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{printf "type=%s name=%s source=%s\n" .Type .Name .Source}}{{end}}{{end}}'
```

For a `type=volume` mount, record the exact `name` and verify it with
`docker volume inspect <existing-volume-name>`. If there is no matching container,
or the mount is a bind mount, stop here and identify the real storage; do not
create an empty replacement. Take and verify your normal database backup before
changing deployment configuration.

Add these settings to the deployment's untracked `.env`, substituting the name
you inspected (do not copy the placeholder literally):

```dotenv
DB_VOLUME_NAME=<existing-volume-name>
DB_VOLUME_EXTERNAL=true
```

Check the resolved volume configuration without printing service credentials:

```bash
docker compose config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["volumes"]["db_data"])'
```

It must show that exact name and `external: true`. Compose now reuses the existing
volume and refuses startup if it is missing, rather than creating empty storage.
External means Compose does not own or delete the volume; no data is copied or
moved. This setting must be enabled on the deployment host to activate protection.

### Restart, redeploy and roll back

For an application restart:

```bash
docker compose restart iam-app account-app order-app market-data-app
```

To build and replace backend applications from a new checkout:

```bash
docker compose build iam-app account-app order-app market-data-app
docker compose up -d --no-deps --no-build iam-app account-app order-app market-data-app
```

Keep the database running and retain its project name, `.env`, volume name and
credentials. Use immutable `IMAGE_TAG` values for releases and retain the previous
images. To roll back the backend images, set `IMAGE_TAG` to the previous release
and run the same `up --no-deps --no-build` command. Rollback requires compatible
database migrations; reverting images does not undo schema changes. The current
frontend is a bind-mounted development server, so its rollback also requires the
matching source checkout and restart.

Use `docker compose stop` to stop a deployment without removing its containers.
Do not use `down -v`, database reseeding, or volume pruning as deployment/rollback
steps. External PostgreSQL storage survives Compose teardown, but other managed
volumes (including Kafka) may not. Direct volume deletion or host-disk loss still
requires backup/restore protection beyond this step.

### CI isolation

Jenkins sets `COMPOSE_PROJECT` per build. The existing CI overlay explicitly uses
`${COMPOSE_PROJECT}_db_data` with `external: false`, ignoring deployment database
volume settings. Its project-scoped `down -v` therefore removes only disposable
CI storage. Jenkins no longer globally prunes Docker volumes. Never set a
deployment's `DB_VOLUME_NAME` to a CI build's volume name.

These settings do not constitute recovery-test evidence. The later recovery suite
must enable PostgreSQL durability, interrupt the services, and compare records
before and after the failure.

## Step 2: isolated durable recovery environment

On the Linux Docker host, with Docker Compose v2, Python 3 and Bash available:

```bash
bash scripts/test-trade-recovery.sh
```

This builds and starts PostgreSQL plus the four backend services, verifies all
four health endpoints, then tears down only that run. It uses the existing base
Compose file and E2E seed overlay, not the CI overlay. PostgreSQL explicitly uses
`fsync=on`, `synchronous_commit=on` and `full_page_writes=on`; the script checks
their effective values and the trade tables. Storage is a disk-backed named
volume, not tmpfs. Each run generates unique project, volume and image names and
uses dynamically assigned host ports. Deployment `.env` settings are ignored.

This step is an environment smoke check, not proof of trade recovery. Frontend
and Kafka are not started by this backend-only harness. The later test steps will
extend it where their scenarios need additional services or failure controls.
Run it on the Docker host (not against a remote Docker context) because host-side
test URLs use loopback. Image builds require network access for dependencies.

The script can wrap a future test command, for example:

```bash
bash scripts/test-trade-recovery.sh bash -c 'your-recovery-test-command'
```

The command inherits `RECOVERY_PROJECT`, `RECOVERY_REPO_ROOT`,
`RECOVERY_RESULTS_DIR`, and `RECOVERY_IAM_URL`, `RECOVERY_ACCOUNT_URL`,
`RECOVERY_ORDER_URL`, `RECOVERY_MARKETDATA_URL`. Future failure controls must use
that project and the same two explicitly selected Compose files. Any database
restart/recreation during the command must retain its volume. Cleanup runs only
after the command finishes (including failure), saving diagnostics under
`.recovery-results/<run-id>/` before removing that run's containers and volumes.
SIGKILL or host failure can bypass cleanup; inspect the printed project name to
remove leftovers manually. Do not use global volume pruning.

Ordinary Jenkins CI still uses its existing durability-disabled command. No
recovery stage has been added to Jenkins yet.

## Step 4: committed-trade persistence checks

Install the E2E dependencies on the Linux Docker host with `npm ci --prefix e2e`
(use a Node version supported by the project's Playwright dependency), then run:

```bash
bash scripts/test-trade-recovery.sh npm --prefix e2e run test:recovery
```

This runs five API/database scenarios with one worker and no automatic retries:
application restart, application container replacement, database restart,
database SIGKILL/recovery, and failed application startup followed by restoring
the previous image. Each scenario creates a real committed BUY through the API
on its own funded account, captures all account trade/ledger/holdings records,
and compares them after the disruption. It also logs in again and checks API
history, cash balance, and holdings. No browser installation is required.

The failed-deployment scenario stops order-app, attempts a replacement using the
same image with an intentionally failing entrypoint, verifies its exit code, and
recreates order-app with the original configuration and exact image. This proves
storage survives application startup failure; it does not test rollback across
different schema versions or different releases. SIGKILL tests process-crash
recovery, not physical disk loss or a host power failure.

`playwright.recovery.config.ts` is separate from normal E2E discovery, which
explicitly ignores recovery specs. The helper checks the generated project name,
resolved database volume and running container's volume mount before controlling
containers. Tests should run only through the harness on the Docker host.
JUnit results and before/after attachments are written into the harness results
directory. Container logs are saved on exit, including when a test fails.

Passing these scenarios on Linux is required for runtime evidence; TypeScript
checks and test discovery alone do not establish persistence. Interrupted trades
and duplicate-settlement scenarios remain the next step.
