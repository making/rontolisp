# The JDK 25 AOT cache over a compiled program (measured, not shipped)

**Finding: the AOT cache halves the cold run of the two float benchmarks and leaves the
other eight inside the report's noise band -- but every millisecond of it comes from
REPLAYED PROFILES, which the JVM only records from a training run that already ran the
workload to steady state. So it is a real answer for a user who runs one jar repeatedly,
and it is NOT an answer the bench-report can adopt: training the JVM column would mean
running each benchmark at full size to profile it, which is profile-guided optimization
no other column in that table gets.** Nothing in the repository changed for this; the
file exists so the next person does not re-run the experiment. `.kb/jvm-double-arithmetic.md`
is where the cold-vs-steady framing comes from and where it ends ("an AOT/CDS-style answer
is out of this file's scope") -- this is that scope.

## What the JDK 25 cache actually holds

`-XX:AOTCache` is the Leyden cache that supersedes plain AppCDS. On this JDK it carries
loaded/linked classes (JEP 483) and METHOD PROFILES (JEP 515). It does **not** carry
compiled code: `java -XX:+PrintFlagsFinal` lists exactly five product AOT flags
(`AOTCache`, `AOTCacheOutput`, `AOTClassLinking`, `AOTConfiguration`, `AOTMode`) and no
code-cache flag at all. With a cache loaded, `AOTReplayTraining` and `AOTAdapterCaching`
come on ergonomically; `AOTClassLinking` and `AOTStubCaching` stay off.

The split is measurable, and it is lopsided. Same cache, same jar, mandelbrot:

| | cold in-program ms |
| --- | ---: |
| no cache | 92-99 |
| cache, `-XX:-AOTReplayTraining` (class data only) | 98-105 |
| cache, as shipped | 55-57 |

**The class half buys nothing here and the profile half buys all of it.** A compiled
rontolisp program is one class of a few kilobytes plus the JDK; there is no class-loading
cost to remove. What the profile removes is the interpreter and C1 tiers running the hot
method while the JIT works out that it is hot.

## Two mechanics that decide where this can be used

**A directory classpath cannot be trained.** The recording step refuses one outright:

```
[error][aot] Error: non-empty directory 'mandel'
Error occurred during CDS dumping
Cannot have non-empty directory in paths
```

So `-o Prog.class` -- the documented default JVM output, and what
`bench-report/measure.sh` builds -- is out of reach on its own. `-o app.jar` works. Any
proposal to put an AOT cache behind a rontolisp output has to move that output to a jar
first.

**On GraalVM the one-command flow silently produces a useless cache.**
`-XX:AOTCacheOutput=x.aot` spawns a child JVM to assemble the cache, and that child
inherits `JAVA_TOOL_OPTIONS` without GraalVM's `--add-modules jdk.internal.vm.ci`. The
assembly reports an error, writes a cache anyway, and every later run that loads it
reports the mirror-image error and gets nothing:

```
[error][aot] Mismatched values for property jdk.module.addmods:
             jdk.internal.vm.ci specified during runtime but not during dump time
[error][aot] Disabling optimized module handling
```

The two-step flow avoids it, because the recording and creating JVMs are both the ordinary
launcher:

```bash
java -XX:AOTMode=record -XX:AOTConfiguration=app.aotconf -cp app.jar Main   # a full run
java -XX:AOTMode=create -XX:AOTConfiguration=app.aotconf -XX:AOTCache=app.aot -cp app.jar
java -XX:AOTCache=app.aot -cp app.jar Main                                  # every run after
```

Same jar, same program, mandelbrot cold in-program, best/median/worst of 7: no cache
83/95/99, one-command cache 84/96/103, two-step cache **47/51/54**. A measurement that
reports "the AOT cache does nothing" on GraalVM has almost certainly used the one-command
flow; the cache sizes give it away too (9.9 MB without the training data, 11.3 MB with).

## The training run has to be the real workload

This is the constraint that keeps it out of the benchmark harness. `mandelbrot` with the
grid size taken from `%host-argv`, so ONE jar can be trained on a small input and measured
on the full one (the cache is keyed to the jar, not to the arguments):

| cache trained at | the training run's own ms | measured at n=400, ms |
| ---: | ---: | ---: |
| no cache | -- | 86-99 |
| n=40 | 5 | 95-100 |
| n=100 | 17 | 81-109 |
| n=200 | 32 | 91-94 |
| n=300 | 60 | 55-56 |
| n=400 | 81 | 55-65 |

The threshold sits between 32 ms and 60 ms of training work -- that is, the training run
buys the later runs nothing until it has itself run long enough to finish warming up. The
cache does not persist "the compilation of this program"; it persists "the profile this
program had reached when it exited". A cheap representative training run does not exist.

## What it is worth, per benchmark

`bench-report/programs/*.lisp`, `-o Bench.jar`, cold in-program ms (the number the report's
run-time table holds), best/median of 5 runs each, alternated:

| benchmark | no cache | AOT cache | median change |
| --- | ---: | ---: | ---: |
| fib | 73 / 82 | 71 / 74 | -10% |
| **mandelbrot** | 82 / 96 | 48 / 56 | **-42%** |
| **matmul** | 90 / 94 | 48 / 57 | **-39%** |
| sieve | 438 / 506 | 426 / 460 | -9% |
| sort | 427 / 452 | 393 / 399 | -12% |
| hash | 491 / 512 | 436 / 460 | -10% |
| string | 229 / 242 | 224 / 244 | +1% |
| clos | 88 / 101 | 85 / 94 | -7% |
| bignum | 303 / 307 | 305 / 310 | +1% |
| list | 357 / 401 | 369 / 375 | -6% |

Only the two float rows move past the report's own ~10% noise threshold, and they are
exactly the rows `.kb/jvm-double-arithmetic.md` identified as mostly warm-up: their
emitted code is already at steady state ahead of SBCL, so removing the warm-up is all
there is left to remove. A row whose cold time is real work (`sieve`, `hash`, `sort`) has
little warm-up share to give back. A 15-round alternated run on mandelbrot puts it at
89/96/103 without and 50/56/61 with (wall clock 193/200/208 against 112/120/128).

## What it costs

- **10.8 MB of cache per program**, and a cache is one program's. Training costs one full
  run of the program plus a second JVM to assemble it: 1.2-1.8 s per benchmark here,
  against ~1.0 s to compile the same benchmark.
- **The cache is keyed to the jar's path AND its timestamp.** Rebuilding the jar
  invalidates it. The failure is safe but loud: three `[error][aot]` lines on stderr, then
  the program runs correctly at the uncached speed and exits 0. A missing or corrupt cache
  file behaves the same way. So a stale cache costs correctness nothing and costs a
  program's stderr three lines that read like a crash.
- **A user who does not opt in pays nothing at all.** No flag, no file, no emission change
  -- this is entirely a launch-time decision by whoever runs the jar.

## Why `bench-report/measure.sh` was NOT changed

todo-577 proposed the harness's build step as the natural home, on the argument that
SBCL's build column already carries its `compile-file` time so a train-then-run protocol
is symmetric. The measurements above say it is not:

- `compile-file` never runs the program. It decides machine code from the source and the
  declarations, with no input. The AOT training run decides a profile from the input, and
  the table shows it must be the full input. The JVM column would be profile-guided on the
  measured workload while five columns beside it are not.
- It would also require moving the JVM column's artifact from `.class` to `.jar` purely to
  make the flag legal -- changing the measurement protocol to make one column look better.

The report's job is comparability, so the finding lands as a labelled side table in
`bench-report/notes/benchmarks.md` (next to the "undeclared" table, which is the existing
precedent for a measured aside), and the run-time table keeps measuring one cold run of a
`.class`, which is what a CLI user gets.

## The CLI's own startup, and why the native binary already answers it

The same cache over `java -jar rontolisp-...-exec.jar` is the biggest single number here,
and it is also the one with an existing better answer. Wall clock, median of 9-12 runs:

| invocation | `java -jar` | + cache trained on `noop.lisp` | + cache trained on a compile | native binary |
| --- | ---: | ---: | ---: | ---: |
| `noop.lisp` | 476 | **144** | 334 | **12** |
| interpret a small program | 620 | 310 | 496 | **118** |
| `-o out.class` | 978 | 834 | **480** | **109** |
| `-o out.wasm` | 1,054 | 926 | **775** | **114** |

Unlike the compiled-program case this DOES transfer across workloads -- a cache trained on
one invocation helps a different one -- because most of what it removes is the CLI's own
class loading rather than a hot loop's profile. It is still shaped by its training run:
train on `noop` and startup wins most, train on a compile and the compile path wins most
(978 -> 480 ms). The caches are 19.4 MB and 23.2 MB.

But `target/rontolisp` beats every cached jar by 4-12x on all four rows, needs no training
step and no 20 MB side file, and is already what `bench-report/measure.sh` prefers when it
is built. So the AOT cache is the second-best answer to CLI startup, worth documenting for
someone running the jar without a GraalVM to hand
(`doc/{en,ja}/compiling/jvm.md`, "Skip the JIT warm-up with an AOT cache"), and not worth
building anything around.

## Pinning tests

None, and deliberately: no behavior changed, no flag was added, no emitted byte moved. The
doc section is prose plus `bash` fences, which `DocExamplesTest` does not execute (it runs
```lisp fences). If a future item ever makes rontolisp emit or manage a cache, the first
thing that needs a test is the `-o app.jar` requirement above -- a mechanism pointed at a
`.class` output cannot work at all.

## Numbers (2026-08-29)

Ubuntu 24.04 LTS, Linux 6.8.0-136-generic, x86_64, Intel Xeon E5-2697A v4 @ 2.60GHz,
64 logical cores, 252 GiB. Oracle GraalVM 25.0.4+7.1 (JVMCI/Graal JIT, the default).
Best/median as marked per table; every A/B alternated baseline and cache within one loop
so machine drift lands on both.
