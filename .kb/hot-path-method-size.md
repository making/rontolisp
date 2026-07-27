# Hot-path method size: HotSpot's HugeMethodLimit

**Invariant: no method that runs per evaluated form, or per indirect call, may
exceed 8000 bytecodes.** `-XX:+DontCompileHugeMethods` is a HotSpot default and
refuses to JIT-compile ANY method above `HugeMethodLimit` (8000 bytes of
bytecode); such a method runs in the bytecode interpreter for the lifetime of the
process. Nothing reports it -- no warning, no flag in a stack trace, and every
functional test still passes. It shows up only as a program that is several times
slower than it should be, which is exactly how it hid twice (todo 188).

Both sites below are dispatch tables whose size tracks how much the program
loads, so the cliff is crossed by adding a LIBRARY, not by editing the method.

## `LispEvaluator.evalCons` (the interpreter)

The operator table -- a `switch (sym.name())` over ~200 case labels -- had reached
8209 bytecodes, so the interpreter's innermost method was never compiled. Split
into `evalCons` (the first half) and `evalConsRareOperator` (the second half,
answering the private `UNHANDLED` sentinel for an operator it does not claim,
which also covers the arms that deliberately fall through to the ordinary
function call: `read`, the `floor` family with a divisor, `reduce`, `sort`).
Worth **2.7x** on an arithmetic-heavy program by itself.

Adding an operator adds bytecode to whichever half holds it. Both halves sit near
4 KB today; `LispEvaluatorHotMethodSizeTest` parses the compiled class and fails
the build if ANY method of `LispEvaluator` crosses the limit. Split again rather
than raising the constant.

## `_invoke_<arity>` (the JVM backend's indirect-call dispatch)

`JvmRuntimeBuilder.buildDispatchMethods` emits one dispatcher per call arity,
holding a case per callable of that arity -- and a variadic function matches every
arity at or above its required count, so the tables grow fast. At cl-postgres
scale the arity-1 table alone held 547 cases / ~15 KB, well past the cliff, and
every indirect call in the program (SHA-256's `rol32`, every `funcall`, every
generic-function call) ran interpreted.

Two changes, both in `JvmRuntimeBuilder`:

- `DISPATCH_SEGMENT_BUDGET` is **6000**, below the 8000 cliff (it used to be
  24000, chosen only against the 64 KB class-file method limit). Cases are
  id-sorted and split into segments under that budget.
- Within a segment the old linear `if (id == funcId)` chain is now a **binary
  search tree** over the sorted funcIds (`emitDispatchTree`), and with more than
  one segment `_invoke_<arity>` becomes a router that bisects the segment
  boundaries and tail-calls `_invoke_<arity>$<k>` (`emitSegmentRouter`). Case
  bodies are rendered once, branch-free, so they can be spliced anywhere in the
  tree.

Both matter, but the budget is the one worth the most: `-XX:-DontCompileHugeMethods`
alone recovered 2.8x on the measured workload, i.e. nearly all of it.

A true O(1) `tableswitch`/`lookupswitch` is still available and is NOT used: the
emitters, `JvmClassShaker` and `StackMapAugmenter` would all have to learn to
decode those variable-length instructions
([stackmap-augmenter.md](stackmap-augmenter.md) lists it as unstarted). Binary
search costs ~10 comparisons at 600 callables versus one, which is noise next to
the ~300 the chain averaged, and needs no new opcode anywhere in the toolchain.

## The measurement that pins it

PBKDF2-HMAC-SHA256, 4096 iterations, ironclad from Quicklisp (2026-07-27,
linux/x86-64, exec jar, wasmtime 47):

| | ironclad alone | inside the cl-postgres stack | tax |
| --- | --- | --- | --- |
| JVM before | 4.9 s | 20.4 s | 4.2x |
| JVM after | 0.70 s | 0.83 s | **1.02x** |

The control that isolates the mechanism: 1200 defuns that are DEFINED and
referenced as values but never called (so they only enlarge the dispatch tables)
leave the JVM unchanged (838 -> 795 ms) and cost the WASM backend 2.0x (7.3 ->
14.3 s) -- see the open item in `.todo/188`, whose `br_table` dispatcher still
carries every case body inline in one function.

## Not covered by the invariant

`Jvm/WasmExprCompiler.compileCons` (19-20 KB) and the two `compile` methods
(10 KB) are past the limit and stay there: they run once per AST node at COMPILE
time, where an interpreted run costs a fraction of a second on the whole program,
not a factor on every evaluated form.
