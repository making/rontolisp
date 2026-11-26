# Session workflow: builds outside the reactor, the deploy job, the snapshot tag, long runs, shared `develop`, after-task checks

## Builds outside the root reactor

`./mvnw test` builds none of these; each needs something the core libraries may not have.

```bash
./mvnw -f docs-tool/pom.xml test               # the documentation site generator
./mvnw -f rontolisp-maven-plugin/pom.xml test  # the compile-src/main/lisp plugin
rontolisp-native/build.sh --test               # Cargo workspace (rustc >= 1.97): host shim + stub, then the Rust tests
./mvnw -f servlet-httpserver/pom.xml test      # the Servlet container (.kb/servlet-httpserver.md): no rontolisp at all
./mvnw -f jdbc-postgresql/pom.xml test         # the JDBC driver (.kb/jdbc-postgresql.md): no rontolisp at all, needs Docker
./mvnw -f rontolisp-java-api/pom.xml verify    # @WitImport for Java programs (.kb/java-frontend.md, "WIT imports"): javac only
./mvnw -f servlet-httpserver-keyvalue/pom.xml test  # sessions in wasi:keyvalue (.kb/servlet-httpserver.md): the two above installed first
./mvnw -f jdbc-spin-postgres/pom.xml test      # the JDBC driver over Spin's connection (.kb/jdbc-spin-postgres.md): rontolisp-java-api and jdbc-postgresql installed first, needs Docker
./mvnw -f webmvc-fn-lite/pom.xml test          # WebMvc.fn on the Servlet API (.kb/webmvc-fn-lite.md): no rontolisp at all, beside Spring Framework
(cd examples/java/<name> && ../../../mvnw package)  # a Java example that uses a library (standalone, no parent), through the plugin's wasm goal: the deployed SNAPSHOTs
```

`rontolisp-maven-plugin` depends on the rontolisp artifact by coordinates, so a SNAPSHOT build
needs `./mvnw install -DskipTests` first, and its own `install` is what `MavenBuildE2eTest`
(`-Drontolisp.plugin.e2e=true`) needs. `rontolisp-native/` is the wasmtime precompile shim and
the runner stub a native executable is made of (`.kb/native-output.md`).

**A worktree beside others installs into a repository of its own** (checked 2026-11-19, Maven
3.9.16): `-Dmaven.repo.local=<own dir> -Dmaven.repo.local.tail=$HOME/.m2/repository` installs
the SNAPSHOTs into the own directory and reads everything else from the shared one, read-only.
`MavenBuildE2eTest`'s child builds get only `-Dmaven.repo.local`; export
`MAVEN_ARGS=-Dmaven.repo.local.tail=$HOME/.m2/repository` and they inherit the tail too.

`MavenBuildE2eTest` shells out to the Maven running it (`${maven.home}`) on its local repository
(`${settings.localRepository}`), and its offline jar fixture declares the
`maven-{resources,compiler,surefire,jar}-plugin.version` the module's pom pins -- all passed by
the module's surefire configuration. On 2026-10-07 the runner image `ubuntu-24.04`
20261004.327 moved `/usr/bin/mvn` to 3.10.0, and the test, then taking `mvn` from PATH
and each plugin's newest version in the local repository, picked `maven-jar-plugin` 3.5.1 -- a
jar without its dependencies, left by an online fixture build that only computed its lifecycle
-- so the offline `package` failed. The pinned versions are the ones the module's own `install`
executes in full, so the offline build resolves under any runner Maven. Rejected: pre-resolving
in the workflow (CI only, the local run stays broken) and dropping `-o` (network-bound, still
the PATH Maven).

