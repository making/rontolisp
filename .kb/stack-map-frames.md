# Class version 61 via the offline StackMapFrames pass

The JVM emitters (every `Jvm*Compiler`, every `Jvm*RuntimeBuilder`, `ByteCodeWriter`) write
frame-free version-50-era code; `JvmLispCompiler.compile()` ALWAYS ends with
`JvmLispCompiler.withFrames` -> `am.ik.jvm.StackMapFrames.generate(classBytes, 61)`, an offline,
language-independent post-pass that makes the bytes acceptable to the type-checking verifier
mandatory from version 51+ and stamps the version. Compiled classes need a Java 17+ JRE
(`doc/{en,ja}/compiling/jvm.md`) -- except a `java:` program, stamped `max(61, 44 + R)` for the
release R its sites resolved against (`JvmLispCompiler.classMajorVersion`,
[java-interop.md](java-interop.md)). The `$PartN` classes of a split and the `java:`
implementation classes (`JvmJavaImplementations.classFiles(withFrames)`) take the same pass.

## `generate`

`java.lang.classfile` (in `java.base`, no dependency) does the work: parse, drop the
`ClassFileVersion`, stamp the new one, pass every method body through
`CodeTransform.ACCEPT_ALL` under `StackMapsOption.GENERATE_STACK_MAPS`. The writer derives the
frames, `max_stack` and `max_locals` from the code -- a declared `max_stack` no longer reaches
the shipped class, so an under-declared one is no longer a `VerifyError`.

