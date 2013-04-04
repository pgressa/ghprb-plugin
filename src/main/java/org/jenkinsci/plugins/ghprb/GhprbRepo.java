package org.jenkinsci.plugins.ghprb;

import hudson.model.AbstractBuild;
import hudson.model.queue.QueueTaskFuture;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

/**
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
public class GhprbRepo {
	private final GhprbTrigger trigger;
	private final Pattern oktotestPhrasePattern;
	private final String reponame;
	private final String githubServer;

	private final HashSet<GhprbBuild> builds;

	private GitHub gh;
	private GHRepository repo;

	public GhprbRepo(GhprbTrigger trigger, String githubServer, String user, String repository){
		this.trigger = trigger;
		this.githubServer = githubServer;
		reponame = user + "/" + repository;

		gh = connect();

		oktotestPhrasePattern = Pattern.compile(trigger.getDescriptor().getOkToTestPhrase());

		builds = new HashSet<GhprbBuild>();
	}

	private GitHub connect(){
		GitHub gitHub = null;
		gitHub = GitHub.connect(trigger.getDescriptor().getUsername(), null, trigger.getDescriptor().getPassword());

		return gitHub;
	}

	private boolean checkState(){
		if(gh == null){
			gh = connect();
		}
		if(gh == null) return false;
		if(repo == null){
			try {
				repo = gh.getRepository(reponame);
			} catch (IOException ex) {
				Logger.getLogger(GhprbRepo.class.getName()).log(Level.SEVERE, "Could not retrieve repo named " + reponame + " (Do you have properly set 'GitHub project' field in job configuration?)", ex);
			}
		}
		if(repo == null){
			Logger.getLogger(GhprbRepo.class.getName()).log(Level.SEVERE, "Could not retrieve repo named {0} (Do you have properly set 'GitHub project' field in job configuration?)", reponame);
			return false;
		}
		return true;
	}

	public void check(Map<Integer,GhprbPullRequest> pulls){
		if(!checkState()) return;

		List<GHPullRequest> prs;
		try {
			prs = repo.getPullRequests(GHIssueState.OPEN);
		} catch (IOException ex) {
			Logger.getLogger(GhprbRepo.class.getName()).log(Level.SEVERE, "Could not retrieve pull requests.", ex);
			return;
		}
		Set<Integer> closedPulls = new HashSet<Integer>(pulls.keySet());

		for(GHPullRequest pr : prs){
			Integer id = pr.getNumber();
			GhprbPullRequest pull;
			if(pulls.containsKey(id)){
				pull = pulls.get(id);
			}else{
				pull = new GhprbPullRequest(pr, this);
				pulls.put(id, pull);
			}
			pull.check(pr,this);
			closedPulls.remove(id);
		}

		removeClosed(closedPulls, pulls);
		checkBuilds();
	}

	private void removeClosed(Set<Integer> closedPulls, Map<Integer,GhprbPullRequest> pulls) {
		if(closedPulls.isEmpty()) return;

		for(Integer id : closedPulls){
			pulls.remove(id);
		}
	}

	private void checkBuilds(){
		Iterator<GhprbBuild> it = builds.iterator();
		while(it.hasNext()){
			GhprbBuild build = it.next();
			build.check();
			if(build.isFinished()){
				it.remove();
			}
		}
	}

	public void createCommitStatus(AbstractBuild<?,?> build, GHCommitState state, String message, int id){
		String sha1 = build.getCause(GhprbCause.class).getCommit();
		createCommitStatus(sha1, state, Jenkins.getInstance().getRootUrl() + build.getUrl(), message, id);
	}

	public void createCommitStatus(String sha1, GHCommitState state, String url, String message, int id) {
		Logger.getLogger(GhprbRepo.class.getName()).log(Level.INFO, "Setting status of {0} to {1} with url {2} and message: {3}", new Object[]{sha1, state, url, message});
		try {
			repo.createCommitStatus(sha1, state, url, message);
		} catch (IOException ex) {
			if(trigger.getDescriptor().getUseComments()){
				Logger.getLogger(GhprbRepo.class.getName()).log(Level.INFO, "Could not update commit status of the Pull Request on Github. Trying to send comment.", ex);
				addComment(id, message);
			}else{
				Logger.getLogger(GhprbRepo.class.getName()).log(Level.SEVERE, "Could not update commit status of the Pull Request on Github.", ex);
			}
		}
	}

	public boolean cancelBuild(int id) {
		Iterator<GhprbBuild> it = builds.iterator();
		while(it.hasNext()){
			GhprbBuild build  = it.next();
			if (build.getPullID() == id) {
				if (build.cancel()) {
					it.remove();
					return true;
				}
			}
		}
		return false;
	}

	public String getName() {
		return reponame;
	}

	public boolean isOktotestPhrase(String comment){
		return oktotestPhrasePattern.matcher(comment).matches();
	}

	public void addComment(int id, String comment) {
		try {
			repo.getPullRequest(id).comment(comment);
		} catch (IOException ex) {
			Logger.getLogger(GhprbRepo.class.getName()).log(Level.SEVERE, "Couldn't add comment to pullrequest #" + id + ": '" + comment + "'", ex);
		}
	}

	public boolean isMe(String username){
		return trigger.getDescriptor().getUsername().equals(username);
	}

	public void startJob(int id, String commit, boolean merged, String targetBranch){
		QueueTaskFuture<?> build = trigger.startJob(new GhprbCause(commit, id, merged, targetBranch));
		if(build == null){
			Logger.getLogger(GhprbRepo.class.getName()).log(Level.SEVERE, "Job didn't started");
			return;
		}
		builds.add(new GhprbBuild(this, id, build, true));
	}

	public String getRepoUrl(){
		return githubServer+"/"+reponame;
	}
}
