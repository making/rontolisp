# `objc:` and `appkit:`: a native macOS window from the REPL, through FFM

Two built-in packages (todo-512, 2026-08-25). **`objc`** binds the Objective-C runtime and
AppKit through `java.lang.foreign` -- `am.ik.objc`, a language-independent library beside
`am.ik.gpu` -- and exposes a handful of generic verbs named after the foreign system, the
exact analogue of `java:` (`.kb/java-interop.md`) minus the reflection. **`appkit`** is a
widget layer written in rontolisp over those verbs (`appkit.lisp`), shipped inside the
interpreter the way `linalg.lisp` is (`.kb/linalg.md`): a bare REPL types
`(appkit:window "hi")` with nothing required and nothing to copy. The user-facing
description is `doc/{en,ja}/guides/objc-appkit.md`; the examples are
`examples/macos/counter.lisp` and, over the `cocoa` grid helper
`examples/macos/cocoa.lisp`, `examples/browser/minesweeper/minesweeper-macos.lisp` and
`examples/macos/life-macos.lisp` (Conway's Life on an `appkit:timer`, the Swing
front-end's twin over one shared core -- the second consumer that fixed the promoted
API, "Where the line goes" below), and
`examples/macos/menubar.lisp` -- a program with NO window at all, a status item in the
system menu bar whose menu entries are Lisp closures (2026-08-26) --
plus `examples/macos/listener.lisp` -- a Lisp listener in a Cocoa window (an `NSTextView`
transcript, an editable field whose Return key is a Lisp closure, `eval` on what it
reads), the shortest demonstration that the window and the evaluator are one image
(GUI, so none of them is in `examples.yaml`), plus the two examples that open nothing and are therefore in
`examples.yaml` under `os: [mac]`. `examples/macos/objc-runtime.lisp` is the window-free
half of the binding (introspection, NSMethodSignature, KVC, a run-time class whose
`isEqual:` Foundation calls, an NSNotificationCenter observer) -- the field that gates a RUN leg on the platform
and leaves the COMPILE legs alone, added for it -- so its output is checked on a Mac and
its lowering everywhere: the one program that gates the blob on through the bare `objc:`
verbs, with no `appkit:` reference and so no splice. `examples/macos/metal-triangle.lisp` +
`metal-cube.lisp` + `metal-robot-arm.lisp` (todo-525,
2026-08-26) + `metal-pagoda-garden.lisp` (2026-08-27) are the GPU set, the first two the
AppKit twins of `examples/browser/webgl-triangle` and `webgl-cube`, the third of
`webgl-robot-arm` and the last one with no browser twin; the surface they share is the
shipped **`metal`** package (todo-565, 2026-08-29), and the rung above it is **`scene`**,
the 3-D viewer for `geom` solids (`examples/macos/scene-solids.lisp`,
`scene-robot-arm.lisp`; `.kb/geom.md`) -- "Metal" below.

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
That was the first cut's real state, found 2026-08-25 from the minesweeper front-end and
true of `counter.lisp` too. `run` never returns, so no thread that has to come back may
call it: `appkit::%app` asks thread 0 to `performSelectorOnMainThread:withObject:` it
`waitUntilDone:` NO, which starts it on the next run-loop cycle -- NESTED inside whatever
loop was parking the thread (the launcher's under `java -jar`, `MainThread.runLoop()`'s in
the binary) -- and blocks nobody. Every hop still works, because `run` drains the main
queue exactly like the loop it replaces; a REPL keeps taking input, `appkit:wait` still
returns when the window closes, and `appkit:click` still drives a button head-lessly.
`%app` is the ONLY place that starts it, so a window built from raw `objc:` in a process
that never called an `appkit:` function draws and answers nothing -- deliberate, since
`objc:` is the generic binding and a Foundation-only script must not become an app.

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

## Where the line goes: widgets ship, layout stays an example

