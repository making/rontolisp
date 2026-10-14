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
  restore it and is NOT adopted (measured 2026-09-29 on the default-level ci-spec class): it
 costs markedly more than writing the frames themselves, and over the class-loader-free resolver above
  it reports false errors (`RuntimeException` not assignable to `Throwable`: its
  assignability needs the real hierarchy), none over the class-loading default.
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

## The API rewrite, measured 2026-09-29 (JDK 25.0.4)

Replacing `StackMapAugmenter` (a hand-written verifier dataflow) with the API, as a
post-pass, is faster on every input measured (a shipped frame-stripped v50 class, example
programs, the whole ci-spec corpus, at both optimize levels, warm-cached timings) and emits
a slightly smaller class from the same input. Whole CLI compile of the ci-spec corpus
(`-o C.class`) speeds up accordingly at both levels, with the same output modulo the
expected difference.

The `java.lang.classfile` dependency costs the images a little: the `-Pweb` playground
wasm and the `-Pnative` CLI binary each grow by a few percent, and a JVM compile through
either still verifies and runs.

Folding the post-pass into the write (the shake and the frames in the one `ClassFile.build` per
class, no written bytes parsed again) nearly halves the corpus write phase in a cold CLI
run (2026-09-29, [jvm-method-size-limits.md](jvm-method-size-limits.md)).

## The readers on the API (2026-09-29)

`ClassFileInfo.parse` reads a `ClassModel`. The byte-level `JvmClassShaker` that shared the old
parse-and-rewrite pipeline is gone with it (2026-09-29): the writer shakes the definition before
writing (`OwnCallGraph`, [optimize-dead-code-elimination.md](optimize-dead-code-elimination.md)),
as the a83 measurement predicted ("one transform doing the shake and the frames ... the move of
emission onto CodeBuilder makes moot").

- **A fresh pool is laid out in write order**, not the master pool's: a constant below index 256
  can land above it and the other way, so an `ldc` is written `ldc` or `ldc_w` by where its
  constant landed, and a branch over it follows (`CodeReplayTest`).
- **The parser refuses a major version above `ClassFile.latestMajorVersion()`**; the hand reader
  did not care. `ClassFileInfo` lowers the version in a copy, so a `--java-classpath` jar built
  for a newer Java still resolves (`JvmClassPathTest.aClassFileNewerThanTheRunningJdkReadsItsDeclaredShape`).

`ClassFileInfo` over the JDK's `ct.sym` signature files parses in the same time as the hand
reader did.

## The `wide` prefix

A local slot past 255 takes a `wide` prefix and a two-byte index: 4 bytes (6 for `iinc`), not 2.
No code bytes exist before the written class's own: a `MethodCode` record carries the slot
whole, `MethodCode`'s measure counts the `wide` form (a mis-measurement would shift every later
instruction and every branch target in `CodeReplay.farBranches`' layout), and the writer picks
the form (`loadLocal` past 255 is written `aload_w`, `Opcode.ALOAD_W`). The one-byte-slot bugs
this replaced (the byte emitters' `wide` rewrite missing `iinc`, the `maphash` and `%obj-slots`
cursors) are dated history; the record carries the slot whole, so there is nothing to rewrite.

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
- `CodeReplayTest.aLongBranchOverWideLocalsIsWrittenInItsGotoWForm` -- `wide` locals measured
  across a branch the replay must widen, framed and run; `#aWideIincKeepsItsSlotAndIncrement`,
  `MethodCodeTest.aLocalPastSlot255TakesTheWideForm`.
- `JvmLispCompilerTest.compileAndRunABodyPastTheOneByteLocalSlotIndex` (silent wrong answer) and
  `#...UnderAnUnsplittableTail` (the verifier notices); all `JvmLispCompilerTest` output is framed;
  `JvmDeadMethodEliminationTest` + `JvmDeadMethodEliminationCorpusTest` (the corpus at both levels, run and compared).
