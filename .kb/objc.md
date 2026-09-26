# `objc:` and `appkit:`: a native macOS window from the REPL, through FFM

- **`objc`** binds the Objective-C runtime and AppKit through `java.lang.foreign` --
  `am.ik.objc`, a language-independent library beside `am.ik.gpu`; the analogue of `java:`
  ([java-interop.md](java-interop.md)) minus reflection.
- **`appkit`** is a widget layer written in rontolisp over those verbs (`appkit.lisp`), shipped
  inside the interpreter like `linalg.lisp`; **`metal`** (`eval/metal.lisp` + `MetalLibrary`) and
  **`scene`** ([geom.md](geom.md)) ship the same way.
- Docs: `doc/{en,ja}/guides/objc-appkit.md`. Examples: `examples/macos/*.lisp`, not in
  `examples.yaml` (whose `os: [mac]` field gates only RUN legs).

**Scope**: macOS only, on the interpreter, on JVM class output and in a `--native` executable for
`macos-aarch64` (below). Every other WASM output REFUSES a program referencing any of the four
packages (`CompileFrontend`, after load inlining, naming the reference) -- permanently: no WASM
runtime offers FFM or AppKit; only the runner stub of a native executable answers the imports. A machine without the runtime SIGNALS at the call. Why it
exists: the native binary is the REPL people run, and `java:` cannot be INTERPRETED there (no
reflection metadata); FFM needs none.

## The one architectural fact: AppKit belongs to thread 0
The thread the kernel started the process on (`pthread_main_np()` answers 1) is the only one that
may touch a window, and the Lisp thread is never it.

- `java -jar`: the launcher already parks thread 0 in a `CFRunLoop`. Nothing to do.
- **Native binary**: `main` IS thread 0. `RontoLispCli.main` always moves the CLI to a spawned
  `main` thread ([interpreter-stack.md](interpreter-stack.md)); what
  `ObjcInterop.mainThreadHandOverRequired()` (native image + macOS + thread 0; cheap -- libSystem
  + CoreFoundation, no AppKit) decides is what thread 0 does next -- park in
  `MainThread.runLoop()` rather than wait for the worker. UNCONDITIONAL on that platform: thread 0
  cannot be handed over later, and since the run loop never returns, the worker ends the process
  with `System.exit` whatever the code.
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

- **"Describes every selector completely" is false for a VARIADIC one**, which is declared byte
  for byte like its fixed-arity twin (`arrayWithObjects:` and `arrayWithObject:` are both `@@:@`).
  On arm64 a variadic argument goes on the STACK and a fixed one in a register, so the declared
  shape leaves the callee walking its `va_list` off a slot nobody wrote -- SIGSEGV in
  `objc_retain`, the one hole in "never a crash". Nothing in the runtime marks it, so the family
  is a TABLE OF NAMES, `am.ik.objc.VariadicSelectors`: the nil-terminated constructors and the
  format-string family. `send` lets a selector in it take arguments PAST the declared arity, each
  marshalled by the carrier its VALUE picks (object/`jlong`/`jdouble` -- what `va_arg` reads for
  `%@`, `%ld`, `%f`), appends the nil terminator ITSELF, and binds with
  `Linker.Option.firstVariadicArg(declared count)`. **The appended nil is unconditional**: the
  nil-terminated half needs it and a `printf`-style callee never reads past its format, which is
  what keeps ONE rule instead of two -- and makes the last variadic argument always `void*`, which
  is what bounds the native table. A variadic selector a PROGRAM declares is still the crash;
  nothing can see it coming. `ObjcRuntime.Signature` (descriptor + split, `-1` when fixed) is the
  `sends` cache key, since the same layouts called variadically are a different stub.

