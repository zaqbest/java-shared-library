/**
 * 将 SSH 形式的 Git 地址转换为 HTTPS 形式，PAT 认证只能走 HTTPS。
 * 已是 HTTPS 的地址原样返回。
 */
def toHttpsUrl(String url) {
    if (!url) {
        return url
    }
    // git@github.com:org/repo.git  ->  https://github.com/org/repo.git
    def m = url =~ /^git@([^:]+):(.+)$/
    if (m.matches()) {
        return "https://${m.group(1)}/${m.group(2)}"
    }
    // ssh://git@github.com/org/repo.git  ->  https://github.com/org/repo.git
    def m2 = url =~ /^ssh:\/\/(?:[^@\/]+@)?([^\/]+)\/(.+)$/
    if (m2.matches()) {
        return "https://${m2.group(1)}/${m2.group(2)}"
    }
    return url
}

/**
 * 判断 Git 凭据的类型，同时兼容 Secret text 与 Username with password 两种凭据。
 * 返回 'string' 或 'usernamePassword'；无法判断时默认按 'string' 处理。
 */
def resolveTokenCredentialType(String credentialsId) {
    def credential = null
    try {
        credential = com.cloudbees.plugins.credentials.CredentialsProvider.findCredentialById(
            credentialsId,
            com.cloudbees.plugins.credentials.common.StandardCredentials.class,
            currentBuild)
    } catch (Throwable t) {
        echo "无法按构建上下文查找凭据 [${credentialsId}]，将回退到全局查找: ${t.message}"
    }
    if (credential == null) {
        try {
            def all = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentialsInItemGroup(
                com.cloudbees.plugins.credentials.common.StandardCredentials.class,
                null, null, null)
            credential = all.find { it.id == credentialsId }
        } catch (Throwable t) {
            echo "查找凭据 [${credentialsId}] 失败: ${t.message}"
        }
    }
    if (credential != null) {
        // 先判断用户名/密码类型：Username with password 凭据含 username 与 password
        if (credential.hasProperty('username') && credential.hasProperty('password')) {
            return 'usernamePassword'
        }
        // Secret text 凭据含 secret 字段
        if (credential.hasProperty('secret')) {
            return 'string'
        }
    }
    return 'string'
}

/**
 * 构造 Git over HTTPS 使用的 Basic 认证头。
 * GitHub 的 PAT 用作密码，username 可为任意非空值（PAT 场景推荐 x-access-token）。
 */
def buildBasicAuthHeader(String user, String token) {
    return "Basic ${"${user}:${token}".getBytes('UTF-8').encodeBase64().toString()}"
}

/**
 * 通过 Personal Access Token 拉取代码。
 */
def checkoutWithToken(String httpsUrl, String branch, String authHeader) {
    checkout([$class: 'GitSCM',
        branches: [[name: "${branch}"]],
        userRemoteConfigs: [[
            url: "${httpsUrl}",
            // 使用 Authorization 头认证时不能再带 credentialsId，
            // 否则 Git 会同时发送两套认证信息而被 GitHub 拒绝
            httpHeaders: [[
                name: 'Authorization',
                value: "${authHeader}"
            ]]
        ]]
    ])
}

def call(Map config = [:]) {
    def branch = config.branch ?: 'main'
    def sshCredentialsId = env.DEFAULT_GIT_SSH_CREDENTIALS_ID ?: 'github-ssh-credential'
    def tokenCredentialsId = config.tokenCredentialsId ?: (env.DEFAULT_GIT_TOKEN_CREDENTIALS_ID ?: 'github-token-credential')
    // 默认保持 SSH 拉取；显式开启后才改用 Personal Access Token
    def useTokenAuth = config.useTokenAuth != null ? config.useTokenAuth : false

    def baseVersion = config.baseVersion ?: '1.0.0-SNAPSHOT'
    def unitTestTask = config.unitTestTask ?: 'test'
    def integrationTestTask = config.integrationTestTask ?: 'integrationTest'
    def gradlePublishTask = config.gradlePublishTask ?: 'publish'
    def autoTag = config.autoTag != null ? config.autoTag : true

    pipeline {
        agent any

        stages {
            stage('拉取代码') {
                steps {
                    script {
                        if (useTokenAuth) {
                            def httpsUrl = toHttpsUrl(env.GIT_URL)
                            def credType = resolveTokenCredentialType(tokenCredentialsId)
                            echo "正在通过 Personal Access Token 从 GitHub 拉取代码 [分支: ${branch}, 地址: ${httpsUrl}, 凭据类型: ${credType}]..."
                            if (credType == 'usernamePassword') {
                                // Username with password 类型的凭据：用户名取自凭据，密码为 PAT
                                withCredentials([usernamePassword(credentialsId: "${tokenCredentialsId}",
                                                                   usernameVariable: 'GITHUB_USER',
                                                                   passwordVariable: 'GITHUB_TOKEN')]) {
                                    def user = env.GITHUB_USER ?: 'x-access-token'
                                    checkoutWithToken(httpsUrl, branch, buildBasicAuthHeader(user, env.GITHUB_TOKEN))
                                }
                            } else {
                                // Secret text 类型的凭据：整串即 PAT，用户名固定为 x-access-token
                                withCredentials([string(credentialsId: "${tokenCredentialsId}", variable: 'GITHUB_TOKEN')]) {
                                    checkoutWithToken(httpsUrl, branch, buildBasicAuthHeader('x-access-token', env.GITHUB_TOKEN))
                                }
                            }
                        } else {
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
                }
            }

            stage('单元测试') {
                steps {
                    echo '正在执行单元测试...'
                    sh 'chmod +x ./gradlew'
                    sh "./gradlew ${unitTestTask}"
                }
            }

            stage('集成测试') {
                steps {
                    echo '正在执行集成测试...'
                    sh "./gradlew ${integrationTestTask}"
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
                        // 仓库地址直接定义在流水线中；仅账号密码从 Jenkins 全局环境变量读取
                        def zaqRepoResolveUrl = 'https://nexus.zaqbest.com/repository/maven-public/'
                        def zaqRepoSnapshotUrl = 'https://nexus.zaqbest.com/repository/maven-snapshots/'
                        def zaqRepoReleaseUrl = 'https://nexus.zaqbest.com/repository/maven-releases/'

                        sh """
                            ./gradlew ${gradlePublishTask} \
                            -PlibVersion=${env.FULL_VERSION} \
                            -Pversion=${env.FULL_VERSION} \
                            -DzaqRepoResolveUrl=${zaqRepoResolveUrl} \
                            -DzaqRepoSnapshotUrl=${zaqRepoSnapshotUrl} \
                            -DzaqRepoReleaseUrl=${zaqRepoReleaseUrl} \
                            -DzaqRepoUsername=${env.ZAQ_REPO_USERNAME} \
                            -DzaqRepoPassword=${env.ZAQ_REPO_PASSWORD}
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