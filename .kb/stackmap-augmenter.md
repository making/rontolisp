# Class version 61 via the offline StackMapAugmenter

Expands the CLAUDE.md "JVM Class Version 61 (Java 17)" invariant. The JVM emitters (every
`Jvm*Compiler`, every `Jvm*RuntimeBuilder`, `ByteCodeWriter`) write frame-free version-50-era code;
`JvmLispCompiler.compile()` ALWAYS ends with
`StackMapAugmenter.augment(classBytes, CLASS_MAJOR_VERSION /* 61 */)`, an offline,
language-independent post-pass in `am.ik.jvm` that makes the bytes acceptable to the type-checking
verifier mandatory from version 51+ and stamps the version. Compiled classes need a Java 17+ JRE
(`doc/{en,ja}/compiling/jvm.md`).

## `augment`

Re-derives everything from the finished bytes (the same single-`Code`-attribute shape
`JvmClassShaker` relies on): a verifier-style worklist dataflow over JVMS verification types,
recording the fixpoint frame at every branch target, exception-handler entry and instruction after an
unconditional transfer.

- Dead code carries no meaningful frame but the verifier demands one: each maximal dead run is
  overwritten with `nop`s ending in `athrow` under a synthetic `[Throwable]`-stack frame (as ASM's
  COMPUTE_FRAMES does), and dead ranges are carved out of the exception table.
- Reference types merge to a nearest common superclass from a FIXED table (boxed numerics under
  `Number`); any other unequal pair merges to `java/lang/Object`. An instruction needing the narrower
  type (e.g. `aaload` on an over-merged non-array) makes the pass THROW at compile time rather than
  emit an unverifiable frame. Interfaces need no modelling (the verifier treats them as `Object`).
- No reflection: native-image and web-image safe. javac-compiled embedded template classes
  ([template-class-embedding.md](template-class-embedding.md)) carry their own frames and are never
  touched.
- Second entry point `osrHostileBackedges`: the same dataflow, answering which backward branches
  target a bci with a non-empty operand stack -- the loop shape HotSpot refuses to OSR-compile
  ([jvm-osr-backedges.md](jvm-osr-backedges.md)). It ACCEPTS an already-augmented class; `augment`
  REJECTS one, since it must not see a stale table.

**Pipeline order is fixed**: optional `JvmClassShaker.shake` FIRST, then augment. The shaker rejects
`Code` sub-attributes (it DROPS a `StackMapTable`, so shake stays callable on augmented bytes), and
the frames reference constant-pool entries the augmenter appends, which the shaker's compaction could
not rewrite. Cost of frames: default output ~+80%, `--optimize` +28%.

Version 61 unlocks not yet used: `invokedynamic` (v51+) for the `_invoke_N` linear if-else id
dispatch (nothing models `tableswitch` either); interface-static `invokestatic` (v52+), for which the
assembler side is ready (`INVOKESTATIC` on a `ConstantPool.addInterfaceMethodref` tag-11 constant).

## The `wide` prefix

A local slot past 255 takes a `wide` prefix and a two-byte index. **Every reader of the finished
bytes must MEASURE it: `wide` is 4 bytes (6 for `iinc`), not 2** --
`StackMapAugmenter.operandLength` + `interpret`/`interpretWide`, `BranchRelaxer.operandLength` (a
mis-measurement shifts every later branch offset; it takes the code list now, since the widened
opcode is a byte of the instruction), `JvmClassShaker`, `OperandStack.feed`.

EMISSION is one chokepoint per code list: `JvmLispCompiler.Ctx.emit` sees `emit(opcode)` then
`emit(slot)` as two calls, asks `OperandStack.awaitingLocalIndex()`, and for a slot past 255
retroactively rewrites the appended opcode byte into `wide opcode u2` (`widenPendingLocalIndex`) --
the rewrite only extends the TAIL, so earlier labels stay valid. `JvmAsm.localOp` does the same for
blocks spliced whole by `Ctx.emitBlock` (`JvmStringCaseFold`, `JvmSubseqCompiler`,
`JvmStringTrimCompiler`, `JvmIntFusionCompiler` -- they look hand-assembled but their slots come from
`Ctx.allocTemp`). Everything else is a `Jvm*RuntimeBuilder` with literal slots, plus
`JvmUncaughtHandler` (slot 1; now a loud check).

**Trap: truncation is SILENT.** `astore 300` written as `astore 44` is caught by the frame walk only
when the wrapped slot holds a DIFFERENT verification type; when the types agree the program simply
answers wrong and the test still passes.

Hard ceiling is now `max_locals`, a u2: `Ctx.allocTemp` throws past 65535. `ctx.nextLocal` only
grows, so a straight-line body burns a slot per temporary.

## Tests

- `ByteCodeWriterTest.generateAndRun{TypedCatch,CatchAny}Handler` -- the RAW assembler contract:
  version-50 output verifies exception handlers without a StackMapTable.
- `ByteCodeWriterTest.generateAndRunAWideLocalIndexAcrossARelaxedBranch` -- `wide` decoding in both
  readers, across a branch `BranchRelaxer` must resize.
- `JvmLispCompilerTest.compileAndRunABodyPastTheOneByteLocalSlotIndex` (silent wrong answer) and
  `#...UnderAnUnsplittableTail` (frame walk notices); all `JvmLispCompilerTest` output is augmented;
  `JvmClassShakerTest` + corpus.
