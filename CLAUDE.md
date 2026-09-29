# CLAUDE.md

```bash
./mvnw clean spring-javaformat:apply compile   # compile
./mvnw clean spring-javaformat:apply package   # executable JAR (-exec classifier)
./mvnw spring-javaformat:apply test            # all tests
```

`docs-tool/`, `rontolisp-maven-plugin/` and `rontolisp-native/` are outside the root reactor
(`.kb/session-workflow.md`).

**Before changing behavior in any area, grep `.kb/` for the topic and read the matching file**
(index: `.kb/README.md`; architecture, package graph and import rules: `.kb/architecture.md`).
User-facing behavior lives in `doc/en/**` + `doc/ja/**`, mirrored in the same commit
(`.kb/documentation-site.md`). A `.kb` premise is a measurement, not a law: when a new
measurement contradicts it, write the new numbers and date into that file; a change the
measurement says is not worth its blast radius is not made.

Rules:

- No external dependencies in the core libraries; `am.ik.*` import no rontolisp package.
- The compile-path pass pipeline is `CompileFrontend.expand`; nothing may restate it.
- Behavior pinned identical across the interpreter, JVM and both WASM backends changes on
  every backend together with its pinning test. "Verified" means run on all four
  (`.kb/running-backends.md`).
- New built-in, macro or special form: `.kb/adding-primitives.md`.
- One maven run per worktree, no `src/` edits during it, never detach a run and end the turn.
- `git merge origin/develop` once, right before the final test run.
- `.todo` numbers come from `.todo/claim-number.sh`.
- After a task: `.kb/session-workflow.md`, "After task completion".