(todo-515, 2026-08-25) `appkit` carries the rungs immediately above a window --
`appkit:color`, `appkit:font`, `appkit:panel` (an `NSBox` in its custom form),
`appkit:set-color`, `appkit:on-click`, `appkit:timer`, a vertically centred
`appkit:label`, and `:background` / `:dark` on `appkit:window` -- because the binary is
what people install and a binary user has no `examples/` directory to copy from. Two of
them cannot be reached by an obvious `objc:send` at all: a centred label needs the font's
line height MEASURED (an `NSTextField` draws its string at the TOP of its frame;
`appkit::%line-height` asks a throwaway `sizeToFit` field once per font and caches by font
address), and a clickable view needs a run-time subclass, since `NSBox` and `NSTextField`
answer no click -- `appkit::%clickable-class` defines `RontoLispAppKitPanel` /
`RontoLispAppKitLabel` over `mouseDown:` / `rightMouseDown:`, and every panel and label is
an instance of one, so one address-keyed table lets a panel and the label drawn over it
share a handler with no event forwarding.

The menu bar joined them on the same argument (2026-08-26): `appkit:status-item`,
`appkit:menu` and `appkit:quit`, plus `&optional` on `appkit:wait`. A menu bar program has
no window, so nothing could release a `wait` that demands one and no red button could end
it -- `quit` sends `terminate:`. Three decisions are load-bearing here too. A menu item is
wired EXACTLY as a button is (target/action into `appkit::*actions*`, keyed by the item's
address), so the one table answers for every widget in the layer and `%invoke` needed no
change. `:dock nil` sets activation policy 1 (accessory) on the shared application
`%app` already started with policy 0 -- the policy is the only difference between a menu
bar program and a windowed one, and it is a keyword rather than a rung of its own.
`appkit:set-text` / `appkit:text` accept the status item ahead of the button test, because
an `NSStatusItem` is NOT a view: the bar draws it through a button it owns, and
`appkit::%status-item-p` is what keeps a timer's `set-text` working on it.

What stays OUT is LAYOUT: `examples/macos/cocoa.lisp` is now the grid alone -- rows
top-down, a panel plus a label per cell, both wired to one handler -- board-game policy,
the AppKit twin of `swing:label-grid-window`, which stayed an example for the same reason.

The decisions that are load-bearing:

- The rungs are `appkit:` functions, NOT a second built-in `cocoa` package: a package name
  is taken for good (no shadowing, no `delete-package`), and one toolkit reading under two
  prefixes buys the user nothing.
- `appkit:on-click`'s handler takes the BUTTON NUMBER -- 1 left, 3 right, the
  `java.awt.event` numbers, so a Swing front-end's handler reads the same. A button's own
  `:on-click` closure takes none (a button has no right click); given a button,
  `on-click` sets its target/action, so one verb wires any widget.
- `appkit:set-color` dispatches on the view the way `appkit:set-text` does: an `NSBox`
  gets `setFillColor:` + `setNeedsDisplay:`, anything else `setTextColor:`.
- A rung costs a `PackageRegistry.APPKIT_FUNCTIONS` entry (the library and the registry
  must agree EXACTLY -- `AppKitLibraryTest`), a per-operator page under
  `doc/{en,ja}/reference/functions/appkit-*.md` with a `_catalog.yaml` entry and a row in
  the guide's table in BOTH languages, and growth in the blob: `AppKitLibrary.process`
  prepends the whole library, unpruned, to every compiled `appkit:` program.

## `objc:send` derives its shape from the runtime -- and the native binary serves a closed table

The runtime describes every selector completely (`method_getTypeEncoding`:
`@68@0:8{CGRect={CGPoint=dd}{CGSize=dd}}16Q48Q56B64`). `TypeEncoding` parses that into a
`FunctionDescriptor` -- a struct flattened to its scalar leaves, ABI-identical for the
homogeneous AppKit structs -- and `ObjcRuntime.send` binds one `objc_msgSend` handle per
distinct shape (Apple's arm64 rule: never through the variadic declaration; an `NSRect`
through a `long` shape is a SIGBUS) and calls it with `invokeWithArguments`, which a
native image serves (probed 2026-08-25). So a wrong selector, arity or operand type is an
`ObjcException` -> a Lisp `error`, never a crash -- with one hole, a VARIADIC selector,
under "Open items". Blocks (`@?`), unions, bitfields and
function pointers are refused by name.

