# `objc:` and `appkit:`: a native macOS window from the REPL, through FFM

- **`objc`** binds the Objective-C runtime and AppKit through `java.lang.foreign` --
  `am.ik.objc`, a language-independent library beside `am.ik.gpu`. Generic verbs named after the
  foreign system; the analogue of `java:` (`.kb/java-interop.md`) minus reflection.
- **`appkit`** is a widget layer written in rontolisp over those verbs (`appkit.lisp`), shipped
  inside the interpreter like `linalg.lisp` (`.kb/linalg.md`).
- **`metal`** (`eval/metal.lisp` + `eval/MetalLibrary`) and **`scene`** (`.kb/geom.md`) ship the
  same way.
- Docs: `doc/{en,ja}/guides/objc-appkit.md`.

**Scope**: macOS only, on the interpreter and on JVM class output (the binding travels inside
the class). Both WASM backends REFUSE a program referencing either package (`CompileFrontend`,
after load inlining, naming the reference) -- permanently: no FFM, no AppKit. A machine without
the runtime (Linux, or a JVM with `--illegal-native-access=deny`) SIGNALS at the call. Why it
exists: the native binary is the REPL people run, and `java:` cannot be INTERPRETED there (no
reflection metadata); FFM needs none.

Examples (GUI ones are not in `examples.yaml`): `examples/macos/counter.lisp`;
`examples/macos/cocoa.lisp` (grid helper) with
`examples/browser/minesweeper/minesweeper-macos.lisp` and `examples/macos/life-macos.lisp`
(Life on an `appkit:timer`, the Swing front-end's twin over one core);
`examples/macos/menubar.lisp` (no window -- a status item whose menu entries are Lisp closures);
`examples/macos/listener.lisp` (a Lisp listener in a Cocoa window);
`examples/macos/objc-runtime.lisp` (window-free: introspection, NSMethodSignature, KVC, a
run-time class whose `isEqual:` Foundation calls, an NSNotificationCenter observer -- the one
program that gates the blob on through bare `objc:` verbs, no `appkit:` and so no splice); the
four `metal-*` examples. `examples.yaml`'s `os: [mac]` field gates a RUN leg on the platform and
leaves COMPILE legs alone.

## The one architectural fact: AppKit belongs to thread 0
The thread the kernel started the process on (`pthread_main_np()` answers 1) is the only one
that may touch a window, and the Lisp thread is never it.

- `java -jar`: the launcher parks thread 0 in a `CFRunLoop` and runs `main` on a second thread.
  Nothing to do.
- **Native binary**: `main` IS thread 0. `RontoLispCli.main`, when
  `ObjcInterop.mainThreadHandOverRequired()` (native image + macOS + thread 0; cheap -- libSystem
  + CoreFoundation, no AppKit), moves to a spawned `main` thread (16 MiB stack) and parks thread
  0 in `MainThread.runLoop()`. UNCONDITIONAL -- thread 0 cannot be handed over later; the worker
  ends the process with `System.exit` whatever the code, since thread 0 never returns.
- `runLoop()` is the launcher's `ParkEventLoop`, not a bare `CFRunLoopRun`: a no-op
  `CFRunLoopSource` keeps the default mode non-empty and `CFRunLoopRunInMode(default, 1e20)` is
  re-entered whenever it returns. **Trap**: a bare `CFRunLoopRun` returns after the first click,
  `main` returns, and the binary sits in `JavaMainWrapper`'s join with a beach-balled window.
  `RONTOLISP_OBJC_TRACE=1` prints every hop and run-loop return.

**Parking thread 0 is not enough -- AppKit must be the one draining it.** A CFRunLoop lets the
window server deliver events; only `-[NSApplication run]` DEQUEUES them
(`nextEventMatchingMask:` -> `sendEvent:`). Until it runs, a window draws and answers no click
(not even the red button), and `isActive`/`isKeyWindow` stay NO. `run` never returns, so
`appkit::%app` asks thread 0 to `performSelectorOnMainThread:withObject:` it `waitUntilDone:` NO
-- starting it on the next run-loop cycle, NESTED inside whatever loop was parking the thread,
blocking nobody. `%app` is the ONLY place that starts it, so a window built from raw `objc:` in a
process that never called an `appkit:` function draws and answers nothing (deliberate).

**Every entry point hops.** `MainThread.sync` hands a body to the main dispatch queue with
`dispatch_sync_f` and waits; the body crosses ONE upcall stub (`trampoline`, `void(void*)`) whose
context pointer is a ticket into a slot map. **Re-entrancy rule**: `dispatch_sync` to the queue
you are draining is a deadlock, so `sync` tests `pthread_main_np()` and runs inline when already
on thread 0. Consequences: an `:on-click` handler runs on thread 0 with the interpreter's GLOBAL
dynamic bindings (`.kb/dynamic-special-variables.md`), and an unhandled exception is PRINTED
(`objc: error in a callback: ...`) rather than thrown -- unwinding into the native frame above an
upcall kills the process. An exception inside a `sync` body is carried back and rethrown on the
caller's thread as it was, so a Lisp non-local exit through `objc:on-main` works.

## Where the line goes: widgets ship, layout stays an example
`appkit` carries `appkit:color`, `appkit:font`, `appkit:panel` (an `NSBox` in custom form),
`appkit:set-color`, `appkit:on-click`, `appkit:timer`, a vertically centred `appkit:label`,
`:background` / `:dark` on `appkit:window`, plus `appkit:status-item`, `appkit:menu`,
`appkit:quit` and `&optional` on `appkit:wait`. Two cannot be reached by an obvious `objc:send`:
- a centred label needs the font's line height MEASURED (an `NSTextField` draws at the TOP of its
  frame); `appkit::%line-height` asks a throwaway `sizeToFit` field once per font, cached by font
  address;
