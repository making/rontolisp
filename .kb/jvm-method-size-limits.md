# JVM backend: no emitted method may outgrow the 64 KB code limit (a branch past the 16-bit offset is written in its goto_w form), a program past one class's 65534-entry constant pool is split into `$PartN` classes, and every class is written through `java.lang.classfile`

Scope: JVM backend (`codegen.jvm`), HARD format limits, and the writer that enforces them. WASM
sibling: [wasm-function-body-size.md](wasm-function-body-size.md). Run
`-Drontolisp.jvm.debug-method-sizes=true` (the 40 largest bodies plus every computed-typep
expansion) first when a large program trips the guard; `-Drontolisp.jvm.debug-write=true` prints
the write phase's time and every written class's size and pool count.

## The four format limits
- **Branch offset, signed 16 bits** — a branch that does not reach is written in its long form
  (`goto_w`, or an inverted short branch over one) by `am.ik.jvm.CodeReplay.Layout`, a fixpoint
  over the method (widening one branch moves every later instruction). The emitters feed it
  through `JvmEmitHelper.patchBranch` -> `Ctx.deferredBranches` (placeholder offset bytes, true
  target recorded); the raw-list `JvmRuntimeBuilder.patchBranch` still throws. NOT the writer's
  own relaxation: see "Emission on java.lang.classfile" below.
- **Code array <= 65535 bytes** (JVMS 4.7.3). HARD; `JvmClassSplitter` rejects the emitted body
  loudly, naming the method, and the API rejects a written one the relaxation grew past it. A
  single enormous USER defun cannot be outlined.
- **`sipush` operand is signed 16 bits.** `JvmEmitHelper.emitIntConst` used to truncate
  silently into a class that VERIFIES and computes the wrong number — reachable via a character
  above the BMP (`.kb/characters-code-points.md`). Now `ldc`/`ldc_w`; the pool-free
  `JvmRuntimeBuilder.emitIntConstStatic` cannot mint a constant and throws.
- **65534 constant-pool entries per CLASS** (`ConstantPool.MAX_INDEX`). Every distinct integer
  literal costs TWO entries (boxed `long` = `CONSTANT_Long`); ~25,000 distinct numbers suffice.
  Not a program limit: past it the program is SPLIT (below). The limit is checked where a class
  is written, never where an entry is minted.

## How a class is written
The class is assembled as data, `am.ik.jvm.ClassDefinition` (header, fields, methods whose
bodies are the emitters' code lists with their handler, line and long-branch tables), and
`am.ik.jvm.JvmClassSplitter.write` writes it -- every class the backend generates, the
`java:` interface implementations included:

- **One master pool.** `ConstantPool` wraps one `java.lang.classfile` `ConstantPoolBuilder`
  for the whole program; the emitters' u2 operands are its indexes, kept whole past 65535 (every
  u2 writer keeps the high part: `JvmRuntimeBuilder.emitU2`, `Ctx.emitU2`, the private copies
  delegate; `OperandStack` reads a pool operand uncut). The builder refuses nothing as entries
  are added -- only a pool being WRITTEN is held to 65535 (`Constant pool is too large`).
- **The scan** (`JvmClassSplitter.Scan`) reads every body's pool operands once: the own-call
  graph (`OwnCallGraph`) answers `unresolvedSelfMethods` (the gate check, on every build) and
  the shake (every `--optimize` level but `off`), and each method's closure of master entries
  is what placement counts.
- **Each class is built with a pool of its own** (`ClassFile.build`, a fresh
  `ConstantPoolBuilder`), holding exactly the entries its members reference in write order --
  so `--optimize=off` output is compacted too. `CodeReplay` plays every body into the
  `CodeBuilder` instruction by instruction: branch targets and handler ranges become labels,
  a master entry is re-minted in the class's pool as its operand is written (an `ldc` takes the
  width its index there needs, both ways), and the frames, `max_stack` and `max_locals` come
  from `StackMapsOption.GENERATE_STACK_MAPS` over `StackMapFrames.resolver`
  ([stack-map-frames.md](stack-map-frames.md)). A local's load/store, an `iinc` and a small int
  constant are written in their SHORTEST form whatever form was emitted (`aload 2` ->
  `aload_2`, `bipush 3` -> `iconst_3`, as `CodeBuilder` writes them); every other instruction
  keeps its form. One pass: no written bytes are parsed again.
- **The decision**: what the class keeps (after the shake) summed over master-entry closures;
  within `classPoolLimit` (the format limit) it is written as one class. Past it, or when the
  frames' own entries overflow a class that fit, it is split.

