# `objc:` and `appkit:`: a native macOS window from the REPL, through FFM

- **`objc`** binds the Objective-C runtime and AppKit through `java.lang.foreign` --
  `am.ik.objc`, a language-independent library beside `am.ik.gpu`; the analogue of `java:`
  ([java-interop.md](java-interop.md)) minus reflection.
- **`appkit`** is a widget layer written in rontolisp over those verbs (`appkit.lisp`), shipped
  inside the interpreter like `linalg.lisp`; **`metal`** (`eval/metal.lisp` + `MetalLibrary`) and
  **`scene`** ([geom.md](geom.md)) ship the same way.
- Docs: `doc/{en,ja}/guides/objc-appkit.md`. Examples: `examples/macos/*.lisp`, not in
  `examples.yaml` (whose `os: [mac]` field gates only RUN legs).

**Scope**: macOS only, on the interpreter and on JVM class output. Both WASM backends REFUSE a
program referencing either package (`CompileFrontend`, after load inlining, naming the reference)
-- permanently: no FFM, no AppKit. A machine without the runtime SIGNALS at the call. Why it
exists: the native binary is the REPL people run, and `java:` cannot be INTERPRETED there (no
reflection metadata); FFM needs none.

## The one architectural fact: AppKit belongs to thread 0
The thread the kernel started the process on (`pthread_main_np()` answers 1) is the only one that
may touch a window, and the Lisp thread is never it.

- `java -jar`: the launcher already parks thread 0 in a `CFRunLoop`. Nothing to do.
- **Native binary**: `main` IS thread 0, so `RontoLispCli.main`, when
  `ObjcInterop.mainThreadHandOverRequired()` (native image + macOS + thread 0; cheap -- libSystem
  + CoreFoundation, no AppKit), moves to a spawned `main` thread (16 MiB stack) and parks thread 0
  in `MainThread.runLoop()`. UNCONDITIONAL -- thread 0 cannot be handed over later; the worker
  ends the process with `System.exit` whatever the code.
- `runLoop()` is the launcher's `ParkEventLoop`: a no-op `CFRunLoopSource` keeps the default mode
  non-empty and `CFRunLoopRunInMode(default, 1e20)` is re-entered whenever it returns. **Trap: a
  bare `CFRunLoopRun` returns after the first click and the binary sits in `JavaMainWrapper`'s
  join with a beach-balled window.** `RONTOLISP_OBJC_TRACE=1` prints every hop and return.
- **Parking thread 0 is not enough -- AppKit must be the one draining it.** Only
  `-[NSApplication run]` DEQUEUES events; until it runs a window draws and answers no click. `run`
  never returns, so `appkit::%app` asks thread 0 to `performSelectorOnMainThread:withObject:` it
  `waitUntilDone:` NO, starting it NESTED inside whatever loop was parking the thread. `%app` is
  the ONLY place that starts it, so a window built from raw `objc:` in a process that never called
  an `appkit:` function answers nothing.
- **Every entry point hops.** `MainThread.sync` hands a body to the main dispatch queue with
  `dispatch_sync_f`; the body crosses ONE upcall stub (`trampoline`, `void(void*)`) whose context
  pointer is a ticket into a slot map. **Re-entrancy rule: `dispatch_sync` to the queue you are
  draining is a deadlock**, so `sync` tests `pthread_main_np()` and runs inline on thread 0. So an
  `:on-click` handler runs on thread 0 with the interpreter's GLOBAL dynamic bindings
  ([dynamic-special-variables.md](dynamic-special-variables.md)), and an unhandled exception is
  PRINTED (`objc: error in a callback: ...`) rather than thrown, unwinding into the native frame
  above an upcall being fatal. An exception inside a `sync` body IS carried back and rethrown on
  the caller's thread, so a Lisp non-local exit through `objc:on-main` works.

## Where the line goes: widgets ship, layout stays an example
`appkit` carries `color`, `font`, `panel` (an `NSBox`), `set-color`, `on-click`, `timer`, a
vertically centred `label`, `:background`/`:dark` on `window`, `status-item`, `menu`, `quit`,
`&optional` on `wait`. LAYOUT stays out (`examples/macos/cocoa.lisp` is the grid alone).

- Two rungs cannot be reached by an obvious `objc:send`: a centred label needs the font's line
  height MEASURED (`appkit::%line-height`, a throwaway `sizeToFit` field once per font, cached by
  font address), and a clickable view needs a run-time subclass (`appkit::%clickable-class` ->
  `RontoLispAppKitPanel`/`RontoLispAppKitLabel` over `mouseDown:`/`rightMouseDown:`, one
  address-keyed table letting a panel and its label share a handler with no event forwarding).
