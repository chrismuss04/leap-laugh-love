pipeline {
    agent any

    stages {
        stage('Checkout') {
            steps {
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
                sh 'docker build -t team-skeleton .'
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
    }
}
