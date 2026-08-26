# 541. CFFI in the native binary, and inside a compiled class

Difficulty: High

Part of `.todo/537`. Needs `.todo/538` and `.todo/539`. Three separate targets, one theme:
the binding has to travel, and where it cannot, the refusal has to name itself.

## The native binary: every downcall shape must be registered

This is the hard half. Native Image compiles a stub per foreign SHAPE ahead of time and
the linker REFUSES a handle for a shape that has none -- `.kb/gpu.md` and the comment atop
`reachability-metadata.json` already say so for CUDA and CBLAS, where the shape set is
fixed and known. CFFI's is neither: `defcfun` invents a shape at run time, in the user's
program, after the binary was built.

What the spike measured (`.todo/537-the-cffi-ecosystem-through-ffm/NativeSpike.java`):

- A descriptor BUILT at run time from strings works, as long as its leaf shape was
  registered -- registration is per shape, not per call site. So the type map staying
  dynamic costs nothing.
- `SymbolLookup.libraryLookup` at run time works: a library can be dlopen'd by a program
  the binary never saw.
- An unregistered shape throws `MissingForeignRegistrationError`, whose message names the
  missing leaf type: `Cannot perform downcall with leaf type (long,int)int`.
- An upcall needs its foreign shape registered AND its Java target reachable for
  reflection; with only the former, `MethodHandles.lookup().findStatic` fails first.

The plan that follows from those four:

1. **Canonicalise the carriers.** Every integer-class argument travels as `jlong` (a C
   callee reads the low bits of the register; this is what SysV and AAPCS64 already do) and
   every pointer as `void*`; `jfloat` and `jdouble` stay distinct, because a float is not a
   narrowed double. That collapses the shape space to four carriers per parameter.
   **Verify the narrow-argument claim on the stack-passed and Apple-varargs paths before
   relying on it** -- it is safe in registers and not obviously safe past the eighth
   argument.
2. **Ship a grid for the small arities and the shapes the built-in consumers actually
   need**, generated, with the tracing agent's spelling so a re-run's diff stays empty
   (the existing file says how). Log what the grid covers.
3. **Turn the miss into a Lisp error that says what to do**: catch
   `MissingForeignRegistrationError`, and signal a `cffi:` error naming the function, the
   shape, and the one metadata entry that would fix it -- plus "or run it on `java -jar`,
   where any shape works". A binding failing in the binary and not on the JVM must not
   look like a bug in the binding.

## The JVM class output: the binding travels inside the `.class`

`-o Prog.class` embeds the whole `am.ik.objc` package today, renamed into the program's
own package and defined at first use (`JvmObjcRuntimeBuilder`; `JvmJavaRuntimeBuilder` for
`java:`). `am.ik.ffi` follows exactly: a `JvmFfiRuntimeBuilder`, a class list that must
follow the package (a test pins it -- `JvmObjcRuntimeBuilderTest`'s shape), the
verifier-acceptable definition order, no nested classes, no rontolisp imports, and the
`LispForeignPointer` twin (`JvmObjcHandle` is the model) so a compiled program has the
same pointer value the interpreter does. The gate is a `ffi:` reference surviving load
inlining, the way `objc:send` gates the objc blob.

## WASM: a permanent, named refusal

`CompileFrontend`, after load inlining, alongside the existing objc/appkit check: a program
that reaches `ffi:` (or `cffi:` through it) fails to compile for either WASM backend with
the reference named, and the message says why -- no foreign function interface in WASM,
not now and not later. The `--no-gc` scalar backend refuses the same way.

## Acceptance

`use.lisp` from `.todo/537-the-cffi-ecosystem-through-ffm/` on the native binary and as
`-o Cffi.class` under `java Cffi` (both after `./mvnw -Pnative clean package`), a
deliberate unregistered shape producing the actionable error rather than a stack trace,
and both WASM backends refusing with the reference named. `JvmRuntimeClassFilesTest` and
the class-list test green.
