# JVM backend: no emitted method may outgrow the 64 KB code limit (branches past the 16-bit offset relax to goto_w), and a program past one class's 65534-entry constant pool is split into `$PartN` classes

Scope: JVM backend (`codegen.jvm`), HARD format limits. WASM sibling:
[wasm-function-body-size.md](wasm-function-body-size.md). Run
`-Drontolisp.jvm.debug-method-sizes=true` (the 40 largest bodies plus every computed-typep
expansion) first when a large program trips the guard.

## The four format limits
- **Branch offset, signed 16 bits** — RELAXED by `am.ik.jvm.BranchRelaxer` (`goto_w`, or an
  inverted short branch over one; fixpoint sizing, remapped exception table), fed by
  `JvmEmitHelper.patchBranch` -> `Ctx.deferredBranches`. No deferred branch => byte-identical
  output. The raw-list `JvmRuntimeBuilder.patchBranch` still throws.
- **Code array <= 65535 bytes** (JVMS 4.7.3). HARD; `am.ik.jvm.ClassDefinition` (and
  `JvmClassSplitter`) reject loudly, naming the method. A single enormous USER defun cannot be
  outlined.
- **`sipush` operand is signed 16 bits.** `JvmEmitHelper.emitIntConst` used to truncate
  silently into a class that VERIFIES and computes the wrong number — reachable via a character
  above the BMP (`.kb/characters-code-points.md`). Now `ldc`/`ldc_w`; the pool-free
  `JvmRuntimeBuilder.emitIntConstStatic` cannot mint a constant and throws.
- **65534 constant-pool entries per CLASS** (`ConstantPool.MAX_INDEX`). Every distinct integer
  literal costs TWO entries (boxed `long` = `CONSTANT_Long`); ~25,000 distinct numbers suffice.
  Not a program limit any more: past it the program is SPLIT (next section). A bounded
  `ConstantPool` still refuses the crossing entry (both slots of a long/double counted), and
  every pool refuses to serialize past it (`ConstantPoolOverflowException`).

## A program past one class's pool is split
**Invariant: a program whose pool fits one class file is written byte for byte as before; one
that does not is its class plus `Name$Part1`, `Name$Part2`, ..., each with a pool of its own.**
Measured by mito's `MitoE2eTest` probe (mito-core + migration + dbd-postgres): **83,456**
entries unshaken (2026-09-24: Utf8 33,089, String 18,775, NameAndType 14,322, Methodref 9,217,
Fieldref 5,129 -- 3,747 of them `QuotePool` fields -- Long 1,283). It crossed at `af1c467fe`
(2026-08-28, the bignum pool tipped it); one array field per pool would have saved ~11.4k and
still been ~6.5k over, and the program keeps growing -- hence a split, not a diet.

- **Lossless until written.** `ConstantPool` holds entries as data (tag, component indexes,
  payload), dedup'd on that; `ConstantPool.unbounded()` (the backend's) grows past the limit.
  Every u2 code writer keeps the high part whole (`JvmRuntimeBuilder.emitU2`, `Ctx.emitU2`,
  the private copies delegate) and `OperandStack` reads a pool operand uncut, so an index past
  65535 still names its entry. The class is assembled as a `ClassDefinition` (pool, header,
  fields, methods with their code lists); `toBytes()` is the unchanged single-class form.
- **The decision** (`JvmLispCompiler`, end of `compile`): `cp.size() <= classPoolLimit` (the
  format limit) takes the old path -- `toBytes`, `JvmClassShaker`, `StackMapAugmenter`. Past it,
  or when the augmenter's own frame-type entries overflow (`ConstantPoolOverflowException`),
  `writeSplit` hands the definition to `am.ik.jvm.JvmClassSplitter`.
- **The split**: `unresolvedSelfMethods` and the shake are `JvmClassShaker`'s rules on the
  definition (a definition that then fits one class comes out byte-identical to the shaker's
  output -- pinned). The class keeps every field and each method found by name or by class
  identity: `main`, jvm-export wrappers and their defuns, `REFLECTIVELY_FOUND_METHODS`
  (`_apply`/`_strv` for the bridges' `getDeclaredMethod`, `_gpuMaterialize`/`_gpuWritten` for
  `RontoFloatArray`'s MethodHandles), plus by the splitter's own rule every instance method and
  initializer, `synchronized` method (monitor = class) and `MethodHandles` caller (lookup =
  class). The rest goes in DECLARATION order into the class while it has room, then into
  parts; budget `MAX_INDEX - RESERVED_ENTRIES` (4096: the augmenter's Class entries, the
  parts' own). A `Methodref` to a moved method is re-pointed to its part at write time; members
  lose `ACC_PRIVATE` (one package). Each class keeps the definition's entry order and appends
  its new Class entries last, so an `ldc`'s one-byte operand stays in range.
- **Where they go**: parts join `runtimeClassFiles()` (`path/Name$PartN.class`), which every
  output shape already writes beside the class (`.kb/jvm-export.md`, "What travels").
- **Measured 2026-09-25** (mito probe, default `--optimize`): `Probe.class` 9.08 MB, 57,820
  entries, 12,640 methods; `Probe$Part1.class` 3.16 MB, 35,760 entries, 1,393 methods (mostly
  runtime helpers -- they come last in declaration order). The two pools repeat ~10k entries.
  The split costs no measurable compile time; the probe's ~250 s compile was
  `expandTopLevelDefinitions`'s runtime-subtypep ancestor table (a linear `findClass` per
  lattice pair), not codegen -- 51 s since that was fixed (`.kb/declarations-type-checks.md`).
  All three JVM legs green again.
- **Still bounded**: all fields stay in the class, so fields plus the kept methods must fit one
  pool (`the class's fixed part ... needs N`); one method's own references must fit one pool
  (`_funName`'s name table is the first to grow with the program: 2 entries per nameable
  function).
- **Test instruments** (system properties, also `Builder` methods):
  `-Drontolisp.jvm.class-pool-limit=N` forces the split onto small programs;
  `-Drontolisp.jvm.pool-index-origin=70000` starts every index past 65535, so a writer that cuts
  one names a missing entry and the compile fails -- every corpus program compiles under it
  (2026-09-25), and `JvmLispCompilerTest`'s programs behave the same in classes of 1,200.

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
  `#refusesATwoSlotEntryThatWouldStraddleTheFormatLimit`,
  `#anUnboundedPoolKeepsFullWidthComponentIndexesPastTheFormatLimit`
- The split: `am.ik.jvm.JvmClassSplitterTest` (parts run, what cannot move stays, indexes past
  65535, byte-identity with the shaker), `JvmLispCompilerSplitTest` (`SplitPrograms` past one
  class for real, forced splits at both optimize levels, an export library),
  `RontoLispCliTest#aProgramPastOneClassesPoolTravelsWithItsPartsInEveryOutputShape`, and the
  three JVM legs of `MitoE2eTest`
- `Uax15E2eTest`, `ClUnicodeTablesTest`; ci-spec `runtime-type-dispatch-and-symbol-designators`
