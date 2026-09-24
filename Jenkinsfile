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
        timeout(time: 45, unit: 'MINUTES')
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

                    // Docker Desktop and current Linux installs ship Compose v2 as the
                    // "docker compose" plugin, and some no longer include the standalone
                    // "docker-compose"; older agents have only the standalone one. Use whichever
                    // this agent has. Left unquoted in the steps below so it splits into words.
                    env.COMPOSE = sh(
                        script: 'if docker compose version >/dev/null 2>&1; then echo "docker compose"; else echo docker-compose; fi',
                        returnStdout: true).trim()
                    echo "Building ${commit} as ${env.IMAGE_TAG} (project ${env.COMPOSE_PROJECT})"
                }
            }
        }

        stage('Build Frontend') {
            steps {
                dir('frontend') {
                    // Its own npm cache rather than the agent user's ~/.npm: a single "sudo npm"
                    // ever run on the agent leaves root-owned files there, and every later
                    // install fails with EACCES. WORKSPACE_TMP (<workspace>@tmp) is outside the
                    // checkout, so the Checkout stage's git clean keeps it and later builds
                    // still reuse the downloads.
                    sh '''
                        node --version
                        npm ci --cache "${WORKSPACE_TMP:-$WORKSPACE@tmp}/npm-cache" --no-audit --no-fund
                        npm run build
                    '''
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
                    # Base images are read from the Dockerfiles so this list can't drift from them.
                    images="postgres:16-alpine maven:3.9-eclipse-temurin-21 $(grep -h '^FROM' */Dockerfile | awk '{print $2}' | sort -u)"
                    for image in $images; do
                        for attempt in 1 2 3; do
                            if docker pull -q "$image"; then
                                break
                            fi
                            if [ "$attempt" = "3" ]; then
                                echo "Could not pull $image after 3 attempts"
                                exit 1
                            fi
                            echo "Pulling $image failed (attempt $attempt/3), retrying..."
                            sleep 5
                        done
                    done
                '''
            }
        }

        stage('Test') {
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
                    docker rm -f "${PG_CONTAINER}" >/dev/null 2>&1 || true

                    docker run -d --name "${PG_CONTAINER}" \
                        -e POSTGRES_DB=paysprint \
                        -e POSTGRES_USER=paysprint \
                        -e POSTGRES_PASSWORD="${TEST_DB_PASSWORD}" \
                        -v "$WORKSPACE/iam-app/src/main/resources/db/leap_laugh_love_schema.sql":/docker-entrypoint-initdb.d/01-schema.sql:ro \
                        postgres:16-alpine

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
                    docker run --rm \
                        --network "container:${PG_CONTAINER}" \
                        --user "$(id -u):$(id -g)" \
                        -v "$WORKSPACE":/app \
                        -w /app \
                        -e TEST_DB_PASSWORD="${TEST_DB_PASSWORD}" \
                        -e MAVEN_CONFIG=/tmp/.m2 \
                        maven:3.9-eclipse-temurin-21 \
                        mvn -B -Duser.home=/tmp -Dmaven.repo.local=/tmp/.m2/repository test
                '''
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
                    sh 'docker rm -f "${PG_CONTAINER}" >/dev/null 2>&1 || true'
                }
            }
        }

        stage('Start Database') {
            steps {
                sh '''
                    set -eu

                    # No port reclaiming here any more. It used to free this executor's
                    # fixed ports before claiming them, which could not distinguish a crashed
                    # build's leftovers from another branch's containers running right now, and
                    # checked minutes before "Start Services" actually bound anything anyway.
                    # Ephemeral ports remove the contention the sweep existed to resolve; the
                    # post block's "docker-compose down -v" still cleans up this build's own
                    # containers.

                    $COMPOSE -p "$COMPOSE_PROJECT" up -d db
                    echo "Waiting for PostgreSQL and schema initialization..."
                    for i in $(seq 1 60); do
                        if $COMPOSE -p "$COMPOSE_PROJECT" exec -T db pg_isready -U paysprint -d paysprint; then
                            echo "PostgreSQL is ready"
                            exit 0
                        fi
                        sleep 2
                    done
                    echo "PostgreSQL did not become ready"
                    exit 1
                '''
            }
        }

        stage('Validate Database') {
            steps {
                sh '''
                    set -eu
                    echo "Checking initialized database schemas..."
                    for i in $(seq 1 30); do
                        ready=$($COMPOSE -p "$COMPOSE_PROJECT" exec -T db psql -U paysprint -d paysprint -Atc "SELECT to_regclass('iam.clients') IS NOT NULL AND to_regclass('trading.accounts') IS NOT NULL AND to_regclass('marketdata.quotes') IS NOT NULL;" 2>/dev/null) || ready=
                        if [ "$ready" = "t" ]; then
                            echo "IAM, trading, and market-data schemas are initialized"
                            exit 0
                        fi
                        sleep 2
                    done
                    echo "Required database tables were not initialized"
                    exit 1
                '''
            }
        }

        stage('Build Docker Images') {
            steps {
                sh '''
                    set -eu
                    $COMPOSE -p "$COMPOSE_PROJECT" build iam-app account-app order-app market-data-app
                    echo "Built service images tagged $IMAGE_TAG"
                '''
            }
        }

        stage('Start Services') {
            steps {
                sh '''
                    set -eu
                    $COMPOSE -p "$COMPOSE_PROJECT" up -d --no-build iam-app account-app order-app market-data-app
                    $COMPOSE -p "$COMPOSE_PROJECT" ps
                '''
            }
        }

        stage('Verify Services') {
            steps {
                sh '''
                    set -eu
                    echo "Waiting for service health endpoints..."
                    for i in $(seq 1 60); do
                        if $COMPOSE -p "$COMPOSE_PROJECT" exec -T iam-app wget -q -O /dev/null http://localhost:8081/actuator/health && \
                           $COMPOSE -p "$COMPOSE_PROJECT" exec -T account-app wget -q -O /dev/null http://localhost:8082/actuator/health && \
                           $COMPOSE -p "$COMPOSE_PROJECT" exec -T order-app wget -q -O /dev/null http://localhost:8084/actuator/health && \
                           $COMPOSE -p "$COMPOSE_PROJECT" exec -T market-data-app wget -q -O /dev/null http://localhost:8083/actuator/health; then
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

    post {
        failure {
            echo "Build ${env.BUILD_NUMBER} failed."
            sh '''
                if [ -n "${COMPOSE_PROJECT:-}" ]; then
                    echo "========== CONTAINER STATUS =========="
                    $COMPOSE -p "$COMPOSE_PROJECT" ps -a || true
                    for service in db iam-app account-app order-app market-data-app; do
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
                        "market-data-app:$IMAGE_TAG" >/dev/null 2>&1 || true
                fi
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
