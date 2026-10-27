// Create (or refresh) the pipeline job for this checkout on first boot.
// Local lab only: guarded so a missing plugin never prevents Jenkins starting.
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitSCM
import hudson.plugins.git.UserRemoteConfig
import hudson.model.ParametersDefinitionProperty
import hudson.model.StringParameterDefinition
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition

def env = System.getenv()
def repo = env.JENKINS_REPO_URL ?: 'https://github.com/An619/bookstore-3tier.git'
def branch = env.JENKINS_BRANCH ?: 'main'
def jobName = env.JENKINS_JOB_NAME ?: 'bookstore-pipeline'

try {
    def jenkins = Jenkins.get()
    def job = jenkins.getItem(jobName)
    if (job == null) {
        job = jenkins.createProject(WorkflowJob, jobName)
    }
    def scm = new GitSCM([new UserRemoteConfig(repo, null, null, null)],
                         [new BranchSpec("*/${branch}")], false, [], null, null, [])
    def definition = new CpsScmFlowDefinition(scm, 'Jenkinsfile')
    definition.setLightweight(true)
    job.setDefinition(definition)
    job.setDescription("Built by the local lab; branch ${branch} of ${repo}")
    job.addProperty(new ParametersDefinitionProperty(
        new StringParameterDefinition('SONARQUBE_ENABLED', 'true', 'Fail the build when the Sonar quality gate fails'),
        new StringParameterDefinition('PUBLISH_IMAGES', 'false', 'Push images to Docker Hub'),
        new StringParameterDefinition('PUBLISH_TO_ECR', 'false', 'Push images to AWS ECR'),
        new StringParameterDefinition('RUN_TRIVY', 'false', 'Run container scanning')
    ))
    job.save()
    println "Configured pipeline job '${jobName}' for ${repo} (${branch})"
} catch (Throwable error) {
    println "WARNING: could not configure pipeline job: ${error}"
}
