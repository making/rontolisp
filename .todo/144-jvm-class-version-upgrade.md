# JVM class version 50 -> 61 (Java 17): finish the StackMapTable upgrade

## State (2026-07-17, uncommitted spike on develop)

The core landed and is green:

- `am.ik.jvm.StackMapAugmenter` (new): offline post-pass that parses the finished
  class file, runs a verifier-style typed dataflow (locals + stack, JVMS
  verification types) per method, inserts a `StackMapTable` at every branch
  target / handler entry / post-unconditional position, neutralizes dead code
  (nop* + athrow under a synthetic `[Throwable]` frame, dead ranges carved out of
  the exception table), and stamps the class version.
- `JvmLispCompiler`: `CLASS_MAJOR_VERSION = 61`; `compile()` ends with
  `StackMapAugmenter.augment(classBytes, 61)` -- always, after the optional
  `JvmClassShaker.shake`. Emission itself is untouched (still writes 50; the
  augmenter overrides).
- `JvmClassShaker`: now drops (not preserves) a `StackMapTable` sub-attribute so
  shake stays callable on augmented bytes; the pipeline order is shake -> augment.
- Frame encoding: same_frame / same_locals_1_stack_item (+extended) /
  append_frame / chop_frame / full_frame.
- Verified: JvmLispCompilerTest 686/686, JvmClassShakerTest + corpus, manual
  4-mode run (plain / --optimize / --dynamic) of a defstruct+CLOS+arrays+
  hash-table+unwind-protect program; corpus constant pool 5864 (guard 52000);
  javadoc clean (Version-class exception only); -Pweb compiles.

## Remaining

1. Docs: `doc/en/compiling/jvm.md:60` (+ the ja mirror) still says "targets Java 6
   (class version 50)"; rewrite for Java 17 and note that running the output now
   needs a Java 17+ JRE. Same-commit en/ja mirror rule applies.
2. `CLAUDE.md` "JVM Class Version 50 (Java 6)" invariant line -> rewrite (version
   61 + StackMapAugmenter as the reason the lenient-verifier constraint is gone).
3. `.kb/error-handling.md:28` ("version 50 verifies exception handlers WITHOUT a
   StackMapTable") and `.kb/fetch-http.md:182` ("interface-static invokestatic,
   illegal in class version 50" -- that restriction is LIFTED at 61; the
   workaround it forced could now be simplified) need updating; consider a new
   `.kb/stackmap-augmenter.md`.
4. Native E2E (`-Pnative` + CiSpecE2eTest) not run yet -- required before push.
   Program OUTPUT is unchanged so ci-spec.yaml should be untouched, but the run
   is the proof.
5. Size: default output grew ~+80% (75KB -> 135KB heavy.lisp), --optimize +28%
   (10.6KB -> 13.5KB). Acceptable? If not: most full_frames come from targets
   with a non-empty operand stack (argument-position control flow); no cheap
   encoding win left beyond what is implemented.
6. Follow-up opportunities the bump unlocks (separate todos if pursued):
   - invokedynamic (v51+): replace the `_invoke_N` linear if-else id dispatch
     with indy + a bootstrap resolving MethodHandles per function id (or at
     least a tableswitch, which the emitters/shaker/augmenter do not model yet).
   - The `.kb/fetch-http.md` interface-static-call workaround (see 3).
   - `MAX_LOCAL_SLOT` 255: `wide` loads/stores would fix `.todo/137`'s silent
     aliasing; the augmenter/shaker would need `wide` decoding.
7. The old exec jar in `target/` predates the change; rebuild before comparing
   sizes or measuring anything.

## Design notes (why offline, and the sharp edges)

- Offline post-pass (vs extending the emit-time `OperandStack`) covers the ~20
  hand-assembled `Jvm*RuntimeBuilder` raw-code methods for free and needs no
  change to any emitter.
- Reference merge: fixed superclass table (boxed numerics under Number),
  everything else -> Object; `aaload` on an over-merged non-array type throws at
  compile time rather than emitting an unverifiable frame. No reflection, so the
  pass is native-image/web-image safe.
- javac-compiled embedded template classes (java: bridge) are separate class
  files with their own frames -- not touched by the augmenter.
- The `+=`-with-side-effecting-read bug class: `p[0] += readU4(classFile, p)`
  loads `p[0]` BEFORE readU4 advances it (cost one debugging round in
  JvmClassShaker).
