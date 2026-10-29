/**
 * Nexus repo URLs, defined directly in the pipeline.
 */
def mavenRepoUrls() {
    return [
        resolveUrl : 'https://nexus.zaqbest.com/repository/maven-public/',
        snapshotUrl: 'https://nexus.zaqbest.com/repository/maven-snapshots/',
        releaseUrl : 'https://nexus.zaqbest.com/repository/maven-releases/'
    ]
}

/**
 * Assemble Maven repo parameters needed by Gradle.
 * Repo URLs come from pipeline definitions.
 * Credentials are not expanded here in plain text, but injected at runtime
 * by the build environment as shell variables,
 * so that plaintext passwords do not appear in command echo (set -x).
 */
def mavenRepoArgs() {
    def repos = mavenRepoUrls()
    return [
        "-DzaqRepoResolveUrl=${repos.resolveUrl}",
        "-DzaqRepoSnapshotUrl=${repos.snapshotUrl}",
        "-DzaqRepoReleaseUrl=${repos.releaseUrl}",
        '-DzaqRepoUsername="$ZAQ_REPO_USERNAME"',
        '-DzaqRepoPassword="$ZAQ_REPO_PASSWORD"'
    ].join(' ')
}

/**
 * Generate the command line for log display.
 * Show the actual username value for troubleshooting;
 * always replace passwords with ****, never print them in plain text.
 */
def displayGradleArgs(String gradleArgs) {
    def username = env.ZAQ_REPO_USERNAME ?: '****'
    def shown = gradleArgs.replace('"$ZAQ_REPO_USERNAME"', username)
    return shown.replaceAll(/-DzaqRepoPassword=("[^"]*"|'[^']*'|\S+)/, '-DzaqRepoPassword=****')
}

/**
 * Safely wrap a string as a shell single-quoted argument
 * to prevent the content from being re-parsed by the shell.
 */
def shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'"
}

/**
 * Construct the Gradle execution script.
 * set +x disables the shell's built-in command echo
 * (which would print expanded plaintext passwords),
 * instead output a masked command that preserves debuggability
 * without leaking passwords.
 */
def gradleShellScript(String label, String gradleArgs, String displayArgs) {
    return [
        'set +x',
        "echo ${shellQuote("Execute Gradle task: ${label}")}",
        "echo ${shellQuote("Execute command: ${displayArgs}")}",
        "./gradlew ${gradleArgs}"
    ].join('\n')
}

/**
 * Execute Gradle command.
 * If nexusCredentialsId is configured, bind Jenkins credentials via withCredentials,
 * and let Jenkins automatically mask sensitive values in logs;
 * otherwise fall back to Jenkins global environment variables
 * ZAQ_REPO_USERNAME / ZAQ_REPO_PASSWORD.
 */
def runGradle(Map config, String label, String gradleArgs) {
    def credentialsId = config.nexusCredentialsId
    if (credentialsId) {
        withCredentials([usernamePassword(credentialsId: credentialsId,
                usernameVariable: 'ZAQ_REPO_USERNAME',
                passwordVariable: 'ZAQ_REPO_PASSWORD')]) {
            sh gradleShellScript(label, gradleArgs, displayGradleArgs(gradleArgs))
        }
    } else {
        sh gradleShellScript(label, gradleArgs, displayGradleArgs(gradleArgs))
    }
}

/**
 * Resolve the branch for this build.
 * Priority: config.branch > Jenkins environment variables (BRANCH_NAME / GIT_BRANCH) > config.defaultBranch
 * Multi-branch (PR) builds fall back from PR reference to the source branch.
 */
def resolveBranch(Map config = [:]) {
    if (config.branch) {
        return config.branch
    }

    def branch = env.BRANCH_NAME ?: env.GIT_BRANCH
    if (!branch) {
        def fallback = config.defaultBranch ?: 'main'
        echo "Failed to get branch from Jenkins environment variables, falling back to ${fallback}."
        return fallback
    }

    branch = branch.trim().replaceFirst('^origin/', '').replaceFirst('^remotes/', '')

    // Multi-branch PR build: checked out PR reference, fall back to source branch
    if (branch.startsWith('PR-')) {
        def source = env.CHANGE_BRANCH ?: env.CHANGE_TARGET
        if (source) {
            branch = source.trim().replaceFirst('^origin/', '').replaceFirst('^remotes/', '')
        }
    }

    return branch
}

/**
 * Read the version property (e.g. libVersion) from the project's gradle.properties.
 * Resolution order: config explicit value > repository gradle.properties > default value.
 * Must be called after checkout (depends on the file already existing in the workspace).
 */
