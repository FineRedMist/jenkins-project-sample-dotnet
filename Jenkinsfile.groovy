
import groovy.xml.*

def testResults = []
def version = "1.0.0.${env.BUILD_NUMBER}"
def nugetVersion = version
def analyses = []

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
        stage('Send Start Notification') {
            steps {
                notifyBuildStatus(BuildNotifyStatus.Pending)
            }
        }
        stage('Setup for forensics') {
            steps {
                discoverGitReferenceBuild()
            }
        }
        stage('Restore NuGet For Solution') {
            steps {
                //  '--no-cache' to avoid a shared cache--if multiple projects are running NuGet restore, they can collide.
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
        stage('Build Solution - Debug') {
            steps {
                echo "Setting NuGet Package version to: ${nugetVersion}"
                echo "Setting File and Assembly version to ${version}"
                bat "dotnet build --nologo -c Debug -p:PackageVersion=${nugetVersion} -p:Version=${version} --no-restore" 
            }
        }
        stage ("Run Tests") {
            steps {
                // MSTest projects automatically include coverlet that can generate cobertura formatted coverage information.
                bat """
                    dotnet test --nologo -c Debug --results-directory TestResults --logger trx --collect:"XPlat code coverage" --no-restore --no-build
                    """
            }
        }
        stage ("Publish Test Output") {
            steps {
                script {
                    def tests = gatherTestResults('TestResults/**/*.trx')
                    def coverage = gatherCoverageResults('TestResults/**/In/**/*.cobertura.xml')
                    testResults << "\n${tests}\n${coverage}"
                }
                mstest testResultsFile:"TestResults/**/*.trx", failOnError: true, keepLongStdio: true
            }
        }
        stage ("Publish Code Coverage") {
            steps {
                publishCoverage(adapters: [
                    coberturaAdapter(path: "TestResults/**/In/**/*.cobertura.xml", thresholds: [
                    [thresholdTarget: 'Group', unhealthyThreshold: 100.0],
                    [thresholdTarget: 'Package', unhealthyThreshold: 100.0],
                    [thresholdTarget: 'File', unhealthyThreshold: 50.0, unstableThreshold: 85.0],
                    [thresholdTarget: 'Class', unhealthyThreshold: 50.0, unstableThreshold: 85.0],
                    [thresholdTarget: 'Method', unhealthyThreshold: 50.0, unstableThreshold: 85.0],
                    [thresholdTarget: 'Instruction', unhealthyThreshold: 0.0, unstableThreshold: 0.0],
                    [thresholdTarget: 'Line', unhealthyThreshold: 50.0, unstableThreshold: 85.0],
                    [thresholdTarget: 'Conditional', unhealthyThreshold: 0.0, unstableThreshold: 0.0],
                    ])
                ], failNoReports: true, failUnhealthy: true, calculateDiffForChangeRequests: true)
            }
        }
        stage('Clean') {
            steps {
                bat "dotnet clean --nologo"
            }
        }
        stage('Build Solution - Release') {
            steps {
                echo "Setting NuGet Package version to: ${nugetVersion}"
                echo "Setting File and Assembly version to ${version}"
                bat "dotnet build --nologo -c Release -p:PackageVersion=${nugetVersion} -p:Version=${version} --no-restore" 
            }
        }
        stage ("Run Security Scan") {
            steps {
                bat "dotnet new tool-manifest"
                bat "dotnet tool install --local security-scan --no-cache"
                script {
                    def slnFile = ""
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
                    bat """
                    dotnet security-scan ${slnFile} --excl-proj=**/*Test*/** -n --cwe --export=sast-report.sarif
                    """

                    def analysisIssues = scanForIssues tool: sarif(pattern: 'sast-report.sarif')
                    analyses << analysisIssues
                    def analysisText = getAnaylsisResultsText(analysisIssues)
                    if(analysisText.length() > 0) {
                        testResults << "Static analysis results:\n" + analysisText
                    } else {
                        testResults << "No static analysis issues to report."
                    }
                    // Rescan. If we collect and then aggregate, warnings become errors
                    recordIssues aggregatingResults: true, enabledForFailure: true, failOnError: true, skipPublishingChecks: true, tool: sarif(pattern: 'sast-report.sarif')
                }
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
                                dotnet nuget push \"${nugetPkg}\" --api-key ${APIKey} --source http://localhost:8081/repository/nuget-hosted
                                """
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                def analysisIssues = scanForIssues tool: msBuild()
                analyses << analysisIssues
                def analysisText = getAnaylsisResultsText(analysisIssues)
                if(analysisText.length() > 0) {
                    testResults << "Build warnings and errors:\n" + analysisText
                } else {
                    testResults << "No build warnings or errors."
                }
                // Rescan. If we collect and then aggregate, warnings become errors
                recordIssues aggregatingResults: true, skipPublishingChecks: true, tool: msBuild()
            }
        }
        failure {
            notifyBuildStatus(BuildNotifyStatus.Failure, testResults)
        }
        unstable {
            notifyBuildStatus(BuildNotifyStatus.Unstable, testResults)
        }
        success {
            notifyBuildStatus(BuildNotifyStatus.Success, testResults)
        }
        cleanup {
            cleanWs(deleteDirs: true, disableDeferredWipeout: true, notFailBuild: true)
        }
    }
}

String readTextFile(String filePath) {
    def bin64 = readFile file: filePath, encoding: 'Base64'
    def binDat = bin64.decodeBase64()

    if(binDat.size() >= 3 
        && binDat[0] == -17
        && binDat[1] == -69
        && binDat[2] == -65) {
        return new String(binDat, 3, binDat.size() - 3, "UTF-8")
    } else {
        return new String(binDat)
    }
}

enum BuildNotifyStatus {
    Pending("started", null, GitHubStatus.Pending),
    Unstable("unstable", "warning", GitHubStatus.Failure),
    Failure("failed", "danger", GitHubStatus.Failure),
    Success("successful", "good", GitHubStatus.Success)

    String notifyText
    String slackColour
    GitHubStatus githubStatus

    BuildNotifyStatus(String text, String colour, GitHubStatus github) {
        notifyText = text
        slackColour = colour
        githubStatus = github
    }
}

void notifyBuildStatus(BuildNotifyStatus status, List<String> testResults = []) {
    def sent = slackSend(channel: '#build-notifications', color: status.slackColour, botUser: true, iconEmoji: ':jenkinscthulhu:', message: "Build ${status.notifyText}: <${env.BUILD_URL}|${env.JOB_NAME} #${env.BUILD_NUMBER}>")
    testResults.each { message ->
        if(message.length() > 0) {
            slackSend(channel: sent.threadId, color: status.slackColour, botUser: true, iconEmoji: ':jenkinscthulhu:', message: message)
        }
    }
    setBuildStatus("Build ${status.notifyText}", status.githubStatus)
}

enum GitHubStatus {
    Pending('PENDING'),
    Failure('FAILURE'),
    Success('SUCCESS')

    String githubState

    GitHubStatus(String state) {
        githubState = state
    }
}

String gatherTestResults(String searchPath) {
    def total = 0
    def passed = 0
    def failed = 0

    findFiles(glob: searchPath).each { f ->
        String fullName = f

        def data = readTextFile(fullName)

        def trx = new XmlParser(false, true, true).parseText(data)

        def counters = trx['ResultSummary']['Counters']

        // echo 'Getting counter values...'
        total += counters['@total'][0].toInteger()
        passed += counters['@passed'][0].toInteger()
        failed += counters['@failed'][0].toInteger()
    }

    if(total == 0) {
        return "No test results found."
    } else if(failed == 0) {
        if(passed == 1) {
            return "The only test passed!"
        } else {
            return "All ${total} tests passed!"
        }
    } else {
        return "${failed} of ${total} tests failed!"
    }
}

String gatherCoverageResults(String searchPath) {
    def linesCovered = 0
    def linesValid = 0
    def files = 0

    findFiles(glob: searchPath).each { f ->
        String fullName = f

        def data = readTextFile(fullName)

        def cover = new XmlParser(false, true, true).parseText(data)

        linesCovered += cover['@lines-covered'].toInteger()
        linesValid += cover['@lines-valid'].toInteger()
        files += 1
    }

    if(files == 0) {
        return "No code coverage results were found to report."
    } else if(linesValid == 0) {
        return "No code lines were found to collect test coverage for."
    } else {
        def pct = linesCovered.toDouble() * 100 / linesValid.toDouble()
        return "${linesCovered} of ${linesValid} lines were covered by testing (${pct.round(1)}%)."
    }
}

String getAnaylsisResultsText(def analysisResults) {
    String issues = ""
    analysisResults.getIssues().each { issue ->
        issues = issues + "* ${issue}\n"
    }
    return issues
}

void setBuildStatus(String message, GitHubStatus state) {
    def gitRepo = ""
    def gitOwner = ""
    def gitSha = ""

    gitSha = env.GIT_COMMIT
    if(env.GIT_URL && env.GIT_URL.toLowerCase().endsWith('.git')) {
        def matcher = env.GIT_URL =~ /.*[:\/](?<owner>[^:\/]*)\/(?<repo>.*)\.git/
        if(matcher.find()) {
            gitRepo = matcher.group("repo")
            gitOwner = matcher.group("owner")
        }
    }

    if(gitRepo.length() == 0
        || gitOwner.length() == 0
        || gitSha.length() == 0) {
        return
    }

    echo "Setting build status for owner ${gitOwner} and repository ${gitRepo} to: (${state}) ${message}"
    githubNotify(credentialsId: 'GitHub-Status-Notify', repo: gitRepo, account: gitOwner, sha: gitSha, context: 'Status', description: message, status: state.githubState, targetUrl: env.BUIlD_URL)
}
