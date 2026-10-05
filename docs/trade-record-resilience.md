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
