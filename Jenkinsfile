pipeline {
    agent any

    environment {
        IMAGE_NAME = "team-skeleton"
    }

    stages {
        stage('Checkout') {
            steps {
                // Checks out the branch/PR that triggered this build.
                // In a Multibranch Pipeline this covers main, feature branches,
                // and PRs automatically — no per-branch configuration needed.
                checkout scm
            }
        }

        stage('Test') {
            steps {
                sh 'docker run --rm -v "$WORKSPACE":/app -w /app maven:3.9-eclipse-temurin-21 mvn -B test'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
                }
            }
        }
        
        stage('Build image') {
            steps {
                // Tags with the Jenkins build number so every build/branch produces a
                // uniquely tagged image — avoids overwriting previous builds' artefacts.
                sh "docker build -t ${IMAGE_NAME}:${BUILD_NUMBER} ."
            }
        }

        stage('Verify container starts') {
            environment {
                // EXECUTOR_NUMBER is unique among builds running concurrently on the
                // same node, so parallel branch builds never bind the same host port.
                DB_PORT = "${5432 + (env.EXECUTOR_NUMBER as Integer)}"
                // Not a real secret — this stage only checks the container boots,
                // it never handles real client tokens/data. Unique per build so
                // concurrent builds don't share a value.
                JWT_SECRET = "ci-smoke-test-secret-${BUILD_NUMBER}-do-not-use-in-prod"
            }
            steps {
                // Unique project name per build so concurrent/parallel branch builds
                // don't collide on container names, and a leftover from an aborted
                // run is torn down before starting a fresh one.
                sh "docker-compose -p ${IMAGE_NAME}-${BUILD_NUMBER} down -v || true"
                sh "docker-compose -p ${IMAGE_NAME}-${BUILD_NUMBER} up -d"
                // `ps` alone never fails the build even if the app crashed on boot;
                // poll the container's actual HEALTHCHECK status (app has no host
                // port, so we can't curl it directly) and fail fast if it never
                // becomes healthy.
                sh '''
                    set -e
                    cid=$(docker-compose -p ${IMAGE_NAME}-${BUILD_NUMBER} ps -q app)
                    for i in $(seq 1 30); do
                        status=$(docker inspect --format='{{.State.Health.Status}}' "$cid" 2>/dev/null || echo "unknown")
                        echo "app health: $status"
                        if [ "$status" = "healthy" ]; then
                            exit 0
                        fi
                        if [ "$status" = "unhealthy" ]; then
                            docker-compose -p ${IMAGE_NAME}-${BUILD_NUMBER} logs app
                            exit 1
                        fi
                        sleep 2
                    done
                    echo "app never became healthy in time"
                    docker-compose -p ${IMAGE_NAME}-${BUILD_NUMBER} logs app
                    exit 1
                '''
            }
            post {
                // Always tear down, even if the steps above fail, so the host port
                // isn't left bound for the next build.
                always {
                    sh "docker-compose -p ${IMAGE_NAME}-${BUILD_NUMBER} down -v || true"
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
