# d52. On the JVM a value tail's count costs a fifth to two fifths of a hop

Difficulty: High

The count that bounds a JVM value-tail chain (`.kb/jvm-tail-bounce.md`) is two static stores
and three loads per hop -- `_vtcDepth` raised and restored, the owner compared -- that no JIT
folds. Against a prototype without it (unbounded, a measurement only), 10M calls: chains of 3 /
5 through globals Graal 147 -> 85 / 241 -> 168 ms, C2 199 -> 136 / 315 -> 246; two forwarders
Graal 73 -> 64, C2 124 -> 85. Leaving out only the owner check or only the exception handler
moves nothing. It is most of what keeps a few-hop chain behind its bounce on Graal (two
forwarders 72 against 38). The kb rejected a depth parameter without measuring it.

## Plan

- Measure the count as an argument: the value tails' own dispatcher `_vtcd<n>` takes the depth
  and hands it to the lambdas and bouncing defuns whose bodies make value tails (an `int`
  parameter more), `_vtc<n>` bounces past the limit by the argument, every other caller passes
  0 and the trampoline the limit. Inlined, the depth is a constant per hop and the checks fold.
- What it changes: those methods' descriptors and dispatcher cases; every thread makes real
  calls (no owner); the bound becomes per non-tail entry instead of per thread, so a non-tail
  recursion that interleaves chains can stack more -- check the 256 KB-stack owner test, the
  1,000,000-deep pins and such a recursion's depth on the 16 MiB worker.
- Compare on both JITs with the shapes in the kb table, bench-report, the corpora and the
  examples' sizes; a routed arity (no `_vtcd<n>`) needs the argument on `_invoke_<n>` or keeps
  the static.
