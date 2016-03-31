marathon-maven-plugin
=====================

Forked from: https://github.com/holidaycheck/marathon-maven-plugin

Maven plugin for interacting with Marathon

It can both process `marathon.json` config and use it to push it to Marathon host for deployment. 

# Maven documentation

For detailed documentation of goals and configuration options run in your project:
`mvn com.hubrick.maven:marathon-maven-plugin:help -Ddetail=true`

# Basic usage

Add plugin configuration to your `pom.xml`:

```xml
<plugin>
	<groupId>com.hubrick.maven</groupId>
	<artifactId>marathon-maven-plugin</artifactId>
	<version>0.1.0</version>
	<configuration>
		<marathonConfigFile>${project.build.directory}/marathon.json</marathonConfigFile>
		<marathonHost>http://${mesos.host}:${mesos.port}</marathonHost>
		<waitOnRunningDeployment>true</waitOnRunningDeployment>
        <waitOnRunningDeploymentTimeoutInSec>300</waitOnRunningDeploymentTimeoutInSec>
        <waitForSuccessfulDeployment>true</waitForSuccessfulDeployment>
        <waitForSuccessfulDeploymentTimeoutInSec>300</waitForSuccessfulDeploymentTimeoutInSec>
	</configuration>
	<executions>
		<execution>
			<id>deploy</id>
			<phase>deploy</phase>
			<goals>
				<goal>deploy</goal>
			</goals>
		</execution>
		<execution>
			<id>apptasks</id>
			<phase>pre-integration-test</phase>
			<goals>
				<goal>apptasks</goal>
			</goals>
			<configuration>
				<propertyPrefix>mesos-</propertyPrefix>
				<delay>5</delay>
			</configuration>
		</execution>
		<execution>
			<id>delete</id>
			<phase>post-integration-test</phase>
			<goals>
				<goal>delete</goal>
			</goals>
		</execution>
	</executions>
</plugin>
```

By default your template `marathon.json` should be in the root project directory.
