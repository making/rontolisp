# Compact the wasm-GC generic float printer, and type-specialise the counted loop

Difficulty: Medium

Two independent size items, both split out of todo-286 (which retired the `~,nF`
inline expansion and is closed). Neither is needed for the size-lineup goal any
more -- `examples/wasm-size/pi_approx` is 5,356 bytes at `--optimize`, under the
6,034-byte floor of the cross-language table -- so this is margin, and margin is
what stops one regression from losing the row again.

All numbers below are `--optimize`, wasm-GC Preview 1, measured 2026-08-07 against
the same Leibniz loop `pi_approx.lisp` runs.

## 1. The generic float printer costs ~3.9 KB the first time a program prints a float

| probe | bytes |
| --- | ---: |
| the loop + `(princ "done")` | 4,129 |
| the loop + `(princ <the f64 result>)` | 8,050 |

Printing ONE float is **+3,921 bytes** -- more than three times what the whole
`~,15F` rendering costs (1,227). It is `_princ_val`'s generic dispatch plus
`_print_f64`, a digit-extraction printer with an i32 arm, an i64 arm, a
`>= 2^63` normalisation loop and NaN/Infinity text
(`WasmRuntimeBuilder.buildPrintF64Core`).

The existence proof that this is not what a float printer has to cost is the same
one todo-286 used: `--no-gc` renders a float inside a **1,042-byte whole module**
(`pi_approx-nogc.lisp`, `NoGcWasmCompiler` -- its comment says it is the same
digit-extraction algorithm "hardened for the scalar backend"). Two implementations
of one algorithm, one of them an order of magnitude smaller.

Worth checking as part of it: `princ` of a value the compiler cannot type keeps the
WHOLE printer reachable, which is why todo-286 had to route the fixed-decimal piece
through `write-string` instead (`.kb/format.md`). A `princ` whose argument form is
statically known to be a string could take the same shortcut --
`compiler/StringValuedForms.certainlyString` already answers that question and is
already consulted by both backends' `write-string`.

## 2. A counted loop over literal bounds carries a boxed induction variable

The empty `(dotimes (i 1000000))` is **3,060 bytes** on its own. The bounds are
literal integers, so the induction variable should be an `i32` with an `i32.lt_s`
test, not an `eqref` re-boxed per iteration with a generic `<` per iteration.
Smaller AND faster, so it belongs at the DEFAULT level rather than behind
`--optimize=size`. `.kb/wasm-unboxed-locals.md` and `.kb/wasm-int-fusion.md` are the
neighbouring machinery; the question to answer first is why the existing unboxed
locals do not already cover a `dotimes` index.

## 3. What is left in `pi_approx` after todo-286

For whoever picks this up, the 5,356-byte module's own breakdown:

| | bytes |
| --- | ---: |
| the program's top-level body (the loop) | 1,111 |
| `_fixed_dec` (the whole `~,nF` renderer) | 535 |
| `_write_stream_str` | 253 |
| `_str_fresh` | 83 |
| everything else (arithmetic tower, I/O, prologue) | ~3,300 |

Item 2 attacks the first row. Item 1 does not appear here at all any more -- the
program no longer prints a float -- but any program that DOES print one pays it.

`.todo/276` (shared coercion helpers, temp reuse) is the third, larger lever and is
already its own item.

## Done when

- The float-printing probe above drops materially, with the `--no-gc` renderer as
  the reference for what the algorithm costs.
- `(dotimes (i <literal>))` emits an unboxed induction variable at `--optimize`, and
  the `ml/` timing examples do not get slower.
- `examples/wasm-size/README.md` re-measured in the same commit, and the four
  backends still print byte-identically (`ExamplesE2eTest`, `ci-spec.yaml`).

## Non-goals

- `--optimize=size`-only trades. Both items are size AND speed wins; if one turns
  out not to be, it belongs behind the `=size` level and this item does not claim it.
- Changing `pi_approx.lisp`. A rewritten program is a different measurement.
