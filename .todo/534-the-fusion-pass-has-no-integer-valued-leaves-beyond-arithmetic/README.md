# Java comparison sources for `.todo/534`

The Java half of the 2026-08-26 comparison against `.todo/517`'s rows. Each file
is the same program as the matching spike in
`.todo/517-sbcl-class-performance-on-the-compiled-backends/`, written the way a
Java programmer would write it -- twice, because the answer differs:

| file | shape |
| --- | --- |
| `LoopSum.java` | `long` accumulator, plain `for` -- the floor |
| `LoopSumStream.java` | `LongStream.rangeClosed(...).sum()` |
| `LoopSumBoxed.java` | `.boxed().reduce(0L, Long::sum)` -- one box per iteration |
| `RandomSum.java` | `ThreadLocalRandom.nextInt` |
| `RandomSumBoxed.java` | `new Random()` (a shared `AtomicLong` CAS) + `Long` accumulator |
| `ArefSum.java` | `long[]` |
| `ArefSumList.java` | `ArrayList<Long>.get` |
| `Nop.java` | prints 0 -- the JVM startup baseline to subtract |

```bash
javac *.java
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
D=.todo/517-sbcl-class-performance-on-the-compiled-backends
java -jar $JAR $D/aref-toplevel.lisp -o Raref.class --class-name Raref
javap -c -p Raref.class | less     # the unfused hot loop this item is about

# best of 5, wall clock; subtract Nop (JVM ~0.087 s) or an empty sbcl --script (~0.007 s)
for r in 1 2 3 4 5; do /usr/bin/time -f %e java -cp . ArefSum; done
```

Measure the JVM rows on the second run onward -- the first pays page cache.
