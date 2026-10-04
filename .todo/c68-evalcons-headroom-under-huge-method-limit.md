# c68. `LispEvaluator.evalCons` is 212 bytecodes under HotSpot's HugeMethodLimit

Difficulty: Medium

Measured 2026-10-04: `evalCons` is 7,788 bytecodes (7,717 before c62's `deferTo`/`BlockTailExit`,
6,812 when the tail-call loop landed). Past 8,000 HotSpot never JIT-compiles it and every
interpreted form runs in the bytecode interpreter (2.7x measured when it last crossed,
`.kb/hot-path-method-size.md`). `LispEvaluatorHotMethodSizeTest` fails the build at the cliff,
so the next arm that grows the method will have to split it under time pressure.

## Plan

- Find arms of `evalCons`'s hot switch that are rare or that only compute `next` from a
  built-in expansion (`builtinMacroExpansion(cons, ...)`) and move them to
  `rareOperatorExpansion` (4,446) -- the split is by KIND (`.kb/interpreter-tail-calls.md`, "The
  operator table is three methods"), so an expansion arm may move there without losing its tail
  position.
- Measure before and after (fib, a loop-heavy and a macro-heavy program, alternating runs):
  an ordinary call already misses three switches, and a moved hot arm costs a miss of its own.
- Target: a few hundred bytecodes of headroom; record the new size in
  `.kb/interpreter-tail-calls.md` and `.kb/hot-path-method-size.md`.