- a clickable view needs a run-time subclass (`NSBox`/`NSTextField` answer no click):
  `appkit::%clickable-class` defines `RontoLispAppKitPanel` / `RontoLispAppKitLabel` over
  `mouseDown:`/`rightMouseDown:`, so one address-keyed table lets a panel and the label drawn
  over it share a handler with no event forwarding.

Menu-bar specifics: a menu item is wired EXACTLY as a button (target/action into
`appkit::*actions*`, keyed by address), so `%invoke` needed no change. `:dock nil` sets activation
policy 1 (accessory) on the shared application `%app` started with policy 0. `appkit:set-text` /
`appkit:text` test for the status item AHEAD of the button test, because an `NSStatusItem` is NOT
a view (`appkit::%status-item-p`). A menu-bar program has no window, so nothing releases a `wait`
that demands one -- `quit` sends `terminate:`.

LAYOUT stays out: `examples/macos/cocoa.lisp` is the grid alone, the AppKit twin of
`swing:label-grid-window`.

- The rungs are `appkit:` functions, NOT a second built-in `cocoa` package: a package name is
  taken for good (no shadowing, no `delete-package`).
- `appkit:on-click`'s handler takes the BUTTON NUMBER -- 1 left, 3 right, the `java.awt.event`
  numbers, so a Swing handler reads the same. A button's own `:on-click` closure takes none;
  given a button, `on-click` sets its target/action, so one verb wires any widget.
- `appkit:set-color` dispatches on the view like `appkit:set-text`: an `NSBox` gets
  `setFillColor:` + `setNeedsDisplay:`, anything else `setTextColor:`.
- A rung costs a `PackageRegistry.APPKIT_FUNCTIONS` entry (library and registry must agree
  EXACTLY -- `AppKitLibraryTest`), a per-operator page under
  `doc/{en,ja}/reference/functions/appkit-*.md` + `_catalog.yaml` entry + a guide-table row in
  BOTH languages, and blob growth: `AppKitLibrary.process` prepends the whole library, UNPRUNED,
  to every compiled `appkit:` program.

