# A native macOS GUI from the REPL, through FFM

Filed 2026-08-25 after a spike that ran end to end on this machine (Apple M4 Max, macOS
26.3.1, Oracle GraalVM 25.0.3). Difficulty: High -- not because anything is unproven (the
whole mechanism is measured and the probes are kept in
`512-a-native-macos-gui-from-the-repl-through-ffm/`), but because it adds a
language-independent library, the project's FIRST upcall, a callback protocol that runs
Lisp on a thread the interpreter does not own, and -- in the native binary -- a change to
which thread the REPL itself runs on.

## The idea

`--blas` and `--gpu` established that a rontolisp process can bind a system library through
FFM with no JNI, no bundled artifact and no dependency: `SymbolLookup.libraryLookup` plus
one `Linker` handle per signature (`.kb/linalg-blas.md`, `.kb/gpu.md`). AppKit is a system
library like any other, and `am.ik.gpu.MetalDriver` already sends Objective-C messages
through `objc_msgSend` for Metal. Point the same binding at
`/System/Library/Frameworks/AppKit.framework/AppKit` and a rontolisp REPL can open a real
Cocoa window -- with nothing installed, on the JVM and in the native binary.

What this is worth over what exists: `examples/jvm/swing.lisp` + `examples/jvm/life-gui.lisp`
already build a GUI through `java:` interop, but that is Swing (AWT look, `java.desktop`
required) and, decisively, **`java:` cannot be INTERPRETED in the native binary at all**
(`.kb/java-interop.md`: no reflection metadata -- even `java:static` on `java.lang.Math`
fails). The `rontolisp` binary is the REPL people actually run, and today it cannot open a
window. FFM works there; the spike proves it.

## What the spike proved (all four runs verified by screenshot)

1. **A window, from a plain `java -jar` JVM.** `objc_getClass` / `sel_registerName` /
   `objc_msgSend` against AppKit: `NSApplication sharedApplication`,
   `setActivationPolicy:`, `NSWindow initWithContentRect:styleMask:backing:defer:`,
   `NSTextField labelWithString:`, `makeKeyAndOrderFront:`. `isVisible` = true and the
   window renders.
2. **Callbacks, with the method body written in Java.** `objc_allocateClassPair` +
   `class_addMethod(cls, @selector(invoke:), imp, "v@:@")` where the IMP is a
   `Linker.upcallStub` -- a target/action button whose action re-enters the JVM. Verified
   without a human by `performClick:`.
3. **The same in a GraalVM native image**, with an `upcalls` section added to
   `reachability-metadata.json` beside the existing `downcalls`.
4. **The whole loop from the rontolisp interpreter**: `appkit-spike.lisp` opens the window
   and passes a **Lisp lambda** as the button handler; clicking it runs Lisp, which sets
   the label text. The probe reaches the facade through `java:static` only because a probe
   may not add anything under `src/` -- the shipped surface is the `objc` package (below), and
   `java:` is scaffolding this item throws away.

## The mechanics that decide the design

**AppKit belongs to thread 0, and the Lisp thread is never it.** This is the one
architectural fact everything else follows from.

- Under `java -jar`, the launcher already parks the process's first thread in a
  `CFRunLoop` and runs `main` on a secondary thread (`pthread_main_np()` answers 0 on the
  Java main thread). So `dispatch_async_f` / `dispatch_sync_f` to `_dispatch_main_q`
  delivers an upcall onto the real main thread and the REPL keeps its own. **This is free
  and needs nothing.**
- In the **native binary** `main` IS thread 0 (`pthread_main_np()` answers 1). Nothing
  drains the main queue, so a dispatch from that thread never runs. The fix is the shape
  the JVM launcher hands us: **the REPL moves to a spawned thread and thread 0 enters
  `CFRunLoopRun`.** Verified in `NativeImageSpike.java` -- a plain `CFRunLoopRun` (no
  `[NSApp run]`, just `finishLaunching`) pumps both AppKit and the main queue. Decide
  whether thread 0 is handed over unconditionally at startup or only for `--gui`; it
  cannot be taken back later, so a lazy answer means "no GUI in this process".
- **Re-entrancy is a real deadlock, not a theoretical one.** The spike crashed the first
  time: a button handler already runs on thread 0, and the Lisp it runs calls back into
  the GUI, which `dispatch_sync`s to the queue it is already on. Every entry point must
  test `pthread_main_np()` and run inline when it is already there.