def resolveBaseVersion(String fallback, String propertyKey = 'libVersion', String propertiesFile = 'gradle.properties') {
    try {
        if (fileExists(propertiesFile)) {
            def contents = readFile(file: propertiesFile)
            // Match key=value, ignore leading whitespace, ignore # comment lines, trim value whitespace
            def matcher = contents =~ /(?m)^\s*${java.util.regex.Pattern.quote(propertyKey)}\s*=\s*(.*)$/
            if (matcher.find()) {
                def value = matcher.group(1).trim()
                if (value) {
                    echo "Read ${propertyKey}=${value} from ${propertiesFile}"
                    return value
                }
            }
            echo "Non-empty ${propertyKey} not found in ${propertiesFile}, falling back to ${fallback}."
        } else {
            echo "${propertiesFile} not found, falling back to ${fallback}."
        }
    } catch (Exception e) {
        echo "Failed to read ${propertiesFile} (${e.message}), falling back to ${fallback}."
    }
    return fallback
}

def call(Map config = [:]) {
    def branch = resolveBranch(config)
    // Only the main branches (main/master) tag and update platform-release dependency versions
    def releaseBranches = config.releaseBranches ?: ['main', 'master']
    def isReleaseBranch = releaseBranches.contains(branch)
    echo "Build branch: ${branch}, is release branch: ${isReleaseBranch}"

    def sshCredentialsId = env.DEFAULT_GIT_SSH_CREDENTIALS_ID ?: 'github-ssh-credential'

    // The final baseVersion is resolved from gradle.properties during the "Generate Version & Publish" stage,
    // here we only keep the config value and default as fallbacks.
    def configuredBaseVersion = config.baseVersion
    def baseVersionPropertyKey = config.baseVersionPropertyKey ?: 'libVersion'
    def baseVersionPropertiesFile = config.baseVersionPropertiesFile ?: 'gradle.properties'
    def unitTestTask = config.unitTestTask ?: 'test'
    def integrationTestTask = config.integrationTestTask ?: 'integrationTest'
    def gradlePublishTask = config.gradlePublishTask ?: 'publish'
    def autoTag = config.autoTag != null ? config.autoTag : true
    def skipUtTest = config.skipUtTest != null ? config.skipUtTest : false
    def skipSitTest = config.skipSitTest != null ? config.skipSitTest : false
    def dependencyRepoUrl = config.dependencyRepoUrl ?: 'git@github.com:zaqbest/platform-release.git'
    def dependencyBranch = config.dependencyBranch ?: 'master'
    def dependencyVersionsFile = 'platform-dependencies/gradle.properties'
    def dependencyComponents = [
        'platform-database', 'platform-monitoring', 'platform-common',
        'platform-config', 'platform-build', 'platform-security',
        'platform-event', 'platform-cache', 'platform-cache2', 'platform-apicore'
    ]

    pipeline {
        agent any

        stages {
            stage('Checkout Code') {
                steps {
                    echo "Checking out code from GitHub via SSH [Branch: ${branch}]..."
                    checkout([$class: 'GitSCM',
                        branches: [[name: "${branch}"]],
                        userRemoteConfigs: [[
                            url: "${env.GIT_URL}",
                            credentialsId: "${sshCredentialsId}"
                        ]]
                    ])

                    script {
                        def actualBranch = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
                        echo "Actual checked-out branch: ${actualBranch} (expected: ${branch})"
                    }
                }
            }

            stage('Unit Test') {
                when {
                    expression { skipUtTest == false }
                }
                steps {
                    echo 'Running unit tests...'
                    sh 'chmod +x ./gradlew'
                    runGradle(config, "Unit Test(${unitTestTask})", "${unitTestTask} ${mavenRepoArgs()} --no-daemon")
                }
            }

            stage('Integration Test') {
                when {
                    expression { skipSitTest == false }
                }
                steps {
                    echo 'Running integration tests...'
                    runGradle(config, "Integration Test(${integrationTestTask})", "${integrationTestTask} ${mavenRepoArgs()} --no-daemon")
                }
            }

            stage('Generate Version & Publish') {
                steps {
                    script {
                        // 1. Get short Git commit hash (first 7 characters)
                        def gitCommit = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                        
                        // 2. Get current timestamp (format: yyyyMMddHHmmss)
                        def timestamp = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
                        
                        // 3. Resolve base version
                        //    Priority: config.baseVersion explicit value > libVersion from gradle.properties > default 1.0.0
                        def baseVersion = resolveBaseVersion(
                            configuredBaseVersion ?: '1.0.0',
                            baseVersionPropertyKey,
                            baseVersionPropertiesFile
                        )

                        // 4. Assemble the dynamic version number matching your Gradle script logic
                        //    Release branch (main/master): stable version number
                        //    Other branches: always append -SNAPSHOT suffix, artifacts are snapshot versions
                        def baseVersionClean = baseVersion.replaceFirst(/-?SNAPSHOT$/, '')
                        def versionCore = "${baseVersionClean}-${timestamp}-${gitCommit}"
                        // Non-release branch forces snapshot; release branch uses baseVersion's explicit configuration
                        def useSnapshot = !isReleaseBranch || baseVersion.endsWith('SNAPSHOT')
                        env.FULL_VERSION = useSnapshot ? "${versionCore}-SNAPSHOT" : versionCore

                        echo "Current build branch: ${branch} (is release branch: ${isReleaseBranch})"
                        echo "Base version: ${baseVersion}"
                        echo "Generated version for this build: ${env.FULL_VERSION}"

                        // 5. Execute Gradle publish
                        runGradle(config, "Publish(${gradlePublishTask})",
                            "${gradlePublishTask} -PlibVersion=${env.FULL_VERSION} -Pversion=${env.FULL_VERSION} ${mavenRepoArgs()} --no-daemon")
                    }
                }
            }

            stage('Git Tag & Push') {
                when {
                    expression { autoTag == true && isReleaseBranch }
                }
                steps {
                    script {
                        def tagName = "v${env.FULL_VERSION}"
                        echo "Creating and pushing Git Tag: ${tagName}"

                        sh 'git config user.name "Jenkins CI"'
                        sh 'git config user.email "jenkins@ci.local"'

                        sh "git tag -a ${tagName} -m 'Automated release by Jenkins build #${env.BUILD_NUMBER}'"
                        
                        sshagent(credentials: ["${sshCredentialsId}"]) {
                                withEnv(['GIT_SSH_COMMAND=ssh -o StrictHostKeyChecking=accept-new']) {
                                    sh "git push origin ${tagName}"
                                }
                        }
                    }
                }
            }

            stage('Update Component Dependency Version') {
                when {
                    expression { isReleaseBranch }
                }
                steps {
                    script {
                        def component = sh(script: 'basename "$(git remote get-url origin)" .git', returnStdout: true).trim()
                        if (!dependencyComponents.contains(component)) {
                            echo "${component} not in dependency version list, skipping update."
                        } else {
                            def propertyName = "${component}-dependencies.version"
                            // Re-checkout the latest branch on each retry to avoid non-fast-forward pushes caused by parallel releases.
                            retry(3) {
                                dir('dependency-version-update') {
                                    deleteDir()
                                    checkout([$class: 'GitSCM',
                                        branches: [[name: dependencyBranch]],
                                        userRemoteConfigs: [[url: dependencyRepoUrl, credentialsId: sshCredentialsId]]
                                    ])

                                    def contents = readFile(file: dependencyVersionsFile)
                                    def linePattern = ~/(?m)^${java.util.regex.Pattern.quote(propertyName)}=[^\r\n]*$/
                                    if (!linePattern.matcher(contents).find()) {
                                        error "Version property ${propertyName} not found: ${dependencyVersionsFile}"
                                    }
                                    def updated = contents.replaceFirst(linePattern, "${propertyName}=${env.FULL_VERSION}")
                                    if (updated != contents) {
                                        writeFile(file: dependencyVersionsFile, text: updated)
                                        sh 'git config user.name "Jenkins CI" && git config user.email "jenkins@ci.local"'
                                        sh "git add -- ${dependencyVersionsFile} && git commit -m 'chore: update ${component} to ${env.FULL_VERSION} [skip ci]'"
                                        sshagent(credentials: [sshCredentialsId]) {
                                            withEnv(['GIT_SSH_COMMAND=ssh -o StrictHostKeyChecking=accept-new']) {
                                                sh "git push origin HEAD:refs/heads/${dependencyBranch}"
                                            }
                                        }
                                    } else {
                                        echo "${propertyName} is already ${env.FULL_VERSION}, no commit needed."
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "Pipeline executed successfully! Version [${env.FULL_VERSION}] has been published to Nexus, and eligible component dependency versions have been updated."
            }
            failure {
                echo 'Pipeline execution failed, please check the logs.'
            }
        }
    }
}