- A menu item is wired EXACTLY as a button (target/action into `appkit::*actions*`, keyed by
  address), so `%invoke` needed no change. `:dock nil` sets activation policy 1 on the shared
  `%app` started with policy 0. `set-text`/`text` test for the status item AHEAD of the button
  test (`appkit::%status-item-p`); a menu-bar program has no window, so `quit` sends `terminate:`.
- The rungs are `appkit:` functions, NOT a second built-in `cocoa` package: a package name is
  taken for good. `on-click`'s handler takes the BUTTON NUMBER (the `java.awt.event` numbers, so
  a Swing handler reads the same); a button's own `:on-click` closure takes none.
- A rung costs a `PackageRegistry.APPKIT_FUNCTIONS` entry (library and registry must agree
  EXACTLY, `AppKitLibraryTest`), a per-operator page + `_catalog.yaml` entry + a guide-table row in
  BOTH languages, and blob growth: `AppKitLibrary.process` prepends the whole library, UNPRUNED,
  to every compiled `appkit:` program.

## `objc:send` derives its shape from the runtime; the native binary serves a CLOSED table
`method_getTypeEncoding` describes every selector completely; `TypeEncoding` parses it into a
`FunctionDescriptor` (struct flattened to scalar leaves) and `ObjcRuntime.send` binds one
`objc_msgSend` handle PER DISTINCT SHAPE -- Apple's arm64 rule; **never through the variadic
declaration, since an `NSRect` through a `long` shape is a SIGBUS** -- calling it with
`invokeWithArguments`, which a native image serves. A wrong selector, arity or operand type is an
`ObjcException` -> a Lisp `error`, never a crash. Blocks (`@?`), unions, bitfields and function
pointers are refused by name.

- A native image builds a downcall stub only for a shape registered at build time
  (`MissingForeignRegistrationError` at `Linker.downcallHandle`), so the served set is a CLOSED
  TABLE in `reachability-metadata.json`: the runtime's own C functions, every shape `appkit.lisp`/
  `metal.lisp` and the documented examples send, the 60 most common shapes of a census over 29 core
  AppKit/Foundation classes (13,065 methods, 90.6% reached), plus `NSTimer`'s
  `scheduledTimerWithTimeInterval:...`. A selector outside the table signals with the exact entry
  to add; the JVM registers nothing, so `java -jar` is where a program discovers what it sends.
  **A new selector in `appkit.lisp`, `metal.lisp`, the `examples/macos` programs the test names, or
  the docs is a row in `ObjcNativeImageForeignConfigTest`'s table.**
- `foreign.upcalls` is the project's first such section. `ObjcClasses` defines a class at run time
  (`objc_allocateClassPair` + `class_addMethod` + `objc_registerClassPair`) whose IMPs are upcall
  stubs from a CLOSED shape set -- `v@:`, `v@:@`, `v@:@@`, `B@:@`, `@@:@`, `q@:@` -- one static
  method each, bound with a CONSTANT `findStatic` (a name in a variable needs reflection metadata).
  A method's encoding is LOOKED UP -- superclass declaration, then adopted protocols
  (`protocol_getMethodDescription`), then the target/action default `v@:` + `@` per colon -- never
  guessed. Dispatch is by (class, SEL) up the superclass chain; re-defining a class this process
  defined REBINDS. `performSelector...` answers are DISCARDED: retaining the garbage a void method
  leaves in x0 SIGSEGVs.
- Every Metal object is PROTOCOL-typed (`id<MTLDevice>`) with a private concrete class, so the test
  has a `proto(...)` row; Metal's DESCRIPTOR classes are the reverse (`alloc` answers
  `...Internal`), so it falls back to that name.

## Bytes and out-parameters: `objc:data`, `objc:bytes`, `:error`
`objc:data` answers an **`NSMutableData`** -- mutable so ONE verb serves both directions (`bytes`
for a `^v` parameter, `mutableBytes` as writable scratch) -- through `eval/PackedBuffer`, so it and
`%write-sequence-packed` cannot disagree about what a `#f` matrix is on the wire (little-endian,
row-major, [binary-sequence-io.md](binary-sequence-io.md)). `objc:bytes` is the read direction.
`:error` in an argument position allocates a pointer-sized out slot (`ObjcRuntime.Out`, filled from
the arena after the call and before it closes) and hands the pair to `ObjcRuntime.checkError`.

- **The gate is `Sent.failed()` -- nil, `NO` or zero -- NOT "the slot is non-NULL"**: Foundation's
  rule is that the RESULT says whether a call failed.
