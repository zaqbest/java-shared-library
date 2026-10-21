/**
 * Nexus 仓库地址，直接定义在流水线中
 */
def mavenRepoUrls() {
    return [
        resolveUrl : 'https://nexus.zaqbest.com/repository/maven-public/',
        snapshotUrl: 'https://nexus.zaqbest.com/repository/maven-snapshots/',
        releaseUrl : 'https://nexus.zaqbest.com/repository/maven-releases/'
    ]
}

/**
 * 组装 Gradle 需要的 Maven 仓库参数。
 * 仓库地址来自流水线定义，账号密码取自 Jenkins 全局环境变量。
 */
def mavenRepoArgs() {
    def repos = mavenRepoUrls()
    return [
        "-DzaqRepoResolveUrl=${repos.resolveUrl}",
        "-DzaqRepoSnapshotUrl=${repos.snapshotUrl}",
        "-DzaqRepoReleaseUrl=${repos.releaseUrl}",
        "-DzaqRepoUsername=${env.ZAQ_REPO_USERNAME}",
        "-DzaqRepoPassword=${env.ZAQ_REPO_PASSWORD}"
    ].join(' ')
}

def call(Map config = [:]) {
    def branch = config.branch ?: 'main'
    def sshCredentialsId = env.DEFAULT_GIT_SSH_CREDENTIALS_ID ?: 'github-ssh-credential'

    def baseVersion = config.baseVersion ?: '1.0.0'
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
        'platform-event', 'platform-cache', 'platform-apicore'
    ]

    pipeline {
        agent any

        stages {
            stage('拉取代码') {
                steps {
                    echo "正在通过 SSH 从 GitHub 拉取代码 [分支: ${branch}]..."
                    checkout([$class: 'GitSCM',
                        branches: [[name: "${branch}"]],
                        userRemoteConfigs: [[
                            url: "${env.GIT_URL}",
                            credentialsId: "${sshCredentialsId}"
                        ]]
                    ])
                }
            }

            stage('单元测试') {
                when {
                    expression { skipUtTest == false }
                }
                steps {
                    echo '正在执行单元测试...'
                    sh 'chmod +x ./gradlew'
                    sh "./gradlew ${unitTestTask} ${mavenRepoArgs()} --no-daemon"
                }
            }

            stage('集成测试') {
                when {
                    expression { skipSitTest == false }
                }
                steps {
                    echo '正在执行集成测试...'
                    sh "./gradlew ${integrationTestTask} ${mavenRepoArgs()} --no-daemon"
                }
            }

            stage('生成版本号与发布') {
                steps {
                    script {
                        // 1. 获取短 Git Commit Hash (前 7 位)
                        def gitCommit = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                        
                        // 2. 获取当前时间戳 (格式: yyyyMMddHHmmss)
                        def timestamp = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
                        
                        // 3. 拼装符合你 Gradle 脚本逻辑的动态版本号
                        env.FULL_VERSION = "${baseVersion.replace('-SNAPSHOT', '')}-${timestamp}-${gitCommit}"
                        if (baseVersion.endsWith('SNAPSHOT')) {
                            env.FULL_VERSION = "${baseVersion.replace('-SNAPSHOT', '')}-${timestamp}-${gitCommit}-SNAPSHOT"
                        }
                        
                        echo "当前构建生成的版本号为: ${env.FULL_VERSION}"

                        // 4. 执行 Gradle 发布
                        sh """
                            ./gradlew ${gradlePublishTask} \\
                            -PlibVersion=${env.FULL_VERSION} \\
                            -Pversion=${env.FULL_VERSION} \\
                            ${mavenRepoArgs()} --no-daemon
                        """
                    }
                }
            }

            stage('Git 打 Tag 并推送') {
                when {
                    expression { autoTag == true }
                }
                steps {
                    script {
                        def tagName = "v${env.FULL_VERSION}"
                        echo "正在创建并推送 Git Tag: ${tagName}"

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

            stage('更新组件依赖版本') {
                steps {
                    script {
                        def component = sh(script: 'basename "$(git remote get-url origin)" .git', returnStdout: true).trim()
                        if (!dependencyComponents.contains(component)) {
                            echo "${component} 不在依赖版本清单中，跳过更新。"
                        } else {
                            def propertyName = "${component}-dependencies.version"
                            // 每次重试都重新检出最新分支，避免并行发布造成非快进推送。
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
                                        error "未找到版本属性 ${propertyName}：${dependencyVersionsFile}"
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
                                        echo "${propertyName} 已经是 ${env.FULL_VERSION}，无需提交。"
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
                echo "流水线执行成功！版本 [${env.FULL_VERSION}] 已发布至 Nexus，符合条件的组件依赖版本已更新。"
            }
            failure {
                echo '流水线执行失败，请检查日志。'
            }
        }
    }
}
