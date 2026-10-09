pipeline {
    agent any

    tools {
        nodejs 'NodeJS'
    }

    options {
        // Two builds of the same branch/PR share one workspace and one executor's port
        // range, so overlapping them corrupts both. Queue them instead.
        disableConcurrentBuilds()
        // A hung build holds this executor's ports and containers until someone notices;
        // failing it releases them via the cleanup block below.
        // Includes the sequential, isolated trade recovery suite (up to 30 minutes).
        timeout(time: 90, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                // Drop untracked/ignored leftovers (stale target/, node_modules/) so a
                // reused workspace can't leak state from the previous build into this one.
                sh 'git clean -fdx'
            }
        }

        stage('Configure Pipeline') {
            steps {
                script {
                    def commit = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    // Tagging by commit alone would let two branches sitting on the same
                    // commit overwrite each other's images, and make the cleanup block
                    // delete an image a concurrent build is still using.
                    env.IMAGE_TAG = "${commit}-${env.BUILD_NUMBER}"
                    env.COMPOSE_PROJECT = "${env.JOB_NAME}-${env.BUILD_NUMBER}"
                        .toLowerCase().replaceAll('[^a-z0-9]+', '-').replaceAll('^-+|-+$', '')

                    // Publish every service on an ephemeral host port: "0:8081" tells Docker to
                    // pick a free one. Nothing outside the containers connects to these - the
                    // database checks and health checks below all go through
                    // "docker-compose exec" - so the host port number is never used by anything,
                    // it only has to not collide.
                    //
                    // Fixed numbers kept colliding however they were assigned. Per-service bases
                    // one apart plus the executor number overlapped outright (executor 0 took
                    // 18081/18082/18083, executor 1 took 18082/18083/18084). Widening that to a
                    // block per executor fixed the arithmetic but not the problem, because
                    // EXECUTOR_NUMBER is only unique within one node: two agents sharing a Docker
                    // daemon both start at 0 and claim the same block. Letting the daemon that
                    // owns the ports do the assigning is the only version of this that cannot
                    // collide, and it removes the cap on how many builds can run at once.
                    //
                    // These must be set rather than left unset: docker-compose.yml defaults them
                    // to the real 5432/8081-8084/4200, which would fight anything running locally
                    // on the agent.
                    env.DB_PORT = '0'
                    env.IAM_PORT = '0'
                    env.ORDER_PORT = '0'
                    env.ACCOUNT_PORT = '0'
                    env.MARKETDATA_PORT = '0'
                    env.FRONTEND_PORT = '0'

                    // The smoke test starts from an empty database every build, so market-data
                    // would generate its full default history - a year of candles at four widths
                    // for every instrument, ~3.8M rows once the S&P 500 is seeded - and then
                    // drop it all in the teardown. Keep the backfill ON, so a broken schema or
                    // query still fails the build, but ask for a token amount: ~31 rows per
                    // instrument instead of ~7,400.
                    //
                    // It matters more than it looks: the backfill runs as an ApplicationRunner,
                    // and Spring starts the web server before those, so /actuator/health answers
                    // while it is still writing. A slow backfill would not fail Verify Services,
                    // it would let the build go green and then tear the container down mid-write.
                    env.MARKETDATA_BACKFILL_TIERS = '86400:7,3600:1'

                    env.JWT_SECRET = "ci-smoke-test-secret-${env.BUILD_NUMBER}-do-not-use-in-prod"

                    // The E2E overlay adds the Playwright suite's test users to the database seed
                    // and gives market-data enough history to chart every range. It has to apply
                    // from the first "up": the seed only runs when Postgres initialises the volume.
                    // The CI overlay gives the frontend container an npm cache that outlives
                    // the build (see docker-compose.ci.yml).
                    env.COMPOSE_FILE = 'docker-compose.yml:docker-compose.e2e.yml:docker-compose.ci.yml'

                    // The E2E runner image (e2e/Dockerfile) is tagged by its lockfile, so it is built
                    // once and reused by every build until the suite's dependencies change.
                    env.E2E_IMAGE = "leap-e2e-runner:" + sh(
                        script: "sha256sum e2e/package-lock.json | cut -c1-12",
                        returnStdout: true).trim()

                    // Feature-branch pushes stop at the smoke check; main and pull requests get
                    // the full E2E suite. Decided here rather than only on the E2E stage because
                    // the parallel stage starts the frontend and builds the runner image for it.
                    // A plain Pipeline job (no BRANCH_NAME) always runs E2E rather than skipping
                    // it on every build.
                    env.RUN_E2E = (!env.BRANCH_NAME || env.BRANCH_NAME == 'main' || env.CHANGE_ID) ? 'true' : 'false'

                    // SonarQube follows the same rule. Community Build keeps one analysis per
                    // project with no branch or PR support, so a feature-branch scan would replace
                    // main's results; only main and pull requests are analysed.
                    env.RUN_SONAR = env.RUN_E2E

                    // Docker Desktop and current Linux installs ship Compose v2 as the
                    // "docker compose" plugin, and some no longer include the standalone
                    // "docker-compose"; older agents have only the standalone one. Use whichever
                    // this agent has. Left unquoted in the steps below so it splits into words.
                    env.COMPOSE = sh(
                        script: 'if docker compose version >/dev/null 2>&1; then echo "docker compose"; else echo docker-compose; fi',
                        returnStdout: true).trim()

                    // The service Dockerfiles cache Maven's repository with "RUN --mount", which
                    // only BuildKit understands. Compose v2 always builds with BuildKit. The
                    // standalone v1 binary uses the legacy builder unless told otherwise, and on
                    // Docker 23+ BuildKit through the CLI needs the buildx plugin - say so up
                    // front rather than failing later on a Dockerfile parse error.
                    if (env.COMPOSE == 'docker-compose') {
                        env.DOCKER_BUILDKIT = '1'
                        env.COMPOSE_DOCKER_CLI_BUILD = '1'
                        sh '''
                            if ! docker buildx version >/dev/null 2>&1; then
                                echo "This agent has standalone docker-compose (v1) and no docker buildx plugin."
                                echo "Install the Compose v2 plugin (docker-compose-plugin) or buildx (docker-buildx-plugin)."
                                exit 1
                            fi
                        '''
                    }

                    // Dependency caches that persist on the agent between builds, outside any
                    // workspace, so every branch shares them and cleanWs can't delete them. Only
                    // downloads live here, never build output, so a cold cache is just slower.
                    // The npm volume is external in docker-compose.ci.yml so "down -v" keeps it;
                    // creating one that already exists is a no-op.
                    sh '''
                        mkdir -p "$HOME/.m2-ci" "$HOME/.npm-ci" "$HOME/.pip-ci"
                        docker volume create leap-ci-npm-cache >/dev/null
                    '''
                    echo "Building ${commit} as ${env.IMAGE_TAG} (project ${env.COMPOSE_PROJECT})"
                }
            }
        }

        stage('Pull Images') {
            steps {
                sh '''
                    set -eu
                    # Pull every image the build runs up front, retrying: Docker Desktop's
                    # containerd store occasionally fails to unpack a layer mid-pull ("failed to
                    # extract layer ... no such file or directory") and a second attempt goes
                    # through. The later stages then use these local copies without pulling.
                    # Base images are read from the Dockerfiles so this list can't drift from them;
                    # the unit tests run on the same Maven image the service Dockerfiles build with.
                    #
                    # The pulls run side by side: on a warm agent each one is a registry round trip
                    # that finds nothing to download, and there is no reason to make them queue.
                    images="postgres:16-alpine node:24-alpine apache/kafka:3.9.0 $(grep -h '^FROM' */Dockerfile | awk '{print $2}' | sort -u)"
                    pull() {
                        for attempt in 1 2 3; do
                            if docker pull -q "$1" >/dev/null; then
                                return 0
                            fi
                            echo "Pulling $1 failed (attempt $attempt/3), retrying..."
                            sleep 5
                        done
                        echo "Could not pull $1 after 3 attempts"
                        return 1
                    }
                    pids=""
                    for image in $images; do
                        pull "$image" &
                        pids="$pids $!"
                    done
                    failed=0
                    for pid in $pids; do
                        wait "$pid" || failed=1
                    done
                    exit "$failed"
                '''
            }
        }

        stage('Start Database') {
            steps {
                // Detached and without waiting: Postgres loads the schema and the S&P 500 seeds
                // while everything below builds, instead of the build standing idle for it.
                // "Validate Database" is where the stack waits for it to finish. Started here,
                // before the parallel branches, so it is the one "up" that creates the compose
                // network - the frontend branch's "up" then joins it rather than racing to
                // create the same network.
                sh '$COMPOSE -p "$COMPOSE_PROJECT" up -d db'
            }
        }

        stage('Build and Test') {
            // These branches share this build's executor and workspace - parallel inside a
            // node needs no second executor. They touch disjoint parts of the workspace and
            // run in disjoint containers, and most of each is waiting on I/O (npm, Docker,
            // Postgres, Spring startup) rather than competing for CPU.
            failFast true
            parallel {
                stage('Frontend') {
                    steps {
                        dir('frontend') {
                            // Its own npm cache rather than the agent user's ~/.npm: a single "sudo
                            // npm" ever run on the agent leaves root-owned files there, and every
                            // later install fails with EACCES. It lives in the agent user's home
                            // rather than beside the workspace so every branch shares it and
                            // cleanWs can't delete it.
                            sh '''
                                node --version
                                npm ci --cache "$HOME/.npm-ci" --no-audit --no-fund
                            '''
                        }
                        script {
                            // The E2E dev server has its own install and its first compile ahead
                            // of it, which was a stretch of the E2E stage spent waiting. Start it
                            // now so that happens alongside the backend build.
                            //
                            // Only after the npm ci above, never before or during: the container
                            // mounts a volume over frontend/node_modules, and if Docker got there
                            // first it would create that directory root-owned and the host install
                            // would fail on it. The container's install goes into that volume, and
                            // its build cache into another, so from here on the host build below
                            // and the container never write to the same files.
                            if (env.RUN_E2E == 'true') {
                                sh '$COMPOSE -p "$COMPOSE_PROJECT" up -d frontend'
                            }
                        }
                        dir('frontend') {
                            sh 'npm run build'
                        }
                        script {
                            // The E2E runner image (e2e/Dockerfile): Node, the suite's dependencies
                            // and Chromium's headless shell only. Tagged by its lockfile, so it is
                            // built on the first build after a dependency change and found already
                            // present otherwise. Built in this branch because the frontend specs
                            // below run in it too.
                            if (env.RUN_E2E == 'true' || env.RUN_SONAR == 'true') {
                                sh '''
                                    set -eu
                                    if ! docker image inspect "$E2E_IMAGE" >/dev/null 2>&1; then
                                        docker build -t "$E2E_IMAGE" e2e
                                    fi
                                '''
                            }
                            if (env.RUN_SONAR == 'true') {
                                // Vitest runs the specs in headless Chromium through Playwright,
                                // which the agent doesn't have; the runner image carries
                                // Playwright's headless shell in /ms-playwright. frontend/ pins the
                                // same Playwright version as e2e/, so it finds that build. Run here,
                                // alongside the Maven suite, rather than as a stage after it.
                                // --user keeps coverage/ and .angular/ owned by the agent account,
                                // as with Maven. Mounted at /repo/frontend, mirroring the checkout:
                                // angular.json writes lcov paths relative to the frontend's parent,
                                // and SonarQube resolves them from the repo root, so they must come
                                // out as frontend/src/...
                                sh '''
                                    set -eu
                                    docker run --rm \
                                        --user "$(id -u):$(id -g)" \
                                        -e HOME=/tmp \
                                        -e CI=1 \
                                        -v "$WORKSPACE/frontend":/repo/frontend \
                                        -w /repo/frontend \
                                        "$E2E_IMAGE" \
                                        npx ng test --watch=false --coverage
                                '''
                            }
                        }
                    }
                    post {
                        always {
                            archiveArtifacts allowEmptyArchive: true, artifacts: 'frontend/coverage/**'
                        }
                    }
                }

                stage('Unit Tests') {
                    environment {
                        // Throwaway CI database credentials; the container is destroyed after the stage.
                        TEST_DB_PASSWORD = "ci-test-password"
                        // Named after the compose project, which carries the branch: BUILD_NUMBER plus
                        // EXECUTOR_NUMBER is not unique across jobs, so two branches that happened to
                        // land on the same build number and executor shared this name - and the
                        // "docker rm -f" below would then destroy the other build's test database
                        // mid-run. Same defect the fixed host ports had.
                        PG_CONTAINER = "pg-test-${COMPOSE_PROJECT}"
                    }
                    steps {
                        sh '''
                            set -eu
                            docker rm -f -v "${PG_CONTAINER}" >/dev/null 2>&1 || true

                            # The data directory is a tmpfs: the postgres image declares it a VOLUME,
                            # so without this every build left an anonymous volume behind (a few
                            # hundred MB each, one per build) until the agent's disk filled. A
                            # throwaway test database also has no use for durability, so it runs
                            # entirely in memory with fsync off - faster schema load and tests.
                            docker run -d --name "${PG_CONTAINER}" \
                                --tmpfs /var/lib/postgresql/data \
                                -e POSTGRES_DB=paysprint \
                                -e POSTGRES_USER=paysprint \
                                -e POSTGRES_PASSWORD="${TEST_DB_PASSWORD}" \
                                -v "$WORKSPACE/iam-app/src/main/resources/db/leap_laugh_love_schema.sql":/docker-entrypoint-initdb.d/01-schema.sql:ro \
                                postgres:16-alpine \
                                -c fsync=off -c synchronous_commit=off -c full_page_writes=off

                            # Poll for a schema table rather than pg_isready: the server answers on its
                            # unix socket while the init scripts are still running, so pg_isready can
                            # report ready before the schema exists.
                            echo "waiting for postgres schema to load..."
                            for i in $(seq 1 45); do
                                if docker exec "${PG_CONTAINER}" psql -U paysprint -d paysprint \
                                        -c "SELECT 1 FROM trading.orders LIMIT 1;" >/dev/null 2>&1; then
                                    echo "postgres ready"
                                    break
                                fi
                                if [ "$i" = "45" ]; then
                                    echo "postgres never became ready"
                                    docker logs "${PG_CONTAINER}"
                                    exit 1
                                fi
                                sleep 2
                            done

                            # --user keeps target/ and surefire-reports/ owned by the agent account.
                            # Without it Maven writes them as root and the next build's "git clean"
                            # can't delete them, wedging this workspace permanently.
                            # Maven needs a writable HOME to run as a non-root uid, hence user.home.
                            #
                            # Sharing the postgres container's network namespace makes the database
                            # reachable at localhost:5432, which is the URL the JDBC tests hardcode.
                            #
                            # The Maven repository is mounted from the agent so dependencies are
                            # downloaded once, not on every build. The directory is created in Configure
                            # Pipeline as the agent user; if Docker had to create it, it would be root's.
                            #
                            # The same Maven image the service Dockerfiles build with, so a fresh agent
                            # pulls one Maven image rather than two. -T 1C builds the modules side by
                            # side once common-security is done. The tests that reach the database
                            # (iam-app and order-app booting their real applications, and order-app's
                            # retention test) each work in their own app's tables, so running the
                            # modules at once doesn't have them tripping over each other.
                            #
                            # TieredStopAtLevel=1 keeps the JIT to its quick first tier. The suite is
                            # dominated by Spring contexts starting in fresh JVMs, which finish before
                            # the optimising tier would pay for itself; measured ~30% off the run.
                            # Set through the environment so Maven's forked test JVMs inherit it too.
                            docker run --rm \
                                --network "container:${PG_CONTAINER}" \
                                --user "$(id -u):$(id -g)" \
                                -v "$WORKSPACE":/app \
                                -v "$HOME/.m2-ci":/tmp/.m2 \
                                -w /app \
                                -e TEST_DB_PASSWORD="${TEST_DB_PASSWORD}" \
                                -e MAVEN_CONFIG=/tmp/.m2 \
                                -e JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1 \
                                maven:3.9.9-eclipse-temurin-21-alpine \
                                mvn -B -T 1C -Duser.home=/tmp -Dmaven.repo.local=/tmp/.m2/repository test
                        '''
                        // Order Reporting ETL (Python). Same throwaway database, which the schema
                        // file gave a reporting schema, so the loader's SQL runs for real; the
                        // rest of the suite needs no database. Run from the repo root so
                        // coverage.xml paths come out as reporting-etl/..., the way SonarQube
                        // resolves them. The pip cache is the agent's, like the Maven repository.
                        sh '''
                            set -eu
                            docker run --rm \
                                --network "container:${PG_CONTAINER}" \
                                --user "$(id -u):$(id -g)" \
                                -v "$WORKSPACE":/app \
                                -v "$HOME/.pip-ci":/tmp/.pip-cache \
                                -w /app \
                                -e HOME=/tmp \
                                -e PIP_CACHE_DIR=/tmp/.pip-cache \
                                -e PIP_DISABLE_PIP_VERSION_CHECK=1 \
                                -e REPORTING_TEST_DB_URL="postgresql://paysprint:${TEST_DB_PASSWORD}@localhost:5432/paysprint" \
                                python:3.12-slim \
                                sh -c 'python -m venv /tmp/venv \
                                    && /tmp/venv/bin/pip install -q -r reporting-etl/requirements-dev.txt \
                                    && /tmp/venv/bin/python -m pytest -c reporting-etl/pyproject.toml --rootdir reporting-etl \
                                        -p no:cacheprovider \
                                        --junitxml=reporting-etl/test-results/junit.xml \
                                        --cov=reporting-etl/reporting_etl --cov-config=reporting-etl/pyproject.toml \
                                        --cov-report=xml:reporting-etl/coverage.xml'
                        '''
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml, reporting-etl/test-results/*.xml'
                            // Preserve Java coverage alongside the test results before workspace cleanup.
                            archiveArtifacts allowEmptyArchive: true, artifacts: '**/target/site/jacoco/**'
                            sh 'docker rm -f -v "${PG_CONTAINER}" >/dev/null 2>&1 || true'
                        }
                    }
                }

                stage('Service Stack') {
                    stages {
                        stage('Build Docker Images') {
                            steps {
                                sh '''
                                    set -eu
                                    $COMPOSE -p "$COMPOSE_PROJECT" build iam-app account-app order-app market-data-app reporting-etl
                                    echo "Built service images tagged $IMAGE_TAG"
                                '''
                            }
                        }

                        stage('Validate Database') {
                            steps {
                                sh '''
                                    set -eu
                                    # Over TCP to 127.0.0.1 rather than the unix socket: while the init
                                    # scripts run, Postgres listens on its socket only, so a socket check
                                    # passes with the seeds half loaded. TCP answers once they are done
                                    # and the real server is up. Normally already true by now - the
                                    # image build above takes longer than the seeds.
                                    echo "Waiting for PostgreSQL to finish initializing..."
                                    for i in $(seq 1 90); do
                                        ready=$($COMPOSE -p "$COMPOSE_PROJECT" exec -T -e PGPASSWORD="${DB_PASSWORD:-changeme}" db \
                                            psql -h 127.0.0.1 -U paysprint -d paysprint -Atc "SELECT to_regclass('iam.clients') IS NOT NULL AND to_regclass('trading.accounts') IS NOT NULL AND to_regclass('marketdata.quotes') IS NOT NULL AND to_regclass('reporting.orders') IS NOT NULL;" 2>/dev/null) || ready=
                                        if [ "$ready" = "t" ]; then
                                            echo "IAM, trading, market-data, and reporting schemas are initialized"
                                            exit 0
                                        fi
                                        sleep 2
                                    done
                                    echo "PostgreSQL did not finish initializing the required schemas"
                                    exit 1
                                '''
                            }
                        }

                        stage('Start Services') {
                            steps {
                                sh '''
                                    set -eu
                                    $COMPOSE -p "$COMPOSE_PROJECT" up -d --no-build iam-app account-app order-app market-data-app kafka kafka-init reporting-etl
                                    $COMPOSE -p "$COMPOSE_PROJECT" ps
                                '''
                            }
                        }

                        stage('Verify Services') {
                            steps {
                                sh '''
                                    set -eu
                                    # reporting-etl has no health endpoint: it counts as up once it is a
                                    # member of its consumer group (listed by its client.id), which means
                                    # it started, reached Kafka and subscribed.
                                    echo "Waiting for service health endpoints..."
                                    for i in $(seq 1 60); do
                                        if $COMPOSE -p "$COMPOSE_PROJECT" exec -T iam-app wget -q -O /dev/null http://localhost:8081/actuator/health && \
                                           $COMPOSE -p "$COMPOSE_PROJECT" exec -T account-app wget -q -O /dev/null http://localhost:8082/actuator/health && \
                                           $COMPOSE -p "$COMPOSE_PROJECT" exec -T order-app wget -q -O /dev/null http://localhost:8084/actuator/health && \
                                           $COMPOSE -p "$COMPOSE_PROJECT" exec -T market-data-app wget -q -O /dev/null http://localhost:8083/actuator/health && \
                                           $COMPOSE -p "$COMPOSE_PROJECT" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic order-events >/dev/null && \
                                           $COMPOSE -p "$COMPOSE_PROJECT" exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group reporting-etl --members 2>/dev/null | grep -q reporting-etl-consumer; then
                                            echo "All services are healthy"
                                            exit 0
                                        fi
                                        echo "Services not ready yet (attempt $i/60)"
                                        sleep 3
                                    done
                                    echo "Services did not become healthy in time"
                                    exit 1
                                '''
                            }
                        }
                    }
                }
            }
        }

        // Static analysis and the stack tests need nothing from each other, so they run side by
        // side rather than the stack tests waiting out the scan and the quality-gate webhook.
        // failFast: a failed quality gate aborts the stack tests, and a failed stack test aborts
        // the analysis - either way the build is red and stops early.
        stage('Verify') {
            failFast true
            parallel {
                stage('Static Analysis') {
                    stages {
                        stage('SonarQube Analysis') {
                            when {
                                environment name: 'RUN_SONAR', value: 'true'
                            }
                            steps {
                                // "SonarScanner" is the scanner installation under Manage Jenkins > Tools and
                                // "SonarQube" the server under Manage Jenkins > System; withSonarQubeEnv supplies
                                // its URL and the sonarqube-token credential. Project settings, including the
                                // coverage report paths from the stages above, are in sonar-project.properties.
                                script {
                                    def scannerHome = tool 'SonarScanner'
                                    withSonarQubeEnv('SonarQube') {
                                        sh """
                                            ${scannerHome}/bin/sonar-scanner --version
                                            ${scannerHome}/bin/sonar-scanner
                                        """
                                    }
                                }
                            }
                    }

                    stage('Quality Gate') {
                        when {
                            environment name: 'RUN_SONAR', value: 'true'
                        }
                        steps {
                            // Waits for SonarQube's webhook to report the gate result and fails the build
                            // on a failed gate. failFast on the parallel stage then stops E2E and the
                            // recovery suite as well, rather than letting them run on to the end.
                            timeout(time: 5, unit: 'MINUTES') {
                                waitForQualityGate abortPipeline: true
                            }
                        }
                    }
                    }
                }

                stage('Stack Tests') {
                    stages {
                        stage('E2E') {
                            // RUN_E2E is set in Configure Pipeline.
                            when {
                                environment name: 'RUN_E2E', value: 'true'
                            }
                            options {
                                timeout(time: 20, unit: 'MINUTES')
                            }
                            steps {
                                sh '''
                                    set -eu
                                    # The Angular dev server: its proxy is what routes /api/** to the services, the
                                    # same way a developer's browser reaches them. Started by the Frontend branch
                                    # of "Build and Test", so its install and first compile are usually done by now.
                                    echo "Waiting for the frontend (installs dependencies on first start)..."
                                    # 127.0.0.1, not localhost: ng serve --host 0.0.0.0 listens on IPv4 only, and
                                    # where the container has IPv6 (Docker Desktop) busybox wget resolves localhost
                                    # to ::1 first and gets "connection refused" from a server that is up.
                                    for i in $(seq 1 120); do
                                        if $COMPOSE -p "$COMPOSE_PROJECT" exec -T frontend wget -q -O /dev/null http://127.0.0.1:4200/; then
                                            echo "Frontend is serving"
                                            break
                                        fi
                                        if [ "$i" = "120" ]; then
                                            echo "Frontend did not start in time"
                                            exit 1
                                        fi
                                        sleep 5
                                    done

                                    # The runner image was built (or found) by the Frontend branch of "Build and Test".
                                    # Attached to the compose network so it reaches the frontend by service name.
                                    # --user keeps the reports owned by the agent account, for the same reason as
                                    # the Maven step. The image already holds node_modules, so nothing installs.
                                    docker run --rm --ipc=host \
                                        --network "${COMPOSE_PROJECT}_default" \
                                        --user "$(id -u):$(id -g)" \
                                        -e HOME=/tmp \
                                        -e CI=1 \
                                        -e BASE_URL=http://frontend:4200 \
                                        -v "$WORKSPACE/e2e":/e2e \
                                        "$E2E_IMAGE" \
                                        sh -c "tsc --noEmit && eslint . && playwright test"
                                '''
                            }
                            post {
                                always {
                                    junit allowEmptyResults: true, testResults: 'e2e/results/junit.xml'
                                    archiveArtifacts allowEmptyArchive: true, artifacts: 'e2e/playwright-report/**, e2e/test-results/**'
                                }
                            }
                    }
                    stage('Trade Recovery Tests') {
                        when {
                            environment name: 'RUN_E2E', value: 'true'
                        }
                        options {
                            timeout(time: 30, unit: 'MINUTES')
                        }
                        environment {
                            CI = 'true'
                        }
                        steps {
                            sh '''
                                set -eu
                                # Recovery needs the local Docker daemon, Compose v2 and Python 3.
                                docker compose version
                                python3 --version
                                # Release this build's ordinary stack before starting another four JVMs.
                                # Keep its containers for diagnostics; final pipeline cleanup removes them.
                                $COMPOSE -p "$COMPOSE_PROJECT" stop
                                npm ci --prefix e2e --cache "$HOME/.npm-ci" --no-audit --no-fund
                                npm --prefix e2e run test:recovery:proxy
                                # Reuse the service images "Build Docker Images" made for this commit rather
                                # than building all four again; the build's cleanup block removes them.
                                RECOVERY_IMAGE_TAG="$IMAGE_TAG" bash scripts/test-trade-recovery.sh npm --prefix e2e run test:recovery
                                # A successful stage must produce a test report, not just a healthy stack.
                                python3 -c 'import glob; assert glob.glob(".recovery-results/*/junit.xml"), "Recovery test report missing"'
                            '''
                        }
                        post {
                            always {
                                script {
                                    try {
                                        // The marker survives termination of the shell/test process.
                                        timeout(time: 5, unit: 'MINUTES') {
                                            sh 'bash scripts/test-trade-recovery.sh --cleanup'
                                        }
                                    } finally {
                                        // Keep diagnostics even when tests or fallback cleanup fail.
                                        archiveArtifacts allowEmptyArchive: true, artifacts: '.recovery-results/**'
                                        junit allowEmptyResults: true, testResults: '.recovery-results/*/junit.xml'
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    post {
        failure {
            echo "Build ${env.BUILD_NUMBER} failed."
            sh '''
                if [ -n "${COMPOSE_PROJECT:-}" ]; then
                    echo "========== CONTAINER STATUS =========="
                    $COMPOSE -p "$COMPOSE_PROJECT" ps -a || true
                    for service in db kafka kafka-init iam-app account-app order-app market-data-app reporting-etl frontend; do
                        echo "========== $service LOGS (LAST 100 LINES) =========="
                        $COMPOSE -p "$COMPOSE_PROJECT" logs --tail=100 "$service" || true
                    done
                fi
            '''
        }
        success {
            echo "Build ${env.BUILD_NUMBER} passed (${env.IMAGE_TAG})."
        }
        cleanup {
            sh '''
                if [ -n "${COMPOSE_PROJECT:-}" ]; then
                    $COMPOSE -p "$COMPOSE_PROJECT" down -v --remove-orphans || true
                fi
                # Every build produces three uniquely tagged images; without this the agent
                # accumulates one set per build until the disk fills.
                if [ -n "${IMAGE_TAG:-}" ]; then
                    docker image rm -f "iam-app:$IMAGE_TAG" "account-app:$IMAGE_TAG" "order-app:$IMAGE_TAG" \
                        "market-data-app:$IMAGE_TAG" "reporting-etl:$IMAGE_TAG" >/dev/null 2>&1 || true
                fi
                # Keep only the current E2E runner; older tags are from superseded lockfiles. (An
                # image another build is using right now refuses removal, hence "|| true".) Also
                # reclaims the ~2GB official Playwright image earlier versions of this file pulled.
                if [ -n "${E2E_IMAGE:-}" ]; then
                    docker images --format '{{.Repository}}:{{.Tag}}' \
                        | grep -E '^leap-e2e-runner:|^mcr.microsoft.com/playwright:' \
                        | grep -vx "$E2E_IMAGE" \
                        | xargs -r docker image rm >/dev/null 2>&1 || true
                fi
                # Database volumes are removed only by this build's scoped Compose teardown.
                # Do not globally prune volumes: persistent deployments may be stopped.
                # "image prune" without -a removes only untagged layers left behind by re-pulled base
                # images, and the build cache is trimmed oldest-first down to a cap that still
                # holds the shared Maven repository.
                docker image prune -f >/dev/null 2>&1 || true
                docker builder prune -f --reserved-space 3GB >/dev/null 2>&1 || true
            '''
            // Must run after the teardown above, never before: "docker-compose down" reads
            // docker-compose.yml out of the workspace, so emptying it first would strand
            // this build's containers and volumes on the agent.
            //
            // The "git clean -fdx" in Checkout only clears a workspace at the START of the
            // next build of the same branch, which never comes for a merged or abandoned
            // one. Without this every branch that ever built parks its last
            // frontend/node_modules (~366MB) on the agent indefinitely.
            //
            // notFailBuild: a workspace that won't delete is a disk problem to chase down,
            // not a reason to turn a green build red.
            cleanWs(notFailBuild: true)
        }
    }
}
