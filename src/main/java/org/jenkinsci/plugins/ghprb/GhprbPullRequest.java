package org.jenkinsci.plugins.ghprb;

import java.io.IOException;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHPullRequest;

/**
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
public class GhprbPullRequest{
	private final int id;
	private final String author;
	private Date updated;
	private String head;
	private boolean mergeable;
	private String targetBranch = "master";

	private boolean shouldRun = false;
	private boolean accepted = false;

	private transient GhprbRepo repo;

	GhprbPullRequest(GHPullRequest pr, GhprbRepo ghprbRepo) {
		id = pr.getNumber();
		updated = pr.getUpdatedAt();
		head = pr.getHead().getSha();
		author = pr.getUser().getLogin();

		repo = ghprbRepo;
		accepted = true;
		shouldRun = true;
		Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.INFO, "Created pull request #{0} on {1} by {2} updated at: {3} SHA: {4}", new Object[]{id, ghprbRepo.getName(), author, updated, head});
	}

	public void check(GHPullRequest pr, GhprbRepo ghprbRepo){
		repo = ghprbRepo;
		if(isUpdated(pr)){
			Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.INFO, "Pull request builder: pr #{0} was updated on {1} at {2}", new Object[]{id, ghprbRepo.getName(), updated});

			int commentsChecked = checkComments(pr);
			boolean newCommit   = checkCommit(pr.getHead().getSha());

			if(!newCommit && commentsChecked == 0){
				Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.INFO, "Pull request was updated on repo " + ghprbRepo.getName() + " but there aren't any new comments nor commits - that may mean that commit status was updated.");
			}
			updated = pr.getUpdatedAt();
		}

		if(shouldRun){
			checkMergeable(pr);
			build();
		}
	}

	private boolean isUpdated(GHPullRequest pr){
		boolean ret = false;
		ret = ret || updated.compareTo(pr.getUpdatedAt()) < 0;
		ret = ret || !pr.getHead().getSha().equals(head);

		return ret;
	}

	private void build() {
		shouldRun = false;

		StringBuilder sb = new StringBuilder();
		if(repo.cancelBuild(id)){
			sb.append("Previous build stopped.");
		}

		if(mergeable){
			sb.append(" Merged build triggered.");
		}else{
			sb.append(" Build triggered.");
		}

		repo.startJob(id,head, mergeable,targetBranch);
		repo.createCommitStatus(head, GHCommitState.PENDING, null, sb.toString(),id);

		Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.INFO, sb.toString());
	}

	// returns false if no new commit
	private boolean checkCommit(String sha){
		if(head.equals(sha)) return false;

		if(Logger.getLogger(GhprbPullRequest.class.getName()).isLoggable(Level.FINE)){
			Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.FINE, "New commit. Sha: " + head + " => " + sha);
		}

		head = sha;
		if(accepted){
			shouldRun = true;
		}
		return true;
	}

	private void checkComment(GHIssueComment comment) throws IOException {
		String sender = comment.getUser().getLogin();
		if (repo.isMe(sender)){
			return;
		}
		String body = comment.getBody();


		// ok to test
		if(repo.isOktotestPhrase(body)){
			accepted = true;
			shouldRun = true;
		}

	}

	private int checkComments(GHPullRequest pr) {
		int count = 0;
		try {
			for (GHIssueComment comment : pr.getComments()) {
				if (updated.compareTo(comment.getUpdatedAt()) < 0) {
					count++;
					try {
						checkComment(comment);
					} catch (IOException ex) {
						Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.SEVERE, "Couldn't check comment #" + comment.getId(), ex);
					}
				}
			}
		} catch (IOException e) {
			Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.SEVERE, "Couldn't obtain comments.", e);
		}
		return count;
	}

	private void checkMergeable(GHPullRequest pr) {
		targetBranch = pr.getBase().getRef();
		Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.INFO, "Merge targetBranch: ".concat(targetBranch));
		try {
			mergeable = pr.getMergeable();
		} catch (IOException e) {
			mergeable = false;
			Logger.getLogger(GhprbPullRequest.class.getName()).log(Level.SEVERE, "Couldn't obtain mergeable status.", e);
		}
	}

	@Override
	public boolean equals(Object obj) {
		if(!(obj instanceof GhprbPullRequest)) return false;

		GhprbPullRequest o = (GhprbPullRequest) obj;
		return o.id == id;
	}

	@Override
	public int hashCode() {
		int hash = 7;
		hash = 89 * hash + this.id;
		return hash;
	}
}
