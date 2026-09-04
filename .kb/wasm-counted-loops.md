# WASM counted loops (`dotimes`, and a `let` + `while` induction variable)

**Invariant: a counted loop's induction variable is an `i64` slot with NO boxed shadow,
and the eligibility scan is the whole proof that nothing can observe it any other way.**

Two entry points, wasm-GC backend only (Preview 1 AND `--component`; the interpreter, the
JVM and `--no-gc` keep the macro expansion):

- `WasmDotimesCompiler` — a `dotimes` over a LITERAL bound, emitted whole (its own
  `block`/`loop` pair).
- `WasmCountedLoopCompiler` — a `let` binding a `while` in the body steps, which is what
  `loop`'s numeric `for` head lowers to (`.kb/loop-iteration-heads.md`) and what a
  hand-written loop spells. The ordinary `WasmLetCompiler`/`WasmWhileCompiler` emit the
  loop; only the variable's REPRESENTATION changes.

**Not gated on `--optimize=size`** (unlike `.kb/wasm-int-fusion.md` and
`.kb/wasm-unboxed-locals.md`): the loop head always shrinks AND always runs faster. The
representation it replaces is not "boxed but cheap" — the general
`LispMacroExpander.expandDotimes` lowering costs per iteration a `_rat_cmp_bits` call, a
`_t_sym` call, a `ref.is_null`, and a guarded `(+ i 1)` that re-boxes into an `eqref`.

## Emitted shape

`(dotimes (i 1000000))` is a 203-byte module (was 1,987):

```wat
i64.const 0 / local.set $i
block / loop
  local.get $i / i64.const 1000000 / i64.ge_s / br_if 1
  <body, for effect>
  local.get $i / i64.const 1 / i64.add / local.set $i / br 0
```

No overflow check on the step and no range test on a read — the literal bound decides both
statically.

## The counted `RawLocal`

Registered in `ctx.rawLocals` (the map the dual representation uses) as
`WasmIntFusionCompiler.RawLocal.counted(slot)`, whose `shadowSlot` is `-1` (canonical
constructor enforces "counted ⇔ no shadow"). Using that map is what makes shadowing,
`defvar` collision and Lisp-2 resolution work with no new rules:
`WasmExprCompiler.compileSymbolRef` consults raw locals before ordinary locals, captures
and module globals, and `WasmLetCompiler` already removes a shadowed outer registration.

Five sites branch on `counted()`:

| site | dual | counted |
| --- | --- | --- |
| `evalLeaves` | snapshot (i64, shadow) into scratch | nothing; `snapI64` IS the counted slot |
| `emitLeafUnboxes` | sentinel test + i31/TYPE_BIGNUM guarded unbox | nothing |
| `emitFallback` | sentinel test, `_int_new` or shadow | `i32.wrap_i64; ref.i31` |
| `emitRawLocalBoxedRead` | `_ub_read` call | `i32.wrap_i64; ref.i31` |
| `WasmSetqCompiler.compileForEffect` | `compileRawStore` | `WasmCountedLoopCompiler.compileStep` — one `i64.add` |

No snapshot is needed: a counted variable's only assignment is its own step, which the
loop's iteration separates from every read. Reading RAW inside a fused tree is the speed
half — index math and accumulation stop paying a per-leaf guard, and a comparison against
it fuses (`tryCompileCompare`).

`compileRawStore` **throws** on a counted target and `compileStep` throws on any value
shape that is not the proven step, so an assignment the eligibility scan missed fails the
compile loudly instead of inventing a representation there is no shadow to hold.

## Eligibility: `dotimes`

All must hold; otherwise fall back to `expandDotimes` verbatim.

- The count is a **literal integer** in `[0, 2^30 - 1]` (i31 range). It is the COUNT that
  must fit, not just the last index — the result form sees the variable holding the count.
- The variable is a non-keyword symbol and not special.
- **Nothing in the body or result form assigns it.** The AST reaching a backend has every
  user macro expanded (`UserMacroExpander`), so the writing-operator set is CLOSED:
  `setq`/`psetq`/`setf`/`psetf`/`multiple-value-setq`/`incf`/`decf`/`push`/`pushnew`/`pop`/
  `rotatef`/`shiftf`/`remf` plus the `defvar` family. For each, the scan takes the PLACE
  arguments: a bare symbol is the variable; a place FORM writes its own sub-place
  (`(setf (getf pl k) v)` -> `pl`, `(setf (ldb spec x) v)` -> `x`, `(setf (aref s i) c)` ->
  `s`). For indexed accessors the sub-place is a known argument and the rest are pure
  subscript/key READS, which keeps `(dotimes (i 16) (setf (aref buf i) 0))` eligible; any
  other accessor is treated as writing anything it mentions. The scan is blind to lexical
  scope and quoting, which can only narrow eligibility.
