# `objc:` and `appkit:`: a native macOS window from the REPL, through FFM

- **`objc`** binds the Objective-C runtime and AppKit through `java.lang.foreign` -- `am.ik.objc`,
  a language-independent library beside `am.ik.gpu`; `java:` ([java-interop.md](java-interop.md))
  minus reflection. Vocabulary: LispWorks 8.1's `OBJC` / `COCOA` and the part of its `FLI` the
  manual's examples use, plus this package's own `on-main`, `data`, `bytes`, `objectp`, blocks,
  exceptions and `invoke-with-error`.
- **`appkit`** (`appkit.lisp`), **`metal`** (`eval/metal.lisp`) and **`scene`** ([geom.md](geom.md))
  are Lisp over `objc`, shipped like `linalg.lisp`. User docs: `doc/{en,ja}/guides/objc-appkit.md`.
  `examples.yaml` (`os: [mac]` gates only RUN legs) holds only the `examples/macos` programs that
  open no window (`objc-runtime`, `system-frameworks`, `audio`).

**Scope**: macOS only -- interpreter, JVM class output, `--native` for `macos-aarch64`. Every other
WASM output REFUSES a program referencing a macOS package (`CompileFrontend`, after load inlining),
permanently: no WASM runtime offers FFM or AppKit. Without the runtime a call SIGNALS. Why it
exists: the native binary is the REPL people run, and `java:` cannot be interpreted there (no
reflection metadata); FFM needs none.

## Layers: one semantics in Lisp over thin per-backend primitives
`eval/objc.lisp` is the WHOLE vocabulary (conversion, ownership, pools, encoding parser, variadic
table, `cocoa:`, `fli:`, exceptions) on every target: the interpreter loads it on the first
resolution of an `objc:` / `cocoa:` name; the compile path splices it (`ObjcLibrary.process`, right
OUTSIDE `AppKitLibrary`). `objc-macros.lisp`, `objc-class.lisp` (class half) and `objc-block.lisp`
(block half) are spliced only when referenced. Under it, per backend, the primitive layer
(`LispNames.OBJC_PRIMITIVES`): interpreter `eval/ObjcPrimitives` over `am.ik.objc`; JVM class
output `codegen/jvm/JvmObjcPrimitivesTemplate` (ships as `<Program>$ObjcPrimitives`); `--native`
`eval/objc-native-primitives.lisp` over the `rlobjc` `p_*` imports (`runner/src/objc/prim.rs`).
**What differs between them is the value representation, never a rule.**

### `%send` is the whole call
- A Lisp string where the encoding says `@` (autoreleased `NSString`) or `*` (C string) is made
  INSIDE the hop, alive for the call (the manual's "released when the function returns"); a `*`
  result is read into a Lisp string inside the hop (Foundation frees a `UTF8String` with its pool).
- **Every `%send` runs in its own autorelease pool on thread 0** (interp/JVM: `MainThread.sync`
  plus push/pop; `--native`: the module is on thread 0, the host pushes/pops). An autoreleased
  result is dead when the send returns unless the hop retained it (mode bits). This hop is why
  ownership cannot be LispWorks' raw retain/release: LispWorks sends on the calling thread, whose
  pool outlives the call.

### What `objc.lisp` decides
- A PLAN per (lookup class, method name) caches parsed encoding, SEL, mode and ARC family
  (`objc::%lookup-plan`; lookup class = `object_getClass`, so a class receiver finds class methods
  via its metaclass) -- an uncached family check would parse per send.
- A block (`@?`) or function pointer (`^?`) is re-spelled `^v` before `%send` so every host parser
  takes it.
- An integer argument past 2^63 travels as its two's complement (`objc::%bits64`: a wasm `:s64`
  import traps on a bignum); unsigned 64-bit results are fixed up from the parsed type.
- **Variadic selectors are a TABLE OF NAMES**, `objc::*variadic-selectors*`: a variadic selector is
  encoded byte for byte like its fixed twin (`arrayWithObjects:` / `arrayWithObject:` are both
  `@@:@`), and on arm64 a variadic argument goes on the STACK -- sent through the declared shape the
  callee walks its `va_list` off a slot nobody wrote (SIGSEGV in `objc_retain`). A listed selector
  types arguments past the declared arity by VALUE (object / `q` / `d`) and passes the split
  (`Linker.Option.firstVariadicArg`). A list-form call says `:variadic-num-of-fixed`; its variadic
  arguments are promoted and widened to 64-bit slots (ABI-equivalent on both macOS ABIs).
- **The appended nil terminator is unconditional**: the nil-terminated half needs it and a
  `printf`-style callee never reads past its format -- one rule, and the last variadic argument is
  always `void*`, which bounds the native binary's variadic grid.
- **`objc::%checked` wraps EVERY call of `%send` / `%send-super` / `%call-function`** ("Exceptions").

## Traps
- `objc:with-autorelease-pool` must be registered in `LispMacroExpander.expandBuiltinMacro` AND
  walked in both `FreeVarAnalyzer` walks, or a JVM compile of a body naming an outer variable dies
  with "closure over X whose binding left it unboxed".
- The interpreter decides a `print`'s routing BEFORE its argument runs, while the argument is what
  loads `objc.lisp` and its `print-object` methods: the print site loads the library first when the
  form names it (`ObjcLibrary.references`), as for torch and geom.
- A pointer prints the address only (`#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x...>`): asking its
  class SIGSEGVs when a refused release's message prints the freed object.
