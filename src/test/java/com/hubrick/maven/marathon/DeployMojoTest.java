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


import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import mesosphere.marathon.client.model.v2.App;
import mesosphere.marathon.client.utils.MarathonException;
import mesosphere.marathon.client.utils.ModelUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.configuration.DefaultPlexusConfiguration;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.FileNotFoundException;

import static org.hamcrest.CoreMatchers.isA;


public class DeployMojoTest extends AbstractMarathonMojoTestWithJUnit4 {

    public static final String APP_ID = "/example-service";
    public static final String APPS_PATH = "/v2/apps";
    public static final String DEPLOYMENTS_PATH = "/v2/deployments";

    @Rule
    public final ExpectedException thrown = ExpectedException.none();
    @Rule
    public final MockWebServerRule server = new MockWebServerRule();

    private String getMarathonHost() {
        return server.getUrl("").toString();
    }

    private DeployMojo lookupDeployMojo(PlexusConfiguration pluginCfg) throws Exception {
        return (DeployMojo) lookupMarathonMojo("deploy", pluginCfg);
    }

    private DeployMojo lookupDeployMojo(String marathonFile) throws Exception {
        PlexusConfiguration pluginCfg = new DefaultPlexusConfiguration("configuration");
        pluginCfg.addChild("marathonHost", getMarathonHost());
        pluginCfg.addChild("marathonConfigFile", marathonFile);
        pluginCfg.addChild("waitOnRunningDeployment", "true");
        pluginCfg.addChild("waitOnRunningDeploymentTimeoutInSec", "300");
        pluginCfg.addChild("waitForSuccessfulDeployment", "true");
        pluginCfg.addChild("waitForSuccessfulDeploymentTimeoutInSec", "300");
        return (DeployMojo) lookupMarathonMojo("deploy", pluginCfg);
    }

    private DeployMojo lookupDeployMojo() throws Exception {
        return lookupDeployMojo(getTestMarathonConfigFile());
    }

