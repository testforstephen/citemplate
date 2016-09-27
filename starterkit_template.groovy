import java.net.*
import groovy.json.JsonSlurper

def jsonPayloadJobs = readFileFromWorkspace(CONFIG)
def settings = new JsonSlurper().parseText(jsonPayloadJobs)

def parseRepoName(repoUrl) {
    def parts = repoUrl.split(/[\/\\]/)
    def repoName = parts[-1]
    repoName = repoName.replaceAll(/\./, '_')
    return repoName
}

def createJob(jobName, upstreamJobName, jobSettings, codeBranch, buildSteps) {
    job(jobName) {
        label(jobSettings['labelExpression'])
        logRotator { // Discard old builds
            daysToKeep(14) // If specified, build records are only kept up to this number of days.
            numToKeep(40) // If specified, only up to this number of build records are kept.
            artifactDaysToKeep(14) // If specified, artifacts from builds older than this number of days will be deleted, but the logs, history, reports, etc for the build will be kept.
            artifactNumToKeep(40) // If specified, only up to this number of builds have their artifacts retained.
        }
        if (upstreamJobName) {
            triggers {
                upstream(upstreamJobName, 'SUCCESS')
            }
        }
        scm {
            git {
                remote {
                    url(jobSettings['repoUrl'])
                    credentials(jobSettings['credentialId'])
                }
                branch(codeBranch)
            }
        }
        steps {
            shell(buildSteps.join('\n'))
        }
    }
}

def createOpbuildJob(jobName, upstreamJobName, jobSettings, testBranch) {
    def contentBranch = jobSettings['branchMapping'][testBranch]['contentBranch']
    def opbuildEndpoint = jobSettings['branchMapping'][testBranch]['opbuildEnv']
    job(jobName) {
        label(jobSettings['labelExpression'])
        logRotator { // Discard old builds
            daysToKeep(14) // If specified, build records are only kept up to this number of days.
            numToKeep(40) // If specified, only up to this number of build records are kept.
            artifactDaysToKeep(14) // If specified, artifacts from builds older than this number of days will be deleted, but the logs, history, reports, etc for the build will be kept.
            artifactNumToKeep(40) // If specified, only up to this number of builds have their artifacts retained.
        }
        triggers {
            upstream(upstreamJobName, 'SUCCESS')
        }
        scm {
            git {
                remote {
                    url(jobSettings['repoUrl'])
                    credentials(jobSettings['credentialId'])
                }
                branch('develop')
            }
        }
        steps {
            shell([
                'npm install && npm update',
                "npm run opse2e -- mergeE2eTestData --sourceBranch master --targetBranch ${contentBranch}",
                "npm run opse2e -- publishE2eTestData --buildEndpoint ${opbuildEndpoint} --buildBranch ${contentBranch}"
                ].join('\n'))
        }
    }
}

def createE2eJob(jobName, upstreamJobName, jobSettings, testBranch) {
    def e2eSettings = jobSettings['branchMapping'][testBranch]
    def commonE2eRepoName = parseRepoName(jobSettings['commonE2eRepoUrl'])
    def repoName = parseRepoName(jobSettings['repoUrl'])
    job(jobName) {
        label(jobSettings['labelExpression'])
        logRotator { // Discard old builds
            daysToKeep(14) // If specified, build records are only kept up to this number of days.
            numToKeep(40) // If specified, only up to this number of build records are kept.
            artifactDaysToKeep(14) // If specified, artifacts from builds older than this number of days will be deleted, but the logs, history, reports, etc for the build will be kept.
            artifactNumToKeep(40) // If specified, only up to this number of builds have their artifacts retained.
        }
        triggers {
            upstream(upstreamJobName, 'SUCCESS')
        }
        multiscm {
            git {
                remote {
                    url(jobSettings['commonE2eRepoUrl'])
                    credentials(jobSettings['credentialId'])
                }
                branch(e2eSettings['sourceBranch'])
                extensions {
                    relativeTargetDirectory(commonE2eRepoName)
                }
            }
            git {
                remote {
                    url(jobSettings['repoUrl'])
                    credentials(jobSettings['credentialId'])
                }
                branch(e2eSettings['sourceBranch'])
                extensions {
                    relativeTargetDirectory(repoName)
                }
            }
        }
        steps {
            shell([
                "cd ${commonE2eRepoName}",
                "npm install && npm update",
                "npm run opse2e -- init",
                "npm run opse2e -- protractor --configFile ${e2eSettings['configFile']} --baseUrl ${e2eSettings['baseUrl']} --branchName ${e2eSettings['contentBranch']} --noEscalation",
                "",
                "cd ../${repoName}",
                "npm install && npm update",
                "npm run opse2e -- init",
                "npm run opse2e -- protractor --configFile ${e2eSettings['configFile']} --baseUrl ${e2eSettings['baseUrl']} --branchName ${e2eSettings['contentBranch']} --noEscalation",
                "",
                "npm run opse2e -- reporter --src 'result/e2e/screenshots' --src '../${commonE2eRepoName}/result/e2e/screenshots' --dest 'result/e2e/screenshots'"
                ].join('\n'))
        }
        publishers {
            archiveArtifacts {
                pattern("${repoName}/result/e2e/screenshots/**/*.*") // Archive E2E test results
            }
        }
    }
}

