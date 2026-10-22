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

/**
 * 解析本次构建的分支。
 * 优先级：config.branch > Jenkins 环境变量(BRANCH_NAME / GIT_BRANCH) > config.defaultBranch
 * 多分支(PR)构建会把 PR 引用回退为源分支。
 */
def resolveBranch(Map config = [:]) {
    if (config.branch) {
        return config.branch
    }

    def branch = env.BRANCH_NAME ?: env.GIT_BRANCH
    if (!branch) {
        def fallback = config.defaultBranch ?: 'main'
        echo "未能从 Jenkins 环境变量中获取分支，回退到 ${fallback}。"
        return fallback
    }

    branch = branch.trim().replaceFirst('^origin/', '').replaceFirst('^remotes/', '')

    // 多分支 PR 构建：检出的是 PR 引用，回退到源分支
    if (branch.startsWith('PR-')) {
        def source = env.CHANGE_BRANCH ?: env.CHANGE_TARGET
        if (source) {
            branch = source.trim().replaceFirst('^origin/', '').replaceFirst('^remotes/', '')
        }
    }

    return branch
}

/**
 * 从项目的 gradle.properties 中读取版本号属性(如 libVersion)。
 * 读取顺序：config 显式指定 > 仓库 gradle.properties > 默认值。
 * 必须在 checkout 之后调用（依赖工作区已存在该文件）。
 */
def resolveBaseVersion(String fallback, String propertyKey = 'libVersion', String propertiesFile = 'gradle.properties') {
    try {
        if (fileExists(propertiesFile)) {
            def contents = readFile(file: propertiesFile)
            // 匹配 key=value，忽略行首空白、忽略 # 注释行，value 去除首尾空白
            def matcher = contents =~ /(?m)^\s*${java.util.regex.Pattern.quote(propertyKey)}\s*=\s*(.*)$/
            if (matcher.find()) {
                def value = matcher.group(1).trim()
                if (value) {
                    echo "从 ${propertiesFile} 读取到 ${propertyKey}=${value}"
                    return value
                }
            }
            echo "${propertiesFile} 中未找到非空的 ${propertyKey}，回退到 ${fallback}。"
        } else {
            echo "未找到 ${propertiesFile}，回退到 ${fallback}。"
        }
    } catch (Exception e) {
        echo "读取 ${propertiesFile} 失败(${e.message})，回退到 ${fallback}。"
    }
    return fallback
}

def call(Map config = [:]) {
    def branch = resolveBranch(config)
    // 只有主分支(main/master)才打 Tag 并推进 platform-release 的依赖版本
    def releaseBranches = config.releaseBranches ?: ['main', 'master']
    def isReleaseBranch = releaseBranches.contains(branch)
    echo "本次构建分支: ${branch}，是否为发布分支: ${isReleaseBranch}"

    def sshCredentialsId = env.DEFAULT_GIT_SSH_CREDENTIALS_ID ?: 'github-ssh-credential'

    // baseVersion 的最终取值在「生成版本号与发布」阶段从 gradle.properties 解析，
    // 这里仅保留 config 传入值与默认值作为回退。
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

                    def actualBranch = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
                    echo "实际检出分支: ${actualBranch}（期望: ${branch}）"
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
                        
                        // 3. 解析基础版本号
                        //    优先级：config.baseVersion 显式指定 > gradle.properties 的 libVersion > 默认 1.0.0
                        def baseVersion = resolveBaseVersion(
                            configuredBaseVersion ?: '1.0.0',
                            baseVersionPropertyKey,
                            baseVersionPropertiesFile
                        )

                        // 4. 拼装符合你 Gradle 脚本逻辑的动态版本号
                        //    发布分支(main/master)：正式版本号
                        //    其它分支：一律追加 -SNAPSHOT 后缀，产物为快照版本
                        def baseVersionClean = baseVersion.replaceFirst(/-?SNAPSHOT$/, '')
                        def versionCore = "${baseVersionClean}-${timestamp}-${gitCommit}"
                        // 非发布分支强制快照；发布分支沿用 baseVersion 的显式配置
                        def useSnapshot = !isReleaseBranch || baseVersion.endsWith('SNAPSHOT')
                        env.FULL_VERSION = useSnapshot ? "${versionCore}-SNAPSHOT" : versionCore

                        echo "当前构建分支: ${branch}（发布分支: ${isReleaseBranch}）"
                        echo "基础版本号: ${baseVersion}"
                        echo "当前构建生成的版本号为: ${env.FULL_VERSION}"

                        // 5. 执行 Gradle 发布
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
                    expression { autoTag == true && isReleaseBranch }
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
                when {
                    expression { isReleaseBranch }
                }
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
