# `objc:` and `appkit:`: a native macOS window from the REPL, through FFM

Two built-in packages (todo-512, 2026-08-25). **`objc`** binds the Objective-C runtime and
AppKit through `java.lang.foreign` -- `am.ik.objc`, a language-independent library beside
`am.ik.gpu` -- and exposes a handful of generic verbs named after the foreign system, the
exact analogue of `java:` (`.kb/java-interop.md`) minus the reflection. **`appkit`** is a
widget layer written in rontolisp over those verbs (`appkit.lisp`), shipped inside the
interpreter the way `linalg.lisp` is (`.kb/linalg.md`): a bare REPL types
`(appkit:window "hi")` with nothing required and nothing to copy. The user-facing
description is `doc/{en,ja}/guides/objc-appkit.md`; the example is
`examples/macos/counter.lisp` (GUI, so not in `examples.yaml`).

What it is worth: the `rontolisp` native binary is the REPL people run, and `java:` cannot
be INTERPRETED there at all (no reflection metadata). FFM needs none, so this is the one
way that binary opens a window. macOS only, on the interpreter and on the JVM class output
(todo-513: the binding travels inside the class, "The JVM backend" below); both WASM
backends refuse a program that references either package (`CompileFrontend`, after load
inlining, naming the reference), permanently -- no FFM, no AppKit -- and a machine
without the runtime -- Linux, a JVM that DENIES native access
(`--illegal-native-access=deny`; the JDK's default is a one-time warning) -- SIGNALS at
the call.

## The one architectural fact: AppKit belongs to thread 0

The thread the kernel started the process on (`pthread_main_np()` answers 1) is the only
one that may touch a window, and the Lisp thread is never it:

- Under `java -jar` the launcher parks thread 0 in a `CFRunLoop` and runs `main` on a
  second thread. Free; nothing to do.
- In the **native binary** `main` IS thread 0 and nothing drains it. So
  `RontoLispCli.main` does what the launcher does: when
  `ObjcInterop.mainThreadHandOverRequired()` (a native image, on macOS, on thread 0 --
  cheap: libSystem + CoreFoundation, no AppKit) the CLI moves to a spawned `main` thread
  (16 MiB stack, at least the OS's 8 MiB for the first thread) and thread 0 parks in
  `MainThread.runLoop()`. UNCONDITIONAL, because thread 0 cannot be handed over later; the
  worker ends the process with `System.exit` whatever the code, since thread 0 never
  returns.
- `runLoop()` is the launcher's `ParkEventLoop`, not a bare `CFRunLoopRun`: a no-op
  `CFRunLoopSource` keeps the default mode non-empty and `CFRunLoopRunInMode(default,
  1e20)` is re-entered whenever it returns. The first cut parked in `CFRunLoopRun`, and
  after the first click the loop returned, `main` returned, and the binary sat in
  `JavaMainWrapper`'s join with a beach-balled window (sampled: thread 0 with no
  CoreFoundation frame at all). `RONTOLISP_OBJC_TRACE=1` prints every hop and every
  run-loop return.

**Parking thread 0 is not enough -- AppKit has to be the one draining it.** A CFRunLoop
lets the window server deliver events to the process, but only `-[NSApplication run]`
DEQUEUES them (`nextEventMatchingMask:` -> `sendEvent:`). Until it runs, a window draws
and nothing in it answers a click -- not a button, not the red close button -- and
`isActive` / `isKeyWindow` stay NO however often `activateIgnoringOtherApps:` is sent.
That was the first cut's real state, found 2026-08-25. `run` never returns, so no thread
that has to come back may call it: `appkit::%app` asks thread 0 to
`performSelectorOnMainThread:withObject:` it `waitUntilDone:` NO, which starts it on the
next run-loop cycle -- NESTED inside whatever loop was parking the thread (the launcher's
under `java -jar`, `MainThread.runLoop()`'s in the binary) -- and blocks nobody. Every hop
still works, because `run` drains the main queue exactly like the loop it replaces; a REPL
keeps taking input, `appkit:wait` still returns when the window closes, and `appkit:click`
still drives a button head-lessly. `%app` is the ONLY place that starts it, so a window
built from raw `objc:` in a process that never called an `appkit:` function draws and
answers nothing -- deliberate, since `objc:` is the generic binding and a Foundation-only
script must not become an app.

**Every entry point hops.** `MainThread.sync` hands a body to the main dispatch queue with
`dispatch_sync_f` and waits; the body crosses through ONE upcall stub (`trampoline`,
`void(void*)`) whose context pointer is a ticket into a slot map. Every `objc:` verb runs
its body through it, so a user never dispatches. **The re-entrancy rule:** a callback
already runs on thread 0, and `dispatch_sync` to the queue you are draining is a deadlock
(the spike's first click), so `sync` tests `pthread_main_np()` and runs inline when it is
already there. Consequences a program sees: a `:on-click` handler runs on thread 0 with
the interpreter's GLOBAL dynamic bindings (`.kb/dynamic-special-variables.md`: bindings
are thread-local), and an exception it does not handle is printed
(`objc: error in a callback: ...`) rather than thrown -- unwinding into the native frame
above an upcall kills the process. An exception inside a `sync` body is carried back and
rethrown on the caller's thread as it was, so a Lisp non-local exit through
`objc:on-main` works.

## `objc:send` derives its shape from the runtime -- and the native binary serves a closed table

The runtime describes every selector completely (`method_getTypeEncoding`:
`@68@0:8{CGRect={CGPoint=dd}{CGSize=dd}}16Q48Q56B64`). `TypeEncoding` parses that into a
`FunctionDescriptor` -- a struct flattened to its scalar leaves, ABI-identical for the
homogeneous AppKit structs -- and `ObjcRuntime.send` binds one `objc_msgSend` handle per
distinct shape (Apple's arm64 rule: never through the variadic declaration; an `NSRect`
through a `long` shape is a SIGBUS) and calls it with `invokeWithArguments`, which a
native image serves (probed 2026-08-25). So a wrong selector, arity or operand type is an
`ObjcException` -> a Lisp `error`, never a crash. Blocks (`@?`), unions, bitfields and
function pointers are refused by name.

A native image builds a downcall stub only for a shape registered at build time
(`MissingForeignRegistrationError`, an `Error`, at `Linker.downcallHandle`), so the served
set is a CLOSED TABLE in `reachability-metadata.json`: the runtime's own C functions, every
shape `appkit.lisp` and the documented examples send, and the 60 most common shapes of a
census over 29 core AppKit/Foundation classes (13,065 methods, 90.6% reached; the spike's
`ShapeCensus.java` at `.todo/512-*/`, re-runnable). A selector outside it signals with the
exact entry to add. The JVM registers nothing and binds any shape, so `java -jar` is where
a program discovers what it sends. Pinned by `ObjcNativeImageForeignConfigTest`: the
runtime shapes and the callback stubs on every machine (against `NativeImageDowncalls.EVERYTHING`),
the `appkit` selectors resolved on a Mac against the file. **A new selector in
`appkit.lisp` or the docs is a row in that test's table.**

The `foreign.upcalls` section is the project's first. `ObjcClasses` defines a class at
run time (`objc_allocateClassPair` + `class_addMethod` + `objc_registerClassPair`) whose
IMPs are upcall stubs from a CLOSED set of shapes, one static method each, bound with a
CONSTANT `findStatic` (the image folds it; a name in a variable needs reflection metadata
and threw `NoSuchMethodException` in the spike): `v@:`, `v@:@`, `v@:@@`, `B@:@`, `@@:@`,
`q@:@`. A method's encoding is looked up -- the superclass's declaration, then the adopted
protocols' (`protocol_getMethodDescription`), then the target/action default
`v@:` + `@` per colon -- never guessed. Dispatch is by (class, SEL) up the receiver's
superclass chain; re-defining a class this process defined REBINDS (a REPL re-evaluates,
the runtime cannot unregister). `performSelector...` answers are discarded: the type is the
target's, and retaining the garbage a void method leaves in x0 SIGSEGVs (found the hard
way).

## Ownership: one retain per wrapper, released on thread 0

`LispObjcObject(address, className)` -- a `LispVal` permittee, `equal` by address -- owns
exactly one reference. `alloc`/`new`/`copy`/`mutableCopy`/`retain` results arrive at +1
and the wrapper takes it; everything else is retained INSIDE the hop that produced it,
because the main queue drains its autorelease pool when the block returns (an
`objc:string` used in a second hop would otherwise be dead). A `Cleaner` releases through
`ObjcRuntime.releaseOnMain` -> `dispatch_async_f`, since AppKit deallocates a window or a
view on thread 0 only. Two wrappers of one object hold two references; alloc + init on
one object balance the same way. Classes own nothing. Hence the rule `appkit:window`
honours and a raw `objc:` window must: `setReleasedWhenClosed:` NO, or the close releases a
reference the wrapper still holds. Leaking is the safe direction everywhere here.

## The JVM backend: the binding travels in the class, and calls back into it

`-o Prog.class` / `-o lib.jar` (todo-513, 2026-08-25) is the `--gpu` route (`.kb/gpu.md`,
"The JVM backend"; `.kb/template-class-embedding.md`, "A closure of classes"): every class
file of `am.ik.objc` is renamed by one prefix rule (`am/ik/objc/` -> the program's package +
`RontoLispObjc`), base64'd, and `Lookup.defineClass`'d by the emitted `_objcInit` on the
first `objc:` call, so the compiled program runs the interpreter's own bytes -- one
encoding parser, one hop, one closed callback set. `JvmObjcRuntimeBuilder` owns the list
(pinned against `target/classes/am/ik/objc` by
`JvmObjcInteropCompilerTest#theBlobCarriesTheWholeLibrary`; a class added to the package
is added there). Two classes ride along, renamed the same way, each ONE class file:

- `JvmObjcTemplate` -> `RontoLispObjcBridge`: the seven verbs against the compiled value
  model, the hand-kept twin of `ObjcBridge` -- marshalling, ownership, the messages --
  KEEP THE TWO IN SYNC. Written with an if-chain over `TypeEncoding.Kind` because an
  enum `switch` lowers to a synthetic `$1` class the blob does not carry (the test pins
  that neither template has a `$` sibling on disk).
- `JvmObjcHandle` -> `RontoLispObjcObject`: the compiled `LispObjcObject` -- address +
  class name, `equals` by address (what the compiled `_equal` falls back to), `toString`
  = `#<objc Class>`. The printer reaches it through the bridge's `objcPrint` hook
  (`JvmRuntimeBuilder.ObjcPrint`, guarded by `_objcInited`), emitted AHEAD of the `java:`
  branch, which would otherwise claim it as a host object.

Three things differ from the `--gpu` blob. **Definition order is not free**: a method
body's sibling reference resolves lazily, but the VERIFIER loads a class it must check
assignability against while defining the referencing class -- a `catch` type must be a
`Throwable` -- so `ObjcException` is defined first (every class in the library catches it;
alphabetical order died in `defineClass` with `NoClassDefFoundError`). **The blob makes
UPCALLS into the program**: a `define-class` method and an `on-main` body are applied
through `_apply`, handed over by `bind(Class)` from `_objcInit` (the `java:proxy`
precedent), which is why `usesObjc` forces `usesEval` and roots `_apply` for the shaker.
**The gate is the seven verbs**, qualified, and `appkit.lisp` reaches them: `AppKitLibrary
.process` splices the widget layer on the compile path (`CompileFrontend`, beside
`LinalgLibrary`; pruned to what the program calls like every library), so an `appkit:`
program compiles as ordinary Lisp whose `objc:send` gates the blob on.

Under the `java` launcher thread 0 is already parked in a `CFRunLoop`, so the hand-over
the native binary needs does not arise; the compiled hop is `MainThread.sync` exactly as
interpreted. A bare `.class` without `--enable-native-access=ALL-UNNAMED` gets the JDK's
one-time warning and works; a `.jar` carries `Enable-Native-Access: ALL-UNNAMED` in its
manifest (every jar does, `JvmJarWriter`). The native binary COMPILES such a program
too: the blob's files are registered in `resource-config.json` beside the other
templates. Each compiled program defines its own copy of the library into its own
loader -- fine for a program, and the reason the test names a run-time class per program
(`objc_allocateClassPair` cannot be undone).

## Package rules and the web build

`am.ik.objc -> (nothing)`; `eval -> am.ik.objc` through ONE class, `eval/ObjcBridge`,
reached only via `eval/ObjcInterop`'s five entry points -- the `LinalgGpu` /
`LinalgGpuKernels` shape -- so `src/web/java/.../Target_ObjcInterop.java` substitutes
them and the browser build carries no FFM. `ObjcCaller` is its own type so the bridge and
the entry class reference each other in no direction (`PackageCycleTest`). `cli` reaches
the hand-over through `ObjcInterop`, never the library. `MetalDriver` is the same runtime
through a hand-written shape table and could ride on `am.ik.objc`; it does not yet.

## Tests

| what | where |
|---|---|
| the encoding parser (pure) | `am.ik.objc.TypeEncodingTest` |
| native-image registration: runtime downcalls + callback upcalls (any machine), appkit selectors (Mac) | `am.ik.objc.ObjcNativeImageForeignConfigTest` |
| the verbs, headless (Foundation + a run-time class; Mac), the signal elsewhere (any machine) | `eval/ObjcInteropTest` |
| the library defines exactly the registry's exports, lazy load, the WASM refusal and the JVM splice | `eval/AppKitLibraryTest` |
| the verbs compiled to a class (the interpreter cases, mirrored), the embedded class list, one file per template | `codegen/jvm/JvmObjcInteropCompilerTest` |
| `eval -> am.ik.objc`, and the library imports nothing | `PackageCycleTest` |

A window is never opened by a test (CI has no display; a doc `lisp` fence that opened one
would hang `DocExamplesTest`, so the guide uses `console` fences). The visible loop --
window, click, label mutated by Lisp, close survived, on `java -jar` AND the native binary
-- is verified by hand with `examples/macos/counter.lisp`; the todo's probes stay under
`.todo/512-*/` for re-running the mechanism on another Mac.

## Open items

- No menu bar and no Cmd-Q (a process with no bundle sets no main menu); an app delegate
  is one `objc:define-class` away and not written. With the event loop running the process
  is a foreground application: it activates, takes focus and appears in the app switcher.
- Callback shapes with struct or integer arguments, and block-taking selectors.
- x86_64: `objc_msgSend_stret` is selected for a struct return wider than 16 bytes and
  has not been exercised.
