pipeline {
    agent any

    stages {
        stage('Checkout') {
            steps {
                checkout scm
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