## `objc:send` derives its shape from the runtime; the native binary serves a CLOSED table
`method_getTypeEncoding` describes every selector completely (e.g.
`@68@0:8{CGRect={CGPoint=dd}{CGSize=dd}}16Q48Q56B64`). `TypeEncoding` parses it into a
`FunctionDescriptor` (struct flattened to scalar leaves, ABI-identical for the homogeneous AppKit
structs); `ObjcRuntime.send` binds one `objc_msgSend` handle PER DISTINCT SHAPE -- Apple's arm64
rule: never through the variadic declaration; an `NSRect` through a `long` shape is a SIGBUS --
and calls it with `invokeWithArguments`, which a native image serves. A wrong selector, arity or
operand type is an `ObjcException` -> a Lisp `error`, never a crash (one hole: a VARIADIC
selector, Open items). Blocks (`@?`), unions, bitfields and function pointers are refused by name.

A native image builds a downcall stub only for a shape registered at build time
(`MissingForeignRegistrationError`, an `Error`, at `Linker.downcallHandle`), so the served set is
a CLOSED TABLE in `reachability-metadata.json`: the runtime's own C functions, every shape
`appkit.lisp`/`metal.lisp` and the documented examples send, and the 60 most common shapes of a
census over 29 core AppKit/Foundation classes (13,065 methods, 90.6% reached). One entry is
outside both -- `NSTimer`'s
`scheduledTimerWithTimeInterval:target:selector:userInfo:repeats:`. A selector outside the table
signals with the exact entry to add. The JVM registers nothing and binds any shape, so `java -jar`
is where a program discovers what it sends. Pinned by `ObjcNativeImageForeignConfigTest`.
**A new selector in `appkit.lisp`, `metal.lisp`, `examples/macos/objc-runtime.lisp`,
`examples/macos/listener.lisp` or the docs is a row in that test's table**; an example that only
calls `appkit:` adds nothing.

`foreign.upcalls` is the project's first such section. `ObjcClasses` defines a class at run time
(`objc_allocateClassPair` + `class_addMethod` + `objc_registerClassPair`) whose IMPs are upcall
stubs from a CLOSED shape set, one static method each, bound with a CONSTANT `findStatic` (a name
in a variable needs reflection metadata): `v@:`, `v@:@`, `v@:@@`, `B@:@`, `@@:@`, `q@:@`. A
method's encoding is LOOKED UP -- superclass declaration, then adopted protocols
(`protocol_getMethodDescription`), then the target/action default `v@:` + `@` per colon -- never
guessed. Dispatch is by (class, SEL) up the superclass chain; re-defining a class this process
defined REBINDS (the runtime cannot unregister). `performSelector...` answers are DISCARDED:
retaining the garbage a void method leaves in x0 SIGSEGVs.

## Bytes and out-parameters: `objc:data`, `objc:bytes`, `:error`
- `objc:data` answers an **`NSMutableData`** -- mutable so ONE verb serves both directions
  (`bytes` for a `^v` parameter, `mutableBytes` as writable scratch). The argument goes through
  `eval/PackedBuffer` (promoted out of `Environment`), so `objc:data` and
  `%write-sequence-packed` cannot disagree about what a `#f` matrix is on the wire
  (little-endian, row-major, `.kb/binary-sequence-io.md`). A string goes as its UTF-8.
- `objc:bytes` is the read direction, a packed `(unsigned-byte 8)` vector.
- `:error` in an argument position allocates a pointer-sized out slot (`ObjcRuntime.Out`, filled
  from the arena after the call and before it closes), passes its address, and hands the pair to
  `ObjcRuntime.checkError` (signals with `localizedDescription` + domain + code). **The gate is
  `Sent.failed()` -- nil, `NO` or zero -- NOT "the slot is non-NULL"**: Foundation's rule is that
  the RESULT says whether a call failed.

