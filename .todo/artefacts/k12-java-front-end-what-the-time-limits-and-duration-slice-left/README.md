# k12: what was measured (2026-11-19)

Results are in `.kb/java-frontend.md`: "java.util.stream", "Inference" (what the
collectors needed) and "The decisions" (an intrinsic class counts where the program can
hold its values).

- `compare.sh File.java [all]` -- a probe under a JDK 25 and on the interpreter (with
  `all`: the JVM, wasm and the component too), the lines that differ
  (`JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar`). k05's script.
- `refused.py File.java` -- the lines of a probe the front end refuses, each commented
  out in a copy until the rest lowers: how the inference's gaps were listed.
- `probes/` -- each printed a JDK's lines on four backends at the end of k12, but where
  said.
  - `Lazy` (216 lines): which stage sees which element when, what `count()` runs, the
    close handlers, what a stream refuses; the corpus case
    `a-stream-hands-its-elements-on-one-at-a-time` as a file.
  - `Sized` (114 rows; a JDK alone, the library lacking four of its classes): each source
    and stage asked for its count, and for it past a `skip`, a `limit` and a sort --
    `known` where no stage ran. Two rows differ here: a PARALLEL stream's
    `sorted().skip(1).count()` runs under a JDK.
  - `Sum` (a JDK alone): 20,000 seeded sums of doubles against four summations. Plain
    9,816, Kahan 19,879, Kahan with the last compensation subtracted 20,000, Neumaier
    15,002.
  - `Sources`: `Arrays.stream`'s ranges, `splitAsStream`, `results`, `lines`, `chars`.
    One line differs: `"😀".chars()` is one code point here, two units under a JDK.
  - `Marks` (102 lines): `characteristics()` of each collector and of each made of
    another; the statistics' constructors.
  - `Collecting` (127 lines), `Numbers` (60), `Everyday` (80): the collectors, the number
    streams and the chains of everyday code; the corpus cases
    `a-collector-gathers-a-stream-and-the-type-arguments-are-found` and
    `the-streams-a-program-writes-over-collections-arrays-texts-and-numbers` hold them
    (an NPE's message, which a JDK words, is its class there).
  - `Literals`: `Name.class::isInstance` and `::cast`.
  - `Mock`: the collector shapes over a program's own classes, written before the
    library to ask the inference. At the start of k12 it stopped at
    `collect(groupingBy(s -> s.length()))`.
- `bench/` -- `run.sh`: `Stages.java`'s shapes on the JVM class, the precompiled wasm
  and a JDK, ns an element (1,000,000 elements, 20 rounds, best of three; a shape is
  chosen by the variable `WHICH`, a program's arguments being empty).
- `sizes/` -- `JAR=<jar> size.sh *.java`, class bytes / wasm bytes: `SzLoop` 84,402 /
  57,341, `SzCount` 94,261 / 61,728, `SzLoopList` 145,414 / 133,052, `SzMapList`
  169,378 / 144,046, `SzToList` 176,782 / 147,040, `SzSorted` 192,371 / 157,605,
  `SzGroup` 217,664 / 167,895, `SzIntLoop` 47,200 / 32,230, `SzInts` 91,757 / 61,457.
  `ListOnly` (no stream) is 192,551 / 156,105 before k12 and 192,551 / 156,087 after; a
  class's bytes move with the length of the file's path.
