# JVM backend: no emitted method may outgrow the 64 KB code limit (a branch past the 16-bit offset is written in its goto_w form), a program past one class's 65534-entry constant pool is split into `$PartN` classes, and every class is written through `java.lang.classfile`

Scope: JVM backend (`codegen.jvm`), HARD format limits, and the writer that enforces them. WASM
sibling: [wasm-function-body-size.md](wasm-function-body-size.md). Run
`-Drontolisp.jvm.debug-method-sizes=true` (the 40 largest bodies plus every computed-typep
expansion) first when a large program trips the guard; `-Drontolisp.jvm.debug-write=true` prints
the write phase's time and every written class's size and pool count.

## The four format limits
- **Branch offset, signed 16 bits** — a branch that does not reach is written in its long form
  (`goto_w`, or an inverted short branch over one) by `am.ik.jvm.CodeReplay.farBranches`, a
  fixpoint over the method's records (widening one branch moves every later instruction). Every
  emitter branches to a `MethodCode` label; the branch record names the label, so no offset is
  ever encoded (the raw-list `patchBranch`, which threw, is gone). NOT the writer's own
  relaxation: see "Emission on java.lang.classfile" below.
- **Code array <= 65535 bytes** (JVMS 4.7.3). HARD; `JvmClassSplitter` rejects the emitted body
  loudly, naming the method, and the API rejects a written one the relaxation grew past it. A
  single enormous USER defun cannot be outlined.
- **`sipush` operand is signed 16 bits.** `JvmEmitHelper.emitIntConst` used to truncate
  silently into a class that VERIFIES and computes the wrong number — reachable via a character
  above the BMP (`.kb/characters-code-points.md`). Now `ldc`/`ldc_w`; `MethodCode.loadConstant`
  refuses a value past the short range (the caller `ldc`s an Integer entry, as `JvmQuotePool`'s
  table size does).
- **65534 constant-pool entries per CLASS** (`ConstantPool.MAX_INDEX`). Every distinct integer
  literal costs TWO entries (boxed `long` = `CONSTANT_Long`); ~25,000 distinct numbers suffice.
  Not a program limit: past it the program is SPLIT (below). The limit is checked where a class
  is written, never where an entry is minted.

## How a class is written
The class is assembled as data, `am.ik.jvm.ClassDefinition` (header, fields, methods whose
bodies are the emitters' `MethodCode` records, with their line tables), and
`am.ik.jvm.JvmClassSplitter.write` writes it -- every class the backend generates, the
`java:` interface implementations included:

- **One master pool.** `ConstantPool` wraps one `java.lang.classfile` `ConstantPoolBuilder`
  for the whole program; an instruction record names its master entry itself, so no index is
  ever encoded and none can be cut. The builder refuses nothing as entries are added -- only a
  pool being WRITTEN is held to 65535 (`Constant pool is too large`).
- **The scan** (`JvmClassSplitter.Scan`) reads every body's entries once: the own-call
  graph (`OwnCallGraph`) answers `unresolvedSelfMethods` (the gate check, on every build) and
  the shake (every `--optimize` level but `off`), and each method's closure of master entries
  is what placement counts.
- **Each class is built with a pool of its own** (`ClassFile.build`, a fresh
  `ConstantPoolBuilder`), holding exactly the entries its members reference in write order --
  so `--optimize=off` output is compacted too. `CodeReplay` plays every body's records into
  the `CodeBuilder`: branch targets and handler ranges become labels, a master entry is re-minted
  in the class's pool as its instruction is written (an `ldc` takes the width its index there
  needs, both ways), a local's load/store, an `iinc` and an int constant take their SHORTEST
  form (as `CodeBuilder` writes them), and the frames, `max_stack` and `max_locals` come from
  `StackMapsOption.GENERATE_STACK_MAPS` over `StackMapFrames.resolver`
  ([stack-map-frames.md](stack-map-frames.md)). Nothing is decoded: no code bytes exist before
  the class's own.
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
- **Test instrument** (a system property, also a `Builder` method):
  `-Drontolisp.jvm.class-pool-limit=N` forces the split onto small programs;
  `JvmLispCompilerTest`'s programs behave the same in classes of 1,200. Its sibling
  `-Drontolisp.jvm.pool-index-origin` (every index started past 65535, so an emitter cutting one
  to 16 bits named a filler entry) went with the last index sink (a91): no index is encoded.

## Emission on `java.lang.classfile`
Every class is WRITTEN by the API ("How a class is written" above), and no method body exists as
bytes before the written class's own. What replaces the byte emitters is three stages:

