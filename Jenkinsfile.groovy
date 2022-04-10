
// The solution file we found to build. Ideally only one per GitHub repository.
def slnFile = ""

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
        stage('Build Solution') {
            steps {
                bat """
                    \"${tool 'MSBuild-2022'}\" ${slnFile} /p:Configuration=Release /p:Platform=\"Any CPU\" /p:ProductVersion=1.0.${env.BUILD_NUMBER}.0
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
    }
}