pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 45, unit: 'MINUTES')
    }

    environment {
        BACKEND_IMAGE = 'bookstore-backend'
        FRONTEND_IMAGE = 'bookstore-frontend'
        IMAGE_TAG = ''
        SONARQUBE_ENABLED = 'false'
        TRIVY_ENABLED = 'false'
        PUBLISH_IMAGES = 'false'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.IMAGE_TAG = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
                }
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

        stage('SonarQube analysis') {
            when {
                expression { env.SONARQUBE_ENABLED == 'true' }
            }
            steps {
                withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                    sh '''
                        set -eu
                        test -n "$SONAR_HOST_URL"
                        command -v sonar-scanner
                        sonar-scanner                           -Dsonar.host.url="$SONAR_HOST_URL"                           -Dsonar.token="$SONAR_TOKEN"
                    '''
                }
            }
        }

        stage('Build container images') {
            steps {
                sh 'docker build -t $BACKEND_IMAGE:$IMAGE_TAG ./backend'
                sh 'docker build -t $FRONTEND_IMAGE:$IMAGE_TAG ./frontend'
            }
        }

        stage('Trivy image scan') {
            when {
                expression { env.TRIVY_ENABLED == 'true' }
            }
            steps {
                sh '''
                    set -eu
                    command -v trivy
                    trivy image --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed "$BACKEND_IMAGE:$IMAGE_TAG"
                    trivy image --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed "$FRONTEND_IMAGE:$IMAGE_TAG"
                '''
            }
        }

        stage('Helm chart validation') {
            steps {
                sh '''
                    set -eu
                    command -v helm
                    helm lint ./helm/bookstore
                    helm template bookstore ./helm/bookstore --namespace bookstore > /tmp/bookstore-rendered.yaml
                    test -s /tmp/bookstore-rendered.yaml
                '''
            }
        }

        stage('Compose integration smoke test') {
            steps {
                sh 'docker compose up --build -d'
                sh '''
                    set -eu
                    attempt=0
                    until curl --fail --silent http://localhost:5000/api/ready; do
                      attempt=$((attempt + 1))
                      if [ "$attempt" -ge 20 ]; then
                        docker compose logs
                        exit 1
                      fi
                      sleep 3
                    done
                    curl --fail --silent http://localhost:5000/api/books | grep -q "DevOps for Beginners"
                    curl --fail --silent http://localhost:8080/ > /dev/null
                    curl --fail --silent http://localhost:8080/api/books | grep -q "Kubernetes Basics"
                '''
            }
            post {
                always {
                    sh 'docker compose logs --no-color || true'
                    sh 'docker compose down -v --remove-orphans || true'
                }
            }
        }

        stage('Publish images to Docker Hub') {
            when {
                allOf {
                    branch 'main'
                    expression { env.PUBLISH_IMAGES == 'true' }
                }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DOCKERHUB_USERNAME', passwordVariable: 'DOCKERHUB_TOKEN')]) {
                    sh '''
                        set -eu
                        BACKEND_REMOTE="docker.io/$DOCKERHUB_USERNAME/bookstore-backend:$IMAGE_TAG"
                        FRONTEND_REMOTE="docker.io/$DOCKERHUB_USERNAME/bookstore-frontend:$IMAGE_TAG"
                        echo "$DOCKERHUB_TOKEN" | docker login --username "$DOCKERHUB_USERNAME" --password-stdin
                        trap 'docker logout >/dev/null 2>&1 || true' EXIT
                        docker tag "$BACKEND_IMAGE:$IMAGE_TAG" "$BACKEND_REMOTE"
                        docker tag "$FRONTEND_IMAGE:$IMAGE_TAG" "$FRONTEND_REMOTE"
                        docker push "$BACKEND_REMOTE"
                        docker push "$FRONTEND_REMOTE"
                    '''
                }
            }
        }
    }

    post {
        always {
            sh 'docker image ls "$BACKEND_IMAGE" --format "{{.Repository}}:{{.Tag}}" || true'
            sh 'docker image ls "$FRONTEND_IMAGE" --format "{{.Repository}}:{{.Tag}}" || true'
            deleteDir()
        }
        success {
            echo 'Validation and deployment-artifact checks passed. Optional SonarQube, Trivy and registry publishing stages run only when explicitly configured.'
        }
        failure {
            echo 'Pipeline failed. Inspect the first failing stage and its console output.'
        }
    }
}
