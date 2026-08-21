pipeline {
    agent any
    tools {
        maven 'MavenLab'
    }
    environment {
        DOCKER_IMAGE = 'leap-laugh-love-app'
        DOCKER_TAG = "${env.BUILD_NUMBER}"
    }
    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        stage('Docker Check') {
            steps {
                sh 'docker version'
            }
        }
        stage('Build') {
            steps {
                sh 'mvn -B clean package'
                
            }
        }
        stage('Build Docker Image') {
            steps {
                sh 'docker build -t ${DOCKER_IMAGE}:${DOCKER_TAG} -f Dockerfile .'
                sh 'docker images | grep ${DOCKER_IMAGE}'
            }
        }
        stage('Archive') {
            steps {
                archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
            }
        }
    }
}