A native image builds a downcall stub only for a shape registered at build time
(`MissingForeignRegistrationError`, an `Error`, at `Linker.downcallHandle`), so the served
set is a CLOSED TABLE in `reachability-metadata.json`: the runtime's own C functions, every
shape `appkit.lisp` and the documented examples send, and the
60 most common shapes of a
census over 29 core AppKit/Foundation classes (13,065 methods, 90.6% reached; the spike's
`ShapeCensus.java` at `.todo/512-*/`, re-runnable). Exactly one entry is outside both --
`NSTimer`'s `scheduledTimerWithTimeInterval:target:selector:userInfo:repeats:`, the clock
behind `appkit:timer`; everything else the widget rungs send (NSBox, the NSTextField
setters, NSFont, NSAppearance) was already served by the census. A selector outside the
table signals with the exact entry to add. The JVM registers nothing and binds any shape, so `java -jar` is where
a program discovers what it sends. Pinned by `ObjcNativeImageForeignConfigTest`: the
runtime shapes and the callback stubs on every machine (against `NativeImageDowncalls.EVERYTHING`),
the `appkit` selectors resolved on a Mac against the file. **A new selector in
`appkit.lisp`, in `examples/macos/objc-runtime.lisp`, in `examples/macos/listener.lisp` or
in the docs is a row in that test's table.** An example that only calls `appkit:` adds
nothing: the widget layer is where the sends live.

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

## Bytes and out-parameters: `objc:data`, `objc:bytes`, `:error`

(todo-525, 2026-08-26) A generic send can express an object, a number, a string and a
struct; it cannot express a BLOCK OF MEMORY or an out-parameter, and Cocoa is full of
both. Three additions close that, and nothing else was needed to reach the GPU.

`objc:data` answers an **`NSMutableData`** -- mutable so ONE verb serves both directions
(`bytes` is the address a `^v` parameter wants, `mutableBytes` is writable scratch a
callee fills) -- holding a packed buffer's bytes. Which bytes is not a new decision: the
argument goes through `eval/PackedBuffer`, promoted out of `Environment` for this, so
`objc:data` and `%write-sequence-packed` cannot disagree about what a `#f` matrix is on
the wire (little-endian, row-major, `.kb/binary-sequence-io.md`). A string goes as its
UTF-8. `objc:bytes` is the read direction, a packed `(unsigned-byte 8)` vector.

`:error` in an argument position of `objc:send` allocates a pointer-sized out slot
(`ObjcRuntime.Out`, filled from the arena after the call and before it closes), passes its
address, and hands the pair to `ObjcRuntime.checkError`, which signals with
`localizedDescription` + domain + code. The gate is `Sent.failed()` -- nil, `NO` or zero --
NOT "the slot is non-NULL": Foundation's own rule is that the RESULT says whether a call
failed, and an error object left behind by a call that succeeded is not an error. Without
this a shader that does not compile is an unexplained `nil`; with it the Metal compiler's
diagnostics, caret and all, arrive as an ordinary Lisp condition.

Two costs are easy to miss. `ObjcRuntime$Out` is a new class file in `am.ik.objc`, so it
is a new row in `JvmObjcRuntimeBuilder.OBJC_CLASSES` (the blob test catches this). And the
compiled twin cannot reuse the interpreter's `PackedBuffer`: a packed float array is a
`float[]`/`double[]` carrying its dimension header IN the array (`[rank, dim_0..., e_0...]`,
`JvmFloatArrayRuntimeBuilder`) and a packed integer vector a `long[]{width, e_0, ...}`, so
`JvmObjcTemplate.bufferBytes` skips what `LispSingleFloatArray.data()` never contained --
the first cut sent the header down the wire and the two backends disagreed by two floats.

## Metal: the GPU is reachable, OpenGL is not