## A program past one class's pool is split
**Invariant: a program whose kept members fit one class file is one class; one that does not is
its class plus `Name$Part1`, `Name$Part2`, ..., each with a pool of its own.** Measured by mito's
`MitoE2eTest` probe (mito-core + migration + dbd-postgres): **83,456** entries unshaken
(2026-09-24: Utf8 33,089, String 18,775, NameAndType 14,322, Methodref 9,217, Fieldref 5,129 --
3,747 of them `QuotePool` fields -- Long 1,283). It crossed at `af1c467fe` (2026-08-28, the
bignum pool tipped it); one array field per pool would have saved ~11.4k and still been ~6.5k
over, and the program keeps growing -- hence a split, not a diet. The quoted datums are one table
since 2026-09-27 (`.kb/quoted-data.md`, "The JVM table"): by arithmetic, not re-measured,
3 x 3,747 = ~11.2k entries out of the probe's pool; the split stays the answer for the rest.

- **Placement** (`JvmClassSplitter.place`): the class keeps every field and each method found by
  name or by class identity: `main`, jvm-export wrappers and their defuns,
  `REFLECTIVELY_FOUND_METHODS` (`_apply`/`_strv` for the bridges' `getDeclaredMethod`,
  `_gpuMaterialize`/`_gpuWritten` for `RontoFloatArray`'s MethodHandles), plus by the
  splitter's own rule every instance method and initializer, `synchronized` method (monitor =
  class) and `MethodHandles` caller (lookup = class). The rest goes in DECLARATION order into
  the class while it has room, then into parts; budget `MAX_INDEX - RESERVED_ENTRIES` (4096: the
  frames' Class entries, the parts' own).