    @Test
    public void testSuccessfulDeployAppNotCreatedYet() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(Resources.toString(Resources.getResource(DeployMojoTest.class, "/appResponse.json"), Charsets.UTF_8)));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(Resources.toString(Resources.getResource(DeployMojoTest.class, "/getAppResponse.json"), Charsets.UTF_8)));

        final DeployMojo mojo = lookupDeployMojo();
        assertNotNull(mojo);

        mojo.execute();

        assertEquals(3, server.getRequestCount());

        RecordedRequest getAppRequest = server.takeRequest();
        assertEquals(APPS_PATH + "/" + APP_ID, getAppRequest.getPath());
        assertEquals("GET", getAppRequest.getMethod());

        RecordedRequest createAppRequest = server.takeRequest();
        assertEquals(APPS_PATH, createAppRequest.getPath());
        assertEquals("POST", createAppRequest.getMethod());

        RecordedRequest getAppRequest2 = server.takeRequest();
        assertEquals(APPS_PATH + "/" + APP_ID, getAppRequest2.getPath());
        assertEquals("GET", getAppRequest2.getMethod());

        App requestApp = ModelUtils.GSON.fromJson(createAppRequest.getBody().readUtf8(), App.class);
        assertNotNull(requestApp);
        assertEquals(APP_ID, requestApp.getId());
    }

    @Test
    public void testSuccessfulDeployAppAlreadyExists() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(Resources.toString(Resources.getResource(DeployMojoTest.class, "/getAppResponse.json"), Charsets.UTF_8)));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(Resources.toString(Resources.getResource(DeployMojoTest.class, "/getAppResponse.json"), Charsets.UTF_8)));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(Resources.toString(Resources.getResource(DeployMojoTest.class, "/updateAppResponse.json"), Charsets.UTF_8)));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(Resources.toString(Resources.getResource(DeployMojoTest.class, "/getAppResponse.json"), Charsets.UTF_8)));

        final DeployMojo mojo = lookupDeployMojo();
        assertNotNull(mojo);

        mojo.execute();

        assertEquals(5, server.getRequestCount());

        RecordedRequest getAppRequest = server.takeRequest();
        assertEquals(APPS_PATH + "/" + APP_ID, getAppRequest.getPath());
        assertEquals("GET", getAppRequest.getMethod());

        RecordedRequest getDeploymentsRequest = server.takeRequest();
        assertEquals(DEPLOYMENTS_PATH, getDeploymentsRequest.getPath());
        assertEquals("GET", getDeploymentsRequest.getMethod());

        RecordedRequest getAppRequest2 = server.takeRequest();
        assertEquals(APPS_PATH + "/" + APP_ID, getAppRequest2.getPath());
        assertEquals("GET", getAppRequest2.getMethod());

        RecordedRequest updateAppRequest = server.takeRequest();
        assertEquals(APPS_PATH + "/" + APP_ID, updateAppRequest.getPath());
        assertEquals("PUT", updateAppRequest.getMethod());
        App requestApp = ModelUtils.GSON.fromJson(updateAppRequest.getBody().readUtf8(), App.class);
        assertNotNull(requestApp);
        assertEquals(APP_ID, requestApp.getId());

        RecordedRequest getAppRequest3 = server.takeRequest();
        assertEquals(APPS_PATH + "/" + APP_ID, getAppRequest3.getPath());
        assertEquals("GET", getAppRequest3.getMethod());
    }

    @Test
    public void testDeployFailedDueToMissingMarathonConfigFile() throws Exception {
        thrown.expect(MojoExecutionException.class);
        thrown.expectCause(isA(FileNotFoundException.class));

        final DeployMojo mojo = lookupDeployMojo("/invalid/path/to/marathon.json");
        assertNotNull(mojo);

        mojo.execute();

        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void testDeployFailedDueToFailedAppStatusCheck() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        thrown.expect(MojoExecutionException.class);
        thrown.expectCause(isA(MarathonException.class));

        final DeployMojo mojo = lookupDeployMojo();
        assertNotNull(mojo);

        mojo.execute();

        assertEquals(1, server.getRequestCount());

        RecordedRequest getAppRequest = server.takeRequest();
        assertEquals(APPS_PATH + APP_ID, getAppRequest.getPath());
        assertEquals("GET", getAppRequest.getMethod());
    }

    @Test
    public void testDeployFailedDueToFailedAppCreation() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(new MockResponse().setResponseCode(500));
        thrown.expect(MojoExecutionException.class);
        thrown.expectCause(isA(MarathonException.class));

        final DeployMojo mojo = lookupDeployMojo();
        assertNotNull(mojo);

        mojo.execute();

        assertEquals(2, server.getRequestCount());

        RecordedRequest getAppRequest = server.takeRequest();
        assertEquals(APPS_PATH + APP_ID, getAppRequest.getPath());
        assertEquals("GET", getAppRequest.getMethod());

        RecordedRequest createAppRequest = server.takeRequest();
        assertEquals(APPS_PATH, createAppRequest.getPath());
        assertEquals("POST", createAppRequest.getMethod());
    }

    @Test
    public void testDeployFailedDueToFailedAppUpdate() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(new MockResponse().setResponseCode(500));
        thrown.expect(MojoExecutionException.class);
        thrown.expectCause(isA(MarathonException.class));

        final DeployMojo mojo = lookupDeployMojo();
        assertNotNull(mojo);

        mojo.execute();

        assertEquals(2, server.getRequestCount());

        RecordedRequest getAppRequest = server.takeRequest();
        assertEquals(APPS_PATH + APP_ID, getAppRequest.getPath());
        assertEquals("GET", getAppRequest.getMethod());

        RecordedRequest updateAppRequest = server.takeRequest();
        assertEquals(APPS_PATH + APP_ID, updateAppRequest.getPath());
        assertEquals("PUT", updateAppRequest.getMethod());
    }

}