- A native image builds a downcall stub only for a shape registered at build time
  (`MissingForeignRegistrationError` at `Linker.downcallHandle`), so the served set is a CLOSED
  TABLE in `reachability-metadata.json`: the runtime's own C functions, every shape `appkit.lisp`/
  `metal.lisp` and the documented examples send, the 60 most common shapes of a census over 29 core
  AppKit/Foundation classes (13,065 methods, 90.6% reached), plus `NSTimer`'s
  `scheduledTimerWithTimeInterval:...`. A selector outside the table signals with the exact entry
  to add; the JVM registers nothing, so `java -jar` is where a program discovers what it sends.
  **A new selector in `appkit.lisp`, `metal.lisp`, the `examples/macos` programs the test names, or
  the docs is a row in `ObjcNativeImageForeignConfigTest`'s table.** The variadic sends are their
  own 144-entry grid, generated from a RULE the same test restates and pins in both directions:
  three fixed halves (`void*(void*,void*,void*)` and `void(void*,void*,void*)` splitting at 3,
  `void(void*,void*,void*,void*)` -- `raise:format:` -- at 4) crossed with 1-12 variadic
  arguments, every carrier combination up to 4 and `void*` only past it.
- In the native binary every send through that table is INTERPRETED by SubstrateVM's method-handle
  interpreter -- a handle created at run time has no AOT code, ~1.7 us a call plus ~0.4 us per
  argument on top of `invokeWithArguments`' own boxing (`.kb/gpu.md`, "An FFM downcall inside a native
  image costs", `.todo/727`; measured on Linux/aarch64 through the same SVM path, not on macOS). A
  per-frame loop of sends is where it would show; nothing in `appkit.lisp` / `metal.lisp` is
  calibrated against the JVM's send cost, so there is no threshold here to re-derive. The route that
  takes the floor out exists since `.todo/729` (`src/native/java`, `.kb/native-downcalls.md`) and was
  not applied here: it is one `@InvokeCFunctionPointer` method per SHAPE, and the send table is a
  generic per-selector shape set that no macOS box in reach can measure.
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
  the array (`[rank, dim_0..., e_0...]`) and a packed integer vector is `byte[]{8, e_0, ...}` /
  `long[]{width, e_0, ...}`,
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

## The JVM backend: the binding travels beside the class, and calls back into it
`-o Prog.class` / `-o lib.jar` uses the `--gpu` route ([gpu.md](gpu.md),
[template-class-embedding.md](template-class-embedding.md)): every class file of `am.ik.objc` is
renamed by one prefix rule (`am/ik/objc/` -> `<Program>$Objc`) and ships beside the program through
`runtimeClassFiles()`; the emitted `_objcInit` only binds, on the first `objc:` call.
`JvmObjcRuntimeBuilder` owns the list (pinned by
`JvmObjcInteropCompilerTest#theProgramShipsTheWholeLibrary`). Two classes ride along, each ONE class
file: `JvmObjcTemplate` -> `<Program>$ObjcBridge` (the seven verbs against the compiled value model,
the hand-kept twin of `ObjcBridge` -- **KEEP THE TWO IN SYNC**; an if-chain over
`TypeEncoding.Kind`, because an enum `switch` lowers to a synthetic `$1` class the builder does not
ship) and `JvmObjcHandle` -> `<Program>$ObjcObject` (address + class name, `equals` by address,
reached by the printer through the bridge's `objcPrint` hook, `JvmRuntimeBuilder.ObjcPrint`,
emitted AHEAD of the `java:` branch which would otherwise claim it).

Differences from the `--gpu` library:
- **It makes UPCALLS into the program**: a `define-class` method and an `on-main` body are
  applied through `_apply`, handed over by `bind(Class)` from `_objcInit`, which is why `usesObjc`
  forces `usesEval` and roots `_apply` for the shaker. `bind` hands over `_strv` the same way
  (nullable -- absent exactly when the program has no array runtime).
- **The gate is the nine verbs**, qualified, and `appkit.lisp` reaches them:
  `AppKitLibrary.process` splices the widget layer on the compile path (pruned to what the program
  calls), so an `appkit:` program compiles as ordinary Lisp whose `objc:send` gates the library on.
- **The same gate keeps the compiled `main` where it was.** Every other class with a `main` runs
  its program on a sized worker thread ([interpreter-stack.md](interpreter-stack.md),
  `JvmSizedMainBuilder`); a class with `usesObjc` does not, and its bytes are what they were
  before the launcher existed (`counter.lisp` as `.class` and `.jar`, compared 2026-09-19).
  `JvmSizedMainTest#anObjcProgramKeepsItsMainOnThreadZero` pins the absence. Moving an objc
  program onto the worker is a GUI change needing the manual macOS check; it was not attempted.
  The check the launcher itself needed has been run (macOS 26.3.1 aarch64, Oracle GraalVM 25.0.3,
  2026-09-20): `counter.lisp` opens its window, counts clicks and exits 0 on closing under
  `java -jar`, the native binary, `java Counter` and `java -jar counter.jar`. A NON-objc program
  there -- where the `java` launcher already runs `main` off thread 0 while thread 0 parks in a
  `CFRunLoop`, so the worker is a second hop -- is unaffected: `nqueens.lisp` prints byte for byte
  what the interpreter does as both `.class` and `.jar` and exits 0, and an unhandled condition
  still prints its report, echoes `Exception in thread "main"` and exits 1.

