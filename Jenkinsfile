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
                // Docker socket is mounted so Testcontainers (used by the order history
                // integration test) can launch its own containers from inside this build container.
                sh 'docker run --rm -v "$WORKSPACE":/app -v /var/run/docker.sock:/var/run/docker.sock -w /app maven:3.9-eclipse-temurin-21 mvn -B test'
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
            }
            steps {
                // Unique project name per build so concurrent/parallel branch builds
                // don't collide on container names, and a leftover from an aborted
                // run is torn down before starting a fresh one.
                sh "docker-compose -p ${IMAGE_NAME}-${BUILD_NUMBER} down -v || true"
                sh "docker-compose -p ${IMAGE_NAME}-${BUILD_NUMBER} up -d"
                sh "docker-compose -p ${IMAGE_NAME}-${BUILD_NUMBER} ps"
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
