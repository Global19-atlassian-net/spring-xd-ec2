/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.xd.ec2;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Component;
import org.springframework.xd.cloud.Deployer;
import org.springframework.xd.cloud.Deployment;
import org.springframework.xd.cloud.InstanceSize;
import org.springframework.xd.cloud.InstanceType;
import org.springframework.xd.cloud.InvalidXDZipUrlException;
import org.springframework.xd.ec2.cloud.AWSDeployer;

/**
 * The Retrieves he user setting and kicks off the provisioning and deployment process.
 * 
 * @author glenn renfro
 * 
 */
@Component
public class Ec2Installer {

	/**
	 * @param args
	 */
	private final static Logger LOGGER = LoggerFactory
			.getLogger(Ec2Installer.class);

	public final static String HIGHLIGHT = "************************************************************************";


	@Autowired
	private Banner banner;

	private Deployer deployer;

	public static final String[] REQUIRED_ENTRIES = { "cluster-name",
		"aws-access-key", "aws-secret-key", "private-key-file",
		"user-name", "region", "machine-size", "security-group",
		"public-key-name", "ami", "multi-node" };

	/**
	 * Displays the banner verifies that the configuration is valid and kicks off the deployment.
	 * Removes old artifacts if present.
	 */
	public void install() {
		try {
			banner.print("banner.txt");
			final Properties properties = getProperties();
			validateConfiguration(properties);
			removeArtifacts();
			deployer = new AWSDeployer(properties);
			final List<Deployment> result = deployer.deploy();
			LOGGER.info("\n\n" + HIGHLIGHT);
			LOGGER.info("*Installation Complete                                                 *");
			LOGGER.info("*The following Servers have been deployed to your XD Cluster           *");
			LOGGER.info(HIGHLIGHT);
			generateArtifacts(result, properties);
			LOGGER.info(HIGHLIGHT);
		}
		catch (InvalidXDZipUrlException zipException) {
			LOGGER.error(zipException.getMessage());
		}
		catch (IllegalArgumentException iae) {
			LOGGER.info(HIGHLIGHT);
			LOGGER.error("An IllegalArgumentException has been thrown with the following message: \n"
					+ iae.getMessage());
			LOGGER.error("\nMake sure you updated the config/xd-ec2.properties");
			LOGGER.info(HIGHLIGHT);
			LOGGER.info(iae.getMessage(), iae);
		}
	}

	/** 
	 * If artifacts from a previoius run are still present, this method deletes them.
	 */
	private void removeArtifacts() {
		File file = new File("ec2servers.csv");
		if (!file.exists()) {
			return;
		}
		if (file.delete()) {
			LOGGER.debug(file.getName() + " is deleted!");
		}
		else {
			LOGGER.debug(file.getName() + " was failed to be deleted!");
		}
	}

	/**
	 * Creates the artifact file that will be used by other processes in a CI build.
	 * @param deployment A list of the deployed servers.
	 * @param properties The properties used to provision the resources and deploy the XD cluster.
	 */
	private void generateArtifacts(List<Deployment> deployment, Properties properties) {
		try {

			File file = new File("ec2servers.csv");

			file.createNewFile();

			FileWriter fw = new FileWriter(file.getAbsoluteFile());
			BufferedWriter bw = new BufferedWriter(fw);
			String port = properties.getProperty("PORT");
			String jmxPort = properties.getProperty("management.port");
			for (final Deployment instance : deployment) {
				if (instance.getType() == InstanceType.SINGLE_NODE) {
					LOGGER.info(String.format(
							"Single Node Instance: %s has been created",
							instance.getAddress().getHostName()));
					bw.write("singleNode," + instance.getAddress().getHostName() + ","
							+ properties.getProperty("server.port") + "," + port + "," + jmxPort + "\n");
				}
				if (instance.getType() == InstanceType.ADMIN) {
					LOGGER.info(String.format(
							">Admin Node Instance: %s has been created",
							instance.getAddress().getHostName()));
					bw.write("adminNode," + instance.getAddress().getHostName() + ","
							+ properties.getProperty("server.port") + "," + port + "," + jmxPort + "\n");

				}
				if (instance.getType() == InstanceType.NODE) {
					LOGGER.info(String.format(
							">>Container Node Instance: %s has been created",
							instance.getAddress().getHostName()));
					bw.write("containerNode," + instance.getAddress().getHostName() + ","
							+ properties.getProperty("server.port") + "," + port + "," + jmxPort + "\n");

				}
			}
			bw.close();

			LOGGER.info("Done");

		}
		catch (IOException e) {
			LOGGER.error(e.getMessage(), e);
		}
	}

	/** 
	 * Retrieves the properties from property file or the environment.
	 * @return a properties object with the configuration to provision resources and deploy the application.
	 */
	private Properties getProperties() {
		Resource resource = new ClassPathResource("xd-ec2.properties");
		Properties props = null;
		try {
			props = PropertiesLoaderUtils.loadProperties(resource);
			Iterator<Object> iter = props.keySet().iterator();
			while (iter.hasNext()) {
				String key = (String) iter.next();
				props.setProperty(key, getAWSProperty(props, key));
			}

		}
		catch (IOException ioe) {
			LOGGER.error("Failed to open xd-ec2.properties file because: "
					+ ioe.getMessage());
			throw new IllegalStateException(ioe.getMessage(), ioe);
		}
		return props;
	}

	private String getAWSProperty(Properties props, String propKey) {
		Properties systemProperties = System.getProperties();

		if (systemProperties.containsKey(propKey) && !systemProperties.getProperty(propKey).equals("")) {
			return systemProperties.getProperty(propKey);
		}
		if (props.containsKey(propKey) && !props.getProperty(propKey).equals("")) {
			return props.getProperty(propKey);
		}

		return "";
	}

	/**
	 * Verifies that all properties that the application needs are setup
	 * properly.
	 */
	private Properties validateConfiguration(Properties props) {
		ArrayList<String> errorList = new ArrayList<String>();
		String value;
		for (int i = 0; i < REQUIRED_ENTRIES.length; i++) {
			value = props.getProperty(REQUIRED_ENTRIES[i]);
			if (value == null || value.length() == 0) {
				errorList.add(REQUIRED_ENTRIES[i]);
			}
		}
		if (errorList.size() > 0) {
			String errorMessage = "The following entries are not configured in your xd-ec2.properties:\n";
			errorMessage += errorList.get(0);
			for (int i = 1; i < errorList.size(); i++) {
				errorMessage += ",\n" + errorList.get(i);
			}
			throw new IllegalArgumentException(errorMessage);
		}

		if (!verifyMachineSize(props.getProperty("machine-size"))) {
			throw new IllegalArgumentException(
					"Invalid machine size specified.  Valid values are small, medium, large");
		}
		if (Boolean.parseBoolean(props.getProperty("multi-node"))) {
			Integer.getInteger(props.getProperty("number-nodes"));
		}
		return props;
	}

	/**
	 * Verifies that the user has chose a valid VM size.
	 * @param machineSize The size of the machine requested by the configuration.
	 * @return True if valid  Else false.
	 */
	private boolean verifyMachineSize(String machineSize) {
		boolean verified = false;
		if (machineSize.equalsIgnoreCase(InstanceSize.SMALL.name())) {
			verified = true;
		}
		else if (machineSize.equalsIgnoreCase(InstanceSize.MEDIUM.name())) {
			verified = true;
		}
		else if (machineSize.equalsIgnoreCase(InstanceSize.LARGE.name())) {
			verified = true;
		}
		return verified;
	}

}