Under the `java` launcher thread 0 is already parked, so no hand-over arises. A bare `.class`
without `--enable-native-access=ALL-UNNAMED` gets the JDK's one-time warning and works; a `.jar`
carries `Enable-Native-Access: ALL-UNNAMED` in its manifest (`JvmJarWriter`). Each compiled program
ships its own copy as class files named after it (`<Program>$Objc*`,
[template-class-embedding.md](template-class-embedding.md)), which is why the test names a run-time
class per program (`objc_allocateClassPair` cannot be undone). Verified on macOS 26.3 aarch64
(Oracle GraalVM 25.0.3, 2026-09-27, screen locked, so clicks were `performClick:` from an
`appkit:timer` and the close `performClose:`): `counter.lisp` counts 3 clicks and exits 0 under
`java -jar`, the native binary, `java Counter`, `java -jar counter.jar` and `--native`; the native
CLI and `java -jar` compile byte-identical class files. **A `native-image` of the `.jar` does NOT
work**: `main` is thread 0 there and nothing hands it over, so the window opens and nothing is ever
dispatched (open item).

## `--native`: the runner is the Objective-C host
`--native -o prog` (`macos-aarch64` only: `CompileFrontend` accepts them when the native target is
`NativeTarget.OBJC_PLATFORM`) accepts the four packages: `ObjcNativeLibrary` splices `objc-native.lisp` -- the nine verbs
over `rontolisp:wasm-import`s from module `rlobjc` -- right OUTSIDE `AppKitLibrary`, and the runner
(`rontolisp-native/runner/src/objc`) answers them over libobjc/AppKit, dlopened on the first
`rlobjc` call (an output that makes none starts as before). Stub +116 KB (1,616,656 ->
1,732,800 B at one codegen unit; 1,799,168 B at the four the stub builds with since
2026-09-25). `counter.lisp` is a 2.36 MB executable.

- **THE decision: the module runs ON thread 0.** A wasmtime `Store` is not `Sync`, and a callback
  arriving on thread 0 while the module ran elsewhere could not enter it: that thread's stack holds
  the store, and a nested call from another thread would leave its wasm frames' GC roots unwalked
  (wasmtime walks the CURRENT thread's activations). So there is no hop: `objc:on-main` is a plain
  call, and a callback arrives INSIDE a host call -- a send that made AppKit call out, or `pump` --
  and re-enters through that call's `Caller`, published in the `CALLER` thread-local (`Entered`).
- **`sleep` is the event loop.** Nothing drains thread 0 while the module computes, so every
  `sleep` of such a program compiles to `objc::%sleep` (`WasmExprCompiler`, keyed on the spliced
  defun `LispNames.OBJC_SLEEP_INTERNAL`) -> `pump`: `nextEventMatchingMask:` + `sendEvent:` once the
  application started, else `CFRunLoopRunInMode`. `appkit:wait`'s 50 ms poll therefore keeps the
  window live at ~0% CPU (the Preview 1 spin would freeze it). A blocking stdin read still freezes
  it.
- **A fetch's wait turns the event loop too.** A program that also fetches (the network runner,
  `.kb/fetch-http.md`, "--native") waits in the runner for a reply's head or next body chunk; once
  the application started, that wait runs `pump` in 20 ms turns until the answer is in
  (`objc::pump_until`, called from `http::wait`), so the windows stay live and a callback arriving
  meanwhile re-enters through the call's `Caller`, as during `sleep`. Before the application starts
  nothing is on screen and the wait blocks on its condition variable. Decided 2026-09-25 over
  blocking like a stdin read: a window frozen for a request's round trip is what the `sleep` pump
  exists to prevent. Verified 2026-09-26 (M4 Max): a window whose 50 ms timer relabels it and posts a
  mouse down/up pair on its button to the application's queue, awaiting a reply held 2 s by the
  origin, got 40 relabels and 4 clicks during the wait at 0.16 s CPU -- the same for a head that
  arrives at once and a body held 2 s mid-transfer. Pinned without a window by `NativeObjcE2eTest`
  (a timer counts turns during the await; an event posted before it is dequeued by the end); with
  the application not started both come back false.
