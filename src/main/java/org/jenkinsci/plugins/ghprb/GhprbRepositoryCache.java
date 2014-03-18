package org.jenkinsci.plugins.ghprb;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import hudson.model.AbstractProject;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Singleton cache which caches GhprbRepository and provides thread-safe access.
 *
 * We have to keep the project name for further pruning because actual setup of relations forbids us
 * to effectively maintain content. The relation is: Project -> Trigger -> Repository but not backwards.
 *
 * @author Pavol Gressa <pavol.gressa@gooddata.com>
 */
public class GhprbRepositoryCache {

	private static final Logger logger = Logger.getLogger(GhprbRepositoryCache.class.getName());
	private static GhprbRepositoryCache cache = new GhprbRepositoryCache();

	public static GhprbRepositoryCache get(){
		return cache;
	}
	// repo-name : ( project name : ghprb-repo )
	private final Map<String,Map<String,GhprbRepository>> repoCache = Maps.newHashMap();

	public boolean containsGhprRepository(AbstractProject project){
		GhprbTrigger trigger = (GhprbTrigger) project.getTrigger(GhprbTrigger.class);
		return trigger != null && trigger.getGhprb() != null;
	}

	public synchronized void putProject(AbstractProject project){
		// Resolve cache structure
		GhprbRepository repository = getRepositoryProject(project);
		if (repository == null){
			logger.log(Level.SEVERE, "Project: {0} doesn't contain the GhprbTrigger - GitHub Pull request builder is not enabled!",project.getName());
			return;
		}

		Map<String,GhprbRepository> repositories = repoCache.get(repository.getName());
		if(repositories == null){
			// create repository projects cache
			repositories = Maps.newHashMap();
			repoCache.put(repository.getName(), repositories);
		}else{
			// or remove for project for update
			if(repositories.containsKey(project.getName()))
				remove(repository.getName(),project.getName());
		}

		// update
		logger.log(Level.INFO,"Register project: {0} for callback from: {1} repo", new Object[]{project.getName(),repository.getName()});
		repositories.put(project.getName(), repository);
	}

	public synchronized Set<GhprbRepository> getRepoSet(String repoName){
		Set<GhprbRepository> set = null;
		if(repoCache.containsKey(repoName)){
			Map<String,GhprbRepository> map = repoCache.get(repoName);
			set = ImmutableSet.copyOf(map.values());
		}else{
			set = Collections.<GhprbRepository>emptySet();
		}
		logger.log(Level.INFO,"For {0} {1} has been found",new Object[]{repoName,set.size()});
		return set;
	}

	public synchronized void removeProject(AbstractProject project, String jobName){
		// Resolve cache structure
		GhprbRepository repository = getRepositoryProject(project);
		if (repository != null){
			remove(repository.getName(),jobName);
		}
	}

	public synchronized void removeProject(AbstractProject project){
		removeProject(project,project.getName());
	}

	private void remove(String repoName, String projectName){
		// Load cache for particular
		Map<String,GhprbRepository> projects = repoCache.get(repoName);
		if(projects != null){
			logger.log(Level.INFO,"From {0} remove callback for {1}",new Object[]{repoName,projectName});
			projects.remove(projectName);
		}
	}

	private GhprbRepository getRepositoryProject(AbstractProject project){
		// check for further processing
		GhprbTrigger trigger = (GhprbTrigger) project.getTrigger(GhprbTrigger.class);
		if (trigger == null || trigger.getGhprb() == null){
			return null;
		}
		return trigger.getGhprb().getRepository();
	}
}
