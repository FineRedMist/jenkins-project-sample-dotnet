
// The solution file we found to build. Ideally only one per GitHub repository.
def version = "1.0.0.${env.BUILD_NUMBER}"
def nugetVersion = version

pipeline {
    // Run on any available Jenkins agent.
    agent any
    options {
        // Show timestamps in the build.
        timestamps()
        // Prevent more than one build from running at a time for this project.
        disableConcurrentBuilds()
        // If Jenkins restarts or the client disconnects/reconnects, abandon the current build instead of trying to continue.
        disableResume()
    }
    triggers {
        // Poll source control periodically for changes.
        pollSCM 'H * * * *'
    }
    stages {
        stage('Restore NuGet For Solution') {
            steps {
                // The command to restore includes:
                //  'NoCache' to avoid a shared cache--if multiple projects are running NuGet restore, they can collide.
                //  'NonInteractive' ensures no dialogs appear which can block builds from continuing.
                bat "dotnet restore --nologo --no-cache"
            }
        }
        stage('Configure Build Settings') {
            when { expression { return fileExists ('Configuration.json') } }
            steps {
                script {
                    def buildConfig = readJSON file: 'Configuration.json'
                    if(buildConfig.containsKey('Version')) {
                        def buildVersion = buildConfig['Version']
                        // Count the parts, and add any missing zeroes to get up to 3, then add the build version.
                        def parts = new ArrayList(buildVersion.split('\\.').toList())
                        while(parts.size() < 3) {
                            parts << "0"
                        }
                        // The nuget version does not include the build number.
                        nugetVersion = parts.join('.')
                        if(parts.size() < 4) {
                            parts << env.BUILD_NUMBER
                        }
                        // This version is for the file and assembly versions.
                        version = parts.join('.')
                    }
                }
            }
        }
        stage('Build Solution') {
            steps {
                echo "Setting NuGet Package version to: ${nugetVersion}"
                echo "Setting File and Assembly version to ${version}"
                bat "dotnet build --nologo -c Release -p:PackageVersion=${nugetVersion} -p:Version=${version} --no-restore" 
            }
        }
        stage ("Run Tests") {
            steps {
                bat """
                    dotnet test --nologo -c Release --results-directory TestResults --logger trx --collect:"XPlat code coverage" --no-restore --no-build
                    """
                script {
                    def testResults = "TestResults/**"
                    findFiles(glob: testResults).each { foundFile ->
                        echo "Found file: ${foundFile}"
                    }
                }
            }
        }
        stage ("Publish MSTest Output") {
            steps {
                mstest testResultsFile:"TestResults/**/*.trx", failOnError: true, keepLongStdio: true
            }
        }
        stage ("Publish Code Coverage") {
            steps {
                publishCoverage(adapters: [
                  coberturaAdapter(path: "TestResults/**/In/**/*.cobertura.xml", thresholds: [
                    [thresholdTarget: 'Group', unhealthyThreshold: 100.0],
                    [thresholdTarget: 'Package', unhealthyThreshold: 100.0],
                    [thresholdTarget: 'File', unhealthyThreshold: 85.0],
                    [thresholdTarget: 'Class', unhealthyThreshold: 85.0],
                    [thresholdTarget: 'Method', unhealthyThreshold: 85.0],
                    [thresholdTarget: 'Instruction', unhealthyThreshold: 50.0, unstableThreshold: 85.0],
                    [thresholdTarget: 'Line', unhealthyThreshold: 85.0],
                    [thresholdTarget: 'Conditional', unhealthyThreshold: 50.0, unstableThreshold: 85.0],
                  ])
                ], failNoReports: true, failUnhealthy: true, calculateDiffForChangeRequests: true)
            }
        }
        stage('Preexisting NuGet Package Check') {
            steps {
                // Find all the nuget packages to publish.
                script {
                    def packageText = bat(returnStdout: true, script: "\"${tool 'NuGet-2022'}\" list -NonInteractive -Source http://localhost:8081/repository/nuget-hosted")
                    packageText = packageText.replaceAll("\r", "")
                    def packages = new ArrayList(packageText.split("\n").toList())
                    packages.removeAll { line -> line.toLowerCase().startsWith("warning: ") }
                    packages = packages.collect { pkg -> pkg.replaceAll(' ', '.') }

                    def nupkgFiles = "**/*.nupkg"
                    findFiles(glob: nupkgFiles).each { nugetPkg ->
                        def pkgName = nugetPkg.getName()
                        pkgName = pkgName.substring(0, pkgName.length() - 6) // Remove extension
                        if(packages.contains(pkgName)) {
                            error "The package ${pkgName} is already in the NuGet repository."
                        } else {
                            echo "The package ${nugetPkg} is not in the NuGet repository."
                        }
                    }
                }
            }
        }
        stage("NuGet Publish") {
            // We are only going to publish to NuGet when the branch is main or master.
            // This way other branches will test without interfering with releases.
            when {
                anyOf {
                    branch 'master';
                    branch 'main';
                }
            }
            steps {
                withCredentials([string(credentialsId: 'Nexus-NuGet-API-Key', variable: 'APIKey')]) { 
                    // Find all the nuget packages to publish.
                    script {
                        def nupkgFiles = "**/*.nupkg"
                        findFiles(glob: nupkgFiles).each { nugetPkg ->
                            bat """
                                \"${tool 'NuGet-2022'}\" push \"${nugetPkg}\" -NonInteractive -APIKey ${APIKey} -Src http://localhost:8081/repository/nuget-hosted
                                """
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: 'TestResults/**/In/**/*.cobertura.xml', allowEmptyArchive: true
        }
        cleanup {
            cleanWs(deleteDirs: true, disableDeferredWipeout: true, notFailBuild: true)
        }
    }
}