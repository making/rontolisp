# `car`/`cdr` as a shared callee at the size level (`WasmConsRuntimeBuilder`)

**Invariant: a `car`/`cdr` site and the shared `_car`/`_cdr` body answer alike -- outside EH mode
they spell ONE shape**
(`WasmEmitHelper.emitInlineConsField`: `local.get x; ref.is_null; if (result eqref)
ref.null eq else local.get x; ref.cast $cons; struct.get $cons k end` -- nil answers nil, a
cons its field, anything else traps on the cast), so a site that calls the body answers
exactly what the inline spelling answered. Which spelling a site gets is decided by the
level alone, never by the program:

| level | a plain local operand | any other operand | `apply`'s argument walk |
| --- | --- | --- | --- |
| `off`, `default` | inline, read in place (17 B) | spilled into a fresh temp, inline (21 B + a local) | inline, per parameter |
| `size` | operand + `call _car` (3-4 B) | same | `local.get; call` per parameter |

- **EH mode checks** ([error-handling.md](error-handling.md), "A wrong-type argument names its
  operator"): there the bodies are CHECKED (nil answers nil, a non-list is `CAR`'s/`CDR`'s
  type-error through `_type_err_list`), and an inline site tests the type ONCE, over the operand
  on the stack (`WasmEmitHelper.emitCheckedConsField`): `<operand> block (eqref -> eqref) block
  (eqref -> eqref) br_on_cast_fail 0 eqref (ref $cons); struct.get $cons k; br 1 end; call _car
  end` (20-21 B + the operand). The blocks take the operand as their parameter, so no operand is
  ever spilled or read twice -- `plainLocalSlot` plays no part here -- and the miss (nil or a
  non-list) is the checked body with the operand still on the stack, so the two answer alike. The
  body itself is `local.get 0` and the same shape, its miss arm the nil test and the landing;
  `nthcdr`'s walk steps with it too (nil has left the loop by then, so its miss is `NTHCDR`'s
  landing, `WasmEmitHelper.emitListTypeError`). A size-level site is unchanged.
- **Why `br_on_cast_fail`.** The first checked site (`local.get x; ref.test $cons; if (result
  eqref) local.get x; ref.cast $cons; struct.get else local.get x; call _car end`) tested the type
  twice on the cons path. Benchmarked 2026-09-26 on wasmtime 49 over list-walking loops in an
  EH module ("unchecked" = the non-EH inline shape forced into the same module): the checked
  `br_on_cast_fail` shape tracks the unchecked one, and wins once Cranelift inlines.

  The check's machine code is no longer than the unchecked null test plus cast; what is left
  is Cranelift's register allocation around the generic `+`, whose i64
  overflow helper is a real call on the hot path -- inlined (`-C inlining=y`), the checked
  loop is the fastest of the three. **A miss arm that never returns buys nothing**:
  `br_on_null` to an inline nil and `call _car; unreachable` for the rest keeps the call's
  live values out of stack slots in a call-free loop (the micro-benchmark's machine code equals
  the unchecked one), but a Lisp loop has calls anyway, and the variant
  costs bytes at every site plus one more opcode for every pass to model; not taken.
  `br_on_cast` (branch on success) measured the same and needs an `eqref -> (ref $cons)`
  block type the module does not declare.
- **What the passes must model.** `am.ik.wasm` decodes `br_on_cast`/`br_on_cast_fail`
  (`WasmCodeModel.CastBranch`, `Instr.isCastBranch`, label in `Instr.a`) and treats it as a
  `br_if` wherever branches are followed: `WasmSections.scanGc` records its two heap types (the
  shaker renumbers the target), `WasmInliner.shapeOf` wraps a moved body whose cast branch
  leaves it, `WasmCarriedLocals` gives it both CFG edges, and `WasmRefTypeFolder` splits the
  operand's set between the label and the fall-through ([wasm-ref-type-fold.md](wasm-ref-type-fold.md)).
  `WasmLocalSink` and `WasmPeephole` see an opaque instruction (an unknown stack effect ends an
  expression walk), which is all they need.
- `FUNC_CAR`/`FUNC_CDR` (`TYPE_CALLABLE_BASE + 0`, `WasmLispCompiler`) are appended after the
  last fixed helper, so no fixed index moves; unreferenced at every level but `size`, and
  the shaker drops them there (`WasmLispCompilerTest.aConsAccessSiteIsOneCallAtTheSizeLevelAndReadsAPlainLocalInPlaceOtherwise`
  pins the body shipping exactly once at `size` and not at all at `default`).
- **The "read in place" licence is `WasmExprCompiler.plainLocalSlot`**: exactly the
  variables `compileSymbolRef` compiles to one bare `local.get` -- not a special (a
  special reads its global), not a `rawLocals` entry (a boxing read), not a
  `boxedVars` cell (an unbox). Anything else goes through `compileExpr` and a temp, as
  before. A new kind of local read that is not a bare `local.get` must be excluded there
  or the fast path reads the wrong thing silently.
- `WasmCarCompiler`/`WasmCdrCompiler`/`WasmApplyCompiler` go through
  `WasmEmitHelper.compileConsField`/`emitConsField`; the spread dispatcher's per-parameter
  walk (`WasmRuntimeBuilder.emitNullSafeCell`) takes the same switch as `sharedConsReaders`,
  threaded through `buildDispatch` from `WasmLispCompiler.optimize`.
- **Why `size` only.** A call per `car` is a speed trade, and `default` makes none
  (`.kb/optimize-dead-code-elimination.md`, "What `SIZE` declines"): on
  a `car`/`cdr` list-traversal loop the shared reader at
  the default level costs time, and at the size level it shrinks cons-using programs
  (`zlib`, the hello-clack Worker) for nothing at the other levels.
- Temps are still a fresh local per computed operand (`Ctx.allocTemp` never recycles). The
  BYTES that cost -- a two-byte index for every local from 128 up -- are recovered by the
  local renumbering (`.kb/optimize-dead-code-elimination.md`, "The local renumbering"), which
  made a scratch slot unnecessary for size; the frames stay wide.

## The size level's effect (2026-09-12)

`--optimize=size` shrinks the cons-using programs (`zlib --no-wasi`, the hello-clack
Worker) through the shared reader, `--optimize` barely; the programs with no cons access
(`hello_world`, `pi_approx`, the `.todo/789` reactor, `webgl-triangle`) are unchanged.

The `--optimize` column effect is the temp-free plain-local read alone. What the size level
gains beyond an external optimizer: `wasm-opt -Oz` finds the same result on the Worker
before and after, i.e. the gain is outside its reach (it does not outline).

## Tests

`WasmLispCompilerIntegrationTest.consAccessAnswersTheSameAtEveryLevel` (nil, a cons's
field, a special, a parameter and a `do`-stepped local as operands, `apply`'s walk exact
and with a rest tail, `funcall` of a computed designator, and `(car 5)` trapping through
the shared reader outside EH mode) and its EH twin `checkedConsAccessAnswersTheSameAtEveryLevel`
(the same answers, and a non-list as `CAR`'s type-error from an inline, a nested and a walked
site at every level), `WasmTreeShakerCorpusTest` (now compiles the corpus at `size` too:
validate + shortest-encoding round trip), the byte-budget pin above and
`WasmLispCompilerTest.aCheckedConsAccessSiteTestsItsOperandOnceAndNeedsNoLocal` (one
`br_on_cast_fail` per checked site, no `ref.test`/`ref.cast` of `$cons`, no local).
