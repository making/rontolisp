# Stack maps from the Class-File API instead of `StackMapAugmenter`

Difficulty: Medium

## The problem

`am.ik.jvm.StackMapAugmenter` (1,456 lines) re-derives a `StackMapTable` for every emitted class
(`.kb/stackmap-augmenter.md`). `java.lang.classfile` (final in JDK 24, in `java.base`, so no
external dependency) does the same from a transform:

```java
ClassFile cf = ClassFile.of(
		ClassFile.ClassHierarchyResolverOption.of(resolver),
		ClassFile.StackMapsOption.GENERATE_STACK_MAPS);
byte[] out = cf.transformClass(cf.parse(frameFreeV50),
		ClassTransform.dropping(e -> e instanceof ClassFileVersion)
			.andThen(ClassTransform.endHandler(cb -> cb.withVersion(61, 0)))
			.andThen(ClassTransform.transformingMethodBodies(CodeTransform.ACCEPT_ALL)));
```

Measured 2026-09-29 (JDK 25.0.3, default output, no `--optimize`): the input was an `-o X.class`
output with its `StackMapTable` dropped and its version set to 50 by the same API; the resolver was
`defaultResolver().orElse(cd -> ClassHierarchyInfo.ofClass(CD_Object))`.

| program | `augment` | Class-File API | time per class |
|---|---|---|---|
| 10-line fib/hash/defstruct/handler-case/sort sample | 60,384 B | 58,692 B | 5.5 -> 3.3 ms |
| `examples/console/contact-book.lisp` | 31,598 B | 30,792 B | 3.9 -> 2.5 ms |
| `examples/console/error-handling.lisp` | 61,430 B | 60,149 B | 7.0 -> 3.4 ms |

`ClassFile.verify` reported 0 errors on all three; each ran under `-Xverify:all` with output
identical to the original.

## What is needed

1. **Web Image first.** `src/web/java/.../RontoPlayground` imports `JvmLispCompiler`, so
   `java.lang.classfile` would enter the `-Pweb` build. Measure that it builds, that a JVM compile
   in the playground still works, and the playground `.wasm` size delta. Also the `native-image`
   CLI build. If either fails, stop here and record the numbers in `.kb/stackmap-augmenter.md`.
2. A resolver with no class-loader access (`defaultResolver()` reads resources through the
   system loader; not available in the images): the augmenter's fixed merge table, plus
   `JvmClassPath` for `java:` programs targeting release R. Unknown types merge to `Object`, as
   now.
3. Replace every `StackMapAugmenter.augment` call (`JvmLispCompiler` x3,
   `JvmJavaImplementations` x2). Keep the pipeline order: shake, then frames. Keep the loud
   compile-time failure on unverifiable code (the API throws on an inconsistent stack; confirm
   the message names the method).
4. `osrHostileBackedges`: read it from the generated `StackMapTableAttribute` (a backward branch
   whose target frame has a non-empty stack) instead of the second dataflow.
5. Measure with `--optimize` and the ci-spec corpus on the JVM target; record size and compile
   time in `.kb/stackmap-augmenter.md`. Delete `StackMapAugmenter` once nothing calls it.

Output bytes change everywhere; update byte-pinned expectations in the same commit.
