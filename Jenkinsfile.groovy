
// The solution file we found to build. Ideally only one per GitHub repository.
def slnFile = ""
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
        stage('Find Solution') {
            steps {
                script {
                    // Search the repository for a file ending in .sln.
                    findFiles(glob: '**').each {
                        def path = it.toString();
                        if(path.toLowerCase().endsWith('.sln')) {
                            slnFile = path;
                        }
                    }
                    if(slnFile.length() == 0) {
                        throw new Exception('No solution files were found to build in the root of the git repository.')
                    }
                    echo "Found solution: ${slnFile}"
                }
            }
        }
        stage('Restore NuGet For Solution') {
            steps {
                // The command to restore includes:
                //  'NoCache' to avoid a shared cache--if multiple projects are running NuGet restore, they can collide.
                //  'NonInteractive' ensures no dialogs appear which can block builds from continuing.
                bat """
                    \"${tool 'NuGet-2022'}\" restore ${slnFile} -NoCache -NonInteractive
                    """
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
                        echo "Build version: ${buildVersion}"
                        echo "Build version parts: ${parts}, ${parts.getClass()}"
                        while(parts.size() < 3) {
                            echo "1, ${parts.size()}"
                            parts << "0"
                            echo "2, ${parts.size()}"
                        }
                        // The nuget version does not include the build number.
                        nugetVersion = parts.join('.')
                        echo "Nuget version: ${nugetVersion}"
                        if(parts.size() < 4) {
                            echo "3, ${parts.size()}"
                            parts << env.BUILD_NUMBER
                            echo "4, ${parts.size()}"
                        }
                        // This version is for the file and assembly versions.
                        version = parts.join('.')
                        echo "Version: ${nugetVersion}"
                    }
                }
            }
        }
        stage('Build Solution') {
            steps {
                echo "Setting NuGet Package version to: ${nugetVersion}"
                echo "Setting File and Assembly version to ${version}"
                bat """
                    \"${tool 'MSBuild-2022'}\" ${slnFile} /p:Configuration=Release /p:Platform=\"Any CPU\" /p:PackageVersion=${nugetVersion} /p:Version=${version}
                    """
            }
        }
        stage ("Run Tests") {
            steps {
                script {
                    // Clean up any old test output from before so it doesn't contaminate this run.
                    bat "IF EXIST TestResults rmdir /s /q TestResults"

                    // The collection of tests to the work to do
                    def tests = [:]

                    // Find all the Test dlls that were built.
                    def testAntPath = "**/bin/**/*.Tests.dll"
                    findFiles(glob: testAntPath).each { f ->
                        String fullName = f

                        // Add a command to the map to run that test.
                        tests["${fullName}"] = {
                            bat "\"${tool 'VSTest-2022'}\" /platform:x64 \"${fullName}\" /logger:trx /inIsolation /ResultsDirectory:TestResults"
                        }
                    }
                    // Runs the tests in parallel
                    parallel tests
                }
            }
        }
        stage ("Convert Test Output") {
            steps {
                script {
                    mstest testResultsFile:"TestResults/**/*.trx", failOnError: true, keepLongStdio: true
                }
            }
        }
        stage('Preexisting NuGet Package Check') {
            steps {
                // Find all the nuget packages to publish.
                script {
                    def packageText = bat(returnStdOut: true, script: "\"${tool 'NuGet-2022'}\" list \"${nugetPkg}\" -NonInteractive -Src http://localhost:8081/repository/nuget-hosted")
                    packageText = packageText.replaceAll("\r", "")
                    def packages = new ArrayList(packages.split("\n").toList())
                    packages.removeAll { line -> line.toLowerCase().startsWith("warning: ") }
                    packages = packages.collect { pkg -> pkg.replaceAll(' ', '.') }

                    def nupkgFiles = "**/*.nupkg"
                    findFiles(glob: nupkgFiles).each { nugetPkg ->
                        def pkgName = nugetPkg.getBaseName()
                        if(packages.contains(pkgName)) {
                            error "The package ${pkgName} is already in the NuGet repository."
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
}