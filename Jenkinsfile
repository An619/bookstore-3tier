pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 35, unit: 'MINUTES')
    }

    environment {
        BACKEND_IMAGE = 'bookstore-backend'
        FRONTEND_IMAGE = 'bookstore-frontend'
        IMAGE_TAG = ''
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script { env.IMAGE_TAG = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim() }
            }
        }

        stage('Backend validation') {
            steps {
                dir('backend') {
                    sh 'node --version'
                    sh 'npm ci'
                    sh 'npm test'
                }
            }
        }

        stage('Frontend validation') {
            steps {
                dir('frontend') {
                    sh 'node --version'
                    sh 'npm ci'
                    sh 'npm run lint'
                    sh 'npm run build'
                }
            }
        }

        stage('Build container images') {
            steps {
                sh 'docker build -t $BACKEND_IMAGE:$IMAGE_TAG ./backend'
                sh 'docker build -t $FRONTEND_IMAGE:$IMAGE_TAG ./frontend'
            }
        }

        stage('Compose integration smoke test') {
            steps {
                sh 'docker compose up --build -d'
                sh '''
                    set -eu
                    attempt=0
                    until curl --fail --silent http://localhost:5000/api/health; do
                      attempt=$((attempt + 1))
                      if [ "$attempt" -ge 20 ]; then
                        docker compose logs
                        exit 1
                      fi
                      sleep 3
                    done
                    curl --fail --silent http://localhost:5000/api/books > /tmp/bookstore-books.json
                    grep -q "DevOps for Beginners" /tmp/bookstore-books.json
                    curl --fail --silent http://localhost:8080/ > /dev/null
                    curl --fail --silent http://localhost:8080/api/books > /tmp/bookstore-proxy-books.json
                    grep -q "Kubernetes Basics" /tmp/bookstore-proxy-books.json
                '''
            }
            post {
                always {
                    sh 'docker compose logs --no-color || true'
                    sh 'docker compose down -v --remove-orphans || true'
                }
            }
        }
    }

    post {
        always {
            sh 'docker image ls "$BACKEND_IMAGE" --format "{{.Repository}}:{{.Tag}}" || true'
            sh 'docker image ls "$FRONTEND_IMAGE" --format "{{.Repository}}:{{.Tag}}" || true'
            cleanWs()
        }
        success {
            echo 'Validation, container build and Compose smoke tests passed. Registry publishing and deployment are intentionally separate milestones.'
        }
        failure {
            echo 'Pipeline failed. Inspect the first failing stage and its console output.'
        }
    }
}