**Recorded -- `MethodCode`.** Every body is `am.ik.jvm.MethodCode` (`Ctx.body` on a compile
context; a builder makes its own): typed instruction records in three parallel arrays -- the
opcode (a local's load or store in its explicit-slot form, `LDC` for any one-slot constant), one
int (a slot, a constant, a `newarray` code, an `iinc`'s slot and increment), and the master-pool
entry or the label a branch names. A position -- a label's, a handler range's, a line entry's
(`MethodCode.position()`, `ClassDefinition.Line`) -- is an instruction's index, so nothing is ever
recomputed from offsets. Labels bind (`newLabel`/`labelBinding`; a branch to an unbound label
waits, `checkComplete` refuses one left waiting), `exceptionCatch` takes bound labels, and
`append` splices a body built apart with every label bound (the dispatch tables' case bodies,
`<clinit>`'s pieces; records copied, labels and handlers rebased; never onto a body feeding an
operand-stack model). `ClassDefinition.Builder.addMethod(access, name, desc, body)` adds it; the
layer names no class definition (a `MethodCode.addTo` made the pair a class cycle,
`PackageCycleTest`). Over a compile context it feeds `Ctx.stack` every typed instruction and
reconciles the operand-stack model where a forward branch's label is bound. The emitters hold
`java.lang.classfile` entries directly (`ConstantPool.entries()`, plus `utf8Entry`/`classEntry`/
`methodRef`/`interfaceMethodRef`/`fieldRef`/`stringEntry` for one built from names); the former
wrapper types and `add*` facades are gone (2026-09-29), as are `JvmAsm`, the private `Asm` copies,
`Ctx.emit`/`emitU2`/`code` and `JvmEmitHelper.patchBranch` -- every emitter, builder and spliced
block writes records.

**Measured -- `size()` is the written size.** A local's load or store, an `iinc` and an int
constant in their shortest forms, an `ldc` by its master index, and a branch in its long form once
the layer knows it far (+2 `goto`, +5 conditional; its offset is known at its label's binding, or
at once for a bound one). The measure can fall short of the written length only where the writer
knows better: a branch pushed out of reach by ANOTHER's widening (+2/+5) or an `ldc` whose
constant landed past 255 in the class's own pool (+1). Every size budget reads this measure --
`chunkCodeBudget` 24,000 (Pass 2b), `JvmBodyOutliner.CODE_BUDGET`, `AstOutliner`'s
bytes-per-node, `DISPATCH_SEGMENT_BUDGET`, `debug-method-sizes` -- because `CodeBuilder` cannot:
it exists only inside the `ClassFile.build` callback of the class the method lands in, which the
split decides after every body is emitted, and the API re-runs a handler to relax a branch, which
emission's side effects would not survive. So a body is BUFFERED and the budgets measure the
buffer. Measured 2026-09-29, the buffer against the written `CodeAttribute` length per kept
method, forms as emitted: mito probe 8,940 methods -- 8,844 equal, 96 shorter, none longer; corpus
6,214 -- 6,213 equal, 1 shorter. With the writer's shortest forms: mito 8,777 shorter (up to 862 B,
6,166,021 -> 5,910,137 in all), corpus 5,892 shorter (up to 2,384); the methods over 8,000 bytes
are the same COUNT either way (20 mito, 62 corpus).

**Written -- `CodeReplay`.** It plays every body's records into the class's `CodeBuilder`:
branch targets and handler ranges become labels, a master entry is re-minted in the class's pool
as its instruction is written (an `ldc` takes the width its index there needs, both ways), a
local's load/store, an `iinc` and an int constant take their shortest form, and the frames,
`max_stack` and `max_locals` come from `StackMapsOption.GENERATE_STACK_MAPS` over
`StackMapFrames.resolver` ([stack-map-frames.md](stack-map-frames.md)). Far branches are
`CodeReplay.farBranches`, a fixpoint over the records' written sizes: the branch record names a
label, so no offset is ever encoded, and only the branches that do not reach are widened
(`JvmClassSplitter` runs under `FAIL_ON_SHORT_JUMPS`, so a branch the fixpoint missed is a loud
compile error). That replaced the API's own `FIX_SHORT_JUMPS` relaxation, which is not minimal --
premise overturned by measurement: once ONE short jump overflows it re-emits the method with
EVERY forward branch long, and the jose suite's `JSON::DECODE-JSON-ARRAY` (7,038 branches, one
past reach) came out 82,541 B -- past the limit -- where the fixpoint wrote 59,751; on the
records' written sizes, 57,909 B, one `goto_w` (`JoseTestSuiteE2eTest` pins it).

