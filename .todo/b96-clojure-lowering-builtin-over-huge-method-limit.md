# b96. `ClojureLowering.builtin` exceeds HotSpot's HugeMethodLimit

Difficulty: Low

Full suite at `50f49fd24` (2026-10-03): `HugeMethodTest.everyMethodOutsideTheRunOnceListIsSmallEnoughToCompile`
fails with `am.ik.rontolisp.clojure.ClojureLowering.builtin` over 8,000 bytes of bytecode.
Every other test passes.

The method runs per call form, so it must stay JIT-compilable: split the dispatch into methods
under the limit (as the test's javadoc describes was done elsewhere), not add it to `RUN_ONCE`.
Find which recent commit pushed it over (b80/b82/b83/b88 all touched Clojure lowering) only if
it guides the split.
