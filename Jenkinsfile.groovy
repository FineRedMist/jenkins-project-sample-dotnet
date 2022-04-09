pipeline {
    agent any
    options {
        timestamps()
        disableConcurrentBuilds()
        disableResume()
        ansiColor('xterm')
        buildDiscarder(logRotator(numToKeepStr: '180', artifactNumToKeepStr: '180'))
    }
    triggers {
        pollSCM 'H * * * *'
    }
    stages {
        stage('This is a test!') {
            steps {
                script {
                    print 'Hello World!'
                }
            }
        }
    }
}