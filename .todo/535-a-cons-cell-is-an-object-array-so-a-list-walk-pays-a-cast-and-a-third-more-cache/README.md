# Spike sources for `.todo/535`

`NthSum.java` is `.todo/517`'s `nth-toplevel.lisp` written in Java with a
hand-rolled cons (`record Cons(long car, Cons cdr)`) -- the honest baseline,
because `NthSumLinked.java` (`LinkedList<Long>.get(999)`) is NOT the same work:
`LinkedList` walks from whichever end is nearer, so index 999 of 1000 arrives in
ONE step and the program runs 10^6 steps instead of 10^9.

`NthSumBoxedCons.java` boxes only the `car` (`record Cons(Long, Cons)`), which
isolates how much of the gap boxing explains -- 2.05 ns/step against the
primitive shape's 1.51 and rontolisp's 3.28.

`nth-small.lisp` + `NthSmall.java` are the footprint A/B: a 100-cell list walked
10^7 times is the same 10^9 steps over a tenth of the memory. rontolisp goes
3.28 -> 2.07 ns/step; the Java number there is unusable (C2 hoists the
loop-invariant walk).

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
D=.todo/517-sbcl-class-performance-on-the-compiled-backends
java -jar $JAR $D/nth-toplevel.lisp -o Rnth.class --class-name Rnth
javap -c -p Rnth.class | sed -n '/_nthcdr/,/^$/p'   # the per-step checkcast
```

Subtract the JVM startup baseline (`.todo/534/Nop.java`, ~0.087 s here) before
dividing by 10^9 steps.
