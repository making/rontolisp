# d39. On Graal a value-tail chain of a few hops runs slower as real calls than as bounces

Difficulty: High

A tail call through a value is a real call until 64 are on the owner thread's stack
(`.kb/jvm-tail-bounce.md`, "Measurements: real calls while shallow"). On C2 that wins almost
everywhere; on Graal (`java Prog` on Oracle GraalVM) some shapes lose to the bounce every value
tail made before: one forwarder through a global 32 -> 58 ms, two 53 -> 99, chains of 10
through 8 state lambdas 29 -> 48, a state machine of 8 closure instances beside a CPS loop
31 -> 87 (alone in its program: 46 both ways). Graal scalar-replaces a bounce whose array
its inlined `_tramp` loop consumes when one closure type dominates the trampoline's profile,
while a real call through `_invoke_<n>` is not inlined past one recursion level. No limit
recovers them (2, 4, 16), nor splitting the counted call into its own method.

## Plan

- Measure an allocation-free bounce on the owner thread (the value tail's designator and
  arguments in owner-confined statics, a shared marker as the answer), which makes the
  trampoline cheaper on both JITs and may let the shapes above bounce again.
- Measure a dispatcher shape the JIT inlines one more level (a dense `tableswitch` over the
  funcIds instead of the binary-search tree, smaller per-arity segments).
- Keep the 1,000,000-deep pins and the 256 KB-stack owner test green; compare on both JITs
  with the shapes in the kb table, bench-report and the corpora.