- Dead code: overwritten with `nop`s ending in `athrow` under a `[Throwable]`-stack frame and
  carved out of the exception table (the API's `PATCH_DEAD_CODE` default, same as ASM).
- Merges go through a `ClassHierarchyResolver` that loads nothing (native-image and web-image
  safe): a FIXED table (`Object`; boxed numerics under `Number`), then a caller's
  `Function<String, ClassFileInfo>` -- `JvmClassFileLookup.classInfo` for a `java:` program,
  so its host types merge along the `ct.sym` / class-path hierarchy -- then anything else is a
  class directly under `Object`, so an unequal pair merges to `Object`. Never claim a
  superclass that is not true: a wrong merge verifies nowhere.
- Failures: an inconsistent stack at a merge is an `IllegalStateException`
  `stack map frames of <class>: Stack content mismatch at bytecode offset N of method m(int)`
  (the API's appended method dump is cut off at the first line). A pool that cannot take the
  frames' own entries (the attribute name, a Class entry per frame type) is a
  `ConstantPoolOverflowException`, matched on the API's `Constant pool is too large` message,
  and `JvmLispCompiler` then takes the split path.
- NOT loud any more: `aaload` on a non-array (an over-lossy merge) makes the generator push
  TOP instead of throwing, which surfaces as a `VerifyError` at class load. The old
  `StackMapAugmenter` threw at compile time; the tests load every class they compile, so
  the regression net is the same.
- javac-compiled embedded template classes ([template-class-embedding.md](template-class-embedding.md))
  carry their own frames and are never touched.
- `osrHostileBackedges` reads the generated `StackMapTable` back: a branch or switch whose
  target bci is at or before it and whose frame has a non-empty stack
  ([jvm-osr-backedges.md](jvm-osr-backedges.md)). Input below version 51 is framed first.

**Pipeline order is fixed**: optional `JvmClassShaker.shake` FIRST, then frames. The shaker
DROPS a `StackMapTable` (`StackMapsOption.DROP_STACK_MAPS`, so shake stays callable on framed
bytes). A `LineNumberTable` (the uncaught report's site ids, [error-handling.md](error-handling.md))
passes through both, its entries riding the instructions they label.

Version 61 unlocks not yet used: `invokedynamic` (v51+) for the `_invoke_N` linear if-else id
dispatch (nothing models `tableswitch` either); interface-static `invokestatic` (v52+), for which the
assembler side is ready (`INVOKESTATIC` on a `ConstantPool.addInterfaceMethodref` tag-11 constant).

## Measured 2026-09-29 (JDK 25.0.4)

Replacing `StackMapAugmenter` (1,456 lines, a hand-written verifier dataflow) with the API.
Pass alone, same frame-free v50 input (a shipped class with its table stripped), mean of 20
after 10 warm-up runs:

| input | `--optimize` | old bytes | new bytes | old ms | new ms |
|---|---|---|---|---|---|
| 10-line fib/hash/defstruct/handler-case/sort sample | default | 59,580 | 57,915 | 4.07 | 2.85 |
| same | off | 339,168 | 333,927 | 31.9 | 14.6 |
| `examples/console/contact-book.lisp` | default | 31,558 | 30,752 | 6.79 | 5.08 |
| same | off | 452,975 | 449,579 | 73.1 | 24.3 |
| `examples/console/error-handling.lisp` | default | 61,378 | 60,097 | 5.67 | 2.97 |
| same | off | 315,274 | 311,100 | 35.7 | 13.4 |
| ci-spec corpus | default | 7,698,237 | 7,662,328 | 1,300-2,200 | 608 |
| same | off | 7,704,285 | 7,668,308 | 1,307 | 500 |

Whole CLI compile of the ci-spec corpus (`-o C.class`, 3 runs): default 20.1-21.3 s -> 15.4-16.5
s, off 17.7-21.9 s -> 14.9-15.8 s; output 7,723,482 -> 7,668,296 B (default).

Image cost of `java.lang.classfile`: `-Pweb` playground `rontoplayground.js.wasm` 27,718,994 ->
28,640,483 B (+3.3%), a JVM compile in it still verifies and runs; `-Pnative` CLI binary
96,143,624 -> 97,192,200 B (+1.1%), `-o X.class` from it verifies and runs.

## The readers and the shaker on the API (2026-09-29)

`ClassFileInfo.parse` reads a `ClassModel`; `JvmClassShaker` builds its call graph from
`InvokeInstruction`/`FieldInstruction` elements and writes through a `ClassTransform` dropping
unreached methods and unused fields under `ConstantPoolSharingOption.NEW_POOL` (the compaction)
and `DROP_STACK_MAPS`. The reachability rules live in `am.ik.jvm.OwnCallGraph`, which
`JvmClassSplitter` fills from its `ClassDefinition` scan, so the two cannot drift.
`am.ik.jvm` main source 5,627 -> 4,993 lines (-634).

- **A fresh pool is laid out in write order**, not the input's: a constant below index 256 can
  land above it, and its `ldc` becomes `ldc_w` with the branches around it re-offset by the API
  (`JvmClassShakerTest.anLdcWhoseConstantMovesPastIndex255WidensAndTheBranchOverItFollows`). The
  old in-place compaction could only shrink an index.
- **The split no longer matches the shaker byte for byte**: it keeps the definition's pool order.
  `JvmClassSplitterTest.aDefinitionThatFitsIsWrittenAsTheShakerWritesIt` pins the same members
  and the same symbolic instructions instead.
- **The parser refuses a major version above `ClassFile.latestMajorVersion()`**; the hand reader
  did not care. `ClassFileInfo` lowers the version in a copy, so a `--java-classpath` jar built
  for a newer Java still resolves (`JvmClassPathTest.aClassFileNewerThanTheRunningJdkReadsItsDeclaredShape`).

Pass alone, frame-free v50 input (a `--optimize=off` class with its table stripped), roots
`main`/`run`/`_apply`/`_lispToString`/`call`, mean of 20 after 10 warm-up runs:

| input | shaken bytes old -> new | shake ms old -> new | `unresolvedSelfMethods` ms old -> new |
|---|---|---|---|
| fib/hash/defstruct/handler-case/sort sample | 45,751 -> 45,663 | 4.9-5.4 -> 14.9-17.3 | 4.9-5.2 -> 4.5-4.8 |
| `examples/console/contact-book.lisp` | 24,709 -> 24,630 | 1.9-2.0 -> 5.0-5.2 | 8.3-9.2 -> 6.5-6.7 |
| `examples/console/error-handling.lisp` | 47,535 -> 47,495 | 2.5-2.6 -> 5.8 | 2.9-4.0 -> 3.4 |
| ci-spec corpus | 5,682,117 -> 5,682,117 | 209-240 -> 317-326 | 81-90 -> 104-115 |

The frame pass after it costs the same on either output (corpus 464-479 ms). So a compile pays
+3-12 ms on a small program and ~+0.1 s on the corpus (~15 s CLI compile). `ClassFileInfo` over
3,000 `ct.sym` signature files: 9.6-11.6 ms both. Not taken: one transform doing the shake and
the frames (`NEW_POOL` + `GENERATE_STACK_MAPS`) wrote the corpus in 557-569 ms against ~720 ms
for the two writes, but couples the two passes' API for a gain the move of emission onto
`CodeBuilder` (which shakes before writing and frames while emitting) makes moot.

## The `wide` prefix

A local slot past 255 takes a `wide` prefix and a two-byte index. **Every hand-written reader of
the code must MEASURE it: `wide` is 4 bytes (6 for `iinc`), not 2** -- `BranchRelaxer.operandLength`
(a mis-measurement shifts every later branch offset; it takes the code list now, since the widened
opcode is a byte of the instruction), `JvmClassSplitter.Sites`, `OperandStack.feed`. The frame
pass, `JvmClassShaker` and `ClassFileInfo` read through `java.lang.classfile`, which measures it
itself.

EMISSION is one chokepoint per code list: `JvmLispCompiler.Ctx.emit` sees `emit(opcode)` then
`emit(slot)` as two calls, asks `OperandStack.awaitingLocalIndex()`, and for a slot past 255
retroactively rewrites the appended opcode byte into `wide opcode u2` (`widenPendingLocalIndex`) --
the rewrite only extends the TAIL, so earlier labels stay valid. `JvmAsm.localOp` does the same for
blocks spliced whole by `Ctx.emitBlock` (`JvmStringCaseFold`, `JvmSubseqCompiler`,
`JvmStringTrimCompiler`, `JvmIntFusionCompiler` -- they look hand-assembled but their slots come from
`Ctx.allocTemp`). Everything else is a `Jvm*RuntimeBuilder` with literal slots, plus
`JvmUncaughtHandler` (slot 1; now a loud check).

**Trap: truncation is SILENT.** `astore 300` written as `astore 44` is caught by the verifier
only when the wrapped slot holds a DIFFERENT verification type; when the types agree the program
simply answers wrong and the test still passes.

Hard ceiling is now `max_locals`, a u2: `Ctx.allocTemp` throws past 65535. `ctx.nextLocal` only
grows, so a straight-line body burns a slot per temporary.

## Tests

- `StackMapFramesTest` -- the fixed table (`Integer`/`Long` -> `Number`), unknown -> `Object`,
  a lookup's superclass, the one-line failure naming the method, the pool overflow, the
  backedge query.
- `ByteCodeWriterTest.generateAndRun{TypedCatch,CatchAny}Handler` -- the RAW assembler contract:
  version-50 output verifies exception handlers without a StackMapTable.
- `ByteCodeWriterTest.generateAndRunAWideLocalIndexAcrossARelaxedBranch` -- `wide` decoding
  across a branch `BranchRelaxer` must resize, framed and run.
- `JvmLispCompilerTest.compileAndRunABodyPastTheOneByteLocalSlotIndex` (silent wrong answer) and
  `#...UnderAnUnsplittableTail` (the verifier notices); all `JvmLispCompilerTest` output is framed;
  `JvmClassShakerTest` + corpus.
