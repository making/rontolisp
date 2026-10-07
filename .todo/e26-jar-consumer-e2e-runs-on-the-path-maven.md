# e26. `JarMavenConsumerE2eTest` runs on the PATH `mvn` and the newest plugins in the local repository

Difficulty: Low

The opt-in `e2e/JarMavenConsumerE2eTest` (`-Drontolisp.jar.e2e=true`) has the flaw the
plugin's `MavenBuildE2eTest` had (`.kb/session-workflow.md`): it shells out to the first `mvn`
on PATH, runs `install-file` at the newest `maven-install-plugin` whose jar the local
repository holds, and its offline consumer `compile` leaves resources/compiler to that Maven's
default bindings. With a PATH Maven newer than the wrapper (3.10.0 on the GitHub runner since
2026-10-04) the offline build names versions the root build never resolved in full.

Fix the same way: pass `${maven.home}`, `${settings.localRepository}` and the pinned plugin
versions through the root surefire `systemPropertyVariables`, run every child build with
`-Dmaven.repo.local`, and declare those versions in the consumer pom.
