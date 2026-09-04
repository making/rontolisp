# The JDK 25 AOT cache over a compiled program (measured, not shipped)

**Finding: the AOT cache halves the cold run of the two float benchmarks (mandelbrot,
matmul) and leaves the other eight inside the report's ~10% noise band -- but all of it
comes from REPLAYED PROFILES, which the JVM records only from a training run that
already ran the workload to steady state. Good for a user who runs one jar repeatedly;
NOT adoptable by bench-report, since training the JVM column is profile-guided
optimization no other column gets.** Nothing in the repo changed for this.
`.kb/jvm-double-arithmetic.md` supplies the cold-vs-steady framing and defers here.

## What the cache holds
`-XX:AOTCache` is the Leyden cache superseding AppCDS: loaded/linked classes (JEP 483)
and METHOD PROFILES (JEP 515). No compiled code -- the only product AOT flags are
`AOTCache`, `AOTCacheOutput`, `AOTClassLinking`, `AOTConfiguration`, `AOTMode`. With a
cache loaded, `AOTReplayTraining` and `AOTAdapterCaching` come on ergonomically;
`AOTClassLinking` and `AOTStubCaching` stay off. The class half buys nothing (a compiled
program is one small class plus the JDK); the profile half buys all of it.

## Mechanics that decide where it can be used
- **A directory classpath cannot be trained**: recording refuses with
  `Cannot have non-empty directory in paths`. So `-o Prog.class` (the default JVM output,
  and what `bench-report/measure.sh` builds) is out of reach; `-o app.jar` works. Any
  proposal to put a cache behind a rontolisp output must move to a jar first.
- **On GraalVM the one-command flow silently produces a useless cache.**
  `-XX:AOTCacheOutput=x.aot` spawns a child JVM that inherits `JAVA_TOOL_OPTIONS` without
  GraalVM's `--add-modules jdk.internal.vm.ci`; assembly errors, writes a cache anyway,
  and every load reports `Mismatched values for property jdk.module.addmods` and gets
  nothing. Use the two-step flow, where both JVMs are the ordinary launcher:

```bash
java -XX:AOTMode=record -XX:AOTConfiguration=app.aotconf -cp app.jar Main   # a full run
java -XX:AOTMode=create -XX:AOTConfiguration=app.aotconf -XX:AOTCache=app.aot -cp app.jar
java -XX:AOTCache=app.aot -cp app.jar Main                                  # every run after
```

- **The training run must be the real workload.** The cache persists the profile the
  program had reached when it exited, not "the compilation of this program". Measured
  threshold: a training run shorter than ~32-60 ms of work buys the later runs nothing.
  No cheap representative training run exists.

## Costs
- ~10.8 MB of cache per program, and a cache is one program's. Training costs one full
  run plus an assembling JVM.
- **The cache is keyed to the jar's path AND timestamp.** Rebuilding invalidates it.
  Failure is safe but loud: three `[error][aot]` stderr lines, then the program runs
  correctly at uncached speed and exits 0. Missing/corrupt cache behaves the same.
- Not opting in costs nothing: no flag, no file, no emission change.

## Why `bench-report/measure.sh` was NOT changed
- SBCL's `compile-file` never runs the program; AOT training profiles the full input. The
  JVM column would be profile-guided on the measured workload while five columns are not.
- It would also force the JVM column's artifact from `.class` to `.jar` purely to make
  the flag legal.

The finding lands as a labelled side table in `bench-report/notes/benchmarks.md` (beside
the "undeclared" table); the run-time table keeps measuring one cold run of a `.class`.

## CLI startup: the native binary already answers it
A cache over `java -jar rontolisp-...-exec.jar` cuts startup several-fold and, unlike the
compiled-program case, DOES transfer across workloads (it mostly removes the CLI's own
class loading), though it still favours whatever it was trained on; caches are 19-23 MB.
`target/rontolisp` beats every cached jar by 4-12x with no training step, so the AOT
cache is the second-best answer -- documented in `doc/{en,ja}/compiling/jvm.md` ("Skip
the JIT warm-up with an AOT cache") and not worth building around.

## Pinning tests
None, deliberately: no behavior, flag, or emitted byte changed. The doc section is prose
plus `bash` fences, which `DocExamplesTest` does not execute (it runs ```lisp fences). If
anything ever makes rontolisp emit or manage a cache, the first thing needing a test is
the `-o app.jar` requirement -- a mechanism pointed at `.class` output cannot work.