- A defstruct's `:conc-name` accessors are interned in the STRUCTURE NAME's package
  (`fli::%pointer-...`, not `objc::`).
- A defun in `objc-class.lisp` must not reuse a primitive's name (`ObjcClassTest`).
- A new call site of `%send` / `%send-super` / `%call-function` must go through `%checked`, or a
  raise reads as nil and its reference leaks. `%checked` runs only on a nil answer and its slot flag
  is required: an `&optional` flag checked per answer would test per send.
- `fboundp` loads no library: `(fboundp 'objc:invoke)` is nil in a fresh interpreter until an
  `objc:` name was resolved (likewise `appkit:`, `linalg:`).
- Mutable state lives in the handle, never a struct slot: `equal` / `equalp` hash tables hash an
  instance by its slots on the interpreter and the JVM.
- A window needs `setReleasedWhenClosed:` NO (`appkit:window` does), or closing releases a
  reference the pointer's `gc` count holds. Leaking is the safe direction everywhere here.
- `JvmObjcPrimitivesTemplate` is ONE class file: no nested class, no enum `switch` (it lowers to a
  synthetic `$1` the builder does not ship).

## One representation, equal by address
`objc:objc-object-pointer` is a defstruct (address + handle) in `objc.lisp` on all four targets;
`objc:objc-class` includes it, `objc:sel` is its own; none is a `structure-object`
(`LispMacroExpander.FOREIGN_POINTER_STRUCT_TAGS`). Classes and selectors are immortal, interned per
address / name in Lisp tables.

- Interpreter / JVM: object pointers are INTERNED per address in a weak table (`ObjcReference`), so
  `eq` holds by identity. Every answer adds a `gc` reference to the one live value (harmless:
  nothing is released early).
- `--native`: no weak references in wasm-GC, so each answer is its own value with one reference,
  compared by the address slot: `WasmLispCompiler.addressKeyedLayout` makes `_eql_tail`, `_equal`,
  `_hash` and `_ihash` compare/hash such an instance by its first slot alone. A module without the
  layout is byte-identical.
- So over-releasing is refused at a different call per target; a program releasing what it owns
  sees the same `retainCount` deltas everywhere (what the corpus prints).
- A pointer is a hash key (`eql` on every target); there is no address accessor. The shipped layers
  keep per-object state in a slot of a class they define ("appkit").

## Ownership
Each `objc-object-pointer` carries a HANDLE (interp/JVM `ObjcReference`; `--native` an `externref`
whose host data holds the counts) with three counts:

