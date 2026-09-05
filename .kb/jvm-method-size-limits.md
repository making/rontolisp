# JVM backend: no emitted method may outgrow the 64 KB code limit (branches past the 16-bit offset relax to goto_w), and no class its 65534-entry constant pool

Scope: JVM backend (`codegen.jvm`), HARD format limits. WASM sibling:
[wasm-function-body-size.md](wasm-function-body-size.md). Run
`-Drontolisp.jvm.debug-method-sizes=true` (the 40 largest bodies plus every computed-typep
expansion) first when a large program trips the guard.

## The four format limits
- **Branch offset, signed 16 bits** — RELAXED by `am.ik.jvm.BranchRelaxer` (`goto_w`, or an
  inverted short branch over one; fixpoint sizing, remapped exception table), fed by
  `JvmEmitHelper.patchBranch` -> `Ctx.deferredBranches`. No deferred branch => byte-identical
  output. The raw-list `JvmRuntimeBuilder.patchBranch` still throws.
- **Code array <= 65535 bytes** (JVMS 4.7.3). HARD; `am.ik.jvm.ByteCodeWriter.writeCode`
  rejects loudly. A single enormous USER defun cannot be outlined.
- **`sipush` operand is signed 16 bits.** `JvmEmitHelper.emitIntConst` used to truncate
  silently into a class that VERIFIES and computes the wrong number — reachable via a character
  above the BMP (`.kb/characters-code-points.md`). Now `ldc`/`ldc_w`; the pool-free
  `JvmRuntimeBuilder.emitIntConstStatic` cannot mint a constant and throws.
- **65534 constant-pool entries per CLASS** (`ConstantPool.MAX_INDEX`). Every distinct integer
  literal costs TWO entries (boxed `long` = `CONSTANT_Long`); ~25,000 distinct numbers suffice.
  Emit sites write `(short) index`, so crossing used to alias an unrelated entry and surface far
  away (`operand-stack model: underflow`). `ConstantPool.add` now refuses the crossing entry
  (counting both slots of a long/double first); `OperandStack.invoke` is the backstop.

## Bodies bounded by construction
- Registry-proportional expansions (computed `typep` 37 KB/site, runtime `subtypep` 59 KB,
  computed-`error` dispatch 90 KB) are shared injected defuns over quoted DATA tables
  ([clos.md](clos.md)). A cond over every condition class lowers to NESTED ifs and hit the
  BRANCH limit at ~195 bytes/class — hence chained dispatch (`%error-runtime` -> `%ER-1` -> ...,
  `chainedDispatchDefuns`, all four backends). Ambiguous literal `slot-value` outlines onto
  `%slot-value(-set)-runtime`.
- `_invoke_<arity>` (66 KB at arity 9) and the spread `_invoke_v` are split by
  `JvmRuntimeBuilder.buildDispatchMethods` into chained ~24 KB segments (`_invoke_9$1`, ...).
  The per-arity family stops at `MAX_CALLABLE_ARITY` (7); `_apply` used to fall off that ladder
  and silently answer nil for an 8+-argument `apply` through a COMPUTED designator.
- `_lookup` (60 KB) is split by `JvmEvalRuntimeBuilder.buildLookupSegments` (`_lookup$1`, ...).
- The top level is chunked (`_top$0`, ..., 40 KB budget, `JvmLispCompiler` Pass 2b) but only
  BETWEEN forms, so injected data tables emit as `defvar`/`setq`-append forms of 48. Per-arm
  branches inside a dispatch chain are LOCAL, so 24 KB leaves slack for both limits.

## Generated data must not become pool entries
- **Read the text back; do not scan it** (`ClUnicodeTables`): a scanned table cost ~208,000 pool
  entries, so each travels as PRINTED TEXT in ~230 string literals (570 entries) read by the
  native reader. Caps: a string constant may not exceed 65535 UTF-8 bytes and the reader
  recurses per element, so a chunk holds at most 20,000 characters AND 1,000 elements.
- A package-qualified symbol read at run time is `eq` to the literal the compiler resolved, so a
  range table's VALUES can be text too.
- Cost: the READER travels into the program (+390 KB class, ~2,450 pool entries) — worth it for
  megabytes, not a small table (`Uax15Tables` scans short chunks on FIRST READ, `.kb/asdf.md`).
  `--optimize` chunking helps the METHOD limit but makes the pool WORSE.

## Symbol function designators
`(funcall f ...)` with a SYMBOL at run time resolves through `_lookup` in dispatcher segment 0
(a String funcval's `Object[]{funcId, arity}` carries the id in slot 0; a miss throws
`The function X is undefined`), so `_lookup` is emitted whenever the program has indirect calls,
not only under eval. WASM mirrors it.

## Local slots (relaxed)
`ALOAD`/`ASTORE` once carried a one-byte index, so slot 256 became slot 0 — a wrong answer, not
a crash, and `AstOutliner`'s 8000-byte `HugeMethodLimit` never fired because `ctx.nextLocal`
only grows. Past 255 a load/store takes the `wide` prefix
([stackmap-augmenter.md](stackmap-augmenter.md)); the hard limit is the u2 `max_locals`, which
`Ctx.allocTemp` refuses to cross. Still open: a temporary's slot is never reused.

## Pinning tests
- `JvmLispCompilerTest#compileAndRunABranchSpanningPastTheSigned16BitOffset`,
  `#compileAndRunTypepWithComputedSpecifier`, `#compileAndRunErrorWithComputedConditionType`,
  `#aCapturedLetVariableAssignedInlineInASiblingBranchPastTheSlotCeiling`,
  `.compileCharBeyondBmpCodePoint`
- `am.ik.jvm.ConstantPoolTest#refusesTheEntryThatWouldCrossTheFormatLimit`,
  `#refusesATwoSlotEntryThatWouldStraddleTheFormatLimit`
- `Uax15E2eTest`, `ClUnicodeTablesTest`; ci-spec `runtime-type-dispatch-and-symbol-designators`
