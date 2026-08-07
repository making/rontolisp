# wasm-GC module size regressed, almost all of it in the arity dispatchers

Difficulty: High

Regenerating every `examples/**/*.wasm` with the compiler at `9cd51dde` (2026-08-07)
against the artifacts built at `7bf7b2ce` (2026-07-17) showed the wasm-GC output
growing several-fold **with the same source and the same flags**. The `--no-gc` and
component paths are unaffected (they got slightly smaller); this is wasm-GC only.

## The measurement

Same `.lisp`, same flags, only the compiler differs:

| program | flags | 7bf7b2ce | 9cd51dde | |
| --- | --- | ---: | ---: | ---: |
| `webgl-cube/cube.lisp` | `--no-wasi --optimize` | 26,602 | 256,407 | 9.6x |
| `webgl-galaxy/galaxy.lisp` | `--no-wasi --optimize` | 17,012 | 68,881 | 4.0x |
| `minesweeper/minesweeper-wasm.lisp` | `--no-wasi --optimize` | 105,800 | 314,413 | 3.0x |
| `rainbow/rainbow.lisp` | `--no-wasi --optimize` | 106,479 | 56,931 | 0.5x |
| `webgl-triangle/triangle.lisp` | `--no-wasi --optimize` | 2,467 | 2,711 | 1.1x |
| `wasm-browser/hello.lisp` | (none) | 103,562 | 319,357 | 3.1x |
| `wasm-browser/hello.lisp` | `--optimize` | 4,644 | 8,730 | 1.9x |

`--optimize=size` does not recover it (cube: 256,407 -> ~192 KB). The string-blob
shaking that landed the same day moves these by tens of bytes, not by the factor.

To rebuild the reference compiler:

```bash
git worktree add /tmp/old 7bf7b2ce && (cd /tmp/old && ./mvnw -q clean package -DskipTests)
```

## Where the bytes are

Disassemble cube both ways and group the WAT by function. **Six generic
arity-dispatch functions hold 102,238 of the new module's 109,693 lines (93%).**
The same six were 7,388 of 10,541 lines before -- they grew **13.8x**, and
everything else in the module is roughly flat.

They are the `(eqref ... ) -> eqref` bodies of arity 1..5, reached from the exported
`frame` through one spliced library helper -- **in both builds**. So they did not
newly appear; they inflated. Distinct `call` targets inside the 5-argument
dispatcher: **19 -> 142**.

The funcall-dispatch gate (`.kb/optimize-dead-code-elimination.md`) is working here
and is not the regression: on cube it reports `33 of 266 defuns dispatchable`
(`-Drontolisp.debug.dispatchgate=true`), and the minimal-funcall floor actually
IMPROVED across the same two commits (94,503 -> 32,465 B on a two-defun program that
`funcall`s a variable). **The open question is why a dispatcher gated to 33
dispatchable defuns calls 142 distinct targets** -- the arms appear to carry
`BuiltinFunctionWrappers` bodies INLINE rather than each wrapper being its own
function the arm merely `call`s. That is what makes the dispatcher both huge and
un-shakeable: the tree-shaker works on whole functions, so an inlined arm can never
be dropped individually.

Two things multiply into that:

- **`BuiltinFunctionWrappers` grew** -- 423 -> 1,072 source lines over the same
  window (roughly 3.5x the quoted names).
- **Every numeric coercion inlines a longer type ladder.**
  `WasmEmitHelper.castFloatGetF64` emits an inline `ref.test` chain over
  i31 / `TYPE_BIGNUM` / `TYPE_BIGINT` / `TYPE_RATIO` / `TYPE_FLOAT`. It was a 3-way
  chain before the boxed-i64 and limb tiers landed. Measured marginal cost of one
  extra site, at `--optimize` (identical at `--optimize=size`):

  | site | 7bf7b2ce | ede8b227 |
  | --- | ---: | ---: |
  | `(sqrt x)` | 59 B | 89 B |
  | `(setq s (+ s 1.5))` | 126 B | 193 B |
  | `(setq s (* s 1.5))` | 126 B | 193 B |
  | `(if (< s 1.5) ...)` | 169 B | 225 B |
  | `(car (list 1.5 2.5))` | 81 B | 79 B |

  A flat ~1.5x on numeric code and nothing on the rest -- small on its own, but it
  applies inside every inlined dispatcher arm as well as in user code, so it
  compounds with the two above. cube's module carries **462** of these ladders
  (count `ref.test (ref <TYPE_BIGINT>)`), and the marginal cost of one more
  dispatchable defun moved 205 B -> 313 B for the same reason.

## What to do

Ordered by expected payoff; each is independently landable.

1. **Give each builtin wrapper its own wasm function and make the dispatcher arm a
   `call`.** This is the shape that lets `WasmTreeShaker` drop wrappers one by one
   instead of only whole dispatchers, and it collapses the 5-arg dispatcher from
   27,189 lines to roughly one arm each. Confirm first that the arms really do
   inline (read `WasmRuntimeBuilder.buildDispatchBody` against the WAT).
2. **Make the dispatcher a `br_table` on a small integer tag** instead of the
   present if/else chain -- the emitted bodies contain `br_table` zero times today,
   so every arm pays a compare and a branch. Also bounds the ctrl depth.
3. **Route `castFloatGetF64` through one shared runtime helper**
   (`_as_f64 : eqref -> f64`) and `call` it: ~85 B per site becomes ~3 B, at one
   extra call per numeric operand. If the speed cost is real, make it the
   `--optimize=size` behavior -- that level exists for exactly this trade.
4. `castFloatGetF64` calls `ctx.allocTemp()` per site and temps are never released,
   so the ladder also inflates the local vector (cube's biggest body declares ~700
   `eqref` locals on one line). Worth checking against `.todo/137`, which is the
   same "temps never released" shape on the JVM.

Verify with the table above plus the browser demos, which is where the artifacts are
checked in: `examples/browser/**` (`cube`, `galaxy`, `platformer`, `robot-arm`,
`battlefront`, `minesweeper`, `hiragana`).

## Non-goals

- The `--no-gc` and component paths: both are flat or smaller across the same
  window, and the component wrapper floor is a different budget.
- The funcall-dispatch gate itself. It works, and its floor improved; this is about
  what one arm costs, not about which functions get an arm.
- Reverting the numeric tiers. The 5-way ladder is the price of exact arbitrary
  precision on wasm; the fix is to emit it once, not to drop a tier.
