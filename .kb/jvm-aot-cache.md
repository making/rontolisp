# The JDK 25 AOT cache over a compiled program (measured, not shipped)

**Finding: the AOT cache halves the cold run of the two float benchmarks (mandelbrot,
matmul) and leaves the other eight inside the report's ~10% noise band — but all of it
comes from REPLAYED PROFILES, recorded only by a training run that already ran the
workload to steady state. Not adoptable by `bench-report/measure.sh`: training the JVM
column is profile-guided optimization no other column gets.** Nothing in the repo
changed. `.kb/jvm-double-arithmetic.md` defers here.

## What it holds
`-XX:AOTCache` (Leyden, superseding AppCDS) holds loaded/linked classes (JEP 483) and
METHOD PROFILES (JEP 515), no compiled code. Product flags: `AOTCache`, `AOTCacheOutput`,
`AOTClassLinking`, `AOTConfiguration`, `AOTMode`. The class half buys nothing here; the
profile half buys all of it.

## Constraints
- **A directory classpath cannot be trained** (`Cannot have non-empty directory in
  paths`), so `-o Prog.class` is out of reach; `-o app.jar` works. Any proposal to put a
  cache behind a rontolisp output must move to a jar first.
- **On GraalVM the one-command `-XX:AOTCacheOutput=` flow silently produces a useless
  cache** (child JVM inherits `JAVA_TOOL_OPTIONS` without `--add-modules
  jdk.internal.vm.ci`; every load reports `Mismatched values for property
  jdk.module.addmods`). Use the two-step `AOTMode=record` -> `AOTMode=create` flow.
- **The training run must be the real workload**; measured threshold, a run shorter than
  ~32-60 ms of work buys later runs nothing. No cheap representative training run exists.
- ~10.8 MB per program, and a cache is one program's. **Keyed to the jar's path AND
  timestamp** — rebuilding invalidates it; failure is safe but loud (three
  `[error][aot]` lines, then correct at uncached speed, exit 0). Not opting in costs
  nothing.

The finding lands as a labelled side table in `bench-report/notes/benchmarks.md`; the
run-time table keeps measuring one cold run of a `.class`.

## CLI startup
A cache over the exec jar cuts startup several-fold and DOES transfer across workloads
(caches 19-23 MB), but `target/rontolisp` beats every cached jar by 4-12x with no
training step. Documented in `doc/{en,ja}/compiling/jvm.md` ("Skip the JIT warm-up with
an AOT cache").

## Tests
None, deliberately: no behavior, flag or emitted byte changed, and the doc section is
`bash` fences, which `DocExamplesTest` does not execute. If rontolisp ever emits or
manages a cache, the first thing needing a test is the `-o app.jar` requirement.
