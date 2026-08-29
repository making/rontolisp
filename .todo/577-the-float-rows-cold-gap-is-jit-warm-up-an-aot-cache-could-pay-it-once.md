# 577. The float rows' cold gap is JIT warm-up; an AOT cache could pay it once

Difficulty: Medium

todo-569's premise measurement (`.kb/jvm-double-arithmetic.md`): with the
declared-float emission in, `mandelbrot` runs 21-26 ms at steady state against
SBCL's 30, but the bench-report row is a single COLD run and reads ~90 ms --
the remaining distance to SBCL is the pre-C2 tiers executing and Graal
compiling, not emitted-code quality. SBCL pays its compilation once, in
`compile-file`; a `java Prog` process pays it again every run.

JDK 25 ships the Leyden AOT cache (`-XX:AOTCacheOutput` on a training run,
`-XX:AOTCache` thereafter; supersedes plain AppCDS): loaded/linked classes and
profiled/compiled code persist across runs of the SAME classpath. Measure
whether a cache trained on one benchmark run moves the cold row toward the
32-36 ms raw-Java ceiling, and if it does, decide where the training step
belongs -- `measure.sh`'s build step is the natural place (SBCL's build column
already holds its compile time, so a train-then-run protocol is symmetric,
and the build column should carry the training cost honestly). Also worth
measuring for `java -jar rontolisp.jar` itself (interpreter startup).

Out of scope: changing what the report MEASURES (a cold run is what a CLI user
gets; the notes now explain the warm-up share). This item is about letting a
compiled program legitimately not re-pay JIT compilation per process.