Two costs: `ObjcRuntime$Out` is a class file in `am.ik.objc`, so a row in
`JvmObjcRuntimeBuilder.OBJC_CLASSES`. And the compiled twin cannot reuse `PackedBuffer`: a packed
float array is a `float[]`/`double[]` carrying its dimension header IN the array
(`[rank, dim_0..., e_0...]`, `JvmFloatArrayRuntimeBuilder`) and a packed integer vector a
`long[]{width, e_0, ...}`, so `JvmObjcTemplate.bufferBytes` must skip what
`LispSingleFloatArray.data()` never contained -- sending the header makes the two backends
disagree by two floats.

## Metal
`metal.lisp` is `webgl-common/gl.lisp`'s twin in substance, loaded lazily on the first `metal:`
resolution like `appkit.lisp`. Consumers: the four `metal-*` examples (which use `metal` DIRECTLY,
with no `geom`/`scene`) plus the `scene` viewer. No Java added: Metal is Objective-C end to end.

- **Exported**: `attach` / `offscreen` / `pixels` / `device` / `layer` / `queue` / `library` /
  `pipeline` / `depth-state` / `floats` / `buffer` / `shared-buffer` / `upload` / `uniform` /
  `frame` / `run` / `resize` / `set-clear-color`, the class `metal:context`, and eleven enum
  members: primitive (`+point+` `+line+` `+triangle+` `+triangle-strip+`), cull mode, winding,
  depth comparison. Pixel formats, load/store actions, blend factors and storage modes stay
  INTERNAL (`metal::+bgra8-unorm+` etc.) -- `attach`/`pipeline`/`frame` own them.
- The context is a CLOS class, not a hash table, so `set-clear-color` and `resize` are verbs
  rather than slot pokes.
- Its splice runs BEFORE `AppKitLibrary`'s in `CompileFrontend` (`metal:run`'s clock is
  `appkit:timer`); the interpreter reaches it lazily. `LibraryDefunPruner` keys it by name
  (`MetalLibraryTest`).
- **OpenGL cannot be reached and never will be**: `glClear`/`glDrawArrays` are plain C functions
  outside `objc_msgSend`, and `objc:` binds no C entry points.
- The one C function Metal appears to need, `MTLCreateSystemDefaultDevice()`, is avoidable:
  **`[[CAMetalLayer layer] preferredDevice]` is a property** and answers the same device. Without
  it `am.ik.gpu`'s `MetalDriver` would have to be reached from `eval`, which the package graph
  forbids.
- Shaders compile at run time from a Lisp string, `newLibraryWithSource:options:error:`.
- The surface is a `CAMetalLayer` on `appkit:window`'s `contentView` -- **`setLayer:` BEFORE
  `setWantsLayer:`**, or AppKit makes its own layer first and the one handed over never becomes
  the backing store. The frame loop is `appkit:timer` (an `NSTimer` on thread 0).
- A convex mesh needs no depth attachment (back-face culling suffices); a non-convex one asks
  `metal:attach :depth t` -- a private `Depth32Float` texture the size of the drawable that the
  pass clears and every pipeline drawing into it must DECLARE, which is why `metal:pipeline` reads
  the format off the context rather than the caller. A glow pass is `metal:pipeline :blend t`
  (additive, one + one) plus `metal:depth-state :writes nil`.
