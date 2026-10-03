# c21. On the JVM a bounce costs every shallow tail call through a value

Difficulty: High

Every tail call through a value in a defun, a lambda or an `apply` bounces
(`.kb/jvm-tail-bounce.md`): an `Object[]` per call and a round trip through `_tramp`'s
megamorphic `_invoke_<n>`, which also stops the JIT from inlining the chain. Most such calls
are shallow -- an adapter closure, a `compose`d function, a `reduce :from-end` step -- and
pay that for a depth they never reach. Measured 2026-10-03 (`java Prog`, best of 7): 10M
calls through two forwarding closures 3 -> 171-209 ms, through a `compose`d closure 137-160
-> 211-224 ms; a defun's value tail has paid the same since the trampoline landed.

## Plan

- Bounce only when deep: a value tail call is a real call (the result passed on unchecked)
  while a per-thread count of the value-tail frames on the stack is under a limit, and a
  bounce past it, so a chain unwinds to the nearest checking frame every LIMIT hops.
- What has to hold: the count is per thread (a static cell drifts under threads, and a
  negative drift lets a chain grow unbounded; any program can call Lisp from several Java
  threads through `jvm-export` or a `java:` callback), it is restored on a non-local exit
  (else it only drifts up, which degrades to bouncing every call), and it costs less than a
  bounce on the shallow path.
- Measure against the numbers above, bench-report, the spec corpora and the examples; keep
  the 1,000,000-deep pins of `.kb/jvm-tail-bounce.md` green.
