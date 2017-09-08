package com.sitewhere.tenant.microservice;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sitewhere.microservice.GlobalMicroservice;
import com.sitewhere.spi.SiteWhereException;
import com.sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor;

/**
 * Microservice that provides tenant management functionality.
 * 
 * @author Derek
 */
public class TenantManagement extends GlobalMicroservice {

    /** Static logger instance */
    private static Logger LOGGER = LogManager.getLogger();

    /** Microservice name */
    private static final String NAME = "Tenant Management";

    /** Relative path for storing tenant templates */
    private static final String TEMPLATES_PATH = "/templates";

    /** Relative path for template population lock */
    private static final String TEMPLATE_POPULATION_LOCK_PATH = "/locks/templates";

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.IMicroservice#getName()
     */
    @Override
    public String getName() {
	return NAME;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.microservice.spi.IGlobalMicroservice#microserviceStart(com.
     * sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void microserviceStart(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	populateTemplatesIfNotPresent();
    }

    /**
     * Populate the list of tenant templates. NOTE: This uses Zk locking to make
     * sure that only one instance of tenant management populates the tenant
     * templates.
     * 
     * @throws SiteWhereException
     */
    protected void populateTemplatesIfNotPresent() throws SiteWhereException {
	CuratorFramework curator = getZookeeperConfigurationManager().getCurator();
	InterProcessSemaphoreMutex lock = new InterProcessSemaphoreMutex(curator,
		getInstanceZkPath() + TEMPLATE_POPULATION_LOCK_PATH);
	try {
	    // Acquire the lock.
	    if (lock.acquire(5, TimeUnit.SECONDS)) {
		LOGGER.info("Acquired lock for populating tenant templates.");
		if (curator.checkExists().forPath(getInstanceZkPath() + TEMPLATES_PATH) == null) {
		    LOGGER.info("Templates not present in Zk. Adding node and importing templates.");
		    curator.create().forPath(getInstanceZkPath() + TEMPLATES_PATH);
		    LOGGER.info("Created templates node in Zk.");

		    copyTemplatesToZk();
		} else {
		    LOGGER.info("Templates already present in Zk.");
		}
	    } else {
		LOGGER.error("Failed to acquire lock for populating tenant templates.");
	    }
	} catch (Exception e) {
	    LOGGER.error("Exception while attempting to populate tenant templates.", e);
	} finally {
	    try {
		lock.release();
		LOGGER.info("Released lock for populating tenant templates.");
	    } catch (Exception e) {
		LOGGER.error("Failed to release template population lock.", e);
	    }
	}
    }

    /**
     * Copy templates from the filesystem into Zk.
     * 
     * @throws SiteWhereException
     */
    protected void copyTemplatesToZk() throws SiteWhereException {
	File templates = new File("/opt/templates");
	if (!templates.exists()) {
	    throw new SiteWhereException("Templates folder not found in Docker image.");
	}
	File[] folders = templates.listFiles(File::isDirectory);
	for (File folder : folders) {
	    try {
		copyTemplateFolderToZk(templates, folder);
	    } catch (Exception e) {
		throw new SiteWhereException("Unable to copy template folder.", e);
	    }
	}
    }

    /**
     * Copy a template folder recursively into Zk.
     * 
     * @param templates
     * @param folder
     * @throws SiteWhereException
     */
    protected void copyTemplateFolderToZk(File templates, File folder) throws Exception {
	CuratorFramework curator = getZookeeperConfigurationManager().getCurator();
	Path relative = templates.toPath().relativize(folder.toPath());
	String zkFolder = getInstanceZkPath() + TEMPLATES_PATH + "/" + relative.toString();
	curator.create().forPath(zkFolder);
	File[] contents = folder.listFiles();
	for (File file : contents) {
	    if (file.isDirectory()) {
		copyTemplateFolderToZk(templates, file);
	    } else if (file.isFile()) {
		String zkFile = zkFolder + "/" + file.getName();
		FileInputStream input = new FileInputStream(file);
		try {
		    byte[] data = IOUtils.toByteArray(input);
		    curator.create().forPath(zkFile, data);
		} catch (IOException e) {
		    IOUtils.closeQuietly(input);
		}
	    }
	}
    }
}