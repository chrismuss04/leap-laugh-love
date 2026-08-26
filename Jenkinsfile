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
            steps {
                sh 'docker-compose up -d'
                sh 'docker-compose ps'
                sh 'docker-compose down -v'
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
