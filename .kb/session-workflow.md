# Session workflow: builds outside the reactor, long runs, shared `develop`, after-task checks

## Builds outside the root reactor

`./mvnw test` builds none of these; each needs something the core libraries may not have.

```bash
./mvnw -f docs-tool/pom.xml test               # the documentation site generator
./mvnw -f rontolisp-maven-plugin/pom.xml test  # the compile-src/main/lisp plugin
rontolisp-native/build.sh --test               # Cargo workspace (rustc >= 1.96): host shim + stub, then the Rust tests
```

`rontolisp-maven-plugin` depends on the rontolisp artifact by coordinates, so a SNAPSHOT build
needs `./mvnw install -DskipTests` first, and its own `install` is what `MavenBuildE2eTest`
(`-Drontolisp.plugin.e2e=true`) needs. `rontolisp-native/` is the wasmtime precompile shim and
the runner stub a native executable is made of (`.kb/native-output.md`).

`MavenBuildE2eTest` shells out to the Maven running it (`${maven.home}`) on its local repository
(`${settings.localRepository}`), and its offline jar fixture declares the
`maven-{resources,compiler,surefire,jar}-plugin.version` the module's pom pins -- all passed by
the module's surefire configuration. Measured 2026-10-07: runner image `ubuntu-24.04`
20261004.327 moved `/usr/bin/mvn` 3.9.16 -> 3.10.0, and the test, then taking `mvn` from PATH
and each plugin's newest version in the local repository, picked `maven-jar-plugin` 3.5.1 -- a
jar without its dependencies, left by an online fixture build that only computed its lifecycle
-- so the offline `package` failed. The pinned versions are the ones the module's own `install`
executes in full, so the offline build resolves under any runner Maven. Rejected: pre-resolving
in the workflow (CI only, the local run stays broken) and dropping `-o` (network-bound, still
the PATH Maven).

## Waiting for a long run

**Never detach a test run and end the turn to wait for it.** Nothing wakes up to read the
result -- a stall indistinguishable from a crash. Block in the foreground on the process:

```bash
./mvnw ... test > run.log 2>&1 &          # or an already-running build
tail -f --pid=$! /dev/null                 # blocks until it exits; raise the tool timeout
echo "exit=$?"; grep -E 'BUILD (SUCCESS|FAILURE)' run.log
```

Three ways a run reports green when it is not:

- **Two maven runs in one worktree** corrupt `target/` and void BOTH results. One run per tree.
- **Editing `src/` while a run is in flight**: the compile phase saw a mixed tree. Freeze the
  sources until it finishes.
- **A truncated run looks clean**: an orphaned build from an earlier interrupted turn can kill
  it early, leaving zero failures and a short report set. Count
  `target/surefire-reports/*.txt` -- a full `./mvnw test` writes ~280 -- and check the exit
  code, not just the failure counts.

With subagents, the PARENT runs the suite (capturing the exit code) and hands the summary
back; the worker is told not to invoke maven.

## Working alongside other sessions

Several sessions push to `develop` at once, so what you tested is not what you push.

- **Take upstream in once, immediately before the final test run** (`git fetch origin`,
  `git merge origin/develop`), so the suite runs over the merged tree.
- **A merge AFTER that run does not require re-running the suite.** Merge, push, let CI cover
  the combination.
- In a worktree, `develop` is held by the main tree: `git fetch origin` ->
  `git merge origin/develop` -> `git push origin HEAD:develop`, retried from the fetch if the
  push is rejected.
- A semantic conflict passes `git merge` cleanly. When both sides touched one mechanism, read
  the other side's diff before pushing.
- **Claim a `.todo/NNN` number, never pick one**: `.todo/claim-number.sh "<why>" [count]`.
  Reading `.todo/` for the highest number cannot work -- two differently-named `NNN-*.md`
  files MERGE cleanly, so a duplicate survives (633 and 634 both happened on 2026-09-02). The
  counter is one file, `NEXT`, on the orphan branch `todo-seq`: claiming is a push to it, so
  racers fight over the same file and git rejects the loser, which retries. It also reads
  develop's live files and `.todo/history/` rows on every claim and skips anything taken, so
  a number filed without the script heals itself -- do NOT cross-check by hand. `todo-seq`
  shares no history with `develop` and must never be merged into it. Row format and the
  duplicate rule: `.todo/.history.md`.

## Java backslash-u rule

javac preprocesses `\u` everywhere, comments included: a raw `\u` without four hex
digits is an `illegal unicode escape` compile error, and a raw `\uXXXX` silently
translates (in a comment the formatter then writes the translated character back,
mangling the line; in a string literal it changes the value). b47 hit both in a javadoc
and a test comment. So: never write a raw `\u` in a `.java` file outside a string
literal -- use `\\u` (a doubled backslash shields it, even in comments) or words like
`backslash-u`. `RawBackslashUTest` pins this: it scans `src/main/java` and
`src/test/java` for an odd-backslash `u` outside string/char/text-block literals and
fails with the rule. It stays out of `src/web/java` and `src/native/java`, so the
`-Pweb`/`-Pnative` lanes are unaffected.

## After task completion

- Format Lisp: `java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar format examples/ src/main/resources/ size-report/programs/ bench-report/programs/`
- A GUI change (`objc:`/`appkit:`, `RontoLispCli.main`'s thread hand-over, the embedded JVM
  blob) is verified by hand with `examples/macos/counter.lisp` on `java -jar`, the native
  binary AND the compiled outputs (`-o Counter.class --class-name Counter` under
  `java Counter`, `-o counter.jar` under `java -jar`) -- no test opens a window.
- Web profile: `./mvnw -Pweb compile` whenever `src/web/java` or a signature it overrides
  changed. Run it AFTER the test suite (or `clean` in between): it leaves the web source set
  in `target/classes`, and a later `./mvnw test` without `clean` fails with
  `NoClassDefFoundError` on excluded classes -- not a regression.
- Native profile: `./mvnw -Pnative clean package -DskipTests` whenever `src/native/java` or a
  member it aliases or substitutes changed (`NativeSubstitutionsTest` pins the member NAMES
  from the JVM lane, not the bodies); a `--blas` change is verified on the binary it produces
  (`.kb/native-downcalls.md`).
- Native E2E (`.kb/running-backends.md`) whenever `ci-spec.yaml` or cross-backend output
  changed.