- One `objc_msgSend` handle per selector SHAPE, never the variadic declaration -- Apple's
  arm64 rule, already respected by `MetalDriver`. `NSRect` is
  `struct(jdouble,jdouble,jdouble,jdouble)` by value; sending it through a `long` shape is
  an immediate SIGBUS.
- Native image folds `MethodHandles.lookup().findStatic(C.class, "literal", constantType)`
  into a direct handle, so an upcall target needs no reflection metadata -- but only with
  CONSTANT arguments. The spike's `findStatic(cls, nameVariable, ...)` failed with
  `NoSuchMethodException` in the image and worked on the JVM. Related, and it applies to
  every handle this feature adds: `476-ffm-downcalls-through-a-non-constant-method-handle.md`.

## Proposed shape

- **`am.ik.objc`** -- a new language-independent library beside `am.ik.gpu`: imports no
  rontolisp package and no external dependency, owns the whole binding (selector/class
  interning, the shape table, the main-thread pump, the runtime-registered target class,
  the upcall registry). CLAUDE.md's package graph gains `am.ik.objc -> (nothing)` and
  `eval -> am.ik.objc`.
- **One reference from `eval`**, the way `eval/LinalgGpu` is the single reference to
  `am.ik.gpu`, so `-Pweb` can cut it (`src/web/java` has no AppKit and must not carry it).
  `am.ik.gpu.MetalDriver` is the same runtime through a hand-written shape table; leave it
  alone for now, and note that it could ride on `am.ik.objc` once this exists.
- **The built-in package is `objc`, and the widget layer is Lisp on top of it** (user
  raised the naming, 2026-08-25; measured below). NOT `gui` -- it names the use, not the
  seam, and this binding is not GUI-only. NOT `appkit` or `cocoa` for the BUILT-IN
  package -- those name a framework, which would mean a hand-written per-widget surface in
  Java that must be grown forever. `objc:` is the exact analogue of `java:`: a package
  named after the foreign system, with a handful of generic verbs --
  `objc:class`, `objc:send`, `objc:define-class` (the runtime-registered target class whose
  IMP is an upcall), `objc:on-main`, `objc:string`. Registered the way `linalg` and `java`
  are: `LispNames` constants, a `PackageRegistry` entry so they are not misclassified as
  user symbols, `Environment.createGlobal()` definitions, `BuiltinFunctionWrappers`
  entries. **`java:static` / `java:proxy` appear ONLY in the probe**, because a probe may
  not add anything under `src/`; nothing in the shipped feature goes through the reflective
  bridge, and the whole surface must work with `java.desktop` absent and reflection
  unavailable -- which is exactly what makes it work in the native binary.
