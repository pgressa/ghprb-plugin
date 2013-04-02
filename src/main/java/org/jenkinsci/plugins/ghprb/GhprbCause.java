package org.jenkinsci.plugins.ghprb;

import hudson.model.Cause;

/**
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
public class GhprbCause extends Cause{
	private final String commit;
	private final int pullID;
	private final boolean merged;
	private final String targetBranch;
	
	public GhprbCause(String commit, int pullID, String targetBranch){
		this(commit, pullID, false, targetBranch);
	}
	public GhprbCause(String commit, int pullID, boolean merged, String targetBranch){
		this.commit = commit;
		this.pullID = pullID;
		this.merged = merged;
		this.targetBranch = targetBranch;
	}

	@Override
	public String getShortDescription() {
		return "Github pull request #" + pullID + " of commit " + commit + (merged? " automatically merged." : ".");
	}

	public String getCommit() {
		return commit;
	}
	
	public boolean isMerged() {
		return merged;
	}

	public int getPullID(){
		return pullID;
	}
	public String getTargetBranch() {
		return targetBranch;
	}
}
