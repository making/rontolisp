# wasm: the inliner's single-use local residue is never collapsed

Difficulty: High

**Status:** open, measured 2026-09-14 against `c972efa5d`.

## What the pipeline does today

Both WASM backends end their optimize path with the same four passes, in this
order (`NoGcWasmCompiler` around the `optimize.eliminatesDeadCode()` branch, and
the GC backend's equivalent):

```
WasmPeephole.rewrite -> WasmInliner.inline -> WasmTreeShaker.shake -> WasmLocalOrder.reorder
```

The order is deliberate and documented in place: the adjacent-instruction
peepholes run FIRST so a body they shrink can still fit the inliner's budget.

The consequence is that **nothing runs after the inliner**. `WasmInliner` binds
each argument to a fresh local (`local.set N`) at the call site, and the residue
that leaves is never collected:

- when the callee reads that parameter first thing, the inlined body starts with
  `local.get N` and the pair should have been `local.tee N`;
- when the callee reads it exactly once anywhere, the local should not exist at
  all -- the argument expression belongs at the use.

`WasmPeephole.collapseTail` already has the `local.set N; local.get N ->
local.tee N` rule. It simply never sees these pairs, because they do not exist
yet when it runs.

## Measured (2026-09-14, `--optimize=size`)

Two different populations, and they are worth very different amounts.

**Adjacent pairs the existing peephole rule would take**, counted off
`wasm-tools print` (a `local.set N` whose immediately next instruction is
`local.get N`):

| program | backend | pairs | bytes |
| --- | --- | ---: | ---: |
| the four-import reactor benchmark (see below) | `--no-gc` | 1 | 2 |
| `size-report/programs/pi_approx/pi_approx-nogc.lisp` | `--no-gc` | 0 | 0 |
| `size-report/programs/pi_approx/pi_approx.lisp` | GC | 0 | 0 |
| `size-report/programs/zlib/zlib.lisp` | GC | 0 | 0 |

So re-running `WasmPeephole.rewrite` after the inliner is worth **2 bytes on one
program and nothing anywhere else**, against a second full decode/encode pass
over a 71,056-byte code section. On that number alone the answer is no.

**Single-assignment, single-use locals** -- one `local.set`/`local.tee` and one
`local.get` in the whole function -- which is the population a copy-propagation
or expression-sinking rule would reach:

| program | backend | candidates |
| --- | --- | ---: |
| the four-import reactor benchmark | `--no-gc` | 1 |
| `pi_approx-nogc.lisp` | `--no-gc` | 8 |
| `pi_approx.lisp` | GC | 4 |
| `zlib.lisp` | GC | **723** |

Each one that can be removed is worth `local.set N` + `local.get N`, so 4 bytes
(6 where `N >= 128`). **723 is an upper bound, not a win**: the candidates here
are all NON-adjacent, so every one of them has intervening code, and sinking is
only legal when nothing between the definition and the use can observe it or
change what it reads. Measure the reachable subset before promising a number.

`WasmLocalOrder`'s own header already names the cause: "an emitter that hands
out a fresh local per temporary and never recycles one".

## Two rules, and they should be judged separately

1. **Copy propagation.** The definition is a single `local.get M` where `M` is
   never written anywhere in the function (a parameter the body never assigns is
   the common case). Then every `local.get N` becomes `local.get M` and `N` goes
   away, with no effect analysis at all -- `M` cannot have changed because
   nothing writes it. This is the cheap, obviously-safe half.
2. **Expression sinking.** The definition is a larger pure expression (the
   inliner's `local.get 0; i64.extend_i32_s` argument prologue is the shape that
   started this) and the use is on every path the definition is on, with nothing
   in between that traps, stores, calls or writes a local the expression reads.
   This needs a real analysis over the decoded body and is where the 723 live.

Rule 1 is worth doing if it reaches a measurable share of the 723. Rule 2 is
worth doing only if rule 1's measurement says the remainder is large.

## What a fix must not break

- The pass belongs in `am.ik.wasm`, which imports nothing project-side and must
  stay language-independent. `WasmCodeModel.decode` is the model to build on.
- Re-running the existing peephole after the inliner is NOT the fix (see the
  first table); if it is added at all it should ride along with a pass that
  earns its own traversal.
- A local removed changes the declaration vector, so `withLocals`-style run
  merging and `WasmLocalOrder`'s renumbering both have to stay correct behind it.
- `local.tee` is a read AND a write: a rule that counts uses must count it in
  both populations. The measurement above does.

## If it does not pay

Record the measured numbers and the date in
`.kb/optimize-dead-code-elimination.md` (the residue census) and close this
without the change. A pass that costs a traversal of a 71 KB code section to
recover single-digit bytes is a result, not a regression.

## The benchmark program

A `--no-gc --no-wasi` reactor with four `:string` host imports behind four Lisp
forwarders, a recursive `fib`, and four exports (two returning `:s32`, two
`:void`). Its shape is the same as
`.todo/artefacts/805-no-gc-literal-import-call-sites/bench.lisp`, one literal
level higher: the literals sit inside the forwarders rather than at the import
call site. At `--optimize=size` it is 930 bytes, code section 230.