- **Re-pointing**: a `Methodref` to a method that went to a part is re-minted naming that part
  as the call is written (a per-master-index map, resolved in `CodeReplay`'s operand function);
  members lose `ACC_PRIVATE` (one package). Fields stay in the class, so a `Fieldref` never
  changes.
- **Where they go**: parts join `runtimeClassFiles()` (`path/Name$PartN.class`), which every
  output shape already writes beside the class (`.kb/jvm-export.md`, "What travels").
- **Measured 2026-09-29** (mito probe, default `--optimize`, the writer below): `Probe.class`
  9,192,449 B, 61,401 entries, 8,452 methods; `Probe$Part1.class` 2,826,457 B, 29,838 entries,
  488 methods -- the same placement the byte writer made, 2,269 code bytes fewer (ldc_w written
  as ldc). 2026-09-25 it was 57,820 + 35,760 entries and 12,640 + 1,393 methods, before the
  quoted-datum table and the later shakes.
- **Still bounded**: all fields stay in the class, so fields plus the kept methods must fit one
  pool (`the class's fixed part ... needs N`); one method's own references must fit one pool
  (`_funName`'s name table grows with the program, 2 entries per nameable function, but is cut
  into `_funName$k` segments, so no one method holds them all).
- **Test instruments** (system properties, also `Builder` methods):
  `-Drontolisp.jvm.class-pool-limit=N` forces the split onto small programs;
  `-Drontolisp.jvm.pool-index-origin=70000` starts every index past 65535
  (`ConstantPool.startingAt`, the indexes before it filler entries), so an emitter that cuts one
  names a filler Utf8 and the write fails -- every corpus program compiled under it
  (2026-09-25), and `JvmLispCompilerTest`'s programs behave the same in classes of 1,200.

## Emission on `java.lang.classfile`
**Where it stands (2026-09-29):** every class is WRITTEN by the API (above). Every method body
has a typed, `CodeBuilder`-shaped layer, `am.ik.jvm.MethodCode` (`Ctx.body` for a compile
context; a builder makes its own), and the hash-table slice emits through it; the rest still
write code bytes (`Ctx.emit`/`emitU2`, `JvmAsm`, raw `List<Integer>` lists), which
`CodeReplay` decodes. The remaining slices are `.todo/a85`-`a91`: the `JvmAsm` builders, the
I/O and socket builders, the core runtime builders, the small builders with
`JvmLispCompiler`'s own code, the expression compilers in two halves, then `MethodCode`
storing instruction records so the code bytes, their decoders and `am.ik.jvm.Opcode` go.

**The layer** (`MethodCode`): typed instructions over master-pool entries
(`ConstantPool.entries()`, plus `classEntry`/`methodRef`/`interfaceMethodRef`/`fieldRef`/
`stringEntry` for an emitter building from names), `size()`, labels (a branch to an unbound
label waits for `labelBinding`; one past the 16-bit offset is recorded as a long branch;
`checkComplete` refuses a branch left waiting -- its placeholder would jump to itself),
`exceptionCatch` over bound labels, and `addTo(definition, ...)`. Until a91 it stores code
bytes, so one body mixes it with the byte emitters: over a compile context it writes into
`Ctx.code` and feeds `Ctx.stack` every byte, reconciling the model at a label exactly as
`JvmEmitHelper.patchBranch` does. It encodes a local in the explicit-slot form the byte
emitters use (`aload 1`, two bytes; `wide` past 255) and an int in the shortest, so a sequence
moved onto it MEASURES what it measured and no budget decides differently; the writer's
shortest forms make the class the same either way.

**The first slice** (`JvmHashRuntimeBuilder` from `JvmAsm`, most of it by a regex pass, and
`JvmHashTableCompiler` from `ctx.emit`/`patchBranch`) came out BYTE-IDENTICAL: the ci-spec
corpus class, the mito probe and its `$Part1`, the jose test suite program and six examples,
compiled with the jar before and after, differ only in the jar build timestamp
`uiop/os:lisp-version-string` embeds. So size and compile time are unchanged by the move itself;
a slice is verified by that comparison plus the suite (tools:
`.todo/artefacts/a85-jvm-runtime-builders-on-jvmasm-move-onto-methodcode/`). It found one bug
class: the byte emitters wrote `iinc` with a one-byte slot (`Ctx.emit`'s `wide` rewrite sees
loads and stores only), so past slot 255 `maphash`'s counter and `%obj-slots`'s cursor named
another local -- a `VerifyError` when that slot held a reference, an endless loop when it held
an int. `MethodCode.iinc` widens; both sites moved onto it.

**The shortest forms, measured 2026-09-29** (the writer's canonical loads, stores and ints,
against the forms emitted): ci-spec corpus class 7,687,513 -> 7,498,301 B (code 4,545,371 ->
4,357,115, -4.1%), mito probe 9,192,449 + 2,826,457 -> 9,008,889 + 2,755,146 B; the corpus
program's output unchanged.

**The two designs that depended on byte positions, and why they survive:**

1. **Size budgets** (`chunkCodeBudget` 24,000 in Pass 2b, `JvmBodyOutliner.CODE_BUDGET`,
   `AstOutliner`'s measured bytes-per-node, `debug-method-sizes`). `CodeBuilder` exposes no bci,
   and cannot be what a body is emitted into: it exists only inside the `ClassFile.build`
   callback of the class the method lands in, which the split decides after every body is
   emitted, and the API re-runs a method's handler to relax a branch, which emission's side
   effects (lambdas registered, bodies outlined, entries minted) would not survive. So a body is
   BUFFERED and the budgets measure the buffer. Measured 2026-09-29, emitted byte count against
   the written `CodeAttribute` length, per kept method, with the forms as emitted: mito probe
   8,940 methods -- 8,844 equal, 96 shorter by 1-188 bytes (an `ldc_w` whose constant landed
   below index 256 in its class), none longer; ci-spec corpus 6,214 -- 6,213 equal, 1 shorter
   by 2. With the writer's shortest forms: mito 163 equal, 8,777 shorter (up to 862 bytes,
   6,166,021 -> 5,910,137 in all), none longer; corpus 322 equal, 5,892 shorter (up to 2,384).
   The methods over 8,000 bytes are the same COUNT either way (20 mito, 62 corpus). The written
   length can exceed the emitted one only by a relaxed branch (+2 / +5) or an `ldc` whose
   constant landed past 255 (+1): a budget measured on the buffer holds, a little pessimistic
   until a91 counts the shortest forms.
2. **The lossless over-limit pool.** The master pool is a `ConstantPoolBuilder`, which grows
   past 65535 without complaint (checked only when written), so the split keeps its design:
   placement over master-entry closures, and re-pointing as a call is written into a class whose
   builder re-mints the entry. Nothing had to be kept symbolic beyond what the definition
   already was.

**`FIX_SHORT_JUMPS` is not a minimal relaxation -- premise overturned by measurement.** Once ONE
short jump of a method overflows, the API throws the method away and re-emits it with EVERY
forward branch in its long form (+5 per conditional, +2 per `goto`). The jose test suite's
`JSON::DECODE-JSON-ARRAY` (7,038 branches, one of them past the reach) came out at 82,541
bytes -- past the limit -- where `BranchRelaxer`'s fixpoint wrote 59,751. The replay widens only
the branches that do not reach (`CodeReplay.Layout`, the same fixpoint, allowing one byte per
`ldc` for the widths the writer picks): 57,909 bytes, one `goto_w`. The writer runs under
`FAIL_ON_SHORT_JUMPS`, so a branch the fixpoint missed is a loud compile error rather than a
method grown by a third. A typed layer relaxing in its own records keeps this rule.

**Measured 2026-09-29** (JDK 25.0.4, cold CLI runs, `-o X.class`, default `--optimize`), the byte
writer (`toBytes`, then the shaker's parse+write, then the frame pass's parse+write; or the
split's writer then frames per class) against one `ClassFile.build` per class:

| program | write phase | whole compile | output |
|---|---|---|---|
| ci-spec corpus | 2,785-2,924 ms -> 1,632-1,639 ms | 16.4-17.1 s -> 14.7-15.3 s | 7,687,515 -> 7,687,513 B |
| mito probe (split) | 2,029-2,094 ms -> 1,666-1,829 ms | 32.7-35.6 s -> 33.3-33.7 s | 9,193,297 + 2,827,878 -> 9,192,449 + 2,826,457 B |
| jose test suite | -- | -- | 5,146,274 -> 5,125,198 B |

The write phase is timed from the class complete as data to the bytes (gate check, shake,
placement, write); the old one parsed the class three times. The outputs are before the
shortest forms above. `am.ik.jvm` lost `BranchRelaxer`, `ByteCodeWriter` and its section DSL
(`MethodsDef`, `AttributesDef`, `CountingDef`), `JvmClassShaker`, `ClassDefinition.toBytes`, the
splitter's byte writer and the pool's own storage: main source 4,993 -> 4,280 lines, 433 of them
`CodeReplay`, the decoder a91 retires; `MethodCode` then added 931 (5,265).

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
- The top level is chunked (`_top$0`, ..., `chunkCodeBudget` 24,000 bytes, `JvmLispCompiler`
  Pass 2b) but only BETWEEN forms, so injected data tables emit as `defvar`/`setq`-append forms
  of 48. Per-arm branches inside a dispatch chain are LOCAL, so 24 KB leaves slack for both
  limits.

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
([stack-map-frames.md](stack-map-frames.md)); the hard limit is the u2 `max_locals`, which
`Ctx.allocTemp` refuses to cross. `iinc` was the one-byte slot `Ctx.emit`'s rewrite missed
(`maphash`, `%obj-slots`; fixed 2026-09-29 by `MethodCode.iinc`). Still open: a temporary's slot
is never reused.

## Pinning tests
- `JvmLispCompilerTest#compileAndRunABranchSpanningPastTheSigned16BitOffset`,
  `#compileAndRunTypepWithComputedSpecifier`, `#compileAndRunErrorWithComputedConditionType`,
  `#aCapturedLetVariableAssignedInlineInASiblingBranchPastTheSlotCeiling`,
  `#aMaphashWhoseCounterLandsPastSlot255IncrementsItsOwnLocal`,
  `#anObjSlotsWalkWhoseCursorLandsPastSlot255DecrementsItsOwnLocal`,
  `.compileCharBeyondBmpCodePoint`
- The layer: `am.ik.jvm.MethodCodeTest` (labels both ways, a long branch, `wide` locals and
  `iinc`, explicit emitted/shortest written forms, an unbound label refused, `invokeinterface`'s
  count, a handler, the operand-stack model kept in step)
- The writer: `am.ik.jvm.CodeReplayTest` (a long branch over wide locals, only the branch that
  does not reach widened, a widening cascade, handlers, `ldc` widened and narrowed by the
  class's pool, wide `iinc`, a body past the limit), `LineNumberTableTest` (lines through the
  shake, a relaxed branch and a part), `ConstantPoolTest` (indexes past the limit, `-0.0`,
  the Utf8 cap), and `JoseTestSuiteE2eTest` (the real 57,909-byte method)
- The split: `am.ik.jvm.JvmClassSplitterTest` (parts run, what cannot move stays, indexes past
  65535, the shake, unresolved own calls), `JvmLispCompilerSplitTest` (`SplitPrograms` past one
  class for real, forced splits at both optimize levels, an export library),
  `RontoLispCliTest#aProgramPastOneClassesPoolTravelsWithItsPartsInEveryOutputShape`, and the
  three JVM legs of `MitoE2eTest`
- `Uax15E2eTest`, `ClUnicodeTablesTest`; ci-spec `runtime-type-dispatch-and-symbol-designators`
