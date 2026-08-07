# Every generic sequence lowering inlines a whole representation dispatch

Difficulty: Medium

Split out of todo-276, whose own five items are closed. What is left of that item's
"wasm-GC modules grew several-fold" measurement is ONE mechanism, and this is it.

## The finding

A program that takes any function as a value carries the whole
`BuiltinFunctionWrappers` catalog -- `generate()` emits every wrapper that is not
explicitly gated, not just the ones the program names -- and each of those wrapper
bodies is a generic sequence operator whose lowering inlines a full
list / string / general-array dispatch, INCLUDING the other generic operators it
calls. `minesweeper` (which names none of these in its own source) at
`--no-wasi`, per function:

| wrapper | bytes |
| --- | ---: |
| `stable-sort` | 11,011 |
| `maplist` | 8,079 |
| `mapcar` | 8,067 |
| `remove-duplicates` / `delete-duplicates` | 7,783 each |
| `substitute` | 7,659 |
| `remove` | 7,656 |
| `substitute-if` / `substitute-if-not` | 7,431 each |
| `remove-if` / `remove-if-not` | 7,427 each |
| `sort` | 7,310 |
| `replace` | 7,267 |
| `reverse` | 7,215 |
| `position` | 6,699 |
| `position-if` / `position-if-not` | ~4,870 each |

That is 217 KB of the module's 261 KB of function bodies, in a Minesweeper.

`mapcar`'s wrapper is the shape to look at: its body is
`(if (null more) (mapcar f l) (do ... (reverse acc) ...))`, and the `(reverse acc)`
in there expands to the SAME 7,215 bytes the `reverse` wrapper is. The wrappers
already are one-definition-per-operator; the cost is that a call to a generic
operator from anywhere else re-inlines it instead of reaching the definition.

## The recipe, already proven twice

`.kb/subseq-runtime.md` and `.kb/string-write-runtime.md`: take the lowering's body,
splice it once as a defun, make the site a call, gate the injection where the
callers actually are (for these, the backend's wrapper loop -- expanding
`expandTopLevelDefinitions` is what did NOT work for `subseq`, because the wrapper
bodies do not exist yet). `subseq` went 2,316 -> 11 bytes a site that way.

The candidates in expansion-cost order are the table above. `expandReverse`,
`expandRemove`, `expandRemoveIf`, `expandSubstitute`, `expandRemoveDuplicates`,
`expandPosition` and `wrapSortForStringSeq` all already take the
`arraysExist` argument, so they already have the shape the gate needs.

Watch for: the JVM's array-runtime gate. A helper whose body names `aref`/`%aset`
turns it on, and turning it on for a program with no array costs ~120 KB
(`.kb/subseq-runtime.md` has the measurement and the two-sided gate that answers it).

## The other half, if this is not enough

`--optimize` keeps these wrappers because the funcall-dispatch gate makes them
roots. Emitting only the wrappers a program can actually reach as VALUES is the
larger lever and a different one; todo-276 declared it a non-goal ("it works, and
its floor improved") and that judgement is worth re-testing, not inheriting.

## Where the baseline stands

Against `7bf7b2ce` (2026-07-17), the window todo-276 measured, after its five items
and the two shared callees landed:

| program | flags | 7bf7b2ce | now | |
| --- | --- | ---: | ---: | ---: |
| `webgl-cube/cube.lisp` | `--no-wasi --optimize` | 26,602 | 33,669 | 1.27x |
| `webgl-galaxy/galaxy.lisp` | `--no-wasi --optimize` | 17,012 | 24,476 | 1.44x |
| `minesweeper/minesweeper-wasm.lisp` | `--no-wasi --optimize` | 105,800 | 255,102 | **2.41x** |
| `rainbow/rainbow.lisp` | `--no-wasi --optimize` | 106,479 | 38,170 | 0.36x |
| `webgl-triangle/triangle.lisp` | `--no-wasi --optimize` | 2,467 | 2,478 | 1.00x |
| `wasm-browser/hello.lisp` | (none) | 103,562 | 257,581 | 2.49x |
| `wasm-browser/hello.lisp` | `--optimize` | 4,644 | 7,624 | 1.64x |

The two `hello` rows are NOT this item and need no work here: `--optimize` is the
arbitrary-precision numeric tier's runtime floor (a deliberate correctness cost,
`.kb/wasm-bignum.md`) plus the generic value printer, which is `.todo/287` item 1;
the `(none)` row is the same wrapper catalog this item is about, un-shaken.

## Also left over from todo-276

- **Compile-path temps are never released.** `ctx.allocTemp()` never frees, so every
  helper that takes one widens the enclosing body's local vector permanently.
  Two callers went away with the shared callees (`_arr_get`/`_arr_set` took the
  displacement walk's two-per-site with them, and `%subseq-core` sites collapsed
  from one-per-`subseq` -- twelve JVM slots each -- to two per program). Everything
  else still leaks; `.todo/137` is the JVM twin, where it is a correctness bug past
  255 slots rather than only a size one.
- **A string-only wasm program still pays `subseq`'s array arm** (~5 KB for one
  site) because the wasm backend passes `arraysExist` true unconditionally.
  Declining it there means trusting `programUsesGeneralArrayOp` the way the JVM
  already does -- a behavior change, so it wants its own decision.

## Done when

- The table above is measurably smaller, with `minesweeper` the headline, and the
  four backends still print byte-identically (`ExamplesE2eTest`, `ci-spec.yaml`).
- Every checked-in `examples/browser/**` artifact rebuilt and every page verified
  in a real browser, the way `.kb/subseq-runtime.md`'s measurements were.
- Each new shared callee gets its `.kb` entry's re-evaluation trigger, and a
  marginal-bytes-per-site pinning test like
  `WasmLispCompilerTest.anElementAccessSiteDoesNotCarryItsOwnCopyOfTheSharedRuntime`.

## Non-goals

- The `--no-gc` and component paths. Both were flat or smaller across the same
  window, and the component wrapper floor is a different budget.
- Reverting the numeric tiers. The 5-way ladder is the price of exact arbitrary
  precision on wasm; the fix is to emit it once, not to drop a tier.
