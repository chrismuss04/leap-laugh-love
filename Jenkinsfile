pipeline {
    agent any
    tools {
        nodejs 'NodeJS'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Configure Pipeline') {
            steps {
                script {
                    env.IMAGE_TAG = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    env.COMPOSE_PROJECT = (env.JOB_NAME + '-' + env.BUILD_NUMBER)
                        .toLowerCase().replaceAll('[^a-z0-9]+', '-')
                    def executor = env.EXECUTOR_NUMBER as Integer
                    env.DB_PORT = (5432 + executor).toString()
                    env.IAM_PORT = (8081 + executor).toString()
                    env.TRADING_PORT = (8082 + executor).toString()
                    env.MARKETDATA_PORT = (8083 + executor).toString()
                    env.JWT_SECRET = "ci-smoke-test-secret-${env.BUILD_NUMBER}-do-not-use-in-prod"
                    echo "Building commit ${env.IMAGE_TAG} (Jenkins build ${env.BUILD_NUMBER})"
                }
            }
        }

        stage('Build Frontend') {
            steps {
                dir('frontend') {
                    sh 'npm ci && npm run build'
                }
            }
        }

        stage('Test Backend') {
            steps {
                sh 'docker run --rm -v "$WORKSPACE":/app -v /var/run/docker.sock:/var/run/docker.sock -w /app maven:3.9-eclipse-temurin-21 mvn -B test'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Start Database') {
            steps {
                sh '''
                    set -eu
                    docker-compose -p "$COMPOSE_PROJECT" up -d db
                    echo "Waiting for PostgreSQL and schema initialization..."
                    for i in $(seq 1 60); do
                        if docker-compose -p "$COMPOSE_PROJECT" exec -T db pg_isready -U paysprint -d paysprint; then
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
                        ready=$(docker-compose -p "$COMPOSE_PROJECT" exec -T db psql -U paysprint -d paysprint -Atc "SELECT to_regclass('iam.clients') IS NOT NULL AND to_regclass('trading.accounts') IS NOT NULL AND to_regclass('marketdata.instruments') IS NOT NULL;" 2>/dev/null) || ready=
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
                    docker-compose -p "$COMPOSE_PROJECT" build iam-app trading-app market-data-app
                    echo "Built service images from commit $IMAGE_TAG"
                '''
            }
        }

        stage('Start Services') {
            steps {
                sh '''
                    set -eu
                    docker-compose -p "$COMPOSE_PROJECT" up -d --no-build iam-app trading-app market-data-app
                    docker-compose -p "$COMPOSE_PROJECT" ps
                '''
            }
        }

        stage('Verify Services') {
            steps {
                sh '''
                    set -eu
                    echo "Waiting for service health endpoints..."
                    for i in $(seq 1 60); do
                        if docker-compose -p "$COMPOSE_PROJECT" exec -T iam-app wget -q -O /dev/null http://localhost:8081/actuator/health && \
                           docker-compose -p "$COMPOSE_PROJECT" exec -T trading-app wget -q -O /dev/null http://localhost:8082/actuator/health && \
                           docker-compose -p "$COMPOSE_PROJECT" exec -T market-data-app wget -q -O /dev/null http://localhost:8083/actuator/health; then
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
                    docker-compose -p "$COMPOSE_PROJECT" ps -a || true
                    for service in db iam-app trading-app market-data-app; do
                        echo "========== $service LOGS (LAST 100 LINES) =========="
                        docker-compose -p "$COMPOSE_PROJECT" logs --tail=100 "$service" || true
                    done
                fi
            '''
        }
        success {
            echo "Build ${env.BUILD_NUMBER} passed (commit ${env.IMAGE_TAG})."
        }
        cleanup {
            sh '''
                if [ -n "${COMPOSE_PROJECT:-}" ]; then
                    docker-compose -p "$COMPOSE_PROJECT" down -v || true
                fi
            '''
        }
    }
}
