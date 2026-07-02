# Template-class embedding (the `JavaBridgeTemplate` mechanism) is a LAST RESORT

The normal way to give compiled output a runtime helper is, in order of preference:

1. A macro expansion into existing primitives (`LispMacroExpander` — no backend work at all).
2. A hand-assembled `Jvm/Wasm<Name>RuntimeBuilder` method (the standard used everywhere else, incl. the full reader and eval interpreters — self-contained, version-50, runs on any JRE 6+, byte-level control, no hidden constraints).
3. Only then, embedding a project-compiled Java class ("template"): read its bytecode from the classpath, rename it into the default package via constant-pool rewrite (`JvmJavaRuntimeBuilder.renameClass`), base64-embed it, and `Lookup.defineClass` it at first use.

Reach for (3) ONLY when the helper is genuinely too complex/error-prone to hand-assemble AND needs JDK facilities that are impractical in raw bytecode — [[java-interop]] qualifies on both counts (cost-based overload resolution, recursive marshalling, `Proxy` invocation handlers); nothing else in the code base currently does.

Demerits to weigh before adding another template:
- (a) it silently raises the output's JRE floor — the template carries the project's class version (currently Java 25), so any program using the feature loses the version-50 "runs on any JRE 6+" property.
- (b) the template obeys invariants javac cannot check — no nested classes/records (each is a second class file the single-blob injection cannot carry; lambdas are fine), no imports of other rontolisp classes, and it must be written against the compiled value representation, duplicating logic that then has to be kept manually in sync with its interpreter twin.
- (c) the `defineClass` machinery is subtle — default-package requirement (Lookup.defineClass demands the caller's runtime package, hence the rename), lazy invokestatic resolution ordering (the `_javaInit` guard must run before the first bridge methodref executes), reflection back-calls needing `setAccessible`.
- (d) the base64 blob bloats every using program's constant pool and must be registered in `resource-config.json` for the native binary to compile the feature.

If a new helper seems to need a template, first check whether the complex part can run at COMPILE time instead (like `LoadInliner` or the wasm-component blobs) or be expressed as a smaller hand-assembled runtime plus compile-time constants; when a template really is unavoidable, pin the rename with a round-trip test (see `JvmJavaInteropCompilerTest#renameClassLeavesOtherUtf8EntriesIntact`) and mirror the interpreter test suite against the compiled path.
