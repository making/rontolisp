# Template-class embedding (the `JavaBridgeTemplate` mechanism) is a LAST RESORT

Preference order for a runtime helper in compiled output:

1. Macro expansion into existing primitives (`LispMacroExpander`).
2. Hand-assembled `Jvm/Wasm<Name>RuntimeBuilder` method -- the standard everywhere else;
   version-61 after the `StackMapAugmenter` pass ([[stackmap-augmenter]]).
3. Embed a project-compiled Java class ("template"): read its bytecode from the classpath,
   rename it into the generated program's package via constant-pool rewrite
   (`JvmJavaRuntimeBuilder.renameClass`), base64-embed, `Lookup.defineClass` at first use.

Use (3) only when the helper is too complex to hand-assemble AND needs JDK facilities
impractical in raw bytecode: [[java-interop]] (overload resolution, recursive marshalling,
`Proxy`) and the three acceleration flags' bridges (`jdk.incubator.vector`, a `critical` FFM
downcall into a CBLAS, the CUDA driver API).

## The one flagless template: `JvmGeomTemplate` ([[geom]])
`geom:` file-scaled loops (character scanner, Newell's normal, open-addressing edge set) that
must round exactly as `eval/GeomKernels` and the `geom.lisp` defuns do. Two extra rules:
- emit gate is a CALL-SITE scan of the pruned program (a program calling no member stays
  byte-identical);
- `_geomInit` catches the `LinkageError` a `defineClass` can raise, so an older JRE than the
  toolchain degrades to the defuns instead of failing.

## Demerits
- (a) raises the output's JRE floor silently -- the template carries the project's class
  version (Java 25) vs. the version-61 "Java 17+" baseline.
- (b) invariants javac cannot check: no nested classes/records (each is a second class file a
  SINGLE-blob injection cannot carry; lambdas are fine), no imports of other rontolisp
  classes, written against the compiled value representation -- logic duplicated from its
  interpreter twin and kept in sync by hand. (The blob need not be single: see below.)
- (c) `defineClass` subtleties: same-package requirement (the rename tracks the generated
  class's package, not a fixed default); lazy invokestatic resolution ordering (`_javaInit`
  must run before the first bridge methodref executes); reflection back-calls need
  `setAccessible`.
- (d) the base64 blob bloats every using program's constant pool and must be in
  `resource-config.json` for the native binary.

Before adding one: check whether the complex part can run at COMPILE time (like `LoadInliner`
or the wasm-component blobs) or become a smaller hand-assembled runtime plus compile-time
constants. If unavoidable, pin the rename with a round-trip test
(`JvmJavaInteropCompilerTest#renameClassLeavesOtherUtf8EntriesIntact`) and mirror the
interpreter test suite against the compiled path.

## Class-closure injection (`--gpu`, `am.ik.gpu`, [[gpu]])
Demerit (b) belongs to the ONE-blob injection, not the mechanism. Six class files, four
classes (two nested), plus a PTX resource:
- one blob + one `Lookup.defineClass` PER class file, in any order (sibling references
  resolve lazily);
- ONE prefix rename over every file (`am/ik/gpu/` -> `RontoLispGpu`), carrying nested classes
  without naming them and rewriting the glue template's references, so the template is written
  against the real library and type-checked by javac;
- a resource read via `getResourceAsStream` cannot follow the classes into the emitted
  package: it travels as its own string constant, handed in through a public entry point.

Cost: 47 KB class files + 10 KB PTX -> ~78 KB base64 (`JvmSimdVectorTemplate` is 83 KB).
Use when the helper is a LIBRARY: several classes, expensive invariants, an interpreter twin
that would otherwise fork. `--blas`'s single flat template ([[linalg-blas]]) predates this and
is still mirrored by hand. Required guard: a test pinning the embedded class LIST against the
package's actual class files (nothing can enumerate a package from a classpath, still less in
a native image); every file also in `resource-config.json`.

## Second closure: `objc:` (`JvmObjcRuntimeBuilder`, [[objc]])
- **Definition order is not free**: the verifier loads a class it must check assignability
  against WHILE the referencing class is defined (a `catch` type must be `Throwable`), so
  `ObjcException` goes first; an alphabetical list dies in `defineClass` with
  `NoClassDefFoundError`.
- **A blob may call back into the program**: the glue template takes the program's `_apply`
  through `bind(Class)` like the `java:` template; the compiler then forces the eval runtime
  and roots `_apply` for the shaker.
- Glue is two templates -- the bridge and the VALUE (`JvmObjcHandle`, the compiled
  `LispObjcObject`) -- each a single class file: an enum `switch` in a template lowers to a
  synthetic `$1` class the blob does not carry, so use an if-chain; a test pins that neither
  has a `$` sibling on disk.
