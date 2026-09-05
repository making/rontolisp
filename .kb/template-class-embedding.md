# Template-class embedding (the `JavaBridgeTemplate` mechanism) is a LAST RESORT

Preference order for a runtime helper in compiled output: (1) macro expansion into existing
primitives; (2) a hand-assembled `Jvm/Wasm<Name>RuntimeBuilder`, the standard everywhere else
([[stackmap-augmenter]]); (3) embedding a project-compiled Java class, renamed into the generated
program's package by constant-pool rewrite (`JvmJavaRuntimeBuilder.renameClass`), base64-embedded and
`Lookup.defineClass`d at first use. Use (3) only for a helper needing JDK facilities impractical in
raw bytecode: [[java-interop]] and the `--simd`/`--blas`/`--gpu` bridges. Before adding one, check
whether the complex part can run at COMPILE time; pin the rename with
`JvmJavaInteropCompilerTest#renameClassLeavesOtherUtf8EntriesIntact`.

**The one flagless template is `JvmGeomTemplate`** ([[geom]]): emit gate is a CALL-SITE scan of the
PRUNED program (a program calling no member stays byte-identical), and `_geomInit` catches the
`LinkageError` a `defineClass` can raise, so an older JRE degrades to the defuns.

## Demerits

- Raises the output's JRE floor silently -- the template carries the project's class version (Java
  25) against the version-61 "Java 17+" baseline.
- Invariants javac cannot check: no nested classes/records under a SINGLE-blob injection (lambdas are
  fine), no imports of other rontolisp classes, written against the compiled value representation and
  kept in sync with its interpreter twin by hand.
- `defineClass`: the same-package requirement; lazy invokestatic resolution ordering (`_javaInit`
  must run before the first bridge methodref); reflection back-calls need `setAccessible`.
- The base64 blob bloats the constant pool and must be in `resource-config.json`.

## Class-closure injection (`--gpu`/`am.ik.gpu` [[gpu]], `objc:` [[objc]])

The single-blob limits are the injection's, not the mechanism's: one blob and one `defineClass` PER
class file in any order (siblings resolve lazily); ONE prefix rename over every file (`am/ik/gpu/` ->
`RontoLispGpu`) carrying nested classes without naming them, so the glue template is written against
the real library and type-checked by javac; a `getResourceAsStream` resource cannot follow and
travels as its own string constant through a public entry point. ~78 KB base64 for `am.ik.gpu`
(`JvmSimdVectorTemplate` is 83 KB). `--blas`'s flat template ([[linalg-blas]]) predates this.

- **Required guard: a test pinning the embedded class LIST against the package's actual class files**
  -- nothing can enumerate a package from a classpath, still less in a native image.
- **Definition order is not free**: the verifier loads a `catch` type WHILE defining the referencing
  class, so `ObjcException` goes first; an alphabetical list dies with `NoClassDefFoundError`.
- **A blob may call back into the program** through `bind(Class)`, which forces the eval runtime and
  roots `_apply` for the shaker.
- An enum `switch` in a template lowers to a synthetic `$1` class the blob does not carry -- use an
  if-chain; a test pins the absence of `$` siblings.
