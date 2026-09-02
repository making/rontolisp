# `min`/`max` apply float contagion on the interpreter only, and the JVM disagrees with itself

Difficulty: Low

Found while closing todo-648 (the signed-zero convergence, 2026-09-02). 648 made the
min/max SELECT identical on all four backends; this is the one thing left that still makes
them answer differently, and it is a question about the result's TYPE, not its sign, which
is why it was left out of that change.

## The measurement

2026-09-02, all four backends from one tree, plus `/usr/bin/sbcl` as the oracle.

| form | interpreter | JVM literal | JVM variable | wasm (both) | SBCL |
| --- | --- | --- | --- | --- | --- |
| `(min 1 2.0)` | `1.0` | `1.0` | **`1`** | **`1`** | **`1`** |
| `(min 2.0 1)` | `1.0` | `1.0` | **`1`** | **`1`** | **`1`** |
| `(max 1 2.0)` | `2.0` | `2.0` | `2.0` | `2.0` | `2.0` |

Three-way, and note the JVM disagrees WITH ITSELF: its double-literal fast path
(`JvmMinCompiler`, which unboxes and reboxes a double) coerces, while `_min`/`_max` hand
back the winning operand as it stands. The `2.0` column agrees only by luck -- the winner
happens to be the float.

`=` still holds across all of them (`(= 1 1.0)` is true everywhere), so this is visible
only in what gets PRINTED and in a type predicate (`(floatp (min 1 2.0))`).

## What the standard says

CLHS `max, min` leaves it open in as many words: when the arguments are a mix of rationals
and floats, "it is implementation-dependent whether `max` or `min` will return the result
of applying the rules of floating-point contagion". So neither answer is wrong, and
"agree with each other" is the whole of the value here -- exactly the situation the
`min`/`max` TIE was in before 648, where the tie-break was settled by matching SBCL.

## What to do

Pick one and make all four say it. The evidence leans toward NOT coercing:

- SBCL does not coerce.
- Three of our own five code paths already do not.
- Not coercing is one fewer allocation on the hot path, and it preserves exactness --
  `(min 1 2.0)` staying the integer `1` keeps a bignum a bignum instead of rounding it
  through a double.

That makes the change: drop the `hasDouble(args) ? new LispDouble(asDouble(best)) : best`
tail from `min` and `max` in `Environment.registerArithmetic`, and stop the JVM literal
path reboxing as a double (either drop that fast path in `JvmMinCompiler`/`JvmMaxCompiler`
or have it select the boxed operand the way the general path does). Nothing changes on
wasm or `--no-gc`.

This MOVES A PRINTED RESULT, unlike 648 -- `(min 1 2.0)` goes from `1.0` to `1` on the
interpreter -- so check `examples/` and the `doc/**` examples (`DocExamplesTest`) for
anything that prints a mixed-type `min`/`max`, and expect `ci-spec.yaml` expectations to
shift. Grep the shipped `.lisp` libraries too (`linalg.lisp`, `torch.lisp`, `appkit.lisp`):
a `min`/`max` guarding an index against a float bound is where a type change would
actually bite.

## Acceptance

- One answer on the interpreter, the JVM (BOTH paths), wasm-GC, the component and
  `--no-gc`, with the choice and the SBCL measurement written into `.kb/linalg-simd.md`
  beside the 648 table.
- A `ci-spec.yaml` case covering mixed rational/float `min` and `max` in both argument
  orders, so the agreement is pinned.
- `LispEvaluatorTest`'s existing float-contagion assertion updated rather than deleted.
