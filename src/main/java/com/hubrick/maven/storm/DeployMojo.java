/**
 * Copyright (C) ${project.inceptionYear} Etaia AS (oss@hubrick.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hubrick.maven.storm;

import com.jayway.awaitility.Awaitility;
import mesosphere.marathon.client.Marathon;
import mesosphere.marathon.client.MarathonClient;
import mesosphere.marathon.client.model.v2.App;
import mesosphere.marathon.client.model.v2.GetAppResponse;
import mesosphere.marathon.client.model.v2.Result;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hubrick.maven.storm.Utils.readApp;

/**
 * Deploys via Marathon by sending config.
 */
@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY)
public class DeployMojo extends AbstractMarathonMojo {

    /**
     * URL of the marathon host as specified in pom.xml.
     */
    @Parameter(property = "marathonHost", required = true)
    private String marathonHost;

    /**
     * Defines if it should wait that the previous running deployment for the same appId finished.
     */
    @Parameter(property = "waitOnRunningDeployment", required = false, defaultValue = "true")
    private Boolean waitOnRunningDeployment;

    /**
     * Max time to wait in sec that the previous running deployment for the same appId finished.
     */
    @Parameter(property = "waitOnRunningDeploymentTimeoutInSec", required = false, defaultValue = "300")
    private Integer waitOnRunningDeploymentTimeoutInSec;

    /**
     * Defines if it should wait that the current running deployment finishes until it proceeds.
     */
    @Parameter(property = "waitForSuccessfulDeployment", required = false, defaultValue = "true")
    private Boolean waitForSuccessfulDeployment;

    /**
     * Max time to wait in sec that the current running deployment finishes until it proceeds.
     */
    @Parameter(property = "waitForSuccessfulDeploymentTimeoutInSec", required = false, defaultValue = "300")
    private Integer waitForSuccessfulDeploymentTimeoutInSec;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Marathon marathon = MarathonClient.getInstance(marathonHost);
        final App app = readApp(marathonConfigFile);
        getLog().info("deploying Marathon config for " + app.getId() + " from " + marathonConfigFile + " to " + marathonHost);
        if (appExists(marathon, app.getId())) {
            getLog().info(app.getId() + " already exists - will be updated");
            if (waitOnRunningDeployment) {
                try {
                    Awaitility.await()
                            .pollInterval(5, TimeUnit.SECONDS)
                            .atMost(waitOnRunningDeploymentTimeoutInSec, TimeUnit.SECONDS).until(() -> {
                        getLog().info("Checking app " + app.getId() + " for deployments in progress...");
                        final Set<String> deployingAppVersions = marathon.getDeployments()
                                .stream()
                                .filter(e -> e.getAffectedApps().contains(app.getId()))
                                .map(e -> e.getVersion())
                                .sorted()
                                .collect(Collectors.toSet());

                        getLog().info("Checking app " + app.getId() + ". Apps currently being deployed: "
                                + deployingAppVersions.size() + ", versions: " + deployingAppVersions.toString());

                        return deployingAppVersions.isEmpty();
                    });
                } catch (Exception e) {
                    throw new MojoExecutionException("Previous deployment still hanging. Didn't finish in "
                            + waitOnRunningDeploymentTimeoutInSec + " seconds", e);
                }
            }

            updateApp(marathon, app);
        } else {
            getLog().info(app.getId() + " does not exist yet - will be created");
            createApp(marathon, app);
        }
    }

    private void updateApp(Marathon marathon, App app) throws MojoExecutionException {
        try {
            final Result result = marathon.updateApp(app.getId(), app);
            if (waitForSuccessfulDeployment) {
                try {
                    Awaitility.await()
                            .pollInterval(5, TimeUnit.SECONDS)
                            .atMost(waitForSuccessfulDeploymentTimeoutInSec, TimeUnit.SECONDS).until(() -> {

                        getLog().info("Checking app " + app.getId() + " with new version " + result.getVersion() + " for successful deployment...");

                        final GetAppResponse getAppResponse = marathon.getApp(app.getId());
                        final App deployingApp = getAppResponse.getApp();
                        final List<String> currentRunningVersions = extractCurrentRunningVersions(deployingApp);

                        final List<String> newRunningVersions = currentRunningVersions
                                .stream()
                                .filter(e -> e.equals(result.getVersion()))
                                .sorted()
                                .collect(Collectors.toList());

                        Collections.sort(currentRunningVersions);
                        getLog().info("Checking app " + app.getId() +
                                ". Running Tasks: " + deployingApp.getTasksRunning() +
                                ", Staged tasks: " + deployingApp.getTasksStaged() +
                                ", Unhealthy tasks: " + deployingApp.getTasksUnhealthy() +
                                ", Healthy tasks: " + deployingApp.getTasksHealthy()
                                + ". Current versions: " + currentRunningVersions.toString());

                        return Objects.equals(deployingApp.getTasksHealthy(), newRunningVersions.size())
                                && Objects.equals(deployingApp.getTasks().size(), newRunningVersions.size());
                    });
                } catch (Exception e) {
                    throw new MojoExecutionException("Current deployment still hanging. Didn't finish in "
                            + waitForSuccessfulDeploymentTimeoutInSec + " seconds", e);
                }
            }
        } catch (Exception updateAppException) {
            throw new MojoExecutionException("Failed to update Marathon config file at " + marathonHost, updateAppException);
        }
    }

    private void createApp(Marathon marathon, App app) throws MojoExecutionException {
        try {
            final App deployedApp = marathon.createApp(app);
            if (waitForSuccessfulDeployment) {
                try {
                    Awaitility.await()
                            .pollInterval(5, TimeUnit.SECONDS)
                            .atMost(waitForSuccessfulDeploymentTimeoutInSec, TimeUnit.SECONDS).until(() -> {

                        getLog().info("Checking new app " + deployedApp.getId() + " for successful deployment...");

                        final GetAppResponse getAppResponse = marathon.getApp(deployedApp.getId());
                        final App deployingApp = getAppResponse.getApp();
                        final List<String> currentRunningVersions = extractCurrentRunningVersions(deployingApp);

                        Collections.sort(currentRunningVersions);
                        getLog().info("Checking app " + deployedApp.getId() +
                                ". Running Tasks: " + deployingApp.getTasksRunning() +
                                ", Staged tasks: " + deployingApp.getTasksStaged() +
                                ", Unhealthy tasks: " + deployingApp.getTasksUnhealthy() +
                                ", Healthy tasks: " + deployingApp.getTasksHealthy()
                                + ". Current versions: " + currentRunningVersions.toString());

                        return Objects.equals(deployingApp.getTasksHealthy(), currentRunningVersions.size())
                                && Objects.equals(deployingApp.getTasks().size(), currentRunningVersions.size());
                    });
                } catch (Exception e) {
                    throw new MojoExecutionException("Current deployment still hanging. Didn't finish in "
                            + waitForSuccessfulDeploymentTimeoutInSec + " seconds", e);
                }
            }
        } catch (Exception createAppException) {
            throw new MojoExecutionException("Failed to push Marathon config file to " + marathonHost, createAppException);
        }
    }

    private List<String> extractCurrentRunningVersions(App deployingApp) {
        return deployingApp.getTasks()
                .stream()
                .map(e -> e.getVersion())
                .sorted()
                .collect(Collectors.toList());
    }
}
