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
- `wide` loads/stores would fix `.todo/137`'s silent local-slot aliasing; the
  augmenter/shaker would need `wide` decoding.

## Pinning tests

- `ByteCodeWriterTest.generateAndRun{TypedCatch,CatchAny}Handler` pin the RAW
  assembler contract the emitters still rely on: version-50 output verifies
  exception handlers without a StackMapTable.
- `JvmLispCompilerTest` (all output augmented) + `JvmClassShakerTest` and its
  corpus exercise the shake -> augment pipeline.