- `ObjcRuntime$Out` is a class file, so a row in `JvmObjcRuntimeBuilder.OBJC_CLASSES`. **The
  compiled twin cannot reuse `PackedBuffer`**: a packed float array carries its dimension header IN
  the array (`[rank, dim_0..., e_0...]`) and a packed integer vector is `long[]{width, e_0, ...}`,
  so `JvmObjcTemplate.bufferBytes` must skip what `LispSingleFloatArray.data()` never contained --
  sending the header makes the two backends disagree by two floats.

## Metal
`metal.lisp` is `webgl-common/gl.lisp`'s twin in substance, loaded lazily on the first `metal:`
resolution. No Java added: Metal is Objective-C end to end. Exported:
`attach`/`offscreen`/`pixels`/`device`/`layer`/`queue`/`library`/`pipeline`/`depth-state`/`floats`/
`buffer`/`shared-buffer`/`upload`/`uniform`/`frame`/`run`/`resize`/`set-clear-color`, the CLOS
class `metal:context`, and eleven enum members; pixel formats, load/store actions, blend factors
and storage modes stay INTERNAL.

- Its splice runs BEFORE `AppKitLibrary`'s in `CompileFrontend` (`metal:run`'s clock is
  `appkit:timer`); `LibraryDefunPruner` keys it by name (`MetalLibraryTest`).
- **OpenGL cannot be reached and never will be**: `glClear`/`glDrawArrays` are plain C functions
  outside `objc_msgSend`. The one C function Metal appears to need,
  `MTLCreateSystemDefaultDevice()`, is avoidable -- **`[[CAMetalLayer layer] preferredDevice]` is a
  property** -- and without it `am.ik.gpu`'s `MetalDriver` would have to be reached from `eval`,
  which the package graph forbids.
- The surface is a `CAMetalLayer` on `appkit:window`'s `contentView` -- **`setLayer:` BEFORE
  `setWantsLayer:`**, or AppKit makes its own layer first and the one handed over never becomes the
  backing store.
- `metal:attach :depth t` allocates a private `Depth32Float` texture that the pass clears and every
  pipeline drawing into it must DECLARE, which is why `metal:pipeline` reads the format off the
  context. Geometry rewritten every frame uses `metal:shared-buffer` + `metal:upload` (memcpy into
  `contents` through `-[NSData getBytes:length:]`), rotating THREE copies.
- **The mouse is `objc:define-class`**, not an `appkit` rung: an `NSView` subclass whose
  `mouseDown:`/`mouseDragged:`/`mouseUp:`/`scrollWheel:`/`acceptsFirstMouse:` are Lisp closures,
  set as the content view BEFORE `metal:attach` puts the layer on it.
- A `linalg` result reaches the GPU with NO conversion; `:element-type 'single-float` picks float32
  and every linalg transform preserves the width ([linalg.md](linalg.md)); one `linalg:transpose`
  bridges row-major storage to Metal's column-major `float4x4`.
- Checkable with NO display: `metal:offscreen`/`metal:pixels` render into an `MTLTexture` and read
  back through `objc:bytes`; `metal:frame` takes the drawable's texture or the context's own, so
  there is ONE encoding path ([geom.md](geom.md)).

### A frame that signals: the callback guard is not the last word
`ObjcClasses.dispatch` catches `Throwable`, hands it to the error sink and answers the method's
zero value, and that guard holds under native-image too. What kills the process is DOWNSTREAM: an
encoder released without `endEncoding` is a Metal ASSERTION, and an assertion is an `abort()`,
which no `catch (Throwable)` can be under. Fix: `metal:frame` ends the encoder, presents the
drawable and commits the buffer from an `unwind-protect` cleanup. The pin is a COMMIT, not a crash
(`SceneOffscreenRenderTest.aFrameWhoseBodySignalsIsStillEndedAndCommitted`). **General rule: a
callback that touches a native object with a begin/end protocol must close it on the signalling
path too.**

## Ownership: one retain per wrapper, released on thread 0
`LispObjcObject(address, className)` -- a `LispVal` permittee, `equal` by address -- owns exactly
one reference. `alloc`/`new`/`copy`/`mutableCopy`/`retain` results arrive at +1 and the wrapper
takes it; everything else is retained INSIDE the hop that produced it, the main queue draining its
autorelease pool when the block returns. A `Cleaner` releases through `ObjcRuntime.releaseOnMain`
-> `dispatch_async_f`, AppKit deallocating a window or view on thread 0 only. Two wrappers of one
object hold two references; classes own nothing. Hence the rule `appkit:window` honours and a raw
`objc:` window must: **`setReleasedWhenClosed:` NO**, or the close releases a reference the wrapper
still holds. Leaking is the safe direction everywhere here.

