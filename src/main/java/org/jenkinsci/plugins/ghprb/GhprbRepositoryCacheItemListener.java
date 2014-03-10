package org.jenkinsci.plugins.ghprb;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;
import jenkins.model.Jenkins;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Listener which keeps the {@link org.jenkinsci.plugins.ghprb.GhprbRepositoryCache} updated. When Jenkins is up
 * and running the {@link GhprbRepositoryCacheItemListener#onLoaded()} initializes the cache and every time when
 * the project has been created or the configuration updated then the cache is actualised.
 *
 * @author Pavol Gressa <pavol.gressa@gooddata.com>
 */
@Extension
public class GhprbRepositoryCacheItemListener extends ItemListener {

	private static final Logger logger = Logger.getLogger(GhprbRepositoryCacheItemListener.class.getName());

	@Override
	public void onLoaded() {
		// initialise the cache
		logger.log(Level.INFO, "Initialize GHPRB repository cache");
		for(AbstractProject<?,?> job : Jenkins.getInstance().getAllItems(AbstractProject.class)){
			processChange(job);
		}
	}

	@Override
	public void onUpdated(Item item) {
		processChange(item);
	}

	@Override
	public void onCreated(Item item) {
		processChange(item);
	}

	@Override
	public void onDeleted(Item item) {
		if (item instanceof AbstractProject) {
			AbstractProject<?, ?> project = (AbstractProject) item;
			logger.log(Level.INFO, "Removing {0} from GHPRB repository callbacks.", project.getName());
			if(GhprbRepositoryCache.get().containsGhprRepository(project)){
				GhprbRepositoryCache.get().removeProject(project);
			}
		}
	}

	@Override
	public void onRenamed(Item item, String oldName, String newName) {
		if (item instanceof AbstractProject) {
			AbstractProject<?, ?> project = (AbstractProject) item;
			logger.log(Level.INFO, "Renamed {0} to {1}. Processing the change.", new Object[]{oldName,newName});
			// we have to remove old callback and register new one
			if(GhprbRepositoryCache.get().containsGhprRepository(project)){
				GhprbRepositoryCache.get().removeProject(project,oldName);
				GhprbRepositoryCache.get().putProject(project);
			}

		}
	}

	private void processChange(Item item) {
		if (item instanceof AbstractProject) {
			AbstractProject<?, ?> project = (AbstractProject) item;
			logger.log(Level.INFO, "Processing {0} for GHPRB repository change.", project.getName());
			if(GhprbRepositoryCache.get().containsGhprRepository(project)){
				GhprbRepositoryCache.get().putProject(project);
			}
		}
	}
}
