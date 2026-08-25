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
   may not add anything under `src/` -- the shipped surface is a `gui` package (below), and
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

- **`am.ik.appkit`** -- a new language-independent library beside `am.ik.gpu`: imports no
  rontolisp package and no external dependency, owns the whole binding (selector/class
  interning, the shape table, the main-thread pump, the runtime-registered target class,
  the upcall registry). CLAUDE.md's package graph gains `am.ik.appkit -> (nothing)` and
  `eval -> am.ik.appkit`.
- **One reference from `eval`**, the way `eval/LinalgGpu` is the single reference to
  `am.ik.gpu`, so `-Pweb` can cut it (`src/web/java` has no AppKit and must not carry it).
- **A dedicated `gui` package of ordinary builtins -- decided, not an option** (user,
  2026-08-25). The shipping surface is `gui:` symbols registered the way `linalg` is:
  `LispNames` constants, a `PackageRegistry` entry so they are not misclassified as user
  symbols, `Environment.createGlobal()` definitions, and `BuiltinFunctionWrappers` entries
  so each is a first-class value -- the "Adding a Built-in Function" workflow in CLAUDE.md,
  end to end. **`java:static` / `java:proxy` appear ONLY in the probe**, because a probe
  may not add anything under `src/`; nothing in the shipped feature goes through the
  reflective bridge, and a user never writes a class name or a signature string. Sketch:
  `(gui:window "title" :width 480 :height 260)`, `(gui:label win text :at (x y w h))`,
  `(gui:button win "Click me" :at (...) :on-click (lambda () ...))`, `(gui:set-text v s)`,
  `(gui:close win)`. A handler is an ordinary Lisp closure applied through the evaluator
  (`LispEvaluator.registerJava()` is the precedent for a builtin that needs `apply`), a
  window is an opaque Lisp value rather than a `LispJavaObject`, and the whole surface must
  work with `java.desktop` absent and reflection unavailable -- which is exactly what makes
  it work in the native binary.
- **Interpreter first.** The JVM output can follow through an embedded blob
  (`JvmGpuRuntimeBuilder` / `JvmJavaRuntimeBuilder` are both precedents -- the class list
  that travels must then include `am.ik.appkit`). Both WASM backends refuse the whole
  package with a compile error, permanently, the way `--gpu` refuses a `.wasm` output.

## Open questions to settle before coding

- **Failure is NOT a silent decline.** `am.ik.gpu` answers `null` and the program runs
  anyway because the answer is identical either way. A window that does not open has no
  fallback: `gui:` must SIGNAL on Linux, on a JVM without native access, on a Mac too old
  for a selector. Decide the condition type and pin it.
- **Object lifetime.** The spike leaks deliberately: every `NSString` is autoreleased
  under a pool that outlives nothing in particular, and the retain/release discipline for
  a window that a Lisp value points at is unwritten. Decide whether a handle is an opaque
  Lisp value with a finalizer, or explicit `gui:close`.
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

`(gui:window ...)` opens a real Cocoa window from the interpreter on `java -jar` AND from
the `rontolisp` native binary REPL; a button handler written in Lisp runs and can mutate
the window; the process survives the window closing; a non-Mac and a native-access-denied
JVM both signal a clear condition; `.kb/appkit-gui.md` carries the thread-0 invariant and
the re-entrancy rule with the test that pins them; `PackageCycleTest` still passes with
the new package; `-Pweb` still compiles.