- Geometry rewritten every frame cannot use `metal:buffer` (copy once, never change):
  `metal:shared-buffer` + `metal:upload` memcpys into `contents` through
  `-[NSData getBytes:length:]`, and the program rotates THREE copies (the CPU writes next frame's
  vertices while the GPU may still read this frame's).
- **The mouse is `objc:define-class`**, not an `appkit` rung: an `NSView` subclass whose
  `mouseDown:` / `mouseDragged:` / `mouseUp:` / `scrollWheel:` / `acceptsFirstMouse:` are Lisp
  closures, set as the content view BEFORE `metal:attach` puts the layer on it, so drawing and
  input are one view. All those selectors are declared by NSView, so encodings come off the
  superclass and land on `v@:@` / `B@:@`; `locationInWindow` answers an NSPoint (struct return, a
  flat list) and `scrollingDeltaY` a double.
- A `linalg` result reaches the GPU with NO conversion (packed float array; `objc:data` takes any
  rank): `linalg:matmul` -> `objc:data` -> `setVertexBytes:`. `:element-type 'single-float` picks
  float32 (a Metal `float4x4`) and every linalg transform preserves the width (`.kb/linalg.md`);
  one `linalg:transpose` bridges row-major storage to Metal's column-major `float4x4`.

`metal-pagoda-garden.lisp` facts worth keeping:
- **The whole scene is ONE cube**: the vertex function takes `id / 36` for the voxel and `id % 36`
  for the corner out of a 36-entry `constant` array, so 13,000 voxels are ONE
  `drawPrimitives:vertexStart:vertexCount:` over 13,000 32-byte records -- no vertex descriptor,
  no index buffer, and deliberately no `...instanceCount:` (its four-integer shape is not in the
  closed table). `(id % 36) / 6` is the face, so the normal is exact.
- **A point sprite carries ONE depth, the centre's** -- a glow emitted where its own lantern is
  loses the depth test; `sp-near` nudges it toward the eye.
- **The sky has to work BELOW the horizon**: the camera looks down by more than its half-FOV, so a
  `dir.y > 0` sky renders two thirds of the window flat. The gradient is symmetric in `abs(dir.y)`.
- **A procedural hash must stay where `fract` still has bits**: past 10^6, float32 leaves `fract`
  four distinct answers. Both hashes are Hoskins-style and stay under ~10^4.
- One 32-bit LCG seeded by a constant builds the scene, so the same garden grows on all three
  JVM-family targets.

Every Metal object a program holds is PROTOCOL-typed (`id<MTLDevice>`) with a private,
machine-specific concrete class (`AGXG16CDevice`), so `ObjcNativeImageForeignConfigTest` has a
`proto(...)` row resolving through `protocol_getMethodDescription`. Metal's DESCRIPTOR classes are
the reverse -- `MTLRenderPipelineDescriptor` is abstract in public and `alloc` answers
`MTLRenderPipelineDescriptorInternal` -- so the test falls back to the `...Internal` name for an
instance row the public class does not declare.

Rendering is checkable with NO display: render into an offscreen `MTLTexture` (storage mode
shared, usage render-target), `getBytes:bytesPerRow:fromRegion:mipmapLevel:` into an `objc:data`
block, read back with `objc:bytes` -- the shipped `metal:offscreen` / `metal:pixels` pair.
`metal:frame` takes the drawable's texture or the context's own, so there is ONE encoding path
(`.kb/geom.md`, "How the renderer is tested"). Its readback shape -- an `MTLRegion`, six
`NSUInteger`s by value -- is the widest struct argument in the closed table.

## A frame that signals: the callback guard is not the last word
`ObjcClasses.dispatch` catches `Throwable`, hands it to the error sink and answers the method's
zero value, so a Lisp error inside a callback prints and the process continues -- and that guard
holds under native-image too, so a "the binary crashes where `java -jar` does not" report is not
about the guard.

What kills the process is DOWNSTREAM. An encoder released without `endEncoding` is a Metal
ASSERTION:

```
-[_MTLCommandEncoder dealloc]:134: failed assertion `Command encoder released without endEncoding'
```

and an assertion is an `abort()`, which no `catch (Throwable)` can be under. Whether a carrier
survives it is TIMING, not semantics (the same defect took a JUnit fork down with
`Process Exit Code: 134` on the JVM). Fix: `metal:frame` ends the encoder, presents the drawable
and commits the buffer from an `unwind-protect` cleanup. The pin is a COMMIT, not a crash (the
abort is timing-dependent, the commit is not): an un-committed frame never reaches the texture, so
the previous frame's pixels survive it
(`SceneOffscreenRenderTest.aFrameWhoseBodySignalsIsStillEndedAndCommitted`).

**General rule: a callback that touches a native object with a begin/end protocol must close it on
the signalling path too.**

## Ownership: one retain per wrapper, released on thread 0
`LispObjcObject(address, className)` -- a `LispVal` permittee, `equal` by address -- owns exactly
one reference. `alloc`/`new`/`copy`/`mutableCopy`/`retain` results arrive at +1 and the wrapper
takes it; everything else is retained INSIDE the hop that produced it, because the main queue
drains its autorelease pool when the block returns. A `Cleaner` releases through
`ObjcRuntime.releaseOnMain` -> `dispatch_async_f`, since AppKit deallocates a window or view on
thread 0 only. Two wrappers of one object hold two references; alloc + init balance the same way.
Classes own nothing. Hence the rule `appkit:window` honours and a raw `objc:` window must:
**`setReleasedWhenClosed:` NO**, or the close releases a reference the wrapper still holds.
Leaking is the safe direction everywhere here.

## The JVM backend: the binding travels in the class, and calls back into it
`-o Prog.class` / `-o lib.jar` uses the `--gpu` route (`.kb/gpu.md`;
`.kb/template-class-embedding.md`, "A closure of classes"): every class file of `am.ik.objc` is
renamed by one prefix rule (`am/ik/objc/` -> the program's package + `RontoLispObjc`), base64'd,
and `Lookup.defineClass`'d by the emitted `_objcInit` on the first `objc:` call, so the compiled
program runs the interpreter's own bytes. `JvmObjcRuntimeBuilder` owns the list (pinned against
`target/classes/am/ik/objc` by `JvmObjcInteropCompilerTest#theBlobCarriesTheWholeLibrary`). Two
classes ride along, renamed the same way, each ONE class file:
- `JvmObjcTemplate` -> `RontoLispObjcBridge`: the seven verbs against the compiled value model,
  the hand-kept twin of `ObjcBridge` -- **KEEP THE TWO IN SYNC**. An if-chain over
  `TypeEncoding.Kind`, because an enum `switch` lowers to a synthetic `$1` class the blob does not
  carry (the test pins that neither template has a `$` sibling on disk).
- `JvmObjcHandle` -> `RontoLispObjcObject`: the compiled `LispObjcObject` -- address + class name,
  `equals` by address (what the compiled `_equal` falls back to), `toString` = `#<objc Class>`.
  The printer reaches it through the bridge's `objcPrint` hook (`JvmRuntimeBuilder.ObjcPrint`,
  guarded by `_objcInited`), emitted AHEAD of the `java:` branch, which would otherwise claim it.

