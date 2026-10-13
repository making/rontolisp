# f79 rerun: `FetchSpecE2eTest` NATIVE-leg triage (2026-10-13, GREEN, no code change)

## Parent failure (the item under triage)

- Full suite on the orchestrator host, tree `dc585b9cc`, log
  `/tmp/opencode/fulltest-wave3.log`: `FetchSpecE2eTest` ran
  `Tests run: 259, Failures: 21, Errors: 0, Skipped: 0` in 22.68 s.
- All 21 failures are the `[3]` (NATIVE) leg: `clojureHttpClient()[3][1]`
  (total-line-count: spec 261 lines, NATIVE printed 191),
  `clojureHttpClient()[3][19..37]` (cases from
  `a-byte-array-body-and-part-are-sent-as-they-are` to the end; the last
  case's slice is empty, so the native binary's output ends ~70 lines early),
  and `e2e()[3][8]` (`an-octet-vector-body-is-sent-as-it-is`: the first
  byte-vector POST arrived with an empty body). Interpreter/JVM/component
  legs of the same cases passed.
- Machine load during that phase (JUnit parallelism lines in the parent log):
  ~69-77, peaking at 353 later in the log (full suite with forkCount 7 plus
  three worker suites on the same host).

## Rerun (this worktree, merged tree `c95da8ce6` == origin/develop)

- `git fetch origin; git merge origin/develop`: already up to date.
- The worktree carried no native shims, so the first rerun SKIPPED the NATIVE
  leg (197 run, 2 skipped: `--native is not available for linux-x86_64`).
  Built the pair once with `CARGO_BUILD_JOBS=8 ./rontolisp-native/build.sh`
  (no `--test`; ~7 min) and reran:
  `./mvnw test -Dtest='FetchSpecE2eTest' -DfailIfNoTests=false
  -Dsurefire.failIfNoSpecifiedTests=false`
- Result: `Tests run: 259, Failures: 0, Errors: 0, Skipped: 0` in 27.23 s,
  BUILD SUCCESS. Machine load ~5-12 during the rerun.
- `dc585b9cc..c95da8ce6` touches no fetch/HTTP/native-runner code
  (only Clojure interop lowering, the WIT parser, docs, and their tests --
  shared frontend paths whose JVM/interpreter legs passed in the parent run),
  and nothing under `rontolisp-native/`, `src/native/`, or `src/web/`.
  So the green rerun is not explained by an intervening fix.

## Conclusion

Under-load native-leg flake (byte-body POSTs and truncated native output at
load ~70 vs green at load ~5-12). No code change. If it recurs, re-open with
both load measurements and the first-diverging case's `run.out`/`run.err`
from that run's `@TempDir`.
