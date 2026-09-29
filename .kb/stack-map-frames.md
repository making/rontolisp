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
  the regression net is the same. `ClassFile.verify` at compile time would restore it and
  is NOT adopted (measured 2026-09-29 on the default-level ci-spec class): 711-755 ms
  against 441-494 ms for the frames themselves, and over the class-loader-free resolver
  above it reports 1,678 false errors (`RuntimeException` not assignable to `Throwable`:
  its assignability needs the real hierarchy), 0 over the class-loading default.
- javac-compiled embedded template classes ([template-class-embedding.md](template-class-embedding.md))
  carry their own frames and are never touched.
- `osrHostileBackedges` reads the generated `StackMapTable` back: a branch or switch whose
  target bci is at or before it and whose frame has a non-empty stack
  ([jvm-osr-backedges.md](jvm-osr-backedges.md)). Input below version 51 is framed first.

**Pipeline order is fixed**: optional `JvmClassShaker.shake` FIRST, then frames. The shaker
DROPS a `StackMapTable` (so shake stays callable on framed bytes), and the frames reference
constant-pool entries the pass appends, which the shaker's compaction could not rewrite. A
`LineNumberTable` (the uncaught report's site ids, [error-handling.md](error-handling.md)) is
the one other `Code` sub-attribute either accepts.

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

## The `wide` prefix

A local slot past 255 takes a `wide` prefix and a two-byte index. **Every reader of the finished
bytes must MEASURE it: `wide` is 4 bytes (6 for `iinc`), not 2** -- `BranchRelaxer.operandLength`
(a mis-measurement shifts every later branch offset; it takes the code list now, since the widened
opcode is a byte of the instruction), `JvmClassShaker`, `OperandStack.feed`. The frame pass reads
through `java.lang.classfile`, which measures it itself.

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