- Not captured by a nested lambda (`FreeVarAnalyzer`) — a capture needs a cell.
- Not in an async body (`ctx.asyncResume` owns locals there), not under `--dynamic`, and
  not at top level in a program that uses `eval` (the ordinary lowering's `(setq i ...)`
  MIRRORS each step into the eval global environment via
  `WasmSetqCompiler.mirrorTopLevelGlobal`, unreadable from a slot). Top level WITHOUT
  `eval` is eligible.

The i64 slot is allocated from `ctx.nextI64Local` before the body and the watermark
restored after, so fused sites inside the body allocate above it
(`.kb/wasm-unboxed-locals.md`, "the i64 locals run").

## Eligibility: a `let` + `while` induction variable

`WasmCountedLoopCompiler.analyze` runs on every `let` the wasm backend compiles. A `let*`
is already nested `let`s, so `loop`'s numeric head presents as the OUTERMOST binding with
the `while` several bodies down — the scan looks at the whole `let` form, not its immediate
body. Anything failing just keeps the ordinary representation.

- The init is a **literal integer** within `±(2^30 - 1)`.
- The name is a non-keyword symbol, not special, not duplicated in the binding list, not
  captured by a lambda in the body (`capturedInLet`).
- The whole `let` form holds **exactly one** assignment of it (the same place-writing scan
  as above, which lives in this class and `WasmDotimesCompiler` calls), and it is
  `(setq VAR (+ VAR n))`, `(+ n VAR)` or `(- VAR n)` with a literal non-zero `n`.
- That `setq` is a **direct element of a `while`'s body list** — statement position, the
  only position a counted variable can be assigned in (there is no boxed value to hand
  back; every other route reaches `compileRawStore`, which throws).
- That `while`'s test **bounds** the variable: a comparison against a literal integer,
  under any number of `not`s, possibly as one conjunct of an `and`. Any conjunct suffices —
  failing it ends the loop before the step runs again. Negation is sound because both
  operands are integers (no NaN); recursion into `and` happens only on the POSITIVE side,
  since a negated conjunction is a disjunction of negations.
- Both extremes must box exactly as an i31: the init, and ONE STEP PAST the bound — that
  value is observable (`(loop for i from 1 to 4 finally (return i))` is 5).

Context gates are the dual representation's minus `--optimize=size`: `--dynamic`, async
body and a top-level body creating a closure are out. `WasmLetCompiler` decides the counted
binding BEFORE the dual one — counted is stronger (no shadow), so it wins.

### The exit test

`WasmComparisonCompiler.tryCompileConditionI32` takes a `negated` request and answers a
`not` by flipping it rather than emitting `i32.eqz`. The numeric head's test is
`(not (> i limit))`, so `while` gets its exit as one bare `i64.gt_s` feeding `br_if` — the
same two instructions `WasmDotimesCompiler` emits. General: before, the `not` wrapper
pushed the WHOLE test onto the boxed path, so every `while`/`if` over a negated comparison
now fuses where it did not.

## Tests

- `WasmLispCompilerIntegrationTest.countedDotimesLoopsMatchTheExpandedLowering` (result
  form's value, `return` out of the body, nested same-name loop, assigned counter falling
  back, captured counter falling back, array fill subscripted by the counter) and the
  `counted-dotimes` ci-spec case (all four backends).
- `WasmLispCompilerIntegrationTest.countedNumericForHeadsMatchTheExpandedLowering` and the
  `counted-numeric-for` ci-spec case: accumulator, exclusive limit, descending `by`, the
  value `finally` sees one step past the limit, `return` out of the body, nested same-name
  head, indexed store subscripted by the variable, and the five refusals — assigned,
  captured, non-integral limit, limit at the i31 ceiling, and the hand-written
  `let` + `while` spelling that must answer the same. Both also run at `--optimize=size`.

## Re-evaluation triggers

- **A non-literal bound is the open half** (`for i from 0 below (length v)` is common). It
  cannot reuse this shape: a runtime limit needs an entry guard ("integer inside the i31
  range?") whose failing arm needs a generic loop to bail into, which means emitting the
  body TWICE. No no-duplication alternative is sound — clamping the limit changes which
  iterations run; a generic exit test still needs the counter bounded; trapping turns a
  legal program into an error. So it is a speed-for-size trade: gate the duplicated version
  on `WasmIntFusionCompiler.speedTradesEnabled` exactly as `.kb/wasm-unboxed-locals.md`
  gates its own duplicated fallback, keeping the variable's post-loop reads on ONE
  representation (write the counted slot back into the boxed local at the counted arm's
  exit). Measure before believing it pays.
- **A counted read feeding a float coercion still round-trips through a box**
  (`(* 2.0 i)` emits `i32.wrap_i64; ref.i31` then calls `_as_f64`). An
  `f64.convert_i64_s` straight off the slot needs `castFloatGetF64` to learn about raw
  operands — a `.kb/wasm-shared-coercion.md` change, not one here.
- If the counted flavour ever needs a THIRD behaviour at one of the five `counted()`
  branches, `RawLocal` should become a sealed interface with two implementations rather
  than a record with a flag.
