package org.jenkinsci.plugins.ghprb;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.AbstractProject;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterValue;
import hudson.model.queue.QueueTaskFuture;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.triggers.TimerTrigger;
import hudson.util.FormValidation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import antlr.ANTLRException;

import com.coravy.hudson.plugins.github.GithubProjectProperty;

/**
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
public final class GhprbTrigger extends Trigger<AbstractProject<?, ?>> {
	public final String cron;

	transient private GhprbRepo                      repository;
	transient private Map<Integer,GhprbPullRequest>  pulls;
	transient         boolean                        changed;

	private static final Pattern githubUserRepoPattern = Pattern.compile("^(http[s]?://[^/]*)/([^/]*)/([^/]*).*");

	@DataBoundConstructor
	public GhprbTrigger(String cron) throws ANTLRException{
		super(cron);
		this.cron = cron;
	}

	@Override
	public void start(AbstractProject<?, ?> project, boolean newInstance) {
		String projectName = project.getFullName();

		pulls = DESCRIPTOR.getPullRequests(projectName);

		GithubProjectProperty ghpp = project.getProperty(GithubProjectProperty.class);
		if(ghpp == null || ghpp.getProjectUrl() == null) {
			Logger.getLogger(GhprbTrigger.class.getName()).log(Level.WARNING, "A github project url is required.");
			return;
		}

		Matcher m = githubUserRepoPattern.matcher(ghpp.getProjectUrl().baseUrl());
		if(!m.matches()) {
			Logger.getLogger(GhprbTrigger.class.getName()).log(Level.WARNING, "Invalid github project url: {0}", ghpp.getProjectUrl().baseUrl());
			return;
		}
		String githubServer = m.group(1);
		String user = m.group(2);
		String repo = m.group(3);
		repository = new GhprbRepo(this, githubServer, user, repo);

		super.start(project, newInstance);
	}

	@Override
	public void stop() {
		repository = null;
		pulls = null;
		super.stop();
	}

	public QueueTaskFuture<?> startJob(GhprbCause cause){
		ArrayList<ParameterValue> values = getDefaultParameters();
		if(cause.isMerged()){
			values.add(new StringParameterValue("sha1","origin/pr/" + cause.getPullID() + "/merge"));
		}else{
			values.add(new StringParameterValue("sha1",cause.getCommit()));
		}
		values.add(new StringParameterValue("ghprbActualCommit",cause.getCommit()));
		values.add(new StringParameterValue("base_branch",cause.getTargetBranch()));
		values.add(new StringParameterValue("pull_id",String.valueOf(cause.getPullID())));

		return this.job.scheduleBuild2(0,cause,new ParametersAction(values));
	}

	private ArrayList<ParameterValue> getDefaultParameters() {
		ArrayList<ParameterValue> values = new ArrayList<ParameterValue>();
		ParametersDefinitionProperty pdp = this.job.getProperty(ParametersDefinitionProperty.class);
		if (pdp != null) {
			for(ParameterDefinition pd :  pdp.getParameterDefinitions()) {
				if (pd.getName().equals("sha1"))
					continue;
				values.add(pd.getDefaultParameterValue());
			}
		}
		return values;
	}

	@Override
	public void run() {
		changed = false;
		repository.check(pulls);
		if(changed) try {
			this.job.save();
		} catch (IOException ex) {
			Logger.getLogger(GhprbTrigger.class.getName()).log(Level.SEVERE, null, ex);
		}
		DESCRIPTOR.save();
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return DESCRIPTOR;
	}

	@Extension
	public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

	public static final class DescriptorImpl extends TriggerDescriptor{
		//private String serverAPIUrl = "https://api.github.com";
		private String username;
		private String password;
		private String publishedURL;
		private String okToTestPhrase = ".*ok\\W+to\\W+test.*";
		private String cron = "*/5 * * * *";
		private Boolean useComments = false;

		// map of jobs (by their fullName) abd their map of pull requests
		private Map<String, Map<Integer,GhprbPullRequest>> jobs;

		public DescriptorImpl(){
			load();
			if(jobs == null){
				jobs = new HashMap<String, Map<Integer,GhprbPullRequest>>();
			}
		}

		@Override
		public boolean isApplicable(Item item) {
			return item instanceof AbstractProject;
		}

		@Override
		public String getDisplayName() {
			return "Github pull requests builder";
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
			//serverAPIUrl = formData.getString("serverAPIUrl");
			username = formData.getString("username");
			password = formData.getString("password");
			publishedURL = formData.getString("publishedURL");

			okToTestPhrase = formData.getString("okToTestPhrase");
			cron = formData.getString("cron");
			useComments = formData.getBoolean("useComments");
			save();
			return super.configure(req,formData);
		}

		public FormValidation doCheckCron(@QueryParameter String value){
			return (new TimerTrigger.DescriptorImpl().doCheckSpec(value));
		}

//		public FormValidation doCheckServerAPIUrl(@QueryParameter String value){
//			if("https://api.github.com".equals(value)) return FormValidation.ok();
//			if(value.endsWith("/api/v3")) return FormValidation.ok();
//			return FormValidation.warning("Github api url is \"https://api.github.com\". Github enterprise api url ends with \"/api/v3\"");
//		}

		public String getUsername() {
			return username;
		}

		public String getPassword() {
			return password;
		}

		public String getPublishedURL() {
			return publishedURL;
		}

		public String getOkToTestPhrase() {
			return okToTestPhrase;
		}

		public String getCron() {
			return cron;
		}

		public Boolean getUseComments() {
			return useComments;
		}

//		public String getServerAPIUrl() {
//			return serverAPIUrl;
//		}

		private Map<Integer, GhprbPullRequest> getPullRequests(String projectName) {
			Map<Integer, GhprbPullRequest> ret;
			if(jobs.containsKey(projectName)){
				 ret = jobs.get(projectName);
			}else{
				ret = new HashMap<Integer, GhprbPullRequest>();
				jobs.put(projectName, ret);
			}
			return ret;
		}
	}
}
