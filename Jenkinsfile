pipeline {
    agent any

    environment {
        IAM_IMAGE = "iam-app"
        TRADING_IMAGE = "trading-app"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Test') {
            steps {
                sh 'docker run --rm -v "$WORKSPACE":/app -v /var/run/docker.sock:/var/run/docker.sock -w /app maven:3.9-eclipse-temurin-21 mvn -B test'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
                }
            }
        }
        
        stage('Build images') {
            steps {
                sh "docker build -f iam-app/Dockerfile -t ${IAM_IMAGE}:${BUILD_NUMBER} ."
                sh "docker build -f trading-app/Dockerfile -t ${TRADING_IMAGE}:${BUILD_NUMBER} ."
            }
        }

        stage('Verify container starts') {
            environment {
                DB_PORT = "${5432 + (env.EXECUTOR_NUMBER as Integer)}"
                IAM_PORT = "${8081 + (env.EXECUTOR_NUMBER as Integer)}"
                TRADING_PORT = "${8082 + (env.EXECUTOR_NUMBER as Integer)}"
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
                           docker-compose -p ${COMPOSE_PROJECT} exec -T trading-app wget -q -O /dev/null http://localhost:8082/actuator/health; then
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