- **`-[NSApplication run]` is never started**: it never returns, and the thread is the module's.
  `appkit::%app`'s `performSelectorOnMainThread:withObject:waitUntilDone:` of `run` to an
  `NSApplication` is answered by marking the application started (and
  `activateIgnoringOtherApps:`), after which `pump` dispatches. The one place the runner reads a
  selector's meaning; `appkit.lisp` is unchanged.
- **Sends: one generic import, no shape table.** The library pushes each argument (`arg_*`, typed
  imports; `:s64` for an address), then `send(receiver, selector)` answers a result KIND fetched
  with `result_*`. The host marshals by the method's own encoding (`encoding.rs`, the twin of
  `TypeEncoding`, same refusals and messages) and calls `objc_msgSend` through `rl_objc_call`
  (`call.rs`): an assembly frame that loads x0-x7, d0-d7, x8 and a stack area, with Apple's AArch64
  classification (HFA -> SIMD registers, struct > 16 B by reference, struct result through x8,
  stack arguments at natural alignment, variadic ones in 8-byte slots) -- so EVERY parseable
  selector is callable, where the native binary serves a closed table. Encodings are cached per
  (class, selector). Measured 2026-09-25 (M4 Max): 0.20 us an integer answer, 0.24 an object
  answer (wrapper + handle), 0.27 one argument, 0.66 a struct argument; `java -jar` 7.4-8.1 us
  (its main-thread hop). `metal-cube` over 6 s: 0.51 s CPU against 4.18 s under `java -jar`,
  `metal-robot-arm` 2.13 s against 8.65 s.
- **Upcalls: one IMP for the closed shape set** (`v@:` ... `q@:@`, the JVM's set, same refusal
  text): on AArch64 `(self, _cmd, a, b) -> x0` serves them all. It looks up (class, SEL) up the
  superclass chain, retains `self` and the object arguments (the wrappers own them), and calls the
  export `rlobjc_callback(id, self, a, b, argc)`, which applies the closure `define-class`
  registered under `id`. A Lisp error is printed in the library (`objc: error in a callback: ...`)
  and answered as zero; a `proc_exit` inside exits with its code; a `throw` to a tag outside the
  callback (a trap here) is printed the same way and contained, as `ObjcClasses.dispatch` contains
  any `Throwable` -- nothing unwinds into the native frame.
- **Ownership is the JVM's -- one retain per wrapper -- through `:extern`.** A wasm-GC module has no
  finalizer, but wasmtime's copying collector DROPS an `externref`'s host data when the reference
  dies (`sweep_extern_refs`, read in the 49.0.0 source). Each object wrapper (`objc:object`, a
  defstruct: address + handle) holds the externref the `own` import returns, whose host data
  (`Owned`) queues the release on drop. The queue runs only from the OUTERMOST host call -- the end
  of a send, a `pump` turn -- never inside a callback, where it could free the object whose method
  is running, nor between a send's argument pushes (a receiver wrapper Cranelift already considers
  dead is released after the send that uses its address). Measured: 300,000 `self` sends leave
  ~19,000 references outstanding, not 300,002 (`NativeObjcE2eTest`). Same rule as the JVM:
  `setReleasedWhenClosed:` NO.
- **Two wrappers of one object are one value**, as on the interpreter (a record) and the JVM
  (`JvmObjcHandle.equals`): `eq`/`eql`/`equal`/`equalp` and all four hash-table tests compare by
  address. Interning one wrapper per address cannot give this -- the table would keep every
  wrapper, so every reference, alive. The comparison is the backend's: the wasm-GC runtime knows the
  layout of `objc:object` (`LispNames.OBJC_OBJECT_TYPE`, `WasmLispCompiler.addressKeyedLayout`)
  and compares and hashes its instances by the first (address) slot alone -- an arm in `_eql_tail`,
  a slot count of 1 in `_equal`'s and `_hash`'s instance arms, and `_ihash` placing such an
  instance by `_hash`. The prelude `equalp` tries `eql` before walking two instances' slots. A
  module without the layout is byte-identical. Pinned by the equality block of
  `objc-native-corpus.lisp` (`NativeObjcE2eTest`, against the interpreter).
