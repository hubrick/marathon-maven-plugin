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
import com.jayway.awaitility.core.ConditionTimeoutException;
import mesosphere.marathon.client.Marathon;
import mesosphere.marathon.client.MarathonClient;
import mesosphere.marathon.client.model.v2.App;
import mesosphere.marathon.client.model.v2.Deployment;
import mesosphere.marathon.client.model.v2.GetAppResponse;
import mesosphere.marathon.client.model.v2.HealthCheckResult;
import mesosphere.marathon.client.model.v2.Result;
import mesosphere.marathon.client.model.v2.Task;
import mesosphere.marathon.client.utils.MarathonException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.hubrick.maven.marathon.Utils.readApp;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

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
                waitForRunningDeployment(marathon, app);
            }

            updateApp(marathon, app);
        } else {
            getLog().info(app.getId() + " does not exist yet - will be created");
            createApp(marathon, app);
        }
    }

    private void waitForRunningDeployment(final Marathon marathon, final App app) throws MojoExecutionException {
        try {
            Awaitility.await()
                    .pollInterval(5, TimeUnit.SECONDS)
                    .atMost(waitOnRunningDeploymentTimeoutInSec, TimeUnit.SECONDS).until(() -> {
                getLog().info("Checking app " + app.getId() + " for deployments in progress...");
                final Set<String> deployingAppVersions = marathon.getDeployments()
                        .stream()
                        .filter(e -> e.getAffectedApps().contains(app.getId()))
                        .map(Deployment::getVersion)
                        .collect(toSet());

                getLog().info("Checking app " + app.getId() + ". Apps currently being deployed: "
                        + deployingAppVersions.size() + ", versions: " + deployingAppVersions.toString());

                return deployingAppVersions.isEmpty();
            });
        } catch (ConditionTimeoutException e) {
            throw new MojoExecutionException("Previous deployment still hanging. Didn't finish in "
                    + waitOnRunningDeploymentTimeoutInSec + " seconds", e);
        }
    }

    private void updateApp(Marathon marathon, App app) throws MojoExecutionException {
        try {
            final Stopwatch stopwatch = new Stopwatch().start();
            final App currentApp = marathon.getApp(app.getId()).getApp();
            final Result result = marathon.updateApp(app.getId(), app, false);
            final String deployedVersion = result.getVersion();
            getLog().info("Checking app " + app.getId() + " with new version " + deployedVersion + " for successful deployment... " +
                    "(Id " + result.getDeploymentId() + ")");


            final long timeoutInSeconds = waitForSuccessfulDeploymentTimeoutInSec *
                    com.google.common.base.Objects.firstNonNull(app.getInstances(), currentApp.getInstances());
            if (waitForSuccessfulDeployment) {
                waitForSuccessfulDeployment(marathon, app.getId(), stopwatch, deployedVersion, timeoutInSeconds);
            }
        } catch (MarathonException updateAppException) {
            throw new MojoExecutionException("Failed to update Marathon config file at " + marathonHost, updateAppException);
        }
    }

    private void waitForSuccessfulDeployment(final Marathon marathon,
                                             final String appId,
                                             final Stopwatch stopwatch,
                                             final String deployedVersion,
                                             final long timeoutInSeconds) throws MojoExecutionException {
        try {
            Awaitility.await()
                    .pollDelay(10, TimeUnit.SECONDS)
                    .pollInterval(5, TimeUnit.SECONDS)
                    .atMost(timeoutInSeconds, TimeUnit.SECONDS).until(() -> {

                final GetAppResponse getAppResponse = marathon.getApp(appId);
                final App deployingApp = getAppResponse.getApp();
                final List<String> currentRunningVersions = extractCurrentRunningVersions(deployingApp);

                final List<String> newRunningVersions = currentRunningVersions
                        .stream()
                        .filter(deployedVersion::equals)
                        .sorted()
                        .collect(toList());

                if (newRunningVersions.isEmpty()) {
                    final List<String> versions = loadCurrentlyDeployingVersions(marathon, deployingApp);
                    versions.stream()
                            .filter(deployedVersion::equals)
                            .findFirst()
                            .orElseThrow(() -> new MojoExecutionException("No version " + deployedVersion + " and" +
                                    " no running deployment found, running versions are " +
                                    currentRunningVersions + ", deployment aborted."));
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

                getLog().info("Checking app " + appId +
                        ". Running Tasks: " + deployingApp.getTasksRunning() +
                        ", Staged tasks: " + deployingApp.getTasksStaged() +
                        ", Unhealthy tasks: " + deployingApp.getTasksUnhealthy() +
                        ", Healthy tasks: " + deployingApp.getTasksHealthy()
                        + ". Current versions: " + currentRunningVersions.toString());

                return Objects.equals(deployingApp.getTasksHealthy(), newRunningVersions.size())
                        && Objects.equals(deployingApp.getTasks().size(), newRunningVersions.size());
            });
        } catch (ConditionTimeoutException e) {
            throw new MojoExecutionException("Current deployment still hanging. Didn't finish in "
                    + waitForSuccessfulDeploymentTimeoutInSec + " seconds", e);
        }
    }

    private void createApp(Marathon marathon, App app) throws MojoExecutionException {
        try {
            final Stopwatch stopwatch = new Stopwatch().start();
            final App deployedApp = marathon.createApp(app);
            final long timeoutInSeconds = waitForSuccessfulDeploymentTimeoutInSec *
                    com.google.common.base.Objects.firstNonNull(app.getInstances(), Integer.valueOf(1));
            if (waitForSuccessfulDeployment) {
                final Set<String> deployingVersions = loadCurrentlyDeployingVersions(marathon, deployedApp).stream()
                        .collect(toSet());
                if (deployingVersions.size() != 1) {
                    throw new MojoExecutionException("Expected exactly one version for newly created app, but got " + deployingVersions);
                }

                waitForSuccessfulDeployment(marathon, deployedApp.getId(), stopwatch, getOnlyElement(deployingVersions), timeoutInSeconds);
            }
        } catch (MarathonException createAppException) {
            throw new MojoExecutionException("Failed to push Marathon config file to " + marathonHost, createAppException);
        }
    }

    private List<String> loadCurrentlyDeployingVersions(final Marathon marathon, final App deployingApp) throws MarathonException {
        return marathon.getDeployments()
                .stream()
                .filter(deployment -> deployment.getAffectedApps().contains(deployingApp.getId()))
                .map(Deployment::getVersion)
                .sorted()
                .collect(toList());

    }

    private List<String> extractCurrentRunningVersions(App deployingApp) {
        return deployingApp.getTasks()
                .stream()
                .map(Task::getVersion)
                .sorted()
                .collect(toList());
    }
}
