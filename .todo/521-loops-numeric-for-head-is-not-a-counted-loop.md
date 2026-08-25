# 521. `loop for i from a to b` is not a counted loop, only `dotimes` is

Difficulty: Medium (the lowering exists and is proven; this extends its
eligibility scan to a second, more general iteration head)

Child of `.todo/517`.

## The defect

`WasmDotimesCompiler` gives a `dotimes` over a literal bound an unboxed `i64`
induction variable with no boxed shadow, and it is not a speed-for-size trade --
it emits less code AND runs faster (`.kb/wasm-counted-loops.md`). `loop`'s
numeric head does not get it. `LispMacroExpander.LoopExpander` lowers
`for i from a to b` into a `let*` plus a `while` over a generic `<=` test with a
generic `(setq i (+ i 1))` step (`.kb/loop-iteration-heads.md`), and that
expansion is what every backend compiles.

The two shapes are the same loop, and on wasm they differ by 2x:

| program, 10^8 iterations, inside a `defun` | wasm-GC | JVM |
| --- | --- | --- |
| `(dotimes (i 100000000) (setq s (+ s i)))` | 0.414 s | 0.452 s |
| `(loop for i from 1 to 100000000 sum i)` | **0.833 s** | 0.394 s |

SBCL does the `loop` spelling in 0.208 s. So on wasm the idiomatic CL spelling
costs 2.0x its own `dotimes` and 4.0x SBCL, while the JVM -- which has no
counted-loop pass at all and pays boxed arithmetic in both (`.todo/412`) --
happens to be flat between them at 1.9x SBCL.

## What to build

Extend the counted-loop treatment to `loop`'s numeric `for` head, i.e. a
`for VAR from A to|below|downto|above B [by S]` clause. The eligibility proof is
the same one `.kb/wasm-counted-loops.md` already writes down and must be
re-established, not assumed, for the more general head:

- the variable is never captured, never assigned in the body, never made
  special, never read by anything that could see a boxed identity;
- the bound and step are integers -- `dotimes`' rule is a LITERAL bound, and
  `loop`'s bound is commonly a computed expression, so decide whether the scan
  proves integrality or the loop guards once at entry and bails to the boxed
  expansion (`JvmTypedLoopCompiler`'s entry-guard shape, `.kb/jvm-typed-loops.md`,
  is the reference for the guard-and-bail alternative);
- the `and`-chained iteration head must keep CL's clause-order semantics -- a
  later clause's assignments do not run when an earlier one terminates -- so a
  multi-clause `loop` either keeps the general lowering or the fast head is
  emitted only for the clause that is provably first and alone.

The accumulator is the other half and is worth measuring separately: `sum`,
`count`, `maximize` and `minimize` all step a single integer that the same
argument keeps unboxed.

Do this on wasm first, where the pass exists and the 2x is measured. Whether the
JVM gets its own counted-loop pass is really `.todo/412`'s question -- an
unboxed induction variable there is one instance of "keep a value unboxed across
operations", and building it twice would be the wrong shape.

## Acceptance

- `(loop for i from 1 to 100000000 sum i)` inside a `defun` lands within 10% of
  the equivalent `dotimes` on wasm-GC.
- A `loop` whose variable is captured, assigned in the body, or non-integral
  compiles to today's expansion and prints today's answer -- including the
  overflow promotion to exact arbitrary precision.
- `ci-spec.yaml` and `ExamplesE2eTest` byte-identical on all four backends.
