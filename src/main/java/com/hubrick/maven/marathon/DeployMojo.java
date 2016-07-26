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
package com.hubrick.maven.marathon;

import com.google.common.base.Stopwatch;
import com.jayway.awaitility.Awaitility;
import mesosphere.marathon.client.Marathon;
import mesosphere.marathon.client.MarathonClient;
import mesosphere.marathon.client.model.v2.App;
import mesosphere.marathon.client.model.v2.GetAppResponse;
import mesosphere.marathon.client.model.v2.HealthCheckResult;
import mesosphere.marathon.client.model.v2.Result;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hubrick.maven.marathon.Utils.readApp;
import static java.util.stream.Collectors.toList;

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
            final Stopwatch stopwatch = new Stopwatch().start();
            final App currentApp = marathon.getApp(app.getId()).getApp();
            final Result result = marathon.updateApp(app.getId(), app);
            final String deployedVersion = result.getVersion();
            getLog().info("Checking app " + app.getId() + " with new version " + deployedVersion + " for successful deployment... " +
                    "(Id " + result.getDeploymentId() + ")");


            final long timeoutInSeconds = waitForSuccessfulDeploymentTimeoutInSec *
                    com.google.common.base.Objects.firstNonNull(app.getInstances(), currentApp.getInstances());
            if (waitForSuccessfulDeployment) {
                try {
                    Awaitility.await()
                            .pollDelay(10, TimeUnit.SECONDS)
                            .pollInterval(5, TimeUnit.SECONDS)
                            .atMost(timeoutInSeconds, TimeUnit.SECONDS).until(() -> {

                        final GetAppResponse getAppResponse = marathon.getApp(app.getId());
                        final App deployingApp = getAppResponse.getApp();
                        final List<String> currentRunningVersions = extractCurrentRunningVersions(deployingApp);

                        final List<String> newRunningVersions = currentRunningVersions
                                .stream()
                                .filter(e -> e.equals(deployedVersion))
                                .sorted()
                                .collect(toList());

                        if (newRunningVersions.isEmpty()) {
                            throw new MojoExecutionException("No version " + deployedVersion + " found, running versions are " +
                                    currentRunningVersions + ", deployment aborted.");
                        }

                        final List<HealthCheckResult> healthyNewInstances = deployingApp.getTasks()
                                .stream()
                                .filter(task -> task.getVersion().equals(deployedVersion))
                                .flatMap(task -> task.getHealthCheckResults() == null ? Stream.of() : task.getHealthCheckResults().stream())
                                .filter(HealthCheckResult::isAlive)
                                .collect(toList());

                        if (!healthyNewInstances.isEmpty() && stopwatch.isRunning()) {
                            stopwatch.stop();
                            getLog().info("Time to first healthy instance is " + stopwatch.toString());
                        }

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
            final long timeoutInSeconds = waitForSuccessfulDeploymentTimeoutInSec *
                    com.google.common.base.Objects.firstNonNull(app.getInstances(), Integer.valueOf(1));
            if (waitForSuccessfulDeployment) {
                try {
                    Awaitility.await()
                            .pollInterval(5, TimeUnit.SECONDS)
                            .atMost(timeoutInSeconds, TimeUnit.SECONDS).until(() -> {

                        getLog().info("Checking new app " + deployedApp.getId() + " for successful deployment...");

                        final GetAppResponse getAppResponse = marathon.getApp(deployedApp.getId());
                        final App deployingApp = getAppResponse.getApp();
                        final List<String> currentRunningVersions = extractCurrentRunningVersions(deployingApp);

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
                .collect(toList());
    }
}