Three differences from the `--gpu` blob:
- **Definition order is not free**: the VERIFIER loads a class it must check assignability against
  while defining the referencing class (a `catch` type must be a `Throwable`), so `ObjcException`
  is defined FIRST; alphabetical order died in `defineClass` with `NoClassDefFoundError`.
- **The blob makes UPCALLS into the program**: a `define-class` method and an `on-main` body are
  applied through `_apply`, handed over by `bind(Class)` from `_objcInit` (the `java:proxy`
  precedent), which is why `usesObjc` forces `usesEval` and roots `_apply` for the shaker. `bind`
  hands over `_strv` the same way (nullable -- absent exactly when the program has no array
  runtime): every string the template accepts funnels through its `lispString`, which renders a
  mutable character vector once there (`.kb/string-write-runtime.md`).
- **The gate is the nine verbs**, qualified, and `appkit.lisp` reaches them:
  `AppKitLibrary.process` splices the widget layer on the compile path (`CompileFrontend`, beside
  `LinalgLibrary`; pruned to what the program calls), so an `appkit:` program compiles as ordinary
  Lisp whose `objc:send` gates the blob on.

Under the `java` launcher thread 0 is already parked in a `CFRunLoop`, so no hand-over arises; the
compiled hop is `MainThread.sync` exactly as interpreted. A bare `.class` without
`--enable-native-access=ALL-UNNAMED` gets the JDK's one-time warning and works; a `.jar` carries
`Enable-Native-Access: ALL-UNNAMED` in its manifest (`JvmJarWriter`). The native binary COMPILES
such a program too (the blob's files are in `resource-config.json`). Each compiled program defines
its own copy of the library into its own loader, which is why the test names a run-time class per
program (`objc_allocateClassPair` cannot be undone).

## Package rules and the web build
`am.ik.objc -> (nothing)`; `eval -> am.ik.objc` through ONE class, `eval/ObjcBridge`, reached only
via `eval/ObjcInterop`'s five entry points (the `LinalgGpu`/`LinalgGpuKernels` shape), so
`src/web/java/.../Target_ObjcInterop.java` substitutes them and the browser build carries no FFM.
`ObjcCaller` is its own type so the bridge and the entry class reference each other in no direction
(`PackageCycleTest`). `cli` reaches the hand-over through `ObjcInterop`, never the library.
`MetalDriver` is the same runtime through a hand-written shape table and could ride on
`am.ik.objc`; it does not yet.

## Tests

| what | where |
|---|---|
| the encoding parser (pure) | `am.ik.objc.TypeEncodingTest` |
| native-image registration: runtime downcalls + callback upcalls (any machine), appkit selectors (Mac) | `am.ik.objc.ObjcNativeImageForeignConfigTest` |
| the verbs, headless (Foundation + a run-time class; Mac), the signal elsewhere | `eval/ObjcInteropTest` |
| `objc:data` / `objc:bytes` round trip and the `:error` slot, interpreted and compiled -- same expectations byte for byte | `eval/ObjcInteropTest`, `codegen/jvm/JvmObjcInteropCompilerTest` |
| the library defines exactly the registry's exports, lazy load, the WASM refusal, the JVM splice | `eval/AppKitLibraryTest`, `eval/MetalLibraryTest` |
| the verbs compiled to a class, the embedded class list, one file per template | `codegen/jvm/JvmObjcInteropCompilerTest` |
| offscreen render, incl. a signalling frame body | `SceneOffscreenRenderTest` |
| `eval -> am.ik.objc`, and the library imports nothing | `PackageCycleTest` |

No test opens a window (CI has no display; a doc `lisp` fence that opened one would hang
`DocExamplesTest`, so the guide uses `console` fences). Verified by hand: `counter.lisp` on
`java -jar` AND the native binary; `minesweeper-macos.lisp` and `life-macos.lisp` for the widened
surface, the latter on all three (`java -jar`, native binary, `-o Life.class`) -- what a
widget-layer change costs, since it travels into every compiled `appkit:` program. `menubar.lisp`
on the same three; the part needing no eyes (title read back, closure fired through the item's
target/action, separator, key equivalent, accessory policy) is a re-runnable script.

## Open items
- No MAIN menu (a process with no bundle sets none), so no Cmd-Q on a windowed program; an app
  delegate is one `objc:define-class` away and not written. The STATUS bar is served, and a status
  menu's key equivalent is where `appkit:quit` is reached from. With the event loop running the
  process is a foreground application unless `:dock nil` asked for the accessory policy.
- Callback shapes with struct or integer arguments, and block-taking selectors.
- A VARIADIC selector is the one hole in "never a crash": the encoding does not mark it, so
  `arrayWithObjects:` is bound as `@@:@`, the nil terminator lands in a register the callee never
  reads, and the process dies in `objc_retain`. Refuse the known set by name the way blocks are,
  then serve them with `Linker.Option.firstVariadicArg`.
- x86_64: `objc_msgSend_stret` is selected for a struct return wider than 16 bytes and has not been
  exercised.