`MavenBuildE2eTest#anExampleOfTheCheckoutBuildsToAWarAndAComponentThatAnswerAlike` (2026-11-23)
builds `examples/java/guestbook` online: it installs a copy of the checkout's
`servlet-httpserver` into the local repository first (`install -DskipTests
-Dmaven.javadoc.skip=true`, the sources jar the wasm goal reads included) and packages with
`-nsu`, so the component is compiled from develop's module, never a newer deployed SNAPSHOT.
The plugin's version is passed as `-Drontolisp.version` (the example's pom pins
`0.1.0-SNAPSHOT`). The example is copied alone, with no parent (l51). The checkout is `rontolisp.plugin.e2e.checkout` (`${project.basedir}/..`).

The root `e2e/JarMavenConsumerE2eTest` (`-Drontolisp.jar.e2e=true`) works the same way: the root
pom's surefire configuration passes `${maven.home}`, `${settings.localRepository}` and the
`maven-{resources,compiler,install}-plugin.version` properties, every child build runs with
`-Dmaven.repo.local`, and the consumer pom declares the resources and compiler versions. It runs
Maven offline and `install-file` needs the install plugin, which only `install` executes, so run
`./mvnw install -DskipTests` first. Reproduced 2026-10-08 with Maven 3.10.0 first on PATH and an
empty scratch repository seeded by `./mvnw install -DskipTests`: the old test failed on
`maven-resources-plugin:3.5.0` (3.10.0's default) not being in the repository.

## The deploy job

`ci.yaml` `deploy:` publishes on `./mvnw`, as every other job builds: the runner's `/usr/bin/mvn`
moves with the image. Found on 2026-10-08: on 3.10.0 every snapshot publish got HTTP 401 with
unchanged credentials. 3.10 binds a server's credentials to the origins declared for its id
(`maven.repository.credentialScope`, default `origin`), and the id `central` always has the
built-in Maven Central repository's origin `https://repo.maven.apache.org`, so credentials under
`central` never reach `central.sonatype.com`. Reproduced locally against a dummy settings file and
a local HTTP server (`-DcentralSnapshotsUrl=http://127.0.0.1:PORT/...`): 3.9.16 sends
`Authorization`, 3.10.0 does not and logs `Not using credentials of server 'central'`.

The Portal credentials therefore live under `sonatype-central`, the `publishingServerId` of every
publishing pom and the id in each deploying workflow's settings; they must agree. Under 3.10 an id with no
declared origin still gets its credentials, with a warning. When the wrapper reaches 3.10, add
`<repositoryOrigins><repositoryOrigin>https://central.sonatype.com</repositoryOrigin>` to that
server (3.10 `strict` scope refuses it otherwise); 3.9.16 rejects the tag with a settings warning,
so it is not there yet. Rejected: `-Dmaven.repository.credentialScope=id` (turns the scoping off)
and `<repositoryOrigins>` on `central` (also hands the Portal token to `repo.maven.apache.org`).

**The side modules deploy from their own workflows** (2026-11-22): `servlet-httpserver`,
`servlet-httpserver-keyvalue`, `jdbc-postgresql` and `rontolisp-java-api` (the keyvalue module's
dependency, published so the keyvalue artifact resolves) each have a `deploy` job in their
workflow, after its test job, on a push. `webmvc-fn-lite` (`am.ik.spring`, l32; the group was `am.ik.webmvc` until 2026-11-23, l60, and that old
snapshot is no longer deployed) has the same job, copied from
`servlet-httpserver`'s: its first run published the snapshot (run 45015365504 on `154bee3fa`,
2026-11-22), so a new group under `am.ik` needs nothing set up. `jdbc-spin-postgres` (`am.ik.jdbc`, m24, 2026-11-25) has the
keyvalue module's job, its two dependencies installed from the checkout. Chosen over steps in `ci.yaml` `deploy:` because a
change to one module runs only its workflow, each deploys after its own tests, and the move to
repositories of their own (`.todo/l07`) takes the workflow file whole; the Vault, JDK and settings
steps are repeated four times for that. The pom does the rest: the sources and javadoc jars are
bound to `package`, `-Psign` binds `gpg:sign` to `verify`, so the job runs one lifecycle
(`-Psign deploy`). `ci.yaml`'s goal list (`javadoc:jar source:jar package gpg:sign deploy`) cannot
be reused: with `jar-no-fork` bound -- the sources jar the examples compile from -- the second
lifecycle attaches it again and `maven-source-plugin` 3.3.1 fails (`We have duplicated artifacts
attached`). Locally `-Psign -Dgpg.skip install` is that job minus the signature and the upload.
No order is enforced between the four: the keyvalue job installs its two dependencies from the
checkout, and the snapshot upload looks at no dependency.

