# Split the CI `test` job by scope so suite growth stops raising the heap floor

Difficulty: Medium

The `test` job failed five consecutive times on 2026-10-21 (runs for
`5ad8125f9`, `8358ac39d`, `7e13dcefc`, `47fe676bb`, `ce626f0f3`); three with a
confirmed `OutOfMemoryError: Java heap space` and zero assertion failures,
stacks in unrelated compile paths (`JvmHandlerCaseCompiler`,
`LambdaLists.desugar`, the bare engine crash) -- cumulative heap pressure in
the reused surefire forks past ~19k tests, not one bad test. (The other two
failed the same job at the same scale; their step logs were already
unrecoverable, so they count as scale evidence, not signatures.) The stopgap
is `-Xmx6g` in surefire's `argLine` (`pom.xml`, `.kb/test-execution.md` "Each
fork's heap"): two forks times 6 GB is the most the 16 GB runner affords
beside Maven itself and the wasmtime host compiles. The next growth spurt has
nowhere to go -- a third fork does not fit, and `-Xmx` cannot exceed the
runner.

## What to build

- Partition the `test` job into two CI jobs by scope -- the natural cut is
  `am.ik.rontolisp.e2e.*` (63 classes: subprocess-spawning, wasmtime compiles,
  network) versus everything else (361 classes) -- each on its own runner with
  its own heap budget, via `-Dtest` patterns or surefire includes/excludes.
  Measure heap and wall time per shard first, then size each shard's floor
  from its own numbers (halving `-Xmx6g` is the hypothesis, not the premise).
- Update the `needs:` chains in `.github/workflows/ci.yaml` (`native-stubs`,
  `native-image`, `maven-plugin`, `deploy` and everything downstream of them)
  to wait for BOTH shards. The split is CI-only: a local `./mvnw test` still
  runs the whole suite in one reactor, unchanged.
- Partition-safety is already the documented contract: a fork is a separate
  JVM process and surefire guarantees neither which fork nor what order
  (`.kb/test-execution.md`, "Sequencing"), and the E2E classes are built for
  multi-process coexistence (per-PID scratch dirs, ephemeral ports, "Two
  builds on one machine"). The todo is done when that contract is shown to
  hold across jobs, not just across forks: no shared file, port, `/tmp` name
  or cache may couple the shards (the `wpc-sub` incident in "A test that runs
  a program in the project root" is the shape to re-check).

## Definition of done

- Two jobs, each green on `develop` with heap headroom measured (peak fork
  RSS or GC log against the floor -- a number in `.kb/test-execution.md`,
  dated, not "it passed").
- A third scope's growth path is written down: what the NEXT split is when a
  shard fills its runner again (by package? by class-name hash? -- decide and
  record, do not build it).
- `./mvnw test` locally still runs everything; `gh run view` of one push
  shows both shards plus all downstream jobs green.

## Non-goals

- `reuseForks=false` (fresh JVM per class kills accumulation, but 424 classes
  times JVM boot plus JIT warm-up is the wall clock the fork model exists to
  avoid -- measure it only as the fallback if two shards do not fit).
- Sharding finer than two (matrix by class hash is flaky-hostile: surefire
  ordering is unspecified and cache keys multiply; two shards suffice while
  they do).
- Lowering memory per test (static-registry diets, wasmtime-module caching):
  profiling work, unbounded -- file it separately if the shard measurements
  point at one owner.

## Context

Cut 2026-10-21 after the `-Xmx6g` stopgap (`b42988628`): the five
consecutive `test`-job failures above, three with the confirmed heap
signature. E2E count at cut time: 63 of 424 test classes.
(`ci-spec.yaml` already runs only in the native job, so the corpus is not the
pressure here; the `examples` job never runs on a pull request.)
