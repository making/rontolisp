# Class version 61: frames computed as the class is written

The JVM emitters (every `Jvm*Compiler`, every `Jvm*RuntimeBuilder`) write frame-free,
version-50-era code into a `ClassDefinition`; `JvmClassSplitter.write` builds every class through
`ClassFile.build` under `StackMapsOption.GENERATE_STACK_MAPS`, so the frames, `max_stack` and
`max_locals` are derived from the code as it is written, and stamps the version
(`JvmClassSplitter.Target`, from `JvmLispCompiler.writeTarget`). A declared `max_stack` never
reaches the shipped class, so an under-declared one is not a `VerifyError`. Compiled classes
need a Java 17+ JRE (`doc/{en,ja}/compiling/jvm.md`) -- except a `java:` program, stamped
`max(61, 44 + R)` for the release R its sites resolved against
(`JvmLispCompiler.classMajorVersion`, [java-interop.md](java-interop.md)). The `$PartN` classes of
a split and the `java:` implementation classes (`JvmJavaImplementations.classFiles(target)`) are
written the same way. The writer: [jvm-method-size-limits.md](jvm-method-size-limits.md), "How a
class is written".

`am.ik.jvm.StackMapFrames` holds the class hierarchy the writer's merges read, a post-pass that
gives a finished frame-free class file its frames (`generate`: parse, drop the
`ClassFileVersion`, stamp the new one, every body through `CodeTransform.ACCEPT_ALL` -- for a
test's hand-built class, and for `osrHostileBackedges` on input below version 51), and the
backedge query.

## The frames

- Dead code: overwritten with `nop`s ending in `athrow` under a `[Throwable]`-stack frame and
  carved out of the exception table (the API's `PATCH_DEAD_CODE` default, same as ASM).
- Merges go through a `ClassHierarchyResolver` that loads nothing (native-image and web-image
  safe, `StackMapFrames.resolver`): a FIXED table (`Object`; boxed numerics under `Number`),
  then a caller's `Function<String, ClassFileInfo>` -- `JvmClassFileLookup.classInfo` for a
  `java:` program, so its host types merge along the `ct.sym` / class-path hierarchy -- then
  anything else is a class directly under `Object`, so an unequal pair merges to `Object`.
  Never claim a superclass that is not true: a wrong merge verifies nowhere.
- Failures: an inconsistent stack at a merge is an `IllegalStateException`
  `writing class <class>: Stack content mismatch at bytecode offset N of method m(int)` from the
  writer (`stack map frames of <class>: ...` from `generate`), the API's appended method dump cut
  off at the first line. A class whose pool cannot take the frames' own entries (the attribute
  name, a Class entry per frame type) is a `ConstantPoolOverflowException`, matched on the API's
  `Constant pool is too large` message, and the writer then splits it.
- NOT loud: `aaload` on a non-array (an over-lossy merge) makes the generator push TOP instead of
  throwing, which surfaces as a `VerifyError` at class load. The tests load every class they
  compile, so the regression net is class loading. `ClassFile.verify` at compile time would
  restore it and is NOT adopted (measured 2026-09-29 on the default-level ci-spec class): 711-755
  ms against 441-494 ms for the frames themselves, and over the class-loader-free resolver above
  it reports 1,678 false errors (`RuntimeException` not assignable to `Throwable`: its
  assignability needs the real hierarchy), 0 over the class-loading default.
- javac-compiled embedded template classes ([template-class-embedding.md](template-class-embedding.md))
  carry their own frames and are never touched.
- `osrHostileBackedges` reads the written `StackMapTable` back: a branch or switch whose target
  bci is at or before it and whose frame has a non-empty stack
  ([jvm-osr-backedges.md](jvm-osr-backedges.md)). Input below version 51 is framed first.

A `LineNumberTable` (the uncaught report's site ids, [error-handling.md](error-handling.md))
is written from the definition's line entries, each riding the instruction it starts at.

Version 61 unlocks not yet used: `invokedynamic` (v51+) for the `_invoke_N` linear if-else id
dispatch (nothing models `tableswitch` either); interface-static `invokestatic` (v52+), for which the
assembler side is ready (`INVOKESTATIC` on a `ConstantPool.addInterfaceMethodref` tag-11 constant).

## Measured 2026-09-29 (JDK 25.0.4)

Replacing `StackMapAugmenter` (1,456 lines, a hand-written verifier dataflow) with the API, as a
post-pass. Pass alone, same frame-free v50 input (a shipped class with its table stripped), mean
of 20 after 10 warm-up runs:

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

Folding the post-pass into the write (the shake and the frames in the one `ClassFile.build` per
class, no written bytes parsed again) took the corpus write phase from 2,785-2,924 ms to
1,632-1,639 ms in a cold CLI run (2026-09-29, [jvm-method-size-limits.md](jvm-method-size-limits.md)).

## The readers on the API (2026-09-29)

`ClassFileInfo.parse` reads a `ClassModel`. The byte-level `JvmClassShaker` that also moved onto
the API that day is gone the same day: the writer shakes the definition before writing
(`OwnCallGraph`, [optimize-dead-code-elimination.md](optimize-dead-code-elimination.md)), as the
a83 measurement predicted ("one transform doing the shake and the frames ... the move of emission
onto CodeBuilder makes moot"). Its own cost over the hand compactor it replaced (+3-12 ms on a
small program, ~+0.1 s on the corpus) went with it.

- **A fresh pool is laid out in write order**, not the master pool's: a constant below index 256
  can land above it and the other way, so an `ldc` is written `ldc` or `ldc_w` by where its
  constant landed, and a branch over it follows (`CodeReplayTest`).
- **The parser refuses a major version above `ClassFile.latestMajorVersion()`**; the hand reader
  did not care. `ClassFileInfo` lowers the version in a copy, so a `--java-classpath` jar built
  for a newer Java still resolves (`JvmClassPathTest.aClassFileNewerThanTheRunningJdkReadsItsDeclaredShape`).

`ClassFileInfo` over 3,000 `ct.sym` signature files: 9.6-11.6 ms, hand reader and API alike.

## The `wide` prefix

A local slot past 255 takes a `wide` prefix and a two-byte index. **Every hand-written reader of
the code must MEASURE it: `wide` is 4 bytes (6 for `iinc`), not 2** -- `CodeReplay.length` (a
mis-measurement shifts every later instruction and every branch target), `OperandStack.feed`.
The writer keeps the form: a replayed `wide aload` is written `aload_w` (`Opcode.ALOAD_W`).

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
- `CodeReplayTest.aTypedCatchAndACatchAnyBothLand` -- handlers of both shapes, framed and run.
- `CodeReplayTest.aLongBranchOverWideLocalsIsWrittenInItsGotoWForm` -- `wide` decoding across a
  branch the replay must widen, framed and run; `#aWideIincKeepsItsSlotAndIncrement`.
- `JvmLispCompilerTest.compileAndRunABodyPastTheOneByteLocalSlotIndex` (silent wrong answer) and
  `#...UnderAnUnsplittableTail` (the verifier notices); all `JvmLispCompilerTest` output is framed;
  `JvmClassShakerTest` + `JvmClassShakerCorpusTest` (the corpus at both levels, run and compared).
