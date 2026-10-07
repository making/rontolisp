# e25. The maven-plugin CI job's E2E breaks whenever the runner image's `mvn` changes

Difficulty: Medium

`MavenBuildE2eTest.aRealBuildCompilesTheLispBeforeTheJavaAndJarsBoth` fails on every
develop run since the `ubuntu-24.04` image 20261004.327 (its `/usr/bin/mvn` went 3.9.16 ->
3.10.0); image 20260927.320 still passes:

    /usr/bin/mvn [-o, -q, package] exited 1: ... maven-jar-plugin:3.5.1 or one of its
    dependencies could not be resolved: ... maven-archiver:jar:3.6.6 has not been downloaded

The E2E runs whatever `mvn` is first on PATH, not the Maven that ran the test and seeded the
local repository, and its offline fixture declares the NEWEST plugin version whose jar the
local repository holds. An earlier online test in the same class resolved only the plugin
descriptor of 3.10.0's default `maven-jar-plugin` (3.5.1) -- jar present, dependencies
absent -- and the offline build picked it.

Plan: reproduce from an empty local repository with Maven 3.10.0 on PATH, then make the
consumer build independent of the ambient Maven and of what the local repository happens to
hold. Record the decision in `.kb`.
