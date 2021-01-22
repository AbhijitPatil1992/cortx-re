#!/usr/bin/env groovy
pipeline { 
    agent {
        node {
            label 'docker-cp-centos-7.8.2003-node'
        }
    }
	
    parameters {
        string(name: 'branch', defaultValue: 'main', description: 'Branch Name')
    }

	environment {
        version = "2.0.0"
        env = "dev"
		component = "provisioner"
        os_version = "centos-7.8.2003"
        pipeline_group = "main"
        release_dir = "/mnt/bigstorage/releases/cortx"
        build_upload_dir = "${release_dir}/components/github/${pipeline_group}/${os_version}/${env}/${component}"

        // Param hack for initial config
        branch = "${branch != null ? branch : 'main'}"
    }

    options {
        timeout(time: 120, unit: 'MINUTES')
        timestamps()
        ansiColor('xterm') 
        disableConcurrentBuilds()
    }

	triggers {
        pollSCM 'H/3 * * * *'
    }
    
    stages {

        stage('Checkout') {
            steps {
				script { build_stage = env.STAGE_NAME }
					checkout([$class: 'GitSCM', branches: [[name: "*/${branch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'AuthorInChangelog']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'cortx-admin-github', url: 'https://github.com/Seagate/cortx-prvsnr.git']]])
            }
        }

        stage('Install Dependencies') {
            steps {
				script { build_stage = env.STAGE_NAME }
                sh encoding: 'utf-8', label: 'Install Python', returnStdout: true, script: 'yum install -y python'
                sh encoding: 'utf-8', label: 'Cleanup', returnStdout: true, script: 'test -d /root/rpmbuild && rm -rf /root/rpmbuild || echo "/root/rpmbuild absent. Skipping cleanup..."'
            }
        }

        stage('Build') {
            steps {
				script { build_stage = env.STAGE_NAME }
                sh encoding: 'utf-8', label: 'Provisioner RPMS', returnStdout: true, script: """
                    sh ./devops/rpms/buildrpm.sh -g \$(git rev-parse --short HEAD) -e $version -b ${BUILD_NUMBER}
                """
                sh encoding: 'utf-8', label: 'Provisioner CLI RPMS', returnStdout: true, script: """
				    sh ./cli/buildrpm.sh -g \$(git rev-parse --short HEAD) -e $version -b ${BUILD_NUMBER}
                """
								
				sh encoding: 'UTF-8', label: 'api', script: '''
					bash ./devops/rpms/api/build_python_api.sh -vv --out-dir /root/rpmbuild/RPMS/x86_64/ --pkg-ver ${BUILD_NUMBER}_git$(git rev-parse --short HEAD)
				'''
				
            }
        }

		stage ('Upload') {
			steps {
				script { build_stage = env.STAGE_NAME }
				sh label: 'Copy RPMS', script: '''
                    mkdir -p $build_upload_dir/$BUILD_NUMBER
                    cp /root/rpmbuild/RPMS/x86_64/*.rpm $build_upload_dir/$BUILD_NUMBER
				'''
                sh label: 'Repo Creation', script: '''pushd $build_upload_dir/$BUILD_NUMBER
                    rpm -qi createrepo || yum install -y createrepo
                    createrepo .
                    popd
				'''

			}
		}
			
		stage ('Tag last_successful') {
			steps {
				script { build_stage = env.STAGE_NAME }
				sh label: 'Tag last_successful', script: '''pushd $build_upload_dir/
                    test -d $build_upload_dir/last_successful && rm -f last_successful
                    ln -s $build_upload_dir/$BUILD_NUMBER last_successful
                    popd
				'''
			}
		}

        stage ("Release") {
            steps {
                script { build_stage = env.STAGE_NAME }
				script {
                	def releaseBuild = build job: 'Main Release', propagate: true, parameters: [string(name: 'release_component', value: "${component}"), string(name: 'release_build', value: "${BUILD_NUMBER}")]
				 	env.release_build = "${BUILD_NUMBER}"
                    env.release_build_location = "http://cortx-storage.colo.seagate.com/releases/cortx/github/$pipeline_group/$os_version/${component}_${BUILD_NUMBER}"
				}
            }
        }
        
        stage ("Deploy VM") {
            when { expression { false  } }
            steps {
                script { build_stage = env.STAGE_NAME }
				script {
				    
                    try {
                        def deployVM = build job: 'Sanity_Testing_VM', propagate: false, parameters: [string(name: 'BUILD', value: "${component}_${BUILD_NUMBER}")]
                        env.deployVMStatus = deployVM.result
                        env.deployVMURL = deployVM.absoluteUrl
                    } catch (err) {
                        echo "Error detected in Deploy VM, but we will continue."
                    }	
				}
            }
        }
	}

	post {
		always {
			script {
            	
				echo 'Cleanup Workspace.'
				deleteDir() /* clean up our workspace */

				env.release_build = (env.release_build != null) ? env.release_build : "" 
				env.release_build_location = (env.release_build_location != null) ? env.release_build_location : ""
				env.component = (env.component).toUpperCase()
				env.build_stage = "${build_stage}"
				env.vm_deployment = (env.deployVMURL != null) ? env.deployVMURL : "" 

                if (env.deployVMStatus != "SUCCESS" && manager.build.result.toString() != "FAILURE") {
                    manager.buildUnstable()
                }
                
				def toEmail = ""
				def recipientProvidersClass = [[$class: 'DevelopersRecipientProvider']]
				if ( manager.build.result.toString() != "SUCCESS") {
					toEmail = ""
					recipientProvidersClass = [[$class: 'DevelopersRecipientProvider'],[$class: 'RequesterRecipientProvider']]
				}
				emailext (
					body: '''${SCRIPT, template="component-email-dev.template"}''',
					mimeType: 'text/html',
					subject: "[Jenkins Build ${currentBuild.currentResult}] : ${env.JOB_NAME}",
					attachLog: true,
					to: toEmail,
                    recipientProviders: recipientProvidersClass
				)
			}
		}
    }
}