The shared surface is the **shipped `metal` package**,
`src/main/resources/am/ik/rontolisp/eval/metal.lisp` + `eval/MetalLibrary` (todo-565,
2026-08-29). It is `webgl-common/gl.lisp`'s twin in substance -- that file imports a WebGL
context from the page, this one builds a Metal one from the `objc:` verbs -- but the two
sides are no longer symmetric in STATUS: the browser half is an example a demo copies, and
this half ships inside the interpreter and the binary, loaded lazily on the first
`metal:` resolution the way `appkit.lisp` is. It was an example (`examples/macos/metal.lisp`,
reached by `(require :metal "metal.lisp")`) until `scene` needed it: a shipped package
cannot reach an example by relative path, and four examples already shared the file, which
is the same "a second consumer fixed the API" argument that promoted the `appkit` rungs.
The consumers are `examples/macos/metal-triangle.lisp`, `metal-cube.lisp` and
`metal-robot-arm.lisp` -- the AppKit answers to `examples/browser/webgl-triangle`,
`webgl-cube` and `webgl-robot-arm` -- plus `metal-pagoda-garden.lisp`, which has no browser
twin, and the `scene` viewer (`.kb/geom.md`). All four examples use `metal` DIRECTLY, with
no `geom` and no `scene`: the promotion must not turn the surface into a private detail of
the viewer.
They add no Java: Metal is an Objective-C API almost end to end, so `objc:send` drives all
of it.

What the promotion froze, and what it costs:

- **The exported names are the decisions a CALLER makes.** `attach` / `device` / `layer` /
  `queue` / `library` / `pipeline` / `depth-state` / `floats` / `buffer` / `shared-buffer`
  / `upload` / `uniform` / `frame` / `run` / `resize` / `set-clear-color`, the class
  `metal:context`, and eleven enum members: the primitive (`+point+` `+line+` `+triangle+`
  `+triangle-strip+`), the cull mode, the winding and the depth comparison. The pixel
  formats, load/store actions, blend factors and storage modes stayed INTERNAL
  (`metal::+bgra8-unorm+` and friends) because `attach`/`pipeline`/`frame` own them; a
  caller never picks one. `+line+` = `MTLPrimitiveTypeLine` is new with the promotion --
  every renderer that draws a grid or a wireframe needs it, and the spike had defined it
  locally.
- **The context is a CLOS class, not the hash table the example used.** The type is public
  now, so its slots have to be the ones it means to promise; `(setf (gethash 'clear ctx))`
  was part of the surface by accident and is `metal:set-clear-color` instead. `resize` is
  the other addition: a resizable window needs the layer's frame, its drawable size and a
  fresh depth texture, and only this file knows all three.
- Its splice runs BEFORE `AppKitLibrary`'s in `CompileFrontend`, because `metal:run`'s
  clock is `appkit:timer`; the interpreter reaches the same thing lazily.
  `LibraryDefunPruner` keys it by name, so a program using `metal:frame` does not carry
  `metal:depth-state` (`MetalLibraryTest`).
- **Its selectors are now the shipped layer's**, so the closed native-image table applies
  to all of them: `ObjcNativeImageForeignConfigTest`'s `SENT` list carries the depth
  texture and depth-stencil descriptors, the blend setters, `newBufferWithLength:options:`,
  `getBytes:length:`, `contents`, `setFragmentBytes:length:atIndex:` and the render-pass
  depth attachment, which the robot arm and the garden sent as examples and nothing pinned.

- **OpenGL cannot be reached and never will be.** `glClear` / `glDrawArrays` are plain C
  functions, outside `objc_msgSend` entirely; `objc:` binds no C entry points. Deprecated
  on macOS since 10.14 besides.
- The one C function Metal appears to need, `MTLCreateSystemDefaultDevice()`, is
  avoidable: **`[[CAMetalLayer layer] preferredDevice]` is a property** and answers the
  same device. This is the fact the whole thing stands on -- without it `am.ik.gpu`'s
  `MetalDriver` would have had to be reached from `eval`, which the package graph forbids.
- Shaders compile at run time from a Lisp string, `newLibraryWithSource:options:error:`.
- The drawing surface is a `CAMetalLayer` set on `appkit:window`'s `contentView`
  (`setLayer:` BEFORE `setWantsLayer:`, or AppKit makes its own layer first and the one
  handed over never becomes the backing store), and the frame loop is `appkit:timer` --
  an `NSTimer` on thread 0, which is where Metal wants the frame anyway.
- The cube needs no depth attachment: a cube is CONVEX, so back-face culling alone leaves
  exactly the visible faces. The robot arm is not, so it asks `metal:attach` for one
  (`:depth t`) -- a private `Depth32Float` texture the size of the drawable that the pass
  clears and every pipeline drawing into it must DECLARE, which is why `metal:pipeline`
  reads the format off the context rather than taking it from the caller. Its glow pass is
  the other half of the same seam: `metal:pipeline :blend t` (additive, one + one) plus a
  `metal:depth-state :writes nil`, so a sprite is hidden by the machine and not by another
  sprite.
