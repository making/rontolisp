# g57: eight tests fail on macOS where the temp and /tmp paths are symlinks

Difficulty: Medium

On macOS `/tmp` -> `/private/tmp` and `/var` -> `/private/var` (the JUnit `@TempDir` lives
under `/var/folders/...`). A local `./mvnw test` on develop (2026-10-23, d3a53299f) fails
these, reproducibly, while Linux CI passes them:

- `ClojureInteropTest.clojureJavaShellRunsAHostProcessLikeTheOracle`: `(sh "pwd" :dir "/tmp")`
  prints `/private/tmp`, the expectation is `/tmp`.
- `JvmLispCompilerTest.compileAndRunUiopFilesystemProbeWalkAndMutate`: some probes return
  `#P"/private/var/folders/..."` where the expectation (and other probes) say `/var/folders/...`.
- `ClojureJavaIoTest`: six methods (resources from the source path and from a jar, on the
  interpreter/JVM and both WASM backends; directory make/list/walk/delete; empty-directory
  delete and file date). The expected and actual URLs/paths differ in the `/private` prefix.

Reproduce: `./mvnw -q test -Dtest='ClojureJavaIoTest,ClojureInteropTest#clojureJavaShell*,JvmLispCompilerTest#compileAndRunUiopFilesystemProbeWalkAndMutate' -Dsurefire.failIfNoSpecifiedTests=false`

## Goal

Decide per case whether the runtime or the test is wrong (does the oracle -- Clojure,
SBCL/UIOP -- canonicalize there? is one backend canonicalizing where the others do not?),
fix it on every backend that disagrees, and leave the listed tests green on macOS and Linux.
