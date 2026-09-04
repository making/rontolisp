# Class version 61 via the offline StackMapAugmenter

Expands the CLAUDE.md "JVM Class Version 61 (Java 17)" invariant.

The JVM emitters (every `Jvm*Compiler`, every `Jvm*RuntimeBuilder`, `ByteCodeWriter`)
write frame-free version-50-era code. `JvmLispCompiler.compile()` ALWAYS ends with
`StackMapAugmenter.augment(classBytes, CLASS_MAJOR_VERSION /* 61 */)` -- an offline,
language-independent post-pass in `am.ik.jvm` that makes the bytes acceptable to the
type-checking verifier mandatory from version 51+ and stamps the version. Compiled
classes need a Java 17+ JRE (`doc/{en,ja}/compiling/jvm.md`).

## `augment`

Re-derives everything from the finished bytes (same single-`Code`-attribute shape
`JvmClassShaker` relies on):

- Per method, a verifier-style worklist dataflow over JVMS verification types, through
  operand stack AND locals.
- Records the fixpoint frame at every branch target, exception-handler entry, and
  instruction after an unconditional transfer. Encodings: same_frame /
  same_locals_1_stack_item (+extended) / append_frame / chop_frame / full_frame.
- Dead code carries no meaningful frame but the verifier demands one: each maximal dead
  run is overwritten with `nop`s ending in `athrow` under a synthetic `[Throwable]`-stack
  frame (as ASM's COMPUTE_FRAMES does), and dead ranges are carved out of the exception
  table.
- Reference types merge to a nearest common superclass from a FIXED table (boxed numerics
  under `Number`); any other unequal pair merges to `java/lang/Object`. An instruction
  needing the narrower type (e.g. `aaload` on an over-merged non-array) makes the pass
  THROW at compile time rather than emit an unverifiable frame.
- Interface types need no modelling (verifier treats them as `Object`).
- No reflection: native-image and web-image safe. javac-compiled embedded template
  classes ([template-class-embedding.md](template-class-embedding.md)) carry their own
  frames and are never touched.

## Second entry point: `osrHostileBackedges`

Same dataflow, answering which backward branches target a bci with a non-empty operand
stack -- the loop shape HotSpot refuses to OSR-compile
([jvm-osr-backedges.md](jvm-osr-backedges.md)). Read-only, and it ACCEPTS an
already-augmented class (existing `StackMapTable` skipped). `augment` REJECTS one: it
re-derives frames and must not see a stale table.

## Pipeline order

Fixed: optional `JvmClassShaker.shake` FIRST, then augment. The shaker rejects `Code`
sub-attributes (it DROPS a `StackMapTable`, so shake stays callable on augmented bytes),
and the frames reference constant-pool entries the augmenter appends, which the shaker's
compaction could not rewrite.

Cost of frames: default output ~+80%, `--optimize` +28%.

## Version 61 unlocks, not yet used

- `invokedynamic` (v51+) could replace the `_invoke_N` linear if-else id dispatch. Not
  started; emitters/shaker/augmenter also do not model `tableswitch`.
- Interface-static `invokestatic` (v52+) is legal and the assembler side is ready
  (`INVOKESTATIC` on a `ConstantPool.addInterfaceMethodref` tag-11 constant;
  `OperandStack`, augmenter and `JvmClassShaker` parse Methodref/InterfaceMethodref
  identically).

## The `wide` prefix

A local slot past 255 takes a `wide` prefix and a two-byte index. Every reader of the
finished bytes must MEASURE it: `wide` is 4 bytes (6 for `iinc`), not 2.

- `StackMapAugmenter.operandLength` sizes it; `interpret` dispatches a `wide`-prefixed
  opcode to `interpretWide` (both entry points follow).
- `BranchRelaxer.operandLength` sizes it too -- a mis-measurement shifts every later
  branch offset. It takes the code list now, since the widened opcode is a byte of the
  instruction.
- `JvmClassShaker` already sized it; `OperandStack.feed` consumes the prefix and sizes
  from the following opcode.

EMISSION is one chokepoint per code list. `JvmLispCompiler.Ctx.emit` sees `emit(opcode)`
then `emit(slot)` as two calls, asks `OperandStack.awaitingLocalIndex()`, and for a slot
past 255 retroactively rewrites the appended opcode byte into `wide opcode u2`
(`widenPendingLocalIndex`). The rewrite only extends the TAIL, so earlier labels and
branch positions stay valid. `JvmAsm.localOp` does the same for blocks spliced whole by
`Ctx.emitBlock` -- they look hand-assembled but their slots come from `Ctx.allocTemp`
(`JvmStringCaseFold`, `JvmSubseqCompiler`, `JvmStringTrimCompiler`,
`JvmIntFusionCompiler`) and grow with the enclosing method. Everything else appending a
load/store to a raw list is a `Jvm*RuntimeBuilder` with literal slots, plus
`JvmUncaughtHandler` (slot 1; now a loud check).

**Trap: truncation is SILENT.** `astore 300` written as `astore 44` is caught by the
frame walk only when the wrapped slot holds a DIFFERENT verification type. When the types
agree the program simply answers wrong and the test still passes.

Hard ceiling is now `max_locals`, a u2: `Ctx.allocTemp` throws past 65535.
`ctx.nextLocal` only grows, so a straight-line body burns a slot per temporary.

## Tests

- `ByteCodeWriterTest.generateAndRun{TypedCatch,CatchAny}Handler` -- the RAW assembler
  contract: version-50 output verifies exception handlers without a StackMapTable.
- `ByteCodeWriterTest.generateAndRunAWideLocalIndexAcrossARelaxedBranch` -- `wide`
  decoding in both readers, across a branch `BranchRelaxer` must resize.
- `JvmLispCompilerTest.compileAndRunABodyPastTheOneByteLocalSlotIndex` (silent wrong
  answer) and `#...UnderAnUnsplittableTail` (frame walk notices).
- `JvmLispCompilerTest` (all output augmented), `JvmClassShakerTest` + corpus.