**Why, in numbers** (2026-09-29, JDK 25.0.4, cold CLI `-o X.class`, default `--optimize`; the
byte writer -- `toBytes`, then the shaker's parse+write, then the frame pass's parse+write, or the
split's writer then frames per class -- against one `ClassFile.build` per class):

| program | write phase | whole compile | output |
|---|---|---|---|
| ci-spec corpus | 2,785-2,924 ms -> 1,632-1,639 ms | 16.4-17.1 s -> 14.7-15.3 s | 7,687,515 -> 7,687,513 B |
| mito probe (split) | 2,029-2,094 ms -> 1,666-1,829 ms | 32.7-35.6 s -> 33.3-33.7 s | 9,193,297 + 2,827,878 -> 9,192,449 + 2,826,457 B |
| jose test suite | -- | -- | 5,146,274 -> 5,125,198 B |

The write phase is timed from the class complete as data to the bytes (gate check, shake,
placement, write); the old one parsed the class three times. The outputs above predate the
shortest forms, which took the corpus class to 7,498,301 B (code 4,545,371 -> 4,357,115, -4.1%)
and the mito probe to 9,008,889 + 2,755,146 B, the program's output unchanged; the corpus compile
then reached 14.7-15.4 s with a write phase of 1,448-1,531 ms once the records carried the
written measure (2026-09-29). Each stage landed byte-identical over 4,857 programs extracted from
the JVM-side tests plus the CLI compiles of the corpus (three levels, `--dynamic`), the mito probe
with `$Part1`, the jose suite and 28 example compiles -- the build timestamp in the version
strings the only difference, and two deliberate form changes without one (`iconst_m1` for
`bipush -1`, `aload 0` for `aload_0`) measured alone. The move retired `BranchRelaxer`,
`ByteCodeWriter` and its section DSL, `JvmClassShaker`, `ClassDefinition.toBytes`, the splitter's
byte writer and the pool's own storage: `am.ik.jvm` main source 4,993 -> 4,280 lines, 433 of them
the byte decoder, and `MethodCode` added 931 over it (5,265). Per-slice history and the
conversion tooling live in `.todo/artefacts/a8*`/`a9*` and git history.

**The two designs that depended on byte positions, and why they survive:**

1. **Size budgets** -- the measure above; a record buffer, not a byte stream, is what they read.
2. **The lossless over-limit pool.** The master pool is a `ConstantPoolBuilder`, which grows past
   65535 without complaint (checked only when written), so the split keeps its design: placement
   over master-entry closures, and re-pointing as a call is written into a class whose builder
   re-mints the entry. Nothing had to be kept symbolic beyond what the definition already was.


## Bodies bounded by construction
- Registry-proportional expansions (computed `typep` 37 KB/site, runtime `subtypep` 59 KB,
  computed-`error` dispatch 90 KB) are shared injected defuns over quoted DATA tables
  ([clos.md](clos.md)). A cond over every condition class lowers to NESTED ifs and hit the
  BRANCH limit at ~195 bytes/class — hence chained dispatch (`%error-runtime` -> `%ER-1` -> ...,
  `chainedDispatchDefuns`, all four backends). Ambiguous literal `slot-value` outlines onto
  `%slot-value(-set)-runtime`.
- `_invoke_<arity>` (66 KB at arity 9) and the spread `_invoke_v` are split by
  `JvmRuntimeBuilder.buildDispatchMethods` into chained segments (`_invoke_9$1`, ...) under
  `DISPATCH_SEGMENT_BUDGET`, 6000 bytes for HotSpot's HugeMethodLimit
  ([hot-path-method-size.md](hot-path-method-size.md)); the branch reach sets no bound there
  (a far branch is written `goto_w`).
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
`Ctx.allocTemp` refuses to cross. `iinc` was the one-byte slot the byte emitter's rewrite missed
(`maphash`, `%obj-slots`; fixed 2026-09-29 by `MethodCode.iinc`). Still open: a temporary's slot
is never reused.

## Pinning tests
- `JvmLispCompilerTest#compileAndRunABranchSpanningPastTheSigned16BitOffset`,
  `#compileAndRunTypepWithComputedSpecifier`, `#compileAndRunErrorWithComputedConditionType`,
  `#aCapturedLetVariableAssignedInlineInASiblingBranchPastTheSlotCeiling`,
  `#aMaphashWhoseCounterLandsPastSlot255IncrementsItsOwnLocal`,
  `#anObjSlotsWalkWhoseCursorLandsPastSlot255DecrementsItsOwnLocal`,
  `.compileCharBeyondBmpCodePoint`
- The layer: `am.ik.jvm.MethodCodeTest` (labels both ways, a far branch measured long forward
  and backward, `wide` locals and `iinc`, the measure as the written size, an unbound label
  refused, `invokeinterface`'s count, a handler, the operand-stack model kept in step and a join
  reached with two shapes refused, an array store and a return chosen by kind, a block spliced
  twice and what cannot be spliced)
- The writer: `am.ik.jvm.CodeReplayTest` (a long branch over wide locals, only the branch that
  does not reach widened, a widening cascade past the measure, handlers, `ldc` widened and
  narrowed by the class's pool, wide `iinc`, a body past the limit), `LineNumberTableTest` (lines
  through the shake, a relaxed branch and a part), `ConstantPoolTest` (a pool past the limit,
  `-0.0`, the Utf8 cap, one entry per content through every facade), and
  `JoseTestSuiteE2eTest` (the real 57,909-byte method)
- The split: `am.ik.jvm.JvmClassSplitterTest` (parts run, what cannot move stays, entries past
  65535 re-minted, the shake, unresolved own calls), `JvmLispCompilerSplitTest` (`SplitPrograms` past one
  class for real, forced splits at both optimize levels, an export library),
  `RontoLispCliTest#aProgramPastOneClassesPoolTravelsWithItsPartsInEveryOutputShape`, and the
  three JVM legs of `MitoE2eTest`
- `Uax15E2eTest`, `ClUnicodeTablesTest`; ci-spec `runtime-type-dispatch-and-symbol-designators`