- **The type** is `objc:object` on every backend: `type-of` answers `OBJC:OBJECT`, `typep` /
  `typecase` / a `defmethod` specializer / `class-of` / `find-class` name it, and it is no
  `structure-object` (nor its `subtypep`). The interpreter's record answers it from
  `builtinTypeName` (and `ClosRegistry.BUILTIN_CLASS_NAMES`); the JVM handle through an arm of
  `expandClassDesignator` gated on the objc runtime (`ctx.objcOps`); the `--native` wrapper IS the
  defstruct `objc:object` (`LispNames.OBJC_OBJECT_TYPE`), which `isStructureObjectLayout` keeps out
  of `structure-object`. A literal specifier is `(objc:objectp x)` (`makeTypeTest`, matched
  qualified); a computed one has an arm in the interpreter's inline dispatch and, for a program
  that spells an `objc:` symbol (`mentionsObjcPackage`), in `%typep-runtime` and the `%find-class`
  built-in list -- no other program pays. On the JVM, `objc:objectp` without the runtime compiles to
  `(progn x nil)`. Pinned by the type block of `objc-native-corpus.lisp` (`NativeObjcE2eTest`),
  `ObjcInteropTest` and `JvmObjcInteropCompilerTest`.
- `objc:data` lays a packed buffer out in Lisp (`%ieee754-single-bits`, lowered on wasm-GC for
  this) -- a per-frame uniform is 64 bytes; bfloat16 arrays and quantized matrices are refused
  (the JVM serves them). `objc:bytes` copies through a `:bytes` result.
- Verified 2026-09-25: the corpus and `scene:` offscreen pixels equal the interpreter's
  (`NativeObjcE2eTest`); `objc-runtime.lisp` and `system-frameworks.lisp` print byte for byte what
  `java -jar` prints; every `examples/macos` program starts and draws. Clicks were checked without
  a hand by posting `NSEvent mouseEventWithType:...` mouse-down/up pairs through `postEvent:atStart:`
  (the path a trackpad takes: `pump` -> `sendEvent:` -> the button's tracking loop -> the action
  IMP): 2 of 2 on `--native` and on `java -jar`. A human click on `counter.lisp` is still the
  GUI rule's check.

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
  `eval/MetalLibraryTest`; `SceneOffscreenRenderTest`; `PackageCycleTest`; `--native`:
  `eval/ObjcNativeLibraryTest` (the verbs defined, the splice), `e2e/NativeObjcE2eTest` (macOS
  aarch64: the corpus `objc-native-corpus.lisp` against the interpreter, a timer during `sleep`, the
  event loop during a fetch's wait, an exit inside a callback, release on wrapper death, `scene:` pixels), the runner's own
  `call.rs` / `encoding.rs` unit tests (`build.sh --test`).
- No test opens a window (CI has no display; the guide uses `console` fences so `DocExamplesTest`
  cannot hang). **Verified by hand: `counter.lisp` on `java -jar` AND the native binary;
  `minesweeper-macos.lisp` and `life-macos.lisp` on all three targets (`java -jar`, native binary,
  `-o Life.class`); `menubar.lisp` on the same three** -- what a widget-layer change costs, since
  it travels into every compiled `appkit:` program.

## Open items
- A compiled `objc:` jar built into a native image hangs: its `main` runs the program on thread 0
  and never parks it in the run loop (`RontoLispCli.main`'s hand-over has no compiled twin).
- No MAIN menu (a process with no bundle sets none), so no Cmd-Q on a windowed program.
- Callback shapes with struct or integer arguments, and block-taking selectors.
- A variadic selector a PROGRAM declares: served only for the names in `VariadicSelectors`, and
  the runtime offers no way to recognise another.
- x86_64: `objc_msgSend_stret` (struct returns wider than 16 bytes) has not been exercised.
- `--native`: a macos-x86_64 runner has no Objective-C host (`call.rs` is Apple's AArch64 convention, and there is no release stub).