- **The arm's geometry is rewritten every frame**, which `metal:buffer` (copy once, never
  change) cannot serve: `metal:shared-buffer` allocates and `metal:upload` memcpys into
  `contents` through `-[NSData getBytes:length:]`. The CPU writes next frame's vertices
  while the GPU may still be reading this frame's, so the program rotates three copies --
  the standard Metal answer, and the one thing WebGL hid from the browser twin.
- **The mouse is `objc:define-class`**, not a rung of `appkit`: an `NSView` subclass whose
  `mouseDown:` / `mouseDragged:` / `mouseUp:` / `scrollWheel:` / `acceptsFirstMouse:` are
  Lisp closures, set as the window's content view BEFORE `metal:attach` puts the layer on
  it, so the drawing surface and the input surface are one view. Every one of those
  selectors is declared by NSView, so the encoding is read off the superclass and all of
  them land on the supported `v@:@` / `B@:@` shapes; `locationInWindow` answers an NSPoint
  (a struct return, a flat list) and `scrollingDeltaY` a double.
- Neither GPU example holds a coordinate in a scalar variable, and the arm is where that
  pays: a point, a direction, a colour and a camera axis are packed single-float
  3-vectors, the joint chain is a rank-2 (joint xyz) array, and combining them is a
  `linalg` call -- which is what lets the look-at (`[R | -R.eye]`, two
  `linalg:concatenate`s over a `linalg:matmul`), the position Jacobian (a skew matrix per
  joint) and the damped normal equations read as the matrix expressions they are. The ONE
  place that stays scalar is the innermost tessellation loop, which writes six floats
  straight into the vertex array several thousand times a frame; a fresh 3-vector per
  corner would be an allocation per float written.
- The cube's MVP matrix is `linalg`'s, not hand-written arithmetic, and it reaches the GPU
  with NO conversion: a linalg result is a packed float array and `objc:data` takes one of
  any rank, so `linalg:matmul` -> `objc:data` -> `setVertexBytes:` is the whole path.
  `:element-type 'single-float` picks float32 (a Metal `float4x4`) and every linalg
  transform preserves the width (`.kb/linalg.md`). One `linalg:transpose` bridges
  row-major storage to Metal's column-major `float4x4`.

`metal-pagoda-garden.lisp` (2026-08-27) is the scene-scale one: a voxel garden of ~13,000
cubes, three pipelines, and four things that move. Four facts are worth keeping.

- **The whole scene is ONE cube.** The vertex function takes `id / 36` for the voxel and
  `id % 36` for the corner out of a 36-entry `constant` array, so a 13,000-voxel scene is
  ONE `drawPrimitives:vertexStart:vertexCount:` over a buffer of 13,000 32-byte records --
  no vertex descriptor, no index buffer, and deliberately no `...instanceCount:`, whose
  four-integer shape is not in the closed objc_msgSend table above (which this example
  therefore leaves unchanged). `(id % 36) / 6` is the face, so the normal is exact and the
  per-face key voxel art wants is free.
- **A point sprite carries ONE depth, the centre's.** A glow emitted where its own stone
  lantern is loses the depth test to the lantern and never appears; the fix is `sp-near`,
  which nudges such a sprite a couple of voxels toward the eye. Found the hard way -- the
  lantern halos, the lit windows and the spire's jewel were all invisible until it existed.
- **The sky has to work BELOW the horizon.** The camera looks down on the garden by more
  than its own half-FOV, so every pixel of visible "sky" is under the horizon; the first cut
  put its clouds, stars and moon in `dir.y > 0` and rendered two thirds of the window one
  flat colour. The gradient is now symmetric in `abs(dir.y)`, and the lower half is the sea
  of cloud the island floats over -- which is also what makes it read as floating.
- **A procedural hash must stay where `fract` still has bits.** The star field's first hash
  multiplied its way past 10^6, where float32 leaves `fract` four distinct answers, and the
  stars switched off in whole directions of the sky depending on where the camera pointed.
  Both hashes are Hoskins-style now and stay under ~10^4.