## The JVM backend: the binding travels in the class, and calls back into it
`-o Prog.class` / `-o lib.jar` uses the `--gpu` route ([gpu.md](gpu.md),
[template-class-embedding.md](template-class-embedding.md)): every class file of `am.ik.objc` is
renamed by one prefix rule (`am/ik/objc/` -> the program's package + `RontoLispObjc`), base64'd and
`Lookup.defineClass`'d by the emitted `_objcInit` on the first `objc:` call.
`JvmObjcRuntimeBuilder` owns the list (pinned by
`JvmObjcInteropCompilerTest#theBlobCarriesTheWholeLibrary`). Two classes ride along, each ONE class
file: `JvmObjcTemplate` -> `RontoLispObjcBridge` (the seven verbs against the compiled value model,
the hand-kept twin of `ObjcBridge` -- **KEEP THE TWO IN SYNC**; an if-chain over
`TypeEncoding.Kind`, because an enum `switch` lowers to a synthetic `$1` class the blob does not
carry) and `JvmObjcHandle` -> `RontoLispObjcObject` (address + class name, `equals` by address,
reached by the printer through the bridge's `objcPrint` hook, `JvmRuntimeBuilder.ObjcPrint`,
emitted AHEAD of the `java:` branch which would otherwise claim it).

Three differences from the `--gpu` blob:
- **Definition order is not free**: the VERIFIER loads a class it must check assignability against
  while defining the referencing class (a `catch` type must be a `Throwable`), so `ObjcException`
  is defined FIRST; alphabetical order died in `defineClass` with `NoClassDefFoundError`.
- **The blob makes UPCALLS into the program**: a `define-class` method and an `on-main` body are
  applied through `_apply`, handed over by `bind(Class)` from `_objcInit`, which is why `usesObjc`
  forces `usesEval` and roots `_apply` for the shaker. `bind` hands over `_strv` the same way
  (nullable -- absent exactly when the program has no array runtime).
- **The gate is the nine verbs**, qualified, and `appkit.lisp` reaches them:
  `AppKitLibrary.process` splices the widget layer on the compile path (pruned to what the program
  calls), so an `appkit:` program compiles as ordinary Lisp whose `objc:send` gates the blob on.

Under the `java` launcher thread 0 is already parked, so no hand-over arises. A bare `.class`
without `--enable-native-access=ALL-UNNAMED` gets the JDK's one-time warning and works; a `.jar`
carries `Enable-Native-Access: ALL-UNNAMED` in its manifest (`JvmJarWriter`). Each compiled program
defines its own copy into its own loader, which is why the test names a run-time class per program
(`objc_allocateClassPair` cannot be undone).

## Package rules and the web build
`am.ik.objc -> (nothing)`; `eval -> am.ik.objc` through ONE class, `eval/ObjcBridge`, reached only
via `eval/ObjcInterop`'s five entry points (the `LinalgGpu`/`LinalgGpuKernels` shape), so
`src/web/java/.../Target_ObjcInterop.java` substitutes them and the browser build carries no FFM.
`ObjcCaller` is its own type so the bridge and the entry class reference each other in no direction
(`PackageCycleTest`). `cli` reaches the hand-over through `ObjcInterop`, never the library.
`MetalDriver` is the same runtime through a hand-written shape table and could ride on
`am.ik.objc`; it does not yet.

## Tests
- `am.ik.objc.TypeEncodingTest`; `am.ik.objc.ObjcNativeImageForeignConfigTest`;
  `eval/ObjcInteropTest` (the verbs headless, the `data`/`bytes` round trip and the `:error` slot,
  the signal off-Mac); `codegen/jvm/JvmObjcInteropCompilerTest` (the same expectations byte for byte
  compiled, the embedded class list, one file per template); `eval/AppKitLibraryTest` /
  `eval/MetalLibraryTest`; `SceneOffscreenRenderTest`; `PackageCycleTest`.
- No test opens a window (CI has no display; the guide uses `console` fences so `DocExamplesTest`
  cannot hang). **Verified by hand: `counter.lisp` on `java -jar` AND the native binary;
  `minesweeper-macos.lisp` and `life-macos.lisp` on all three targets (`java -jar`, native binary,
  `-o Life.class`); `menubar.lisp` on the same three** -- what a widget-layer change costs, since
  it travels into every compiled `appkit:` program.

## Open items
- No MAIN menu (a process with no bundle sets none), so no Cmd-Q on a windowed program.
- Callback shapes with struct or integer arguments, and block-taking selectors.
- **A VARIADIC selector is the one hole in "never a crash"**: the encoding does not mark it, so
  `arrayWithObjects:` is bound as `@@:@`, the nil terminator lands in a register the callee never
  reads, and the process dies in `objc_retain`. Refuse the known set by name the way blocks are,
  then serve them with `Linker.Option.firstVariadicArg`.
- x86_64: `objc_msgSend_stret` (struct returns wider than 16 bytes) has not been exercised.
