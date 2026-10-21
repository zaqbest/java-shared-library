def call(Map config = [:]) {
    def branch = config.branch ?: 'main'
    // 优先从全局环境变量获取，如果没有配置则直接使用你在截图里创建的凭据 ID
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
                        def gitCommit = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                        def timestamp = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
                        env.FULL_VERSION = "${baseVersion}-${timestamp}-${gitCommit}"
                        echo "当前构建生成的版本号为: ${env.FULL_VERSION}"

                        sh "./gradlew ${gradlePublishTask} -Pversion=${env.FULL_VERSION}"
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
                        
                        // 使用对应的 SSH 凭据安全推送 Tag 到 GitHub
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
