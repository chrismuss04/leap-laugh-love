pipeline {
    agent any
    tools {
        nodejs 'NodeJS'
    }

    environment {
        IAM_IMAGE = "iam-app"
        TRADING_IMAGE = "trading-app"
        MARKETDATA_IMAGE = "market-data-app"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Test Frontend') {
            steps {
                sh '''
                    cd frontend
                    npm install
                    npm run build
                '''
            }
            post {
                success {
                    echo "Frontend build successful"
                }
                failure {
                    echo "Frontend build failed"
                }
            }
        }

        stage('Test') {
            environment {
                // Throwaway CI database credentials; the container is destroyed after the stage.
                TEST_DB_PASSWORD = "ci-test-password"
                PG_CONTAINER = "pg-test-${BUILD_NUMBER}-${EXECUTOR_NUMBER}"
            }
            steps {
                sh '''
                    set -e
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

                    # Sharing the postgres container's network namespace makes the database
                    # reachable at localhost:5432, which is the URL the JDBC tests hardcode.
                    docker run --rm \
                        --network "container:${PG_CONTAINER}" \
                        -v "$WORKSPACE":/app \
                        -v /var/run/docker.sock:/var/run/docker.sock \
                        -w /app \
                        -e TEST_DB_PASSWORD="${TEST_DB_PASSWORD}" \
                        maven:3.9-eclipse-temurin-21 mvn -B test
                '''
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
                    sh 'docker rm -f "${PG_CONTAINER}" >/dev/null 2>&1 || true'
                }
            }
        }
        
        stage('Build images') {
            steps {
                sh "docker build -f iam-app/Dockerfile -t ${IAM_IMAGE}:${BUILD_NUMBER} ."
                sh "docker build -f trading-app/Dockerfile -t ${TRADING_IMAGE}:${BUILD_NUMBER} ."
                sh "docker build -f market-data-app/Dockerfile -t ${MARKETDATA_IMAGE}:${BUILD_NUMBER} ."
            }
        }

        stage('Verify container starts') {
            environment {
                DB_PORT = "${5432 + (env.EXECUTOR_NUMBER as Integer)}"
                IAM_PORT = "${8081 + (env.EXECUTOR_NUMBER as Integer)}"
                TRADING_PORT = "${8082 + (env.EXECUTOR_NUMBER as Integer)}"
                MARKETDATA_PORT = "${8083 + (env.EXECUTOR_NUMBER as Integer)}"
                JWT_SECRET = "ci-smoke-test-secret-${BUILD_NUMBER}-do-not-use-in-prod"
                COMPOSE_PROJECT = "${(env.JOB_NAME + '-' + env.BUILD_NUMBER).toLowerCase().replaceAll('[^a-z0-9]+', '-')}"
            }
            steps {
                sh "docker-compose -p ${COMPOSE_PROJECT} down -v || true"
                sh "docker-compose -p ${COMPOSE_PROJECT} up -d --build"
                sh '''
                    set -e
                    for i in $(seq 1 60); do
                        if docker-compose -p ${COMPOSE_PROJECT} exec -T iam-app wget -q -O /dev/null http://localhost:8081/actuator/health && \
                           docker-compose -p ${COMPOSE_PROJECT} exec -T trading-app wget -q -O /dev/null http://localhost:8082/actuator/health && \
                           docker-compose -p ${COMPOSE_PROJECT} exec -T market-data-app wget -q -O /dev/null http://localhost:8083/actuator/health; then
                            echo "all services are healthy"
                            exit 0
                        fi
                        echo "services not ready yet (attempt $i/60)"
                        sleep 3
                    done
                    echo "services never became healthy in time"
                    docker-compose -p ${COMPOSE_PROJECT} logs
                    exit 1
                '''
            }
            post {
                always {
                    sh "docker-compose -p ${COMPOSE_PROJECT} down -v || true"
                }
            }
        }
    }

    post {
        always {
            sh 'docker image prune -f'
        }
        failure {
            echo "Build ${BUILD_NUMBER} failed — check console output."
        }
        success {
            echo "Build ${BUILD_NUMBER} passed."
        }
    }
}
