# Spike sources for `.todo/527`

`aref-N.lisp` reads a random element of an N-element GENERAL array 10^7 times;
`draw-N.lisp` is the identical loop with the `aref` removed. The per-access cost
is the difference divided by 10^7 -- the size sweep is the whole finding, because
it is flat in N on SBCL and linear in N here.

`aref-ub32-1000000.lisp` and `aref-double-1000000.lisp` are the same read against
the two representations that already pack (`.kb/packed-integer-vectors.md`, the
packed floats), i.e. the measured ceiling of the fix.

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
D=.todo/527-a-general-arrays-elements-are-boxed-so-a-random-aref-is-two-cold-hops

for n in 1000 10000 100000 1000000; do
  for k in aref draw; do
    sbcl --script $D/$k-$n.lisp
    java -jar $JAR $D/$k-$n.lisp -o /tmp/A.class --class-name A && java -cp /tmp A
  done
done

perf stat -e cycles,instructions,dTLB-load-misses java -cp /tmp A
java -XX:+UseTransparentHugePages -cp /tmp A        # isolates the TLB half
```
