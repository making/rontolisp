# Class version 61 via the offline StackMapAugmenter

Expands the CLAUDE.md "JVM Class Version 61 (Java 17)" invariant.

The JVM backend's emitters (every `Jvm*Compiler`, every hand-assembled
`Jvm*RuntimeBuilder`, `ByteCodeWriter` itself) are unchanged from the version-50
era: they write frame-free code in whatever shape is convenient, including code
that only the lenient type-inference verifier would accept as-is.
`JvmLispCompiler.compile()` then ALWAYS ends with
`StackMapAugmenter.augment(classBytes, CLASS_MAJOR_VERSION /* 61 */)` -- an
offline, language-independent post-pass in `am.ik.jvm` that makes the finished
bytes acceptable to the type-checking verifier mandatory from version 51+ and
stamps the class version. Compiled classes therefore need a Java 17+ JRE
(user-facing statement: `doc/{en,ja}/compiling/jvm.md`).

## What the pass does

Re-derives everything from nothing but the finished class bytes (the same
single-`Code`-attribute class shape `JvmClassShaker` relies on):

- Per method, a verifier-style worklist dataflow whose abstract values are the
  JVMS verification types (int/float/long/double, null, uninitialized,
  reference types with class names), tracked through operand stack AND locals.
- Records the fixpoint frame at every position that needs one: each branch
  target, each exception-handler entry, each instruction after an unconditional
  transfer. Encodings: same_frame / same_locals_1_stack_item (+extended) /
  append_frame / chop_frame / full_frame.
- Dead code (never reached by the dataflow) cannot carry a meaningful frame but
  the verifier still demands one; each maximal dead run is neutralized the way
  ASM's COMPUTE_FRAMES does it -- overwritten with `nop`s ending in `athrow`
  under a synthetic `[Throwable]`-stack frame, and dead ranges are carved out
  of the exception table.

## The second entry point: `osrHostileBackedges`

The same dataflow answers a question the emitters cannot answer for themselves:
which backward branches target a bci whose operand stack is non-empty -- the one
loop shape HotSpot refuses to OSR-compile
([jvm-osr-backedges.md](jvm-osr-backedges.md)). It is read-only (no frames are
written, no version stamped) and, unlike `augment`, it ACCEPTS an
already-augmented class: a `StackMapTable` sitting in a `Code` attribute is
skipped rather than rejected, so the analysis can be pointed at a finished
`.class` file. `augment` still rejects one, because it re-derives the frames and
must not be handed a stale table.

## Merge rules and safety properties

- Reference types merge to a nearest common superclass from a FIXED table (the
  boxed numerics under `Number`); any other unequal pair merges to
  `java/lang/Object`. A downstream instruction that would need the narrower
  type (e.g. `aaload` on an over-merged non-array) makes the pass THROW at
  compile time rather than emit an unverifiable frame.
- Interface types need no modelling: the verifier treats them as `Object` and
  defers the real check to `invokeinterface`.
- No reflection anywhere, so the pass is native-image and web-image safe.
- javac-compiled embedded template classes ([template-class-embedding.md](template-class-embedding.md),
  the `java:` bridge) are separate class files carrying their own frames -- the
  augmenter never touches them.

## Pipeline order (and why offline)

Order is fixed: optional `JvmClassShaker.shake` FIRST, then augment. The shaker
rejects `Code` sub-attributes (it now DROPS a `StackMapTable` rather than
preserving it, so shake stays callable on already-augmented bytes), and the
frames reference constant-pool entries the augmenter appends, which the
shaker's compaction would not know how to rewrite.

Offline (vs extending the emit-time `am.ik.jvm.OperandStack` model,
[error-handling.md](error-handling.md)) was chosen because it covers the ~20
hand-assembled `Jvm*RuntimeBuilder` raw-code methods for free and needs no
change to any emitter.

## Cost

Default output grew ~+80% (heavy.lisp 75KB -> 135KB), `--optimize` +28%
(10.6KB -> 13.5KB) -- an accepted trade-off (decision 2026-07-17). Most
full_frames come from branch targets with a non-empty operand stack
(argument-position control flow); no cheaper encoding is left beyond what is
implemented.

## What version 61 unlocks (not yet used)

- `invokedynamic` (v51+): could replace the `_invoke_N` linear if-else id
  dispatch. Not started; needs its own todo first (the emitters/shaker/
  augmenter also do not model `tableswitch` yet).
- Interface-static `invokestatic` (v52+): legal now, and the assembler side is
  ready (emit `INVOKESTATIC` on a `ConstantPool.addInterfaceMethodref` tag-11
  constant; `OperandStack`, the augmenter, and `JvmClassShaker` parse
  Methodref/InterfaceMethodref bodies identically, so no other change is
  needed). The one workaround the old ban had forced --
  `Collections.emptyList()` where `List.of()` was unusable, in the http-handler
  runtime -- was already gone before the bump: response-header marshalling
  replaced it with a genuinely mutable `ArrayList`, and a survey of every
  `Jvm*RuntimeBuilder` found no other avoidance site. Nothing to simplify;
  future emitters may just use interface statics.

## The `wide` prefix (todo-562, 2026-08-29)