The scene draws no random number from the machine: one 32-bit LCG seeded by a constant
builds it, so the same garden grows on the interpreter, the native binary and the compiled
JVM class -- checked by eye against all three.

Every Metal object a program holds is PROTOCOL-typed (`id<MTLDevice>`), and the concrete
class is private and machine-specific (`AGXG16CDevice` here), which is why
`ObjcNativeImageForeignConfigTest` grew a `proto(...)` row that resolves through
`protocol_getMethodDescription`. Metal's DESCRIPTOR classes are the other way round --
`MTLRenderPipelineDescriptor` is abstract in public and `alloc` answers
`MTLRenderPipelineDescriptorInternal`, which is where the properties are declared -- so
the test falls back to the `...Internal` name for an instance row the public class does not
declare. Only two shapes were outside the census:
`newBufferWithBytes:length:options:` and `drawPrimitives:vertexStart:vertexCount:`.

No test opens a Metal window either, but unlike AppKit the rendering can be checked with
no display at all: render into an offscreen `MTLTexture` (storage mode shared, usage
render-target), `getBytes:bytesPerRow:fromRegion:mipmapLevel:` into an `objc:data` block
and read the pixels back with `objc:bytes` -- which is how the triangle and the cube were
verified here, and the shortest demonstration that the two new verbs are the round trip
they claim to be.

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
**The gate is the nine verbs**, qualified, and `appkit.lisp` reaches them: `AppKitLibrary
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
| `objc:data` / `objc:bytes` round trip and the `:error` slot, interpreted and compiled -- the same expectations byte for byte | `eval/ObjcInteropTest`, `codegen/jvm/JvmObjcInteropCompilerTest` |
| the library defines exactly the registry's exports, lazy load, the WASM refusal and the JVM splice | `eval/AppKitLibraryTest` |
| the verbs compiled to a class (the interpreter cases, mirrored), the embedded class list, one file per template | `codegen/jvm/JvmObjcInteropCompilerTest` |
| `eval -> am.ik.objc`, and the library imports nothing | `PackageCycleTest` |

A window is never opened by a test (CI has no display; a doc `lisp` fence that opened one
would hang `DocExamplesTest`, so the guide uses `console` fences). The visible loop --
window, click, label mutated by Lisp, close survived, on `java -jar` AND the native binary
-- is verified by hand with `examples/macos/counter.lisp`, and the widened surface
(a run-time NSBox/NSTextField subclass whose `mouseDown:`/`rightMouseDown:` are Lisp
functions, an `NSTimer`) with `examples/browser/minesweeper/minesweeper-macos.lisp` and
`examples/macos/life-macos.lisp` -- the latter on all three (`java -jar`, the native
binary, `-o Life.class`), which is what a change to the widget layer costs, since it now
travels into every compiled `appkit:` program. The menu bar is
`examples/macos/menubar.lisp` on the same three, and the part of it that needs no eyes --
the item's title read back, the closure fired through the item's own target/action, the
separator, the key equivalent, the accessory policy -- is a script anyone can re-run
without clicking anything;
the todo's probes stay under
`.todo/512-*/` for re-running the mechanism on another Mac.

## Open items

- No MAIN menu (a process with no bundle sets none), so no Cmd-Q on a windowed program;
  an app delegate is one `objc:define-class` away and not written. The STATUS bar is
  served -- `appkit:status-item` -- and a status menu's own key equivalent works, which is
  where `appkit:quit` is reached from. With the event loop running the process is a
  foreground application unless `:dock nil` asked for the accessory policy: it activates,
  takes focus and appears in the app switcher.
- Callback shapes with struct or integer arguments, and block-taking selectors.
- A VARIADIC selector is the one hole in "never a crash" (todo-516): the encoding does not
  mark it, so `arrayWithObjects:` is bound as `@@:@`, the nil terminator lands in a
  register the callee never reads, and the process dies in `objc_retain`. Refuse the known
  set by name the way blocks are refused, then serve them with
  `Linker.Option.firstVariadicArg`.
- x86_64: `objc_msgSend_stret` is selected for a struct return wider than 16 bytes and
  has not been exercised.