- `gc` -- released on thread 0 when the handle is collected. An `@` result arrives with one:
  retained in the hop, or taken over from the `alloc` / `new` / `copy` / `mutableCopy` families
  (ARC's word rule). The `init` family CONSUMES the receiver's reference (one `gc`, else one
  `manual`) and answers +1.
- `manual` -- `objc:retain`; NOT released at collection (how a program keeps a delegate alive after
  dropping the pointer, as in LispWorks).
- `pooled` -- references an emulated pool holds.
- A class pointer owns nothing.

`objc:release` gives up one `manual`, else one `gc`; holding neither SIGNALS instead of
over-releasing.

**Pools are emulated in Lisp**: a real `NSAutoreleasePool` pushed in one hop and popped in another
would interleave with thread 0's own loop pools.

## FLI: foreign objects and pointers
`fli:` lives in `objc.lisp` over existing primitives (`calloc` / `free` through `%symbol-address` +
`%call-function`, access through `%peek` / `%poke`). No `rlobjc` import of its own, so `--native`
allocates in the runner's heap (never linear memory) as the interpreter and JVM do.
`with-dynamic-foreign-objects` allocates on the heap (LispWorks: the stack) and frees on every exit.

- **A foreign pointer is `fli::pointer`** carrying its pointee's ELEMENT, laid out once
  (`objc::%element-type`). Not interned, not `eql` by address -- `fli:pointer-eq` is.
- **One element per FLI type** in `objc::*elements*`, REPLACED on a miss, never changed in place (a
  render block reads it on the audio thread), dropped by `define-objc-struct` / `-typedef`
  (`ObjcClassTest#aRedefinedStructureIsLaidOutAgain`). An access is then arithmetic plus one
  `%peek` / `%poke`.
- **Every pointer Objective-C hands Lisp is one**, typed by its declaration; the byte primitives
  take an integer (`fli:pointer-address`). A structure ARGUMENT of a method or block still arrives as
  its Lisp value (vector / cons).
- **Layout is the C rule over named slots** (`objc::%fli-layout`); `fli:size-of` answers the
  recorded LispWorks sizes. A PARSED structure is laid out by the same rule over its NESTED members
  on every host (`TypeEncoding.Type`, `encoding.rs`, `objc::%type-size`): `{Outer={Inner=dc}c}` is
  24 bytes with the tail at 16, `{O=c{I=cd}}` puts the inner `c` at 8. The VALUE stays the leaves in
  memory order. The JVM hands FFM the flat leaves when they sit where natural alignment puts them
  (every AppKit struct, so the closed table's spellings hold) and the nested layout otherwise -- the
  linker refuses padding alignment does not call for. A spelled shape includes its padding
  (`padding(7)`): the image builder's `structLayout` refuses a member off its alignment.
- Refused, never a crash: a null pointer, a `:void` pointee without `:type`, an aggregate
  dereferenced without `:copy-foreign-object`, a Lisp string or vector stored where an object goes
  (it would not outlive the store).
- **Not `ffi:`'s pointer**: `ffi` is interpreter/JVM only (a Java `LispForeignPointer`), `fli` must
  run on `--native`. The bridge is the integer (`fli:pointer-address`).

## Bytes: `objc:data`, `objc:bytes`
`objc:data` answers an **`NSMutableData`** (one value serves `bytes` for a `^v` parameter and
`mutableBytes` as scratch) with a packed buffer's bytes as `write-sequence` writes them
([binary-sequence-io.md](binary-sequence-io.md)) or a string's UTF-8; `objc:bytes` answers a fresh
`(unsigned-byte 8)` vector. Lisp over `%octets` (the representation's question, so per backend) and
`%write-octets` / `%read-octets`.

- JVM trap (`JvmObjcPrimitivesTemplate.bufferBytes`): **a packed float array carries its dimension
  header IN the array** (`[rank, dim_0..., e_0...]`), a packed integer vector is the bare `byte[]`
  (copied) or `long[]{width, e_0, ...}` -- only the elements go on the wire.
- `--native`: Lisp (`%ieee754-single-bits`); bfloat16 and quantized refused.

## Class definition
- **`objc-macros.lisp`**: pure `cl`, types QUOTED (conversion decided per call). **The compile path
  expands user macros BEFORE it splices libraries**, so `ObjcLibrary.withMacros` puts these
  `defmacro`s in front of `UserMacroExpander`; the interpreter loads the library when a call names
  one (`definesMacro`). `mentionsType` counts `objc:standard-objc-object` (the macro-time evaluator
  runs the expansion's `defclass`). A method body is ONE argument list `(lambda (%current-super
  object pointer result args) ...)`: a wasm lambda takes at most ten parameters.
- **A shipped library that defines classes** (`appkit.lisp`, `scene.lisp`) is spliced AFTER that
  expansion, so its `process` splices `ObjcLibrary.expandDefinitions` of its forms (cached).
- **`objc-class.lisp`** is spliced only when the program names one of its definitions or
  `standard-objc-object` (`referencesClassHalf`), so a calling-only program carries no CLOS init
  protocol. It plugs into `objc.lisp` through hooks, so `objc.lisp` never names it.
- **`%add-method`**: the host's IMP calls the function with the RAW arguments (an object argument
  arrives RETAINED); flags by ARC family (`%result-flags`). Root methods pass 0: `+allocWithZone:`
  answers the super call's +1 as is.
- **One IMP per METHOD, never per shape**: a super send runs the superclass's IMP for a receiver
  whose class defines the same selector, so a (receiver class, selector) lookup would recurse.
- JVM / interpreter: `am.ik.objc.ObjcMethods` binds `dispatch` once (a CONSTANT `findStatic`) and
  adapts it per method -- any shape under `java`; the native binary's upcall table: "The native
  binary".
- `--native` (`runner/src/objc/class.rs`): the IMP is `imp_implementationWithBlock` over a GLOBAL
  block whose invoke is the assembly `rl_objc_block_imp`: libobjc's trampoline leaves the argument
  registers and stack as the caller laid them, so ONE entry serves every shape (`Reader` re-applies
  `call.rs`'s classification). The pending arguments of the send in progress are saved around the
  `rlobjc_method` re-entry.
- **A class the process defined is marked, not remembered**: every class pair gets the ivar
  `rontolispDefinedClass`, so a later definition of the name -- another interpreter in the JVM, a
  compiled program's renamed copy of `am.ik.objc`, a re-evaluated form -- reuses it (replacing
  methods) while a class Lisp did not define is refused. A host table would not cross those copies;
  the runtime cannot remove a class.
- **Definitions wait for the runtime**: `%ready` realizes queued classes in definition order and
  installs each method on its class and every subclass of a mixin ancestor.

### `standard-objc-object`, ownership, `objc-object-destroyed`
- `+allocWithZone:` (a root method on each Lisp-defined hierarchy) makes or ADOPTS the Lisp object;
  `*adopting*` is a global value, not a binding (the method runs on thread 0).
- `*lisp-objects*` (address -> instance) is STRONG (LispWorks' rule): the Lisp object lives until
  the count reaches zero, when `-dealloc` runs `objc-object-destroyed`. `make-instance`'s reference
  is the program's to `objc:release`. **The `Cleaner` cannot run first**: the value is reachable
  from the registered instance until `-dealloc`.
- **A registered object has one pointer value on every target** (`%live-pointer` asks the intern
  table, then the registered-pointer hook), so on `--native` too an answer adds its count to the
  value `release` later gives up.
- A method's receiver is BORROWED, never retained -- `-dealloc` runs on a receiver no one may
  retain.

### A Lisp error, a `throw`, an exit inside a callback (methods and blocks, every target)
- A Lisp error, an escaping Objective-C exception, or a `throw` to an outer tag is contained by
  `objc::%run-method`'s `handler-case`: printed (`objc: error in a callback: ...`) and answered as
  zero, never unwound through Objective-C's frames (on `--native` such a `throw` is a trap, contained
  the same way).
- An EXIT is not a condition: it unwinds as a host exception to the upcall guard (`ObjcMethods` /
  `ObjcBlocks`, `ProcessExit`), which ends the process with its code; `--native`: `proc_exit`.

## Blocks and C functions
LispWorks' `OBJC` has no block interface, so the block names are this package's own.

- **No implicit wrapping, decided**: an encoding says `@?`, never what the block takes (the extended
  `@?<...>` is in protocol metadata only), so a Lisp function passed where a block goes SIGNALS,
  naming `make-objc-block`. A wrong guess would be a crash.
- `objc-block.lisp` is spliced when the program names one of its definitions or `objc-block`
  (`ObjcLibrary.referencesBlockHalf`); a FREED block signals where it is used.
- **The literal** (`am.ik.objc.ObjcBlocks`, `runner/src/objc/block.rs`): the function's ID travels
  INSIDE the literal because `_Block_copy` copies it to the heap whenever a callee keeps it; IDs are
  never reused, so a block called after its function is gone is reported, never routed to another.
  A STACK isa: `_Block_copy` of a global block answers the same pointer, while `_Block_release` of
  our own storage is a no-op. The copy/dispose helpers count HOLDERS per ID; `free-objc-block` gives
  up the Lisp value's, so `with-objc-block` around a `dispatch_async` is safe. A block never freed
  leaks (no finalizer). The JVM's literal is `malloc`'d: a native image serves no shared arena
  (`Arena.ofShared().close()` throws `UnsupportedFeatureError`), and a block may be freed from any
  thread.
- **Interpreter / JVM: a block runs on the thread that calls it** (one upcall stub per SHAPE into
  `ObjcBlocks.dispatch`). A libdispatch worker runs it concurrently under the interpreter's thread
  rules ([threads.md](threads.md)). Foundation calls a comparator or enumerator on thread 0.
- **`--native`: only thread 0 enters the module.** A block arriving inside a host call on thread 0
  (`CALLER` set) re-enters through `rlobjc_method`. One arriving elsewhere: a `void` block is QUEUED
  and a no-op `dispatch_async_f` to the main queue ends a pump turn; it runs at the program's next
  `sleep`. A value-answering block there is refused: printed, zero answered.
- **C functions run on the CALLING thread** (`ObjcRuntime.callRaw`), never hopped: a function that
  waits (`dispatch_sync`, a semaphore) must not hold thread 0 while a block it waits for needs it.
  An object result follows Core Foundation's Create rule (libdispatch too): a name containing
  `Create` / `Copy` / `_create` / `_copy` hands over +1, anything else is retained in the call.

### A block on a real-time thread: `examples/macos/audio.lisp`
An `AVAudioSourceNode` whose render block (shape `jint(void*,void*,void*,jint,void*)`) is a Lisp
closure.

- **Offline** (`enableManualRenderingMode:`): AVFAudio calls the block on the sending thread --
  thread 0 on every target. The transcript is byte-identical on every target; `examples.yaml` checks
  it (no `--native` leg). A chunk larger than the buffer's `frameCapacity` fails with status -1 and
  `NSError` -50, not a short render.
- **Live** (`startAndReturnError:`): the real-time I/O thread. `--native` refuses the
  value-answering block there, so the example skips live on `:rontolisp-native`. Interpreter / JVM:
  **the block must send nothing** -- a send hops to thread 0 while thread 0 sits in
  `-[AVAudioEngine stop]` waiting for the render thread: deadlock. So samples go through `fli:`
  (`%poke`, no hop); `fli:` C calls run on the calling thread and are fine. Cost: "Measured
  decisions".

## Exceptions and NSError
Without a catching frame, `objc_exception_throw`'s unwinder reaches the FFM stub / JIT frames or
the wasm host frames, which have no unwind information, and `std::terminate` ends the process.

- **THE decision: a real `@catch (id)` frame under every call, not the uncaught-exception handler.**
  Every `objc_msgSend`, `objc_msgSendSuper` and `%call-function` target is called through a native
  frame whose call site an LSDA covers (one handler, `OBJC_EHTYPE_id`, `__objc_personality_v0`).
  Phase 2 unwinds the callee's frames WITH their cleanups (`@finally`, `@synchronized`, C++
  destructors), as Objective-C's own `@catch` would. An exception Cocoa catches deeper never reaches
  it; a C++ exception of another type passes and terminates. Rejected:
  `objc_setUncaughtExceptionHandler` plus a non-local exit abandons the callee's frames without
  cleanups, leaves libc++abi's caught-exception list stale, and runs only once the search FAILED --
  on the JVM after the JIT frames above the send (and on thread 0 `_dispatch_client_callout`'s
  catch-all, which terminates).
- The landing pad retains the exception; the host answers nil and keeps the address for
  `objc::%raised`, cleared at the start of each call so it is never misattributed.
- **The innermost call on that thread catches.** One escaping a Lisp-defined method or block is a
  callback error.
- **`invoke-with-error`** retains what the error slot holds inside the hop (the `NSError` is
  autoreleased into the pool the hop drains). Foundation's rule: the RESULT says whether a call
  failed, so it signals only when the result says failed AND an error was written.

### Interpreter and JVM class output: `am.ik.objc.ObjcCatch` writes the frame at run time
rontolisp ships no native code, and the frame must sit between the FFM stub and the callee, so
`ObjcCatch` writes AArch64 code into an `mmap`ped page made read-execute for good (never rewritten:
a page rewritten while another thread runs it faults, and HotSpot's W^X state is per thread, so
`MAP_JIT` toggling from Java would take the JIT's code cache away mid-call). `java` carries
`allow-unsigned-executable-memory`; a native image is not hardened.

- **A slot forwards the call unchanged**, copying the caller's stack argument area (at most 4032
  bytes -- a wider shape is called directly, uncaught). Callee and size sit in a DATA cell, so the
  code never changes; the handle keeps the callee's own shape, so the native binary's table serves
  it unchanged.
- **The recorder** is an upcall into the copy of `ObjcCatch` that allocated the slot (a
  `ThreadLocal` -> `ObjcRaised`); `MainThread.sync` carries it back to the Lisp thread.
- **The unwinder finds the code through `__unw_add_find_dynamic_unwind_sections` (macOS 14)**,
  answering with an `.eh_frame` and a stand-in `mach_header` as `dso_base`. **Trap:
  `__register_frame` of a dynamic FDE registers it with `dso_base` 0, and Apple's `unw_set_reg`
  reads the CPU subtype of the image a new IP lies in -- SIGSEGV at the landing pad** (macOS 26.3).
- **One chain of regions per PROCESS**: libunwind keeps a short fixed table of finders, and a JVM
  holds a copy of `am.ik.objc` per compiled program it loads -- a finder per copy ran out inside
  `JvmObjcBaseCompilerTest`. The first region is published in the system property
  `rontolisp.objc.catch` (JVM-wide, never inherited by a child), the rest chained, one finder, one
  JVM-wide lock (`System.getProperties()`). 120 slots a region, at most 16 regions; past that,
  without the finder API, or on x86_64, a call goes straight to its callee.
  `-Drontolisp.objc.catch=0` turns catching off.
- Registered for the native binary: `mmap`, `mprotect`, `sys_icache_invalidate`, the finder
  registration (`ObjcNativeImageForeignConfigTest`); shipped classes in `JvmObjcRuntimeBuilder`.

### `--native`: the catch is in `rl_objc_call` (`call.rs`)
The same LSDA in `global_asm!`. libobjc is dlopened at run time, so the stub cannot name
`__objc_personality_v0` or `OBJC_EHTYPE_id`: the personality is a forwarder and the type entry is
INDIRECT through `rl_objc_ehtype_slot`, filled by `call::install` when the runtime opens (before
that the forwarder answers "continue unwinding"). `prim.rs` answers result kind 5 (`RAISED`).

## AppKit belongs to thread 0
The thread the kernel started the process on (`pthread_main_np()` answers 1) is the only one that
may touch a window, and the Lisp thread is never it.

- `java -jar`: the launcher already parks thread 0 in a `CFRunLoop`.
- **Native binary**: `main` IS thread 0. `RontoLispCli.main` moves the CLI to a spawned thread
  ([interpreter-stack.md](interpreter-stack.md)); `ObjcInterop.mainThreadHandOverRequired()` (cheap:
  no AppKit) decides that thread 0 parks in `MainThread.runLoop()`. UNCONDITIONAL on that platform:
  thread 0 cannot be handed over later, and since the run loop never returns, the worker ends the
  process with `System.exit`.
- **Compiled `main`: the same hand-over, in bytecode** ("The JVM backend").
- `runLoop()` is the launcher's `ParkEventLoop` (a no-op `CFRunLoopSource`, `CFRunLoopRunInMode`
  re-entered). **Trap: a bare `CFRunLoopRun` returns after the first click, leaving the binary in
  `JavaMainWrapper`'s join with a beach-balled window.** `RONTOLISP_OBJC_TRACE=1` prints every hop.
- **Parking thread 0 is not enough -- AppKit must drain it.** Only `-[NSApplication run]` DEQUEUES
  events; it never returns, so `appkit::%app` has thread 0 `performSelectorOnMainThread:` it, NESTED
  inside whatever loop parks the thread. `%app` is the ONLY place that starts it: a window built
  from raw `objc:` in a process that never called an `appkit:` function answers nothing.
- **Every entry point hops** (`MainThread.sync`: `dispatch_sync_f` to the main queue through ONE
  upcall stub). **`dispatch_sync` to the queue you are draining is a deadlock**, so `sync` runs
  inline on thread 0 -- an `:on-click` handler runs there with the interpreter's GLOBAL dynamic
  bindings ([dynamic-special-variables.md](dynamic-special-variables.md)). An exception inside a
  `sync` body is rethrown on the caller's thread, so a non-local exit through `objc:on-main` works.

## The native binary: a CLOSED table of shapes
`ObjcRuntime.sendRaw` binds one `objc_msgSend` handle PER DISTINCT SHAPE -- Apple's arm64 rule;
**never through the variadic declaration: an `NSRect` through a `long` shape is a SIGBUS**. Unions,
bitfields and function pointers are refused by name.

- A native image builds a downcall stub only for a shape registered at build time, so the served set
  is a table in its own file,
  `META-INF/native-image/am.ik.rontolisp/rontolisp-objc/reachability-metadata.json`: the runtime's
  own C functions, every shape the shipped layers and documented examples send, the 60 most common
  shapes of a census over 29 core AppKit/Foundation classes, and `NSTimer`'s
  `scheduledTimerWithTimeInterval:...`. A selector outside it signals with the exact entry to add;
  the JVM registers nothing, so `java -jar` is where a program discovers what it sends. **A new
  selector in `appkit.lisp`, `metal.lisp`, `scene.lisp`, `objc.lisp`, the `examples/macos` programs
  the test names, or the docs is a row in `ObjcNativeImageForeignConfigTest`'s table.**
- **Variadic sends are a 144-entry grid** generated from a RULE the same test restates and pins in
  both directions (three fixed halves x 1-12 variadic arguments, every carrier combination up to 4,
  `void*` only past it).
- Metal objects are PROTOCOL-typed with private concrete classes, so the test has `proto(...)` rows
  (`protocol_getMethodDescription`); Metal's DESCRIPTOR classes are the reverse (`alloc` answers
  `...Internal`), so it falls back to that name.
- **Upcalls (methods, blocks) are a closed table too** (`foreign.upcalls`); any other shape is
  refused when defined / made, naming the entry (`ObjcRuntime.upcall`); the test reads the shipped
  layers' `define-objc-method` forms (`EXAMPLE_BLOCKS` for the rest). Decided over a generated grid:
  exact integer widths are part of an upcall's shape (a 32-bit argument's upper register half is
  garbage on arm64), so a grid over widths, floats and structs has no useful bound.
- Every send through the table is INTERPRETED by SubstrateVM's method-handle interpreter (a handle
  created at run time has no AOT code) ([gpu.md](gpu.md), "An FFM downcall inside a native image
  costs"; measured on Linux/aarch64). The AOT route ([native-downcalls.md](native-downcalls.md)) is
  not applied: one `@InvokeCFunctionPointer` method per SHAPE, and the send table is a generic
  per-selector shape set no macOS box in reach can measure.

## The JVM backend: the binding travels beside the class, and calls back into it
`-o Prog.class` / `-o lib.jar` uses the `--gpu` route ([gpu.md](gpu.md),
[template-class-embedding.md](template-class-embedding.md)): every class of `am.ik.objc` is renamed
(`am/ik/objc/` -> `<Program>$Objc`) and ships beside the program; `JvmObjcRuntimeBuilder` owns the
list (`JvmObjcBaseCompilerTest#theProgramShipsTheWholeLibrary`). A class a program defines is
marked, so a second copy in one JVM reuses it ("Class definition").

- **It makes UPCALLS into the program**: methods, blocks and `on-main` bodies are applied through
  `_apply`, handed over by `bind(Class)` -- so the objc runtime forces `usesEval` and roots `_apply`
  for the shaker. `_strv` too (nullable: absent exactly when the program has no array runtime).
- **The gate is the primitive layer** (`JvmObjcPrimitivesCompiler.names()`): a program whose pruned
  `objc.lisp` calls no primitive (`(objc:objectp 1)`) carries no binding.
- **The same gate adds the thread-0 hand-over to the compiled `main`** (`JvmSizedMainBuilder`,
  [interpreter-stack.md](interpreter-stack.md)): when `<Program>$ObjcMainThread.handOverRequired()`
  is true, `main` starts the worker and parks in `runLoop()`; the worker ends the process
  (`System.exit(0)`, or the throwable through the uncaught-exception handler and `System.exit(1)`).
  Under `java` the answer is false before anything native is bound (no
  `org.graalvm.nativeimage.imagecode`). Pinned by `JvmSizedMainTest` (on macOS simulated with
  `-XstartOnFirstThread` + `-Dorg.graalvm.nativeimage.imagecode=runtime`) and
  `ShippedBridgeNativeImageE2eTest#anObjcJarRunsAsANativeImageThatHandsThreadZeroToAppKit` (macOS,
  opt-in, a real image).
- A bare `.class` without `--enable-native-access=ALL-UNNAMED` gets the JDK's one-time warning; a
  `.jar` carries `Enable-Native-Access: ALL-UNNAMED` (`JvmJarWriter`).
- **An image of a compiled `objc:` program needs no configuration**: the user's jar holds none of
  rontolisp's `META-INF`, so `JvmObjcRuntimeBuilder` ships, per program, a verbatim copy of the
  `rontolisp-objc` file and `rontolisp-objc-bridge/<program>/` (reflection for `_apply`, `_strv`).
  Without the first every send refuses its stub; without `_apply`: `objc: no _apply method`;
  without `_strv` the first string the program BUILT dies in `MissingReflectionRegistrationError`.
  That file stands ALONE, so it repeats shapes shared with the main file;
  `ObjcNativeImageForeignConfigTest` checks every layer against it alone
  (`NativeImageDowncalls.OBJC`), `ShippedBridgeClassFilesTest` pins the shipped bytes.

## `--native`: the runner is the Objective-C host
`--native -o prog` (`macos-aarch64` only, `NativeTarget.OBJC_PLATFORM`): `ObjcNativeLibrary` splices
`objc-native-primitives.lisp` (over `rlobjc` `p_*` imports) right OUTSIDE `ObjcLibrary`; the runner
(`rontolisp-native/runner/src/objc`) answers them over libobjc/AppKit, dlopened on the first
`rlobjc` call (an output that makes none starts as before).

- **THE decision: the module runs ON thread 0.** A wasmtime `Store` is not `Sync`, and a callback
  arriving on thread 0 while the module ran elsewhere could not enter it (wasmtime walks only the
  CURRENT thread's activations for GC roots). So `objc:on-main` is a plain call, and a callback
  arrives INSIDE a host call -- a send, or `pump` -- and re-enters through that call's `Caller`
  (`CALLER` thread-local).
- **`sleep` is the event loop.** Every `sleep` of such a program compiles to `objc::%sleep`
  (`WasmExprCompiler`, `LispNames.OBJC_SLEEP_INTERNAL`) -> `p_pump`. `appkit:wait`'s 50 ms poll
  keeps a window live; a blocking stdin read freezes it.
- **A fetch's wait turns the event loop too** ([fetch-http.md](fetch-http.md), "--native"): once
  the application started, the wait runs `pump` in 20 ms turns (`objc::pump_until` from
  `http::wait`); before that it blocks on its condition variable.
- **`-[NSApplication run]` is never started** (it never returns; the thread is the module's).
  `appkit::%app`'s `performSelectorOnMainThread:` of `run` is answered by marking the application
  started -- the one place the runner reads a selector's meaning (`prim.rs`); `appkit.lisp` is
  unchanged.
- **Sends: one generic import, no shape table.** The host marshals by the encoding (`encoding.rs`,
  the twin of `TypeEncoding`, same refusals and messages) and calls through `rl_objc_call`
  (`call.rs`, Apple's AArch64 classification in assembly) -- so EVERY parseable selector is
  callable.
- **Ownership through `:extern`.** wasm-GC has no finalizer, but wasmtime's collector DROPS an
  `externref`'s host data when the reference dies (`sweep_extern_refs`, 49.0.0). A handle is the
  externref `p_new_handle` returns; its drop queues the release of the `gc` share. The queue runs
  only from the OUTERMOST host call, never inside a callback (it could free the object whose method
  is running), nor between a send's argument pushes.
- Clicks are checkable without a hand by posting `NSEvent mouseEventWithType:...` down/up pairs
  through `postEvent:atStart:`. A human click on `counter.lisp` is still the GUI rule's check.

## appkit: where the line goes (widgets ship, layout stays an example)
Rungs (`doc/**/reference/functions/appkit*.md`) are `appkit:` functions, not a second built-in
package: a package name is taken for good (`cocoa` is LispWorks'; the examples' grid package is
`board`). LAYOUT stays out (`examples/macos/board.lisp`).

- Two rungs no obvious `objc:invoke` reaches: a centred label needs the font's line height MEASURED
  (`appkit::%line-height`, a throwaway `sizeToFit` field per font, cached), and a clickable view
  needs a subclass (`appkit::panel-view` / `appkit::label-view` over the mixin `appkit::clickable`;
  a panel and its label share one handler with no event forwarding).
- **Per-object state lives in a Lisp class, not an address-keyed table**: a control's target is an
  `appkit::action` (AppKit holds a target weakly; the Lisp object keeps it, since `make-instance`'s
  reference is never given up), a timer's an `appkit::ticker`.
- `set-text` / `text` test for the status item AHEAD of the button test; a menu-bar program has no
  window, so `quit` sends `terminate:`. `on-click`'s handler takes the BUTTON NUMBER (the
  `java.awt.event` numbers); a button's own `:on-click` closure takes none.
- A rung costs a `PackageRegistry.APPKIT_FUNCTIONS` entry (agreeing EXACTLY with the library,
  `AppKitLibraryTest`), a per-operator page + `_catalog.yaml` entry + guide-table row in BOTH
  languages, and blob growth: `AppKitLibrary.process` prepends the whole library, UNPRUNED, with the
  class half of `objc`, to every compiled `appkit:` program.

## Metal
`metal.lisp` is `webgl-common/gl.lisp`'s twin in substance; no Java (Metal is Objective-C end to
end). Pixel formats, load/store actions, blend factors and storage modes stay INTERNAL.

- Its splice runs BEFORE `AppKitLibrary`'s (`metal:run`'s clock is `appkit:timer`);
  `LibraryDefunPruner` keys it by name (`MetalLibraryTest`).
- **OpenGL cannot be reached**: `glClear` / `glDrawArrays` are plain C functions outside
  `objc_msgSend`. `MTLCreateSystemDefaultDevice()` is avoided -- **`[[CAMetalLayer layer]
  preferredDevice]` is a property** -- else `am.ik.gpu`'s `MetalDriver` would have to be reached
  from `eval`, which the package graph forbids.
- **`setLayer:` BEFORE `setWantsLayer:`**, or AppKit makes its own layer and the one handed over
  never backs the view.
- `metal:attach :depth t`'s depth texture must be DECLARED by every pipeline drawing into it, so
  `metal:pipeline` reads the format off the context. Per-frame geometry rotates THREE shared buffers.
- **The per-frame byte paths skip the NSData**: `metal:upload` / `metal:uniform` write through
  `objc::%octets` / `objc::%write-octets` -- one send where the NSData route took five.
- **The mouse is `objc:define-objc-class`**: an `NSView` subclass set as the content view BEFORE
  `metal:attach` puts the layer on it.
- A `linalg` result reaches the GPU with NO conversion (float32 preserved, [linalg.md](linalg.md));
  one `linalg:transpose` bridges row-major storage to Metal's column-major `float4x4`.
- Checkable with NO display: `metal:offscreen` / `metal:pixels`; `metal:frame` takes the drawable's
  texture or the context's own, so there is ONE encoding path ([geom.md](geom.md)).
- **A frame that signals**: an encoder released without `endEncoding` is a Metal ASSERTION
  (`abort()`, below any handler). `metal:frame` ends, presents and commits from an `unwind-protect`
  cleanup (`SceneOffscreenRenderTest.aFrameWhoseBodySignalsIsStillEndedAndCommitted`). **General
  rule: a callback touching a native object with a begin/end protocol closes it on the signalling
  path too.**

## Package rules and the web build
`am.ik.objc -> (nothing)`; `eval -> am.ik.objc` through ONE class, `eval/ObjcPrimitives`, reached
only via `eval/ObjcInterop`'s entry points (the `LinalgGpu` shape), so
`src/web/java/.../Target_ObjcInterop.java` substitutes `ObjcInterop.registerPrimitives` and the
browser build carries no FFM. `cli` reaches the hand-over through `ObjcInterop`. `MetalDriver` is
the same runtime through a hand-written shape table and could ride on `am.ik.objc`; it does not yet.

## Measured decisions
- **Per send**, the single Lisp layer beat a per-backend Java bridge: no per-frame loop is near a
  send's cost, and the per-frame paths that would be (`metal:uniform`, `metal:upload`) copy straight
  into foreign memory. Compiled, the Lisp layer is lost in the hop; interpreted, `objc.lisp` is a
  real share of a send.
- **The catching frame** costs nothing measurable.
- **`audio.lisp` live**: the deadline is ~22.7 us a sample at 44.1 kHz. Every interpreted and
  compiled target renders in real time (the pointer carries its element, "FLI";
  [lambda-lists.md](lambda-lists.md), [interpreter-expansion-memo.md](interpreter-expansion-memo.md)).
  What remains is the evaluator's per-form work. Measured and not taken: `%peek` / `%poke` by kind
  code instead of encoding -- not worth three hosts' primitive layers.

## Tests
Corpora (each against its `.expected`, interpreter / JVM class / `--native` byte for byte, nothing
address-dependent; methods use registered shapes so the native binary runs them):
`src/test/resources/objc-base-corpus.lisp` (`objc-base-corpus.expected`; incl. "on-main
propagates an error"), `objc-class-corpus.lisp` (a tail-padded nested structure through a Lisp
method `nested:` by `NSInvocation`, whose C-side call pins the layout), `objc-block-corpus.lisp` (`--native`: `objc-block-corpus-native.expected`,
differing exactly on the thread a block ran on and whether a `dispatch_async` ran before the wait),
`objc-exception-corpus.lisp`.

- `eval/ObjcBaseTest`; `ObjcBaseTest#theRecordedLispWorksAnswersHold` over
  `src/test/resources/objc-lispworks-answers.lisp` (recorded by hand from LispWorks Personal 8.1.2
  arm64, unscriptable): `invoke` answers 1/0 for a `BOOL` even encoded `B`; Foundation structures
  are doubles and 64-bit integers; `objc-class-method-signature` names `(:struct cocoa:ns-range)`
  and prefers the instance method; a missing method is a `simple-error` naming the RUNTIME class
  (`No method "x" for object #<Pointer: ...>, class "__NSCFString".`).
- `eval/ObjcClassTest`, `ObjcBlockTest`, `ObjcExceptionTest` (each also signals off-Mac),
  `eval/ObjcLibraryTest`.
- `codegen/jvm/JvmObjcBaseCompilerTest` -- the corpora compiled, the embedded class list, half
  gating, the widget layer's gate.
- `codegen/jvm/JvmSizedMainTest`, opt-in `e2e/ShippedBridgeNativeImageE2eTest`,
  `ShippedBridgeClassFilesTest` -- the compiled hand-over and the shipped registrations.
- `am.ik.objc.TypeEncodingTest`; `am.ik.objc.ObjcNativeImageForeignConfigTest` (downcall table,
  variadic grid, upcall shapes incl.
  `ObjcNativeImageForeignConfigTest#everyShapeTheDocumentedBlockExamplesUseIsRegistered` and
  `EXAMPLE_BLOCKS`, the `ObjcCatch` functions, every selector the corpus, layers, examples and docs
  send).
- `eval/AppKitLibraryTest` / `eval/MetalLibraryTest` / `eval/SceneLibraryTest`;
  `SceneOffscreenRenderTest`; `PackageCycleTest`.
- `--native`: `eval/ObjcNativeLibraryTest`; `e2e/NativeObjcE2eTest` (macOS aarch64; incl. 300,000
  answers of one object leave < 100,000 references); the runner's `call.rs` / `encoding.rs` unit tests (`build.sh --test`),
  incl. `an_objective_c_exception_stops_at_the_call`.
- No test opens a window (CI has no display; the guide uses `console` fences so `DocExamplesTest`
  cannot hang). By hand only: the windowed `examples/macos` programs (`counter.lisp`,
  `minesweeper-macos.lisp`, `life-macos.lisp`, `menubar.lisp`, `audio.lisp` live) and the guide's
  worker-thread blocks. A widget-layer change travels into every compiled `appkit:` program.

## Open items
- No MAIN menu (a process with no bundle sets none), so no Cmd-Q on a windowed program.
- A variadic selector a PROGRAM sends in the string form is served only for the names in
  `objc::*variadic-selectors*`; the runtime offers no way to recognise another.
- x86_64: `objc_msgSend_stret` (struct returns wider than 16 bytes) has not been exercised.
- x86_64 and macOS before 14: `ObjcCatch` writes no frame, so an Objective-C exception inside a
  call still ends the process.
- `--native`: a macos-x86_64 runner has no Objective-C host (`call.rs` is Apple's AArch64
  convention, and there is no release stub).
- An interpreted render block writes sample by sample: each `(setf fli:dereference)` spends a real
  share of the 22.7 us budget ("Measured decisions"); a one-call copy of a Lisp array has no public
  spelling yet.
