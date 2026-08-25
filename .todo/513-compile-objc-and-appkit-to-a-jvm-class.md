# Compile `objc:` and `appkit:` to a JVM `.class` / `.jar`

Filed 2026-08-25 when todo-512 shipped the two packages interpreter-only (user asked whether
the compile refusal is a hard constraint: it is not, on the JVM). Difficulty: High -- the
mechanism exists (`JvmGpuRuntimeBuilder` carries a whole language-independent library into
the output), but this is the first embedded blob whose classes make UPCALLS into the
compiled program, and the compiled value model has no Objective-C object yet.

## What is possible and what is not

- **JVM (`-o Prog.class`, `-o lib.jar`): possible.** A compiled program runs on a JVM with
  `java.lang.foreign`, and under the `java` launcher thread 0 is already parked in a
  `CFRunLoop` (`.kb/objc.md`, "AppKit belongs to thread 0"), so the hand-over the native
  binary needs does not arise. Everything `eval/ObjcBridge` does can be done against the
  compiled representation.
- **Both WASM backends: impossible, permanently.** No FFM, no AppKit. `CompileFrontend`'s
  refusal narrows to the WASM outputs and stays.

## The shape of the work

1. **A compiled representation for an Objective-C object.** The interpreter's
   `LispObjcObject(address, className)` has no counterpart in the compiled value model.
   The `java:` precedent wraps nothing (a host object is the raw reference); here the
   value must carry the address AND the ownership (one retain per wrapper, released on
   thread 0 through a `Cleaner` -- `ObjcBridge`'s rule, which the compiled runtime must
   reproduce, or leak deliberately and say so). Printing `#<objc Class>` goes through
   `JvmRuntimeBuilder`'s print hooks the way `JavaPrint` does. `equal` by address.
2. **The blob.** `JvmGpuRuntimeBuilder` renames and embeds a CLOSURE of `am.ik.gpu` classes
   under one prefix rule (`.kb/gpu.md`, "The JVM backend"); do the same for `am.ik.objc`
   (`TypeEncoding`, `ObjcRuntime`, `ObjcClasses`, `MainThread`, `ObjcException`) plus a
   `JvmObjcTemplate` bridge that holds the marshalling now in `ObjcBridge` -- against the
   compiled representation, so KEEP THE TWO IN SYNC the way `JavaBridgeTemplate` mirrors
   `JavaInterop`. The template must have no nested classes and no rontolisp imports.
3. **Call sites.** A `JvmObjcInteropCompiler` for the seven verbs, wired into
   `JvmExprCompiler.compileCons()` like `JvmJavaInteropCompiler`; `usesObjc` gates the blob
   and forces `usesEval`, because...
4. **Callbacks apply a compiled function.** `objc:define-class`'s methods and
   `objc:on-main`'s body are Lisp functions applied from an upcall on thread 0. The
   `java:proxy` precedent calls back through `_apply` via `bind(Class)`; `ObjcClasses`'s
   `Method` interface takes the same hook. The IMP shapes are the same closed set, bound
   from CONSTANT `findStatic`s inside the blob -- that already holds in the library.
5. **`appkit.lisp` on the compile path.** `AppKitLibrary` gains a `process(program)`
   pre-pass like `LinalgLibrary`'s (prepend the forms when the program references the
   package), placed in `CompileFrontend`'s splice chain; the library honours the
   portability constraints already (`do` declares a variable, no parameter `setq`), so it
   compiles as ordinary Lisp. Its `objc:define-class` runs at the program's top level.
6. **Native access.** A `.jar` output can carry `Enable-Native-Access: ALL-UNNAMED` in its
   manifest (the exec jar does); a bare `.class` run with `java Prog` prints the JDK's
   restricted-method warning unless the user passes `--enable-native-access`, which the
   `--blas` docs already tell them to do. No `reachability-metadata.json` concern: that is
   the native binary's, and a compiled class runs on a JVM.
7. **The native binary can then COMPILE such a program** (the template class resources go
   in `resource-config.json`, the `JavaBridgeTemplate` precedent) even though it also
   interprets it.

## Acceptance

`examples/macos/counter.lisp` compiled with `-o Counter.class` opens the same window under
`java Counter`, the button's closure mutates the label, `appkit:wait` returns on close;
`-o counter.jar` runs with `java -jar`; the WASM outputs still refuse with the reference
named; `JvmObjcInteropCompilerTest` mirrors `ObjcInteropTest`'s headless cases (Mac, the
Foundation objects and a run-time class); `.kb/objc.md`'s "Open items" loses its JVM bullet
and gains the blob's class-list pin (`JvmLinalgGpuAccelCompilerTest` precedent).
