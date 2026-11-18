# k01: what was measured (2026-11-18)

Results are in `.kb/java-frontend.md`: "java.util.concurrent" (the classes, the stamps, a
phaser's answers, a pool's hooks, the interface of a callback) and "The decisions" (a
structure's name, a field hiding one above it).

- `run4.sh File.java` -- a program under a JDK 25 and on the four backends
  (`JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar`), the lines that differ per backend;
  the numbers of objects and of pools are dropped before the comparison.
- `probes/` -- each printed a JDK's lines on four backends at the end of k01, but for the
  lines named here. `Stamped`, `Stamped2` (the stamps, the 127th reader, the views),
  `Phase2` (a phaser's refusals, `onAdvance` that throws, a tier's `onAdvance` not asked,
  the stamp conversions' corners, an interrupt ahead of a free lock), `Phase` (a line
  moves with the threads: how many workers see the end), `Phase3` (which thread is
  answered 3 moves with the load, on a JDK as here), `Phase4` (the arrival that ends the
  tree is answered the next phase), `Transfer`, `Transfer2` (an element a transfer waits
  for, removed, cleared or drained, is taken), `Counted` (differs where a JDK copies a
  task's exception and in a `ClassCastException`'s words), `Cf2` (differs in a
  `NullPointerException`'s words and in `defaultExecutor() == commonPool()`), `Pool2` (the
  hooks, the setters, `prestart..`, `purge`, the wrappers; differs in what a lambda prints
  and where two idle workers race), `Core0` (a pool with no core thread, a task that threw
  counted: both wrong before k01), `Sched` (a scheduled pool's hooks, sizes and `purge`),
  `Acc` (the accumulators; a function that is no sum with four threads differs on a JDK
  from run to run), `Hide` (the binary names in `toString()`, hidden fields, `super.x`).
- `sizes/` -- `OLD=<jar of 142d397a5> NEW=<jar> size.sh *.java`, class bytes / wasm bytes
  before -> after: `Nest` 27,303 -> 27,300 / 13,043 -> 13,043; `Exec` (a fixed pool)
  274,688 -> 278,314 / 224,454 -> 226,375; `Hooked` (the same with an empty
  `afterExecute`) 279,093 -> 317,014 / 224,985 -> 239,533; `Single` 320,108 -> 327,579 /
  238,152 -> 241,674; `Cf` 326,051 -> 330,003 / 243,605 -> 245,799; `Sched1` 345,962 ->
  352,031 / 250,061 -> 252,774 (348,553 B of wasm while the table's end was a
  `Runnable`); k00's `Counter` 126,133 -> 126,421 / 109,069 -> 109,253;
  `examples/java/shop` 413,654 -> 414,275 / 326,075 -> 326,221.
