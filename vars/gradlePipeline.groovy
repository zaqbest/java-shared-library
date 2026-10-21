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
                steps {
                    echo '正在执行单元测试...'
                    sh 'chmod +x ./gradlew'
                    sh "./gradlew ${unitTestTask} ${mavenRepoArgs()}"
                }
            }

            stage('集成测试') {
                steps {
                    echo '正在执行集成测试...'
                    sh "./gradlew ${integrationTestTask} ${mavenRepoArgs()}"
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
                            ${mavenRepoArgs()}
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
                            sh "git push origin ${tagName}"
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "流水线执行成功！版本 [${env.FULL_VERSION}] 已成功发布至 Nexus 并推送 Tag。"
            }
            failure {
                echo '流水线执行失败，请检查日志。'
            }
        }
    }
}
