import java.net.*
import groovy.json.JsonSlurper

def jsonPayloadJobs = readFileFromWorkspace(CONFIG)
def settings = new JsonSlurper().parseText(jsonPayloadJobs)

def parseRepoName(repoUrl) {
    def parts = repoUrl.split(/[\/\\]/)
    def repoName = parts[-1]
    return repoName
}

def createJob(jobName, upstreamJobName, jobSettings, modelbranch) {
    def branchSettings = jobSettings['branchMapping'][modelbranch]
    def sourceBranch = branchSettings['sourceBranch'] ? branchSettings['sourceBranch'] : modelbranch
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
        if (jobSettings['repoUrl'].class == String) {
            scm {
                git {
                    remote {
                        url(jobSettings['repoUrl'])
                        credentials(jobSettings['credentialId'])
                    }
                    branch(sourceBranch)
                }
            }
        } else {
            multiscm {
                jobSettings['repoUrl'].eachWithIndex { repoUrl, index ->
                    git {
                        remote {
                            url(repoUrl)
                            credentials(jobSettings['credentialId'])
                        }
                        if (sourceBranch.class == String) {
                            branch(sourceBranch)
                        } else {
                            branch(sourceBranch[index])
                        }
                        extensions {
                            relativeTargetDirectory(parseRepoName(repoUrl))
                        }
                    }
                }
            }
        }
        steps {
            shell(branchSettings['buildSteps'].join('\n'))
        }
        publishers {
            if (branchSettings['archiveArtifacts']) {
                archiveArtifacts {
                    pattern(branchSettings['archiveArtifacts']) // Archive E2E test results
                }
            }
        }
    }
}

def createMergeJob(jobName, upstreamJobName, jobSettings, sourceBranch, mergeTo) {
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
                branch(sourceBranch)
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
    branches.each {
        def ciJobName = folderName + '/' + it + '_ci'
        def opbuildJobName = folderName + '/' + it + '_opbuild'
        def e2eJobName = folderName + '/' + it + '_e2e'
        def mergeJobName = folderName + '/' + it + '_merge'
        def versionJobName = folderName + '/' + it + '_bump_version'

        switch (it) {
            case "develop":
                // create ci job
                createJob(ciJobName, null, settings['ci'], it)
                // create opbuild job
                createJob(opbuildJobName, it + '_ci', settings['opbuild'], it)
                // create e2e job
                createJob(e2eJobName, it + '_opbuild', settings['e2e'], it)
                // create merge job
                createMergeJob(mergeJobName, it + '_e2e', settings['ci'], it, mergeMapping[it])
                break
            case "release":
                // create ci job
                createJob(ciJobName, null, settings['ci'], it)
                // create opbuild job
                createJob(opbuildJobName, it + '_ci', settings['opbuild'], it)
                // create e2e job
                createJob(e2eJobName, it + '_opbuild', settings['e2e'], it)
                // create bumpVersion job
                if (settings['bumpVersion'] && settings['bumpVersion']['branchMapping'][it]) {
                    createJob(versionJobName, null, settings['bumpVersion'], it)
                }
                break
            case "hotfix":
                // create ci job
                createJob(ciJobName, null, settings['ci'], it)
                // create opbuild job
                createJob(opbuildJobName, it + '_ci', settings['opbuild'], it)
                // create e2e job
                createJob(e2eJobName, it + '_opbuild', settings['e2e'], it)
                // create bumpVersion job
                if (settings['bumpVersion'] && settings['bumpVersion']['branchMapping'][it]) {
                    createJob(versionJobName, null, settings['bumpVersion'], it)
                }
                break
            case "master":
                // create ci job
                createJob(ciJobName, null, settings['ci'], it)
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