def createMergeJob(jobName, upstreamJobName, jobSettings, codeBranch, mergeTo) {
    def repoName = parseRepoName(jobSettings['repoUrl'])
    job(jobName) {
        label(jobSettings['labelExpression'])
        logRotator { // Discard old builds
            daysToKeep(14) // If specified, build records are only kept up to this number of days.
            numToKeep(40) // If specified, only up to this number of build records are kept.
            artifactDaysToKeep(14) // If specified, artifacts from builds older than this number of days will be deleted, but the logs, history, reports, etc for the build will be kept.
            artifactNumToKeep(40) // If specified, only up to this number of builds have their artifacts retained.
        }
        if (upstreamJobName) {
            triggers {
                upstream(upstreamJobName, 'SUCCESS')
            }
        }
        scm {
            git {
                remote {
                    name(repoName)
                    url(jobSettings['repoUrl'])
                    credentials(jobSettings['credentialId'])
                }
                branch(codeBranch)
                extensions {
                    mergeOptions {
                        branch(mergeTo) // Sets the name of the branch to merge.
                        remote(repoName) // Sets the name of the repository that contains the branch to merge.
                    }
                }
            }
        }
        publishers {
            git {
                pushOnlyIfSuccess()
                pushMerge()
            }
        }
    }
}

def execute(settings) {
    def folderName = "ops_${settings['partnerName']}_template"
    // create & update a folder for this project
    folder(folderName) {
        displayName(folderName)
    }

    def branches = ['develop', 'release', 'hotfix', 'master']
    def mergeMapping = [
        develop: 'release',
        release: 'master',
        hotfix: 'master'
    ]
    def bumpMapping = [
        develop: 'prerelease',
        release: 'minor',
        hotfix: 'patch'
    ]
    branches.each {
        def ciJobName = folderName + '/' + it + '_ci'
        def opbuildJobName = folderName + '/' + it + '_opbuild'
        def e2eJobName = folderName + '/' + it + '_e2e'
        def mergeJobName = folderName + '/' + it + '_merge'
        def versionJobName = folderName + '/' + it + '_bump_version'

        def buildSteps = [
            'npm install',
            'npm update',
            "npm run opst -- deployTheme -B docker_${it}"
        ]
        def bumpVersionSteps = [
            'npm version ' + bumpMapping[it],
            'git push --follow-tags ' + settings['ci']['repoUrl'] + ' HEAD:' + it
        ]
        switch (it) {
            case "develop":
                createJob(ciJobName, null, settings['ci'], it, buildSteps)
                createOpbuildJob(opbuildJobName, it + '_ci', settings['opbuild'], it)
                createE2eJob(e2eJobName, it + '_opbuild', settings['e2e'], it)
                createMergeJob(mergeJobName, it + '_e2e', settings['ci'], it, mergeMapping[it])
                break
            case "release":
                createJob(ciJobName, null, settings['ci'], it, buildSteps)
                createOpbuildJob(opbuildJobName, it + '_ci', settings['opbuild'], it)
                createE2eJob(e2eJobName, it + '_opbuild', settings['e2e'], it)
                createJob(versionJobName, null, settings['ci'], it, bumpVersionSteps) // bump version
                break
            case "hotfix":
                createJob(ciJobName, null, settings['ci'], it, buildSteps)
                createOpbuildJob(opbuildJobName, it + '_ci', settings['opbuild'], it)
                createE2eJob(e2eJobName, it + '_opbuild', settings['e2e'], it)
                createJob(versionJobName, null, settings['ci'], it, bumpVersionSteps) // bump version
                break
            case "master":
                createJob(ciJobName, null, settings['ci'], it, buildSteps)
                // create master_mergeTo_develop & master_mergeTo_hotfix jobs
                createMergeJob(mergeJobName + 'To_develop', null, settings['ci'], it, 'develop')
                createMergeJob(mergeJobName + 'To_hotfix', null, settings['ci'], it, 'hotfix')
                break
        }
    }

    // create & update deliveryPipelineView
    deliveryPipelineView("${folderName}/deliveryPipelineView") {
        pipelineInstances(1)
        columns(1)
        updateInterval(10)
        allowPipelineStart()
        enableManualTriggers()
        allowRebuild()
        showTestResults(true)
        pipelines {
            component('develop_pipeline', 'develop_ci')
            component('release_pipeline', 'release_ci')
            component('hotfix_pipeline', 'hotfix_ci')
            component('master_pipeline', 'master_ci')
            component('Bump hotfix branch package version and add git tag', 'hotfix_bump_version')
            component('Bump release branch package version and add git tag', 'release_bump_version')
        }
    }
}

// Main entry point
execute(settings)