A local slot past 255 has no one-byte operand, so the load/store takes a `wide`
prefix and a two-byte index. Every reader of the finished bytes therefore has to
MEASURE it -- `wide` is 4 bytes (6 for `iinc`, whose constant widens with the
index), not the 2 a bare opcode-plus-index would be:

- `StackMapAugmenter.operandLength` sizes it, and `interpret` dispatches a
  `wide`-prefixed opcode to `interpretWide`, which applies the same push/store
  over the two-byte index (both `augment` and `osrHostileBackedges` walk through
  these, so both follow).
- `BranchRelaxer.operandLength` sizes it too -- a mis-measured instruction there
  shifts every later branch offset. Its `operandLength` takes the code list now,
  because the widened opcode is a byte of the instruction.
- `JvmClassShaker` already sized it (it carries no constant-pool index, so
  nothing else changes).
- `OperandStack` models it: `feed` consumes the prefix and sizes the widened
  instruction from the opcode that follows.

The EMISSION side is one chokepoint per code list. `JvmLispCompiler.Ctx.emit`
sees `emit(opcode)` then `emit(slot)` as two calls, so it asks
`OperandStack.awaitingLocalIndex()` and, for a slot past 255, retroactively
rewrites the one opcode byte it already appended into `wide opcode u2`
(`widenPendingLocalIndex` moves the model's position bookkeeping; the stack
effect is unchanged). Because the rewrite only ever extends the TAIL of the
list, every label and branch position recorded earlier stays valid.
`JvmAsm.localOp` does the same for the blocks spliced in whole by
`Ctx.emitBlock` -- those look like hand-assembled runtime bodies but their slots
come from `Ctx.allocTemp` (`JvmStringCaseFold`, `JvmSubseqCompiler`,
`JvmStringTrimCompiler`, `JvmIntFusionCompiler`), so they grow with the
enclosing method. That second site was found by putting a loud check where the
"this cannot happen" assumption was: it fired immediately, on the existing
`JvmLispCompilerTest.aFoldedCallPrintsWhatTheRuntimeWouldHave`, whose
`string-upcase` block was writing slot 494 as `astore 238`. That test PASSED
with the truncation, which is the whole hazard -- a store and its matching load
wrap the same way, so an aliased pair is invisible until the slot it landed on
is also live. Everything else that appends a load/store to a raw list is a
`Jvm*RuntimeBuilder` whose slots are literals, plus `JvmUncaughtHandler` (slot 1
in practice, and now a loud check rather than an assumption).

Before this, `astore 300` was written as `astore 44`. The frame walk catches
that only when the wrapped slot holds a DIFFERENT verification type -- the
`.todo/562` reproducer surfaced as `aaload at 8659 on non-array type
java/lang/Object` because slot 256 wrapped onto the closure environment in slot
0. When the types agree nothing notices, and the program simply answers wrong:
a `let`-bound `keep` overwritten by a temporary gave `(0 0)` where the
interpreter gives `(7 0)`.

### What it costs, measured 2026-08-29

Compiling every example under `examples/` with the jar before and after, byte
for byte (193 of 219 compile on the JVM backend): **189 are byte-identical**.
Three are not -- `examples/browser/hiragana/{train,dataset,prototypes}.lisp`,
which genuinely reach slots 256..294 and were therefore being written as slots
0..38 before this. `train.lisp` grows 399,944 -> 401,004 bytes (+0.27%) for 287
`wide` instructions. The fourth apparent difference,
`examples/jvm/cffi-sqlite.lisp`, is the SAME SIZE and is not this change at all:
it is a pre-existing per-run permutation of two global static fields
(`.todo/570`, recorded in
[emitted-output-determinism.md](emitted-output-determinism.md)).

The concatenated ci-spec corpus (420 cases, a 3.9 MB class) is byte-identical
apart from a build-timestamp literal the program itself embeds -- so
`CiSpecE2eTest`'s output cannot shift, and nothing outside `am.ik.jvm` /
`codegen.jvm` was touched, so the interpreter and both WASM backends cannot
either.

The hard ceiling is now `max_locals`, a u2: `Ctx.allocTemp` throws past 65535
naming the limit. What is left of `.todo/137` is the COUNT, not the index --
`ctx.nextLocal` only grows, so a straight-line body still burns a slot per
temporary; past 255 each of its loads and stores costs three extra bytes, and
`max_locals` sizes every full_frame the method carries.

## Pinning tests

- `ByteCodeWriterTest.generateAndRun{TypedCatch,CatchAny}Handler` pin the RAW
  assembler contract the emitters still rely on: version-50 output verifies
  exception handlers without a StackMapTable.
- `ByteCodeWriterTest.generateAndRunAWideLocalIndexAcrossARelaxedBranch` pins
  the `wide` decoding in both readers at once: a `wide astore`/`wide aload` of
  slot 300 around a branch far enough out that `BranchRelaxer` has to size the
  body past it, augmented to version 61 and run.
- `JvmLispCompilerTest.compileAndRunABodyPastTheOneByteLocalSlotIndex` (the
  SILENT wrong answer) and `#...UnderAnUnsplittableTail` (the same overflow
  where the frame walk notices) pin the end-to-end path.
- `JvmLispCompilerTest` (all output augmented) + `JvmClassShakerTest` and its
  corpus exercise the shake -> augment pipeline.
