# Class-file readers and the shaker on the Class-File API

Difficulty: Medium

## The problem

`am.ik.jvm` parses class files by hand in several places: `ClassFileInfo` and `JvmClassPath`
(the `java:` interop lookup, `.kb/java-interop.md`), `JvmClassShaker` (dead-method removal over
finished bytes and embedded template classes), and the `wide`-aware readers listed in
`.kb/stackmap-augmenter.md`, "The `wide` prefix". `java.lang.classfile` (`ClassFile.parse`,
`ClassModel`, `CodeModel`, `ClassTransform`) covers all of it in `java.base`.

## What is needed

- Do after the stack-map replacement has proved the API works in the `-Pweb` and `native-image`
  builds; if it did not, this item is cancelled with it.
- `ClassFileInfo` / `JvmClassPath`: parse through `ClassModel`. `JvmClassFileLookupTest` pins the
  candidates and must stay green unchanged.
- `JvmClassShaker`: a `ClassTransform` dropping unreached methods. Invocations are read from
  `CodeModel` elements instead of raw operand bytes, which removes its `wide` measurement.
- `JvmClassSplitter` shares the shaker's rules on a `ClassDefinition`; keep the pinned property
  that a definition fitting one class splits byte-identically to the shaker's output, or restate
  it for the new form.
- Record the line count removed from `am.ik.jvm` and any compile-time change in
  `.kb/architecture.md` or the matching `.kb` file.