- **`appkit` is a BUILT-IN package of rontolisp, not an example to copy** (user, 2026-08-25).
  It is written in rontolisp itself and SHIPPED -- the `linalg` pattern exactly:
  `src/main/resources/am/ik/rontolisp/eval/appkit.lisp` on the classpath, an
  `AppKitLibrary` beside `LinalgLibrary` (the interpreter splices the definitions lazily
  from `LispEvaluator.resolveFunction` on the first resolution of an `appkit:` name; the
  compile path prepends them from a `process(program)` pre-pass when the program
  references the package), `LispNames` + `PackageRegistry` for the package and its
  externality, and a `resource-config.json` pattern so the NATIVE BINARY carries the
  source. A user opens the REPL and calls `(appkit:window "title" :width 480 :height 260)`
  -- no `require`, no file to copy, no system to load. **This is the difference from
  `examples/jvm/swing.lisp`**, which is a Lisp-level `swing` package a consumer must splice
  itself; that shape is explicitly NOT what this ships. The rest of the surface is
  `(appkit:button win "Click me" :at (...) :on-click (lambda () ...))`,
  `(appkit:set-text v s)` and `(appkit:close win)`. A handler is an ordinary Lisp closure
  applied through the evaluator (`LispEvaluator.registerJava()` is the precedent for a
  builtin that needs `apply`), and a window is an opaque Lisp value rather than a
  `LispJavaObject`. One implementation in Lisp then runs on every backend that has `objc:`
  under it, which is what `.kb/linalg.md`'s "written in rontolisp itself so a single
  implementation runs on every backend" buys everywhere else. Two chores come with the
  pattern: `appkit.lisp` honors the portability constraints `linalg.lisp`'s own header
  lists (a `do` loop always declares a variable, a parameter is never `setq`'d), and
  CLAUDE.md's post-task `rontolisp format ... src/main/resources/` now covers it. `cocoa`
  stays free and unused -- it is an Apple umbrella narrower than the seam and wider than a
  widget set.
- **Interpreter first.** The JVM output can follow through an embedded blob
  (`JvmGpuRuntimeBuilder` / `JvmJavaRuntimeBuilder` are both precedents -- the class list
  that travels must then include `am.ik.objc`). Both WASM backends refuse the whole
  package with a compile error, permanently, the way `--gpu` refuses a `.wasm` output.

## Why `objc` and not a curated framework surface (measured 2026-08-25)

**The Objective-C runtime describes every selector completely**, which is what makes a
generic `objc:send` honest rather than a footgun -- the signature is not guessed or
hand-tabulated, it is read back from the runtime (`Encodings.java`):

```
NSWindow  initWithContentRect:styleMask:backing:defer:  @68@0:8{CGRect={CGPoint=dd}{CGSize=dd}}16Q48Q56B64
NSColor   colorWithRed:green:blue:alpha:                @48@0:8d16d24d32d40
```

So `objc:send` derives the `FunctionDescriptor` from `method_getTypeEncoding`, and a wrong
selector, a wrong arity or a wrong operand type is a Lisp condition instead of the SIGBUS
that sending an `NSRect` through a `long` shape gives you.

**The native image cannot build a stub for a shape it did not see at build time**, so the
derived shapes must come from a CLOSED registered set. `ShapeCensus.java` counts how big
that set has to be -- every method of 25 core AppKit/Foundation classes, 9,932 of them,
bucketed by FFM shape:

| registered shapes | share of methods reachable |
|---|---|
| 25 | 86.1% |
| 50 | 91.2% |
| 100 | 94.7% |
| (all) 440 | 100% |

The top four alone -- `id(id,SEL,id)`, `BOOL(id,SEL)`, `void(id,SEL,id)`, `void(id,SEL)` --
are 55%. A table of ~50 shapes in `reachability-metadata.json` therefore reaches
essentially the whole surface anyone writes by hand, and a selector outside it must
SIGNAL, naming the shape to add. 391 of the 9,932 encodings carry types this census does
not map at all (unions, blocks, bitfields, `_Complex`); a block-taking selector is its own
piece of work and is deliberately out of the first cut.

## Open questions to settle before coding

- **Failure is NOT a silent decline.** `am.ik.gpu` answers `null` and the program runs
  anyway because the answer is identical either way. A window that does not open has no
  fallback: `objc:` must SIGNAL on Linux, on a JVM without native access, on a Mac too old
  for a selector. Decide the condition type and pin it.
- **Object lifetime.** The spike leaks deliberately: every `NSString` is autoreleased
  under a pool that outlives nothing in particular, and the retain/release discipline for
  a window that a Lisp value points at is unwritten. Decide whether a handle is an opaque
  Lisp value with a finalizer, or explicit `appkit:close`.
- **A process with no bundle** gets no Dock icon or menu bar by default;
  `setActivationPolicy:` 0 (regular) was enough for the spike to take focus, but a menu
  bar, `applicationShouldTerminateAfterLastWindowClosed:` and Cmd-Q need an app delegate --
  another runtime-registered class. Closing the last window must NOT end the REPL.
- **`--enable-native-access`** is already in the exec jar's manifest; check what the
  `rontolisp` binary and an embedder need.
- **Testing without a screen.** CI has no display. The pinning test should assert the
  binding builds and the objects come back non-NULL (headless-safe), and the visible
  behavior belongs in `examples/` with a `README` note, not in `DocExamplesTest` -- a doc
  example that opens a window would hang it (`.kb/java-interop.md` already carries that
  rule for Swing).

## Acceptance

`(appkit:window ...)` -- typed into a bare REPL, with nothing required and nothing
installed -- opens a real Cocoa window over the built-in `objc:` verbs, on `java -jar` AND
in the `rontolisp` native binary; a button handler written in Lisp runs and can mutate
the window; the process survives the window closing; a non-Mac and a native-access-denied
JVM both signal a clear condition; `.kb/objc.md` carries the thread-0 invariant, the
re-entrancy rule and the registered shape table with the tests that pin them;
`PackageCycleTest` still passes with the new package; `-Pweb` still compiles.
