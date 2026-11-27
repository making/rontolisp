# n05. JVM: a Java long[] reads Long.MIN_VALUE through a bail, and one bail slows its loop for good

Difficulty: High

A Java `new long[n]` is `(make-array n :initial-element 0)`, a plain general array, whose
packed shape keeps `Long.MIN_VALUE` as nil's sentinel: a fused read or store of that integer
bails into the twin (`.kb/jvm-int-fusion.md`, "Declared arrays"). Once a JIT has seen the
bail it compiles it as a call, and no longer lifts over it what the loop asks each round --
the question a fused method asks of its storage ("Storage resolved once") among them.

Measured 2026-11-27 (Xeon E5-2697A v4, Oracle GraalVM 25.0.5.1, median of rounds 6-11, five
alternated pairs at load 7-9, ms), SHA-512 of 4 MiB, whose padding word is that integer,
against 4 MiB plus one byte, which never bails: C2 72-74 against 52-54, Graal 52-55 against
45-46. The second pair is the ceiling.

- A Java `long[]` can hold no nil: Java stores only longs into it. Price a packed array that
  says so where it is made (the Java front end's own constructor), whose word is the integer
  whatever its value: no sentinel question, no twin, no list slot for the integer. Decide
  what a store through Lisp of a value that is no such integer does (it widens today), and
  keep every reader of the packed shape answering alike (`.kb/adjustable-arrays.md`,
  "Representation": `emitSentinelElement`'s callers, `JavaBridgeTemplate.marshal`, `_jseq`).
- Not the way: a cheaper question. A count the widening bumps measured C2 58.5-62, Graal
  47.5-52 on the same 4 MiB, but it is unsound (`.kb/jvm-int-fusion.md`, "Storage resolved
  once", "Rejected") and leaves the twin where it is.
