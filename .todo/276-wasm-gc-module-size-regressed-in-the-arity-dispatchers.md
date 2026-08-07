# wasm-GC module size regressed, almost all of it in the arity dispatchers

Difficulty: High

Regenerating every `examples/**/*.wasm` with the compiler at `0e044c7a` (2026-08-07)
against the artifacts built at `7bf7b2ce` (2026-07-17) showed the wasm-GC output
growing several-fold **with the same source and the same flags**. The `--no-gc` and
component paths are unaffected (they got slightly smaller); this is wasm-GC only.

## The measurement

Same `.lisp`, same flags, only the compiler differs:

| program | flags | 7bf7b2ce | 0e044c7a | |
| --- | --- | ---: | ---: | ---: |
| `webgl-cube/cube.lisp` | `--no-wasi --optimize` | 26,602 | 256,407 | 9.6x |
| `webgl-galaxy/galaxy.lisp` | `--no-wasi --optimize` | 17,012 | 68,881 | 4.0x |
| `minesweeper/minesweeper-wasm.lisp` | `--no-wasi --optimize` | 105,800 | 314,413 | 3.0x |
| `rainbow/rainbow.lisp` | `--no-wasi --optimize` | 106,479 | 56,931 | 0.5x |
| `webgl-triangle/triangle.lisp` | `--no-wasi --optimize` | 2,467 | 2,711 | 1.1x |
| `wasm-browser/hello.lisp` | (none) | 103,562 | 319,357 | 3.1x |
| `wasm-browser/hello.lisp` | `--optimize` | 4,644 | 8,730 | 1.9x |

`--optimize=size` does not recover it (cube: 256,407 -> 191,694). Neither do the two
size passes that landed the same day: the string-blob shaking moves these by tens of
bytes, and after the **pure-builtin literal fold** (`.kb/pure-builtin-fold.md`) every
number above and every number below is byte-for-byte unchanged -- all 24 example
modules regenerate identically across it. That is the expected shape, not a
disappointment: the fold pays where a computation over literals pinned a runtime
dispatch tree, and none of these programs has one. It does mean the fold cannot be
counted against this item.

To rebuild the reference compiler:

```bash
git worktree add /tmp/old 7bf7b2ce && (cd /tmp/old && ./mvnw -q clean package -DskipTests)
```

## Where the bytes are

Disassemble cube both ways and group the WAT by function. **Six generic
arity-dispatch functions hold 102,232 of the new module's 109,453 function-body
lines (93%).** The same six were 7,382 of 10,335 lines before -- they grew **13.8x**,
and everything else in the module is roughly flat.

They are the `(eqref ...) -> eqref` bodies of arity 0..4 (the leading `eqref` is the
function id), reached from the exported `frame` through one spliced library helper --
**in both builds**. So they did not newly appear; they inflated.

**What grew is inline code, not calls.** Per-function, biggest first:

| | 7bf7b2ce | 0e044c7a |
| --- | --- | --- |
| biggest dispatcher | 2,149 lines / 9 distinct callees | 27,188 lines / 23 distinct callees |
| second | 1,446 / 3 | 21,188 / 20 |
| all six | 7,382 lines | 102,232 lines |

A body that grows 12.6x while its callee set barely moves is a body whose ARMS carry
their code inline. So the arms appear to hold `BuiltinFunctionWrappers` bodies
directly rather than each wrapper being its own function the arm merely `call`s --
which is also what makes them un-shakeable, since the tree-shaker works on whole
functions and can never drop an inlined arm. **Confirming that against
`WasmRuntimeBuilder.buildDispatchBody` is the first thing to do.**

The funcall-dispatch gate (`.kb/optimize-dead-code-elimination.md`) is working here
and is not the regression: on cube it reports `33 of 266 defuns dispatchable`
(`-Drontolisp.debug.dispatchgate=true`), and the minimal-funcall floor actually
IMPROVED across the same window (94,503 -> 32,605 B on a two-defun program that
`funcall`s a variable). The cost is per arm, not per admitted function.

Two things multiply into that:

- **`BuiltinFunctionWrappers` grew** -- 423 -> 1,072 source lines over the same
  window (roughly 3.5x the quoted names).
- **Every numeric coercion inlines a longer type ladder.**
  `WasmEmitHelper.castFloatGetF64` emits an inline `ref.test` chain over
  i31 / `TYPE_BIGNUM` / `TYPE_BIGINT` / `TYPE_RATIO` / `TYPE_FLOAT`. It was a 3-way
  chain before the boxed-i64 and limb tiers landed. Measured marginal cost of one
  extra site, at `--optimize` (identical at `--optimize=size`):

  | site | 7bf7b2ce | 0e044c7a |
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
   instead of only whole dispatchers, and it collapses the biggest dispatcher from
   27,188 lines to roughly one arm each. Confirm first that the arms really do
   inline (read `WasmRuntimeBuilder.buildDispatchBody` against the WAT).
2. **Make the dispatcher a `br_table` on a small integer tag** instead of the
   present if/else chain -- the emitted bodies contain `br_table` zero times today,
   so every arm pays a compare and a branch. Also bounds the ctrl depth.
3. ~~**Route `castFloatGetF64` through one shared runtime helper**
   (`_as_f64 : eqref -> f64`) and `call` it.~~ **LANDED 2026-08-07**, at the DEFAULT
   level: the speed cost this item hedged against is not there (`ml/mlp` 5.6 s ->
   5.4 s, `pi_approx` 0.12 s either way), so it did not need `--optimize=size`. It
   also retired the ratio runtime's own COPY of the ladder, sixteen more sites.
   Measured: `pi_approx` 5,356 -> 3,540 (-33.9%), `ml/mlp` -10.1%, `ml/nn` -11.4%.
   `.kb/wasm-shared-coercion.md`.
4. ~~`castFloatGetF64` calls `ctx.allocTemp()` per site and temps are never
   released.~~ **LANDED with 3**: the call site no longer allocates one at all. The
   REST of the "temps never released" shape is untouched and still worth a pass --
   every other `allocTemp()` caller, and `.todo/137` for the JVM twin.

Verify with the table above plus the browser demos, which is where the artifacts are
checked in: `examples/browser/**` (`cube`, `galaxy`, `platformer`, `robot-arm`,
`battlefront`, `minesweeper`, `hiragana`).

**The checked-in artifacts are stale by the amount item 3 already bought** (measured
2026-08-07 against the modules committed at `3c6e73e6`, same sources, same flags):
cube 256,407 -> 218,235 (-14.9%), galaxy 68,881 -> 57,148 (-17.0%), rainbow 56,931
-> 49,714 (-12.7%), wasm-browser/hello 8,730 -> 7,624 (-12.7%), minesweeper 314,413
-> 303,308 (-3.5%), triangle 2,711 -> 2,478 (-8.6%). They were deliberately NOT
rebuilt in that pass: `3c6e73e6` set the bar at "verified by running them", live in a
page, which a headless session cannot meet. Rebuild them WITH that verification when
items 1 and 2 land -- those move the same files far more, so one rebuild covers both.

## Non-goals

- The `--no-gc` and component paths: both are flat or smaller across the same
  window, and the component wrapper floor is a different budget.
- The funcall-dispatch gate itself. It works, and its floor improved; this is about
  what one arm costs, not about which functions get an arm.
- Reverting the numeric tiers. The 5-way ladder is the price of exact arbitrary
  precision on wasm; the fix is to emit it once, not to drop a tier.