Measured 2026-11-22 on `c3b6a52b6`: all four deploy jobs published (`am.ik.servlet` and
`am.ik.jdbc` are under the credentials' namespace, snapshots enabled); the keyvalue job's first
try failed on HTTP 429 from `repo.maven.apache.org` while installing its dependencies and passed
on a re-run. An empty project depending on `servlet-httpserver-keyvalue` and `jdbc-postgresql`
with only `https://central.sonatype.com/repository/maven-snapshots/` added and an empty local
repository resolved all four `0.1.0-SNAPSHOT` jars (`dependency:resolve`, Maven 3.9.16).

Measured 2026-11-22 with `--java-snapshots` (l33): `--java-source-dep am.ik.servlet:servlet-httpserver:0.1.0-SNAPSHOT`
(plus `am.ik.spring:webmvc-fn-lite:0.1.0-SNAPSHOT`) runs `examples/java/guestbook` and `examples/java/mvcfn`
on the interpreter, the sources jar bringing `jakarta.servlet-api`'s through the pom. The keyvalue module did
not (`cannot read WIT wit/keyvalue.wit beside a file of a jar`) until l35 read a `@WitImport` WIT in its jar
(`.kb/java-frontend.md`, "WIT imports"): `--java-source-dep am.ik.servlet:servlet-httpserver-keyvalue:0.1.0-SNAPSHOT
--java-snapshots` alone, its pom bringing the container, `rontolisp-java-api` and the Servlet API, compiles
`examples/java/guestbook` as a component importing `wasi:keyvalue/store@0.2.0-draft2` (2026-11-22, the
`20261122.093224-1` jar).

## The snapshot tag

`ci.yaml`'s `release` and `lispresen.yaml`'s `release` move their snapshot tag
(`0.1.0-SNAPSHOT`, `lispresen-0.1.0-SNAPSHOT`) in place through
`.github/scripts/move-snapshot-tag.sh`; the release is updated, never recreated (the comment in
`ci.yaml`). GITHUB_TOKEN cannot hold the `workflows` permission, and GitHub refuses it any ref at
a commit with a `.github/workflows` file added or changed relative to the DEFAULT BRANCH's tip
(403 `Resource not accessible by integration`). Measured 2026-10-24: lispresen's run of
`71a37b3ef`, itself a workflow change, moved its tag while it was the tip; `a3de5305a`'s runs
were refused once `770fedc35` changed `ci.yaml` behind them; `0ee4585f2` moved while the tip
already had `lispresen.yaml`, which it lacked (a file only the tip has is no refusal). The
old-to-new diff is not what is checked. So a run that a workflow change overtook publishes
nothing and passes: a newer run of the same workflow publishes, and with none the script
dispatches one on the branch. A run older than the tag's commit publishes nothing either. Only a
token with `workflows` (a GitHub App or PAT secret) would let every run move the tag.

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

## Repository history

On 2026-11-26 the public history was rewritten into one commit, `37bdeb724` (tree identical
to the old `10087239d`). The old history is the private `making/rontolisp-archive`, the
local remote `archive` (push disabled): old commit IDs in `.kb`/`.todo` resolve with
`git show <id>` locally and in the archive repository, not on `making/rontolisp`. The
`.todo/history` links of earlier rows were repointed to the archive. Never push an
`archive/*` ref or a branch based on one to `origin`: it re-publishes the whole old history.

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
