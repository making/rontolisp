# `objc:` and `appkit:`: a native macOS window from the REPL, through FFM

- **`objc`** binds the Objective-C runtime and AppKit through `java.lang.foreign` -- `am.ik.objc`,
  a language-independent library beside `am.ik.gpu`; the analogue of `java:`
  ([java-interop.md](java-interop.md)) minus reflection. Vocabulary: LispWorks 8.1's `OBJC` /
  `COCOA` (*Objective-C and Cocoa Interface User Guide and Reference Manual*) and the part of its
  `FLI` the manual's examples use, plus this package's own `on-main`, `data`, `bytes`, `objectp`,
  blocks, exceptions and `invoke-with-error`.
- **`appkit`** (`appkit.lisp`), **`metal`** (`eval/metal.lisp` + `MetalLibrary`) and **`scene`**
  ([geom.md](geom.md)) are Lisp over `objc`, shipped like `linalg.lisp`.
- User docs: `doc/{en,ja}/guides/objc-appkit.md`. Examples: `examples/macos/*.lisp`, in
  `examples.yaml` (whose `os: [mac]` gates only RUN legs) only the three that open no window
  (`objc-runtime`, `system-frameworks`, `audio`).

**Scope**: macOS only -- the interpreter, JVM class output, and a `--native` executable for
`macos-aarch64`. Every other WASM output REFUSES a program referencing any of the macOS packages
(`CompileFrontend`, after load inlining, naming the reference), permanently: no WASM runtime offers
FFM or AppKit. A machine without the runtime SIGNALS at the call. Why it exists: the native binary
is the REPL people run, and `java:` cannot be INTERPRETED there (no reflection metadata); FFM
needs none.

## Layers: one semantics in Lisp over thin per-backend primitives
`eval/objc.lisp` (+ `ObjcLibrary`) is the WHOLE vocabulary -- conversion, ownership, pools,
tracing, the encoding parser, the variadic table, `cocoa:`, `fli:`, exceptions -- run on every
target: the interpreter loads it on the first resolution of an `objc:` / `cocoa:` name; the
compile path splices it (`ObjcLibrary.process`, right OUTSIDE `AppKitLibrary`). Beside it:
`objc-macros.lisp` (defining macros), `objc-class.lisp` (class half), `objc-block.lisp` (block
half), each spliced only when referenced (below). Under it, per backend, the primitive layer
(`LispNames.OBJC_PRIMITIVES`):

- base: `objc::%get-class`, `%class-name`, `%object-class`, `%class-p`, `%register-selector`,
  `%selector-name`, `%method-types`, `%send`, `%new-handle`, `%refs`, `%interned`, `%intern`,
  `%load-module`, `%initialize`, `%raised`, `%octets`, `%write-octets`, `%read-octets`, and the
  public `objc:on-main`;
- class half: `%allocate-class`, `%add-ivar`, `%register-class`, `%add-method`, `%add-protocol`,
  `%superclass`, `%send-super`, `%ivar-offset`, `%ivar-types`, `%peek`, `%poke`;
- block half: `%make-block`, `%free-block`, `%call-function`, `%symbol-address`.

Implementations: interpreter `eval/ObjcPrimitives` over `am.ik.objc` (`ObjcRuntime.sendRaw`,
`ObjcReference`); JVM class output `codegen/jvm/JvmObjcPrimitivesTemplate` (ships as
`<Program>$ObjcPrimitives`) compiled to by `JvmObjcPrimitivesCompiler`; `--native`
`eval/objc-native-primitives.lisp` over the `rlobjc` `p_*` imports (`runner/src/objc/prim.rs`).
**What differs between them is the value representation, never a rule.**

### `%send` is the whole call
Arguments: receiver address, SEL address, the encoding string (the method's own, or one built from
a list-form method's FLI types), the variadic split (`-1`, or the number of FIXED method
arguments), the raw arguments, a mode.

- Raw in: an integer for every integral or address kind (BOOL 0/1), a float for `f`/`d`, a list of
  leaves in memory order for a struct, and a Lisp STRING where the encoding says `@` (autoreleased
  `NSString`) or `*` (C string) -- made INSIDE the hop, alive for the call (the manual's "released
  when the function returns").
- Raw out: integer / float / list of leaves / nil; `*` is read into a Lisp string inside the hop
  (Foundation frees a `UTF8String` buffer with its pool).
- Mode bit 1: retain an `@` result inside the hop; bit 2: hand a `*` result back as an address;
  bit 4 (`ObjcRuntime.RETAIN_OUT`, `prim.rs` `RETAIN_OUT`): retain what the last argument's slot
  holds (`invoke-with-error`).
- **Every `%send` runs in its own autorelease pool on thread 0** (interp/JVM: `MainThread.sync`
  plus push/pop; `--native`: the module is on thread 0, the host pushes/pops). An autoreleased
  result is dead when the send returns unless the hop retained it. This hop is why ownership cannot
  be LispWorks' raw retain/release: LispWorks sends on the calling thread, whose pool outlives the
  call.

### What `objc.lisp` decides
- A PLAN per (lookup class, method name) caches the parsed encoding, SEL, mode and ARC family flags
  (`objc::%lookup-plan`; the lookup class is `object_getClass` of the receiver, so a class receiver
  finds class methods through its metaclass). An uncached family check cost 15 us a send.
- The encoding parser maps to the FLI descriptors (`objc::%fli-type`); a block (`@?`) or function
  pointer (`^?`) is re-spelled `^v` before `%send` so every host parser takes it.
- An integer argument past 2^63 travels as its two's complement (`objc::%bits64`: a wasm `:s64`
  import traps on a bignum); an unsigned 64-bit result or struct leaf is fixed up from the parsed
  type.
- **Variadic selectors are a TABLE OF NAMES**, `objc::*variadic-selectors*` (the nil-terminated
  constructors and the format-string family): a variadic selector is declared byte for byte like
  its fixed twin (`arrayWithObjects:` and `arrayWithObject:` are both `@@:@`), and on arm64 a
  variadic argument goes on the STACK -- sent through the declared shape the callee walks its
  `va_list` off a slot nobody wrote (SIGSEGV in `objc_retain`). The string form of a listed
  selector types arguments PAST the declared arity by VALUE (object / `q` / `d` -- what `va_arg`
  reads for `%@`, `%ld`, `%f`) and passes the split, which `sendRaw` binds with
  `Linker.Option.firstVariadicArg`. A list-form call says so with `:variadic-num-of-fixed`; its
  variadic arguments travel promoted and WIDENED to a 64-bit slot (`q`/`d`/`@`, ABI-equivalent on
  both macOS ABIs).
- **The appended nil terminator is unconditional**, for both forms: the nil-terminated half needs
  it and a `printf`-style callee never reads past its format -- one rule, and the last variadic
  argument is always `void*`, which is what bounds the native binary's variadic grid.
- **`objc::%checked` wraps EVERY call of `%send` / `%send-super` / `%call-function`** in
  `objc.lisp`, `objc-class.lisp` and `objc-block.lisp` ("Exceptions").

## Traps
- `objc:with-autorelease-pool` (expands to `(objc::%call-with-autorelease-pool (lambda () ...))`)
  must be registered in `LispMacroExpander.expandBuiltinMacro` AND walked in both `FreeVarAnalyzer`
  walks, or a JVM compile of a body naming an outer variable dies with "closure over X whose
  binding left it unboxed".
- The interpreter decides a `print`'s routing BEFORE its argument runs, while the argument is what
  loads `objc.lisp` and its `print-object` methods: the print site loads the library first when the
  form names it (`ObjcLibrary.references`), as for torch and geom.
- A pointer prints the address only (`#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x...>`,
  `OBJC:OBJC-CLASS`, `OBJC:SEL`): asking its class SIGSEGVs when a refused release's message
  prints the freed object.
- rontolisp interns a defstruct's `:conc-name` accessors in the STRUCTURE NAME's package:
  `(:conc-name objc::%fp-)` on `fli::pointer` defines `FLI::%FP-ADDRESS`; the accessors are
  `fli::%pointer-...`.
- A defun in `objc-class.lisp` must not reuse a primitive's name (a helper `%method-types` once
  replaced the primitive; `ObjcClassTest`).
- A new call site of `%send` / `%send-super` / `%call-function` must go through `%checked`, or a
  raise reads as nil and its reference leaks.
- `%checked` runs only on a nil answer and the slot flag is a required parameter: checking every
  answer with an `&optional` flag cost 1.5-3 us a send.
- `fboundp` loads no library: `(fboundp 'objc:invoke)` answers nil in a fresh interpreter until an
  `objc:` name was resolved (true of `appkit:` and `linalg:` too).
- Mutable state lives in the handle, never a struct slot: `equal` / `equalp` hash tables hash an
  instance by its slots on the interpreter and the JVM.
- A window needs `setReleasedWhenClosed:` NO (`appkit:window` does), or closing releases a
  reference the pointer's `gc` count holds. Leaking is the safe direction everywhere here.
- `JvmObjcPrimitivesTemplate` is ONE class file: no nested class, no enum `switch` (it lowers to a
  synthetic `$1` the builder does not ship).

## One representation, equal by address
`objc:objc-object-pointer` is a defstruct (address + handle) in `objc.lisp`, the same on all four
targets; `objc:objc-class` INCLUDES it (a Class is an object) and `objc:sel` is its own. None is a
`structure-object` (`LispMacroExpander.FOREIGN_POINTER_STRUCT_TAGS`, `isStructureObjectLayout`).
Classes and selectors are immortal, interned per address / name in Lisp tables; a result whose
object is a class answers the interned `objc-class`.

- Interpreter / JVM: object pointers are INTERNED per address in a weak table (`ObjcReference`,
  `%interned` / `%intern`), so `eq` holds by identity. Every answer for an address adds a `gc`
  reference to the ONE live value (a kept button's `retainCount` grows by one per callback that
  receives it; harmless -- nothing is released early).
- `--native`: no weak references in wasm-GC, so each answer is a value of its own with one
  reference, compared by the address slot: `WasmLispCompiler.addressKeyedLayout` holds the
  `objc-object-pointer` layout when the program carries it, and the eql arm (`_eql_tail`),
  `_equal`'s and `_hash`'s instance arms and `_ihash` compare and hash such an instance by that
  first slot alone. A module without the layout is byte-identical.
- So a program releasing more often than it received is refused at a different call per target;
  one releasing what it owns sees the same `retainCount` deltas everywhere (what the corpus prints).
- A pointer is a hash key (`eql` on every target); there is no address accessor. The shipped layers
  keep per-object state in a slot of a class they define ("appkit").

## Ownership
Each `objc-object-pointer` carries a HANDLE (interp/JVM `ObjcReference`; `--native` an `externref`
whose host data holds the counts) with three counts:

- `gc` -- released on thread 0 when the handle is collected. An `@` result arrives with one:
  retained in the hop, or taken over from the `alloc` / `new` / `copy` / `mutableCopy` families
  (ARC's rule: the word at the start, ignoring leading underscores, followed by the end or a
  non-lowercase character). The `init` family CONSUMES the receiver's reference (one `gc`, else one
  `manual`, no message) and answers +1.
- `manual` -- `objc:retain` sends `retain` and counts one here; NOT released at collection (how a
  program keeps a delegate alive after dropping the pointer, as in LispWorks).
- `pooled` -- references an emulated pool holds.
- A class pointer owns nothing: `retain` / `release` / `autorelease` change no count.

`objc:release` gives up one `manual`, else one `gc`, and sends `release`; holding neither SIGNALS
instead of over-releasing. `objc:autorelease` gives one up the same way to the innermost live pool,
or with none back to `gc`. `(objc:invoke p "retain" | "release" | "autorelease")` routes to these.

**Pools are emulated in Lisp**: a real `NSAutoreleasePool` pushed in one hop and popped in another
would interleave with thread 0's own loop pools. `with-autorelease-pool` binds
`objc::*autorelease-pools*`; `make-autorelease-pool` pushes one; `(release pool)` drains it and
every pool made after it.

## FLI: foreign objects and pointers
`fli:with-dynamic-foreign-objects` (in `objc-macros.lisp`), `allocate-foreign-object`,
`free-foreign-object`, `dereference` (+ `setf`), `foreign-slot-value` (+ `setf`), `size-of`,
`pointerp`, `pointer-address`, `make-pointer`, `null-pointer-p`, `pointer-eq`,
`define-foreign-function` -- in `objc.lisp` over existing primitives: `calloc` / `free` through
`%symbol-address` + `%call-function`, reads and writes through `%peek` / `%poke` by encoding. No
`rlobjc` import of its own, so `--native` allocates in the runner's heap (never linear memory) as
the interpreter and the JVM do. `with-dynamic-foreign-objects` allocates on the heap (LispWorks:
the stack) and frees on every exit.

- **A foreign pointer is `fli::pointer`**: address, FLI type, the pointee's ELEMENT -- its
  layout worked out once (`objc::%element-type`): `#(size alignment parsed encoding access
  slots)`, the encoding what `%peek` / `%poke` take (the FLI type's own, else `%unparse` of the
  parsed pointee, then with no size: indexing it signals as `fli:size-of` does), `access` one
  of `:void :aggregate :boolean :real :other`, `slots` a named structure's
  `((name-string offset fli-type) ...)`. Not interned, not `eql` by address --
  `fli:pointer-eq` is.
- **One element per FLI type**, in `objc::*elements*` (`equal`), which is REPLACED on a miss,
  never changed in place (a render block reads it on the audio thread), and dropped by
  `define-objc-struct` / `-typedef` (`objc::%forget-elements`; `ObjcClassTest#aRedefinedStructureIsLaidOutAgain`).
  `fli:size-of`, `%fli-layout`, `%slot`'s offsets and a `:type` access all read it; an
  access is then arithmetic plus one `%peek` / `%poke`.
- **Every pointer Objective-C hands Lisp is one**, typed by its declaration: an `invoke` / C
  function result (`%result`'s `:pointer` arm), a callback argument declared `(:pointer T)`
  (`%convert-argument`), a dereferenced pointer. Everything taking an address takes one
  (`%raw-address`, `:object`, `:cstring`); a structure parameter takes one and COPIES it (also what
  a method answering a structure may return). The byte primitives take an integer: `objc:data`,
  `objc:bytes`, `metal:upload` and `metal::%stage` unwrap with `fli:pointer-address`.
- `invoke-into` a foreign object pokes the result into it (a `char *` result as its address,
  mode 2). A result variable (a non-keyword result style of `define-objc-method`) is a foreign
  object of the result type, answered and freed when the body returns. A structure ARGUMENT of a
  method or block still arrives as its Lisp value (vector / cons).
- `cocoa:set-ns-*` fill a foreign object as well as a vector / cons; the four structures' slots
  (`objc::*struct-slots*`: `x y`, `width height`, `origin size`, `location length`, matched by
  symbol name); `define-objc-struct` records its slots there.
- **Layout is the C rule over named slots** (`objc::%fli-layout`): `fli:size-of` answers the
  recorded LispWorks sizes; an ivar of a declared structure type is sized by it. A PARSED structure
  is laid out by the same rule over its NESTED members on every host (`TypeEncoding.Type`,
  `encoding.rs` `Type::structure`, `objc::%type-size`): `{Outer={Inner=dc}c}` is 24 bytes with the
  tail at 16, `{O=c{I=cd}}` puts the inner `c` at 8. The VALUE stays the leaves in memory order,
  each at its C offset. The JVM hands FFM the flat leaves when they sit where natural alignment puts
  them (every AppKit struct, so the closed table's spellings hold) and the nested layout otherwise
  -- the linker refuses padding alignment does not call for. A spelled shape includes its padding
  (`padding(7)`): the image builder rebuilds it with `structLayout`, which refuses a member off its
  alignment.
- Refused, never a crash: a null pointer, a `:void` pointee without `:type`, an aggregate
  dereferenced without `:copy-foreign-object` (`:error` default; `nil` a pointer, `t` a `calloc`'d
  copy), a Lisp string or vector stored where an object goes (it would not outlive the store).
- **Not `ffi:`'s pointer**: `ffi` is interpreter/JVM only (a Java `LispForeignPointer`), `fli` must
  run on `--native`; one value would need a wasm-GC representation of a Java value. The bridge is
  the integer (`fli:pointer-address`).
- Cost: ~3 us for a `(setf fli:dereference)` interpreted, ~0.05 us compiled
  ("Measurements"); what is left interpreted is the evaluator's own per-form cost.

## Bytes: `objc:data`, `objc:bytes`
`objc:data` answers an **`NSMutableData`** (one value serves `bytes` for a `^v` parameter and
`mutableBytes` as scratch) holding a packed buffer's bytes exactly as `write-sequence` writes them
([binary-sequence-io.md](binary-sequence-io.md)) or a string's UTF-8; `objc:bytes` answers an
NSData's contents as a fresh `(unsigned-byte 8)` vector. Both are Lisp (`dataWithLength:` +
`mutableBytes`; `length` + `bytes`) over:

- `%octets` -- a value's bytes as an octet vector, or nil; the representation's question, so the
  backend's: `eval/PackedBuffer` (also bfloat16 arrays and quantized matrices);
  `JvmObjcPrimitivesTemplate.bufferBytes` (**a packed float array carries its dimension header IN
  the array** -- `[rank, dim_0..., e_0...]` -- and a packed integer vector is the bare `byte[]`
  of its octets (copied, so `%octets` answers a fresh vector) or `long[]{width, e_0, ...}`, so only
  the elements go on the wire, as `LispSingleFloatArray.data()` does); Lisp on `--native` (`%ieee754-single-bits`, lowered on wasm-GC for this; bfloat16 and
  quantized refused).
- `%write-octets` / `%read-octets` -- block copy between an octet vector and foreign memory
  (`ObjcRuntime.writeBytes` / `readBytes`; `--native` `p_write_bytes` / `p_read_bytes`, a `:bytes`
  parameter and result into a buffer the Lisp side allocates).

## Class definition
`define-objc-class` / `-method` / `-class-method`, `current-super`, `standard-objc-object`,
`objc-object-var-value`, `objc-object-copied` / `-destroyed`, `define-objc-struct` / `-typedef` /
`-protocol`, `cocoa:add-observer` / `remove-observer`.

- **`objc-macros.lisp`**: pure `cl`, each macro expanding into a call of `objc-class.lisp` with its
  types QUOTED (conversion decided per call). **The compile path expands user macros BEFORE it
  splices libraries**, so `ObjcLibrary.withMacros` puts these `defmacro`s in front of
  `UserMacroExpander` (which drops them); the interpreter loads the library when a call names one
  (`definesMacro`, checked just before the user-macro lookup). The macro-time evaluator runs the
  expansion's `defclass`, so `mentionsType` counts `objc:standard-objc-object` and loads the library
  too. A method body becomes `(lambda (%current-super object pointer result args) ...)` -- ONE
  argument list (a wasm lambda takes at most ten parameters); `current-super` expands to that
  variable.
- **A shipped library that defines classes** (`appkit.lisp`, `scene.lisp`) is spliced AFTER that
  expansion, so its `process` splices `ObjcLibrary.expandDefinitions` of its forms (run once,
  cached: `AppKitLibrary.expandedForms`); the interpreter evaluates the raw forms.
- **`objc-class.lisp`** is spliced only when the expanded program names one of its definitions or
  `standard-objc-object` (`referencesClassHalf`), so a calling-only program carries no CLOS init
  protocol (an `appkit:` program always does). It plugs into `objc.lisp` through
  `*object-pointer-hook*`, `*pointer-object-hook*`, `*registered-pointer-hook*` and
  `*realize-hook*`, so `objc.lisp` never names it.
- **`%add-method (cls sel types function flags)`**: the host installs an IMP calling `function` with
  the receiver's address and the RAW arguments (`%send`'s conventions; an object argument arrives
  RETAINED, which `%wrap-object` takes over) and marshals the raw answer by the encoding; flags 1
  retains an object answer, 3 retains and autoreleases it (`%result-flags`, by ARC family). Root
  methods pass 0: `+allocWithZone:` answers the super call's +1 as is.
- **One IMP per METHOD, never per shape**: a super send runs the superclass's IMP for a receiver
  whose class defines the same selector, so a (receiver class, selector) lookup would recurse.
- JVM / interpreter: `am.ik.objc.ObjcMethods` binds `dispatch(Object, Object[])` once (a CONSTANT
  `findStatic`) and adapts it per method with `insertArguments` / `asCollector` / `asType` to the
  encoding's `FunctionDescriptor` -- any shape under `java`, structs included; the native binary's
  upcall table: "The native binary".
- `--native`: `runner/src/objc/class.rs`. The IMP is `imp_implementationWithBlock` over a GLOBAL
  block whose invoke is the assembly `rl_objc_block_imp`: libobjc's trampoline puts the block in x0
  and the receiver in x1 and leaves x2-x7, d0-d7, x8 and the stack as the caller laid them, so one
  entry saves them all and `Reader` takes each argument back by `call.rs`'s classification in
  reverse (HFA in d registers, struct > 16 B by reference, stack at natural alignment); the answer
  goes to x0/x1, d0-d3 or through x8. The block carries the method's index. The module export
  `rlobjc_method(index, self)` reads arguments with `p_cb_count` / `p_cb_arg` (fetched with
  `p_result_*`) and pushes its answer with `p_arg_*`; the pending arguments of the send in progress
  are saved around it.
- **A class the process defined is marked, not remembered**: every class pair gets the ivar
  `rontolispDefinedClass`, so a later definition of the name -- another interpreter in the JVM, a
  compiled program's renamed copy of `am.ik.objc`, a re-evaluated form -- reuses it (replacing
  methods) while a class Lisp did not define is refused. A host table would not cross those copies;
  the runtime cannot remove a class.
- **Definitions wait for the runtime**: `%ready` (`ensure-objc-initialized`, or the first `invoke`
  / class lookup) realizes queued classes in definition order and installs each recorded method on
  its class and on every subclass of a mixin ancestor.

### `standard-objc-object`, ownership, `objc-object-destroyed`
- One slot, `objc::%objc-pointer%` (slots match by base name, so the name avoids a user's).
  `+allocWithZone:` (a root method, installed on the root of each Lisp-defined hierarchy only)
  makes or ADOPTS the Lisp object: `make-instance`'s `initialize-instance :around` sets the global
  `*adopting*` (a global value, not a binding -- the method runs on thread 0), sends `alloc`, then
  `init` or the `:init-function`, keeps the final pointer; an object Objective-C allocated gets
  `(make-instance class :%objc-pointer address)`.
- `*lisp-objects*` (address -> instance) is STRONG (LispWorks' rule): the Lisp object lives until
  the count reaches zero, when `-dealloc` runs `objc-object-destroyed`, removes the entry and sends
  `dealloc` to super. `make-instance`'s reference (the `init` answer's `gc`) is the program's to
  `objc:release`. **The `Cleaner` cannot run first**: the value is reachable from the registered
  instance until `-dealloc`, and after it holds no reference.
- **A registered object has one pointer value on every target**: `%live-pointer` asks the intern
  table, then `*registered-pointer-hook*`, so on `--native` too an answer adds its count to the ONE
  value `release` later gives up.
- A method's receiver is BORROWED (`%borrow`), never retained -- `-dealloc` runs on a receiver no
  one may retain.

### A Lisp error, a `throw`, an exit inside a callback (methods and blocks, every target)
- A Lisp error, an escaping Objective-C exception, or a `throw` to an outer tag is contained by
  `objc::%run-method`'s `handler-case`: printed (`objc: error in a callback: ...`) and answered as
  zero (`%zero-answer`), never unwound through Objective-C's frames (on `--native` such a `throw` is
  a trap, contained the same way).
- An EXIT is not a Lisp condition: it unwinds through the Lisp frames as a host exception to the
  upcall guard (`am.ik.objc.ObjcMethods` / `ObjcBlocks`), which recognizes it (`ProcessExit`) and
  ends the process with its code; `--native`: a `proc_exit` inside exits with its code.

## Blocks and C functions
`make-objc-block`, `free-objc-block`, `with-objc-block`, `call-objc-block`,
`define-objc-block-type`, the type `objc-block`, `objc-block-pointer`, `objc-block-live-p`,
`fli:define-foreign-function`. LispWorks' `OBJC` has no block interface, so the block names are
this package's own.

- **No implicit wrapping, decided**: an encoding says `@?`, never what the block takes (the extended
  `@?<...>` appears in protocol metadata only), so a Lisp function passed where a block goes
  SIGNALS, naming `make-objc-block`. A wrong guess would be a crash. `@?` parses as an object in
  `TypeEncoding` and `encoding.rs` (extended form skipped): a send passes only the address.
- `objc-block.lisp` (spliced when the program names one of its definitions or `objc-block`,
  `ObjcLibrary.referencesBlockHalf`) plugs in through `*block-pointer-hook*`: an `objc-block` stands
  for its literal's address where a block, pointer or object goes; a FREED one signals there.
  Arguments and answer convert as a method's (`%convert-argument`, `%callback-answer`,
  `%zero-answer`, `%declared-type`, in `objc.lisp` so both halves share them).
- **Primitives**: `%make-block (types signature function)` answers the literal's address;
  `%free-block`; `%call-function (address types fixed args mode)` -- a C call through an address,
  `%send`'s raw conventions, `types` covering every argument -- which `call-objc-block` makes on the
  invoke pointer (`%peek` of the literal at +16) and `fli:define-foreign-function` on a `dlsym`
  answer; `%symbol-address` (`dlsym(RTLD_DEFAULT)`). `types` spells the block itself `^v`;
  `signature` (for `_Block_signature`) spells it `@?`.
- **The literal** (`am.ik.objc.ObjcBlocks`, `runner/src/objc/block.rs`): isa
  `&_NSConcreteStackBlock`, flags `BLOCK_HAS_COPY_DISPOSE | BLOCK_HAS_SIGNATURE`, invoke, descriptor,
  then an ID. The ID travels INSIDE the literal because `_Block_copy` copies it to the heap whenever
  a callee keeps it, and the copy must find the function; IDs are never reused, so a block called
  after its function is gone is reported, never routed to another. A STACK isa: `_Block_copy` of a
  global block answers the same pointer (storage would have to outlive every holder), while
  `_Block_release` of our own storage is a no-op (`BLOCK_NEEDS_FREE` clear). The copy/dispose
  helpers count HOLDERS per ID (the Lisp value plus each live copy); `free-objc-block` gives up the
  Lisp value's, so `with-objc-block` around a `dispatch_async` is safe. A block never freed leaks
  (no finalizer). The JVM's literal is `malloc`'d, not an arena's: a native image serves no shared
  arena (`Arena.ofShared().close()` threw `UnsupportedFeatureError`), and a block may be freed from
  any thread.
- **Interpreter / JVM: a block runs on the thread that calls it.** One FFM upcall stub per block
  SHAPE, all landing in `ObjcBlocks.dispatch`, which finds the function by ID and converts by the
  block's own encoding. A libdispatch worker is attached by the JVM and runs the function
  concurrently, with the interpreter's thread rules ([threads.md](threads.md)): no dynamic binding
  of the program's thread visible, a closure's capture still read. Foundation calls a comparator or
  enumerator on the sending thread, thread 0. The helpers are upcalls too (`void(void*,void*)`,
  `void(void*)`); no Lisp runs in them.
- **`--native`: only thread 0 enters the module.** The invoke function is `rl_objc_block_imp` (a
  method block and a program block are told apart by the descriptor; the ID sits where the method
  index does). A block arriving inside a host call on thread 0 (`CALLER` set: a send, a
  `dispatch_sync` through `p_call_function`, a pump turn) re-enters through `rlobjc_method` -- a
  block's function joins the methods' table, called with no receiver. One arriving elsewhere: a
  `void` block is QUEUED (object arguments retained, C strings copied, one holder taken) and a no-op
  `dispatch_async_f` to the main queue makes a pump turn in progress return; `pump` drains the queue
  each turn, so the block runs at the program's next `sleep`. A block answering a value there is
  refused: printed, zero answered. Dead IDs reach the module through `p_block_reap` and leave its
  table on the next make or free.
- **C functions run on the CALLING thread** (`ObjcRuntime.callRaw`, in a pool of that thread), never
  hopped: a function that waits (`dispatch_sync`, a semaphore) must not hold thread 0 while a block
  it waits for needs it. `callRaw` binds UNBOUND downcall handles per shape (the target an
  argument). An object result follows Core Foundation's Create rule (libdispatch too): a name
  containing `Create` / `Copy` / `_create` / `_copy` hands over +1, anything else is retained in the
  call.

### A block on a real-time thread: `examples/macos/audio.lisp`
An `AVAudioSourceNode` whose render block (`OSStatus (BOOL *, const AudioTimeStamp *, UInt32,
AudioBufferList *)`, shape `jint(void*,void*,void*,jint,void*)`) is a Lisp closure. The calling
thread decides where it can run (measured in the block with `pthread_main_np`):

- **Offline** (`enableManualRenderingMode:` 0, then `renderOffline:toBuffer:error:`): AVFAudio runs
  the graph INSIDE the send and calls the block on the sending thread -- thread 0 on every target,
  `--native` included (re-entering through `CALLER`). Silent, no device; the transcript is
  byte-identical on `java -jar`, the native binary, `-o X.class`, `-o x.jar` and `--native`, and
  `examples.yaml` checks it (no `--native` leg there). A chunk larger than the buffer's
  `frameCapacity` fails with status -1 and `NSError` -50, not a short render.
- **Live** (`startAndReturnError:`): the engine's real-time I/O thread, never thread 0. `--native`
  refuses the value-answering block there (187 refusals in 2 s), so the example skips live on
  `:rontolisp-native`. Interpreter / JVM: **the block must send nothing** -- a send hops to thread 0
  while thread 0 sits in `-[AVAudioEngine stop]` waiting for the render thread: deadlock (jstack:
  thread 0 in `sendRaw` of `stop`, the I/O thread in `MainThread.sync`). So the samples go through
  `fli:` (`%poke`, no hop), not `objc:data` + `getBytes:length:`; `fli:` C calls
  (`pthread_main_np`) are fine, they run on the calling thread. The frame counts it reaches:
  "Measurements".

## Exceptions and NSError
`objc:objc-exception` (readers `objc-exception-name`, `-reason`, `-object`), `objc:ns-error`
(`ns-error-domain`, `-code`, `-description`, `-object`), `objc:invoke-with-error`, in `objc.lisp`.
Without a catching frame, `objc_exception_throw`'s unwinder reaches the FFM stub / JIT frames or the
wasm host frames, which have no unwind information, and `std::terminate` ends the process.

- **THE decision: a real `@catch (id)` frame under every call, not the uncaught-exception handler.**
  Every `objc_msgSend`, `objc_msgSendSuper` and `%call-function` target is called through a native
  frame whose call site an LSDA covers -- one handler, type `OBJC_EHTYPE_id`, under
  `__objc_personality_v0`. Phase 1 stops there; phase 2 unwinds the callee's frames WITH their
  cleanups (`@finally`, `@synchronized`, C++ destructors), exactly the frames Objective-C's own
  `@catch` would unwind. An exception Cocoa catches deeper never reaches it; a C++ exception of
  another type passes and terminates. Rejected: `objc_setUncaughtExceptionHandler` plus a non-local
  exit abandons the callee's frames without cleanups, leaves libc++abi's caught-exception list stale
  (one entry per exception), and runs only once the search has FAILED -- on the JVM after getting
  through the JIT frames above the send (and on thread 0 `_dispatch_client_callout`'s catch-all,
  which terminates).
- **The landing pad** takes the exception (`objc_begin_catch`), retains it, ends the catch and hands
  the address up. The host's `%send` / `%send-super` / `%call-function` answer nil and keep the
  retained address for `objc::%raised` (nil when the last call raised nothing, else the address, 0
  for a thrown nil; reading clears it; the hosts clear it at the start of each call, so it is never
  misattributed). `%checked` turns a raise into `objc::%signal-exception`: the object wrapped
  (taking over the +1), `name` / `reason` read when it `isKindOfClass:` `NSException`, else the
  class name and no reason. The report names the call:
  `-objectAtIndex: raised NSRangeException: ...` (`+` to a class, a C function by name).
- **The innermost call on that thread catches.** One escaping a Lisp-defined method or block is a
  callback error ("A Lisp error ... inside a callback").
- **`invoke-with-error`** `calloc`s a pointer slot (`%symbol-address` + `%call-function`), passes it
  last, and sends with mode bit 4: the host retains what the slot holds inside the hop (the
  `NSError` is autoreleased into the pool the hop drains). It signals `ns-error` when the result
  says failed (nil, `NO`, zero, void) AND an error was written -- Foundation's rule: the RESULT says
  whether a call failed; a failure with no error answers the result. The method name must end in
  `error:` or `Error:` (`startAndReturnError:`). `metal:library` / `metal:pipeline` are written over
  it, so a bad shader's diagnostics are the condition's description.

### Interpreter and JVM class output: `am.ik.objc.ObjcCatch` writes the frame at run time
rontolisp ships no native code, and the frame must sit between the FFM stub and the callee, so
`ObjcCatch` writes AArch64 instructions into an `mmap`ped page made read-execute for good (never
rewritten: a page rewritten while another thread runs it faults, and HotSpot's W^X state is per
thread, so `MAP_JIT` toggling from Java would take the JIT's own code cache away mid-call). `java`
carries `allow-unsigned-executable-memory`; a native image is not hardened (`mprotect` to
`PROT_READ|PROT_EXEC` verified under both, 2026-09-28).

- **A slot forwards the call unchanged**: x0-x7, d0-d7, x8 untouched; it copies the caller's
  stack-argument area under its own 32-byte frame, sized by an upper bound from the
  `FunctionDescriptor` (every argument at its size rounded to 8, plus 8; 64-byte steps, at most
  4032 -- a wider shape is called directly, uncaught). Callee, size and recorder sit in a per-slot
  DATA cell, so a slot is keyed by (target, size) and the code never changes. The downcall handle is
  bound to the slot with the callee's own shape, so the native binary's table serves it unchanged.
- **The recorder** is an upcall (`void(void*)`) into the copy of `ObjcCatch` that allocated the
  slot; it keeps the address in a `ThreadLocal`, which `ObjcRuntime` reads right after the downcall
  and turns into `ObjcRaised` (an `ObjcException` carrying the retained address); `MainThread.sync`
  carries it back to the Lisp thread.
- **The unwinder finds the code through `__unw_add_find_dynamic_unwind_sections` (macOS 14)**: a
  finder, also written there, answers for a region's code page with its `.eh_frame` (one CIE with
  `zPLR` -- absolute personality, LSDA and addresses -- and one FDE per slot, all naming one LSDA)
  and a stand-in `mach_header` (arm64, subtype ALL) as `dso_base`. **Trap: `__register_frame` of a
  dynamic FDE registers it with `dso_base` 0, and Apple's `unw_set_reg` reads the CPU subtype of the
  image a new IP lies in -- SIGSEGV at the landing pad** (macOS 26.3, 2026-09-28).
- **One chain of regions per PROCESS**: libunwind keeps a short fixed table of finders, and a JVM
  holds a copy of `am.ik.objc` per compiled program it loads -- a finder per copy ran out inside
  `JvmObjcBaseCompilerTest`. The first region is published in the system property
  `rontolisp.objc.catch` (hex address; JVM-wide, never inherited by a child), the rest chained from
  it, one finder walks them, and every copy allocates under one JVM-wide lock
  (`System.getProperties()`). 120 slots a region, at most 16 regions; past that, without the finder
  API, or on x86_64, a call goes straight to its callee and an exception still ends the process.
  `-Drontolisp.objc.catch=0` publishes "no region" (catching off).
- Registered for the native binary: `mmap`, `mprotect`, `sys_icache_invalidate` and the finder
  registration (`reachability-metadata.json`, `ObjcNativeImageForeignConfigTest`). Travelling:
  `ObjcRaised`, `ObjcCatch`, `ObjcCatch$Asm`, `ObjcCatch$Bytes` (`JvmObjcRuntimeBuilder`).

### `--native`: the catch is in `rl_objc_call` (`call.rs`)
The same LSDA in `global_asm!`: `.cfi_personality 155, _rl_objc_personality`, `.cfi_lsda` to a
`__gcc_except_tab` table whose one type entry is INDIRECT through `rl_objc_ehtype_slot`. libobjc is
dlopened at run time, so the stub cannot name `__objc_personality_v0` or `OBJC_EHTYPE_id`: the
personality is a forwarder and the slot is filled by `call::install` when the runtime opens (before
that the forwarder answers "continue unwinding"). The landing pad calls `rl_objc_caught`, which
marks the `Frame`; `Call::invoke` answers `Err(Raised(thrown))`; `prim.rs` answers result kind 5
(`RAISED`, the address through `p_result_int`), which `objc-native-primitives.lisp` keeps for its
`%raised`.

## AppKit belongs to thread 0
The thread the kernel started the process on (`pthread_main_np()` answers 1) is the only one that
may touch a window, and the Lisp thread is never it.

- `java -jar`: the launcher already parks thread 0 in a `CFRunLoop`.
- **Native binary**: `main` IS thread 0. `RontoLispCli.main` always moves the CLI to a spawned
  `main` thread ([interpreter-stack.md](interpreter-stack.md));
  `ObjcInterop.mainThreadHandOverRequired()` (native image + macOS + thread 0; cheap -- libSystem +
  CoreFoundation, no AppKit) decides that thread 0 then parks in `MainThread.runLoop()` rather than
  wait for the worker. UNCONDITIONAL on that platform: thread 0 cannot be handed over later, and
  since the run loop never returns, the worker ends the process with `System.exit`.
- **Compiled `main`: the same hand-over, in bytecode** ("The JVM backend").
- `runLoop()` is the launcher's `ParkEventLoop`: a no-op `CFRunLoopSource` keeps the default mode
  non-empty and `CFRunLoopRunInMode(default, 1e20)` is re-entered whenever it returns. **Trap: a bare
  `CFRunLoopRun` returns after the first click, leaving the binary in `JavaMainWrapper`'s join with
  a beach-balled window.** `RONTOLISP_OBJC_TRACE=1` prints every hop and return.
- **Parking thread 0 is not enough -- AppKit must drain it.** Only `-[NSApplication run]` DEQUEUES
  events. It never returns, so `appkit::%app` asks thread 0 to `performSelectorOnMainThread:
  withObject:waitUntilDone:` NO it, starting it NESTED inside whatever loop parks the thread. `%app`
  is the ONLY place that starts it: a window built from raw `objc:` in a process that never called
  an `appkit:` function answers nothing.
- **Every entry point hops.** `MainThread.sync` hands a body to the main dispatch queue with
  `dispatch_sync_f`; the body crosses ONE upcall stub (`trampoline`, `void(void*)`) whose context
  pointer is a ticket into a slot map. **`dispatch_sync` to the queue you are draining is a
  deadlock**, so `sync` tests `pthread_main_np()` and runs inline on thread 0 -- an `:on-click`
  handler runs there with the interpreter's GLOBAL dynamic bindings
  ([dynamic-special-variables.md](dynamic-special-variables.md)). An exception inside a `sync` body
  is carried back and rethrown on the caller's thread, so a non-local exit through `objc:on-main`
  works.

## The native binary: a CLOSED table of shapes
`method_getTypeEncoding` describes every fixed-arity selector completely; `TypeEncoding` parses it
into a `FunctionDescriptor` (a struct as its scalar leaves at their C offsets, "FLI") and
`ObjcRuntime.sendRaw` binds one `objc_msgSend` handle PER DISTINCT SHAPE -- Apple's arm64 rule;
**never through the variadic declaration: an `NSRect` through a `long` shape is a SIGBUS** --
called with `invokeWithArguments`, which a native image serves. `ObjcRuntime.Signature`
(descriptor + split, `-1` when fixed) is the `sends` cache key. A wrong selector, arity or operand
type is an `ObjcException` -> a Lisp `error`; unions, bitfields and function pointers are refused by
name.

- A native image builds a downcall stub only for a shape registered at build time
  (`MissingForeignRegistrationError` at `Linker.downcallHandle`), so the served set is a table in a
  file of its own, `META-INF/native-image/am.ik.rontolisp/rontolisp-objc/reachability-metadata.json`
  (read beside the main file; a compiled program carries a copy, "The JVM backend"): the runtime's
  own C functions, every shape `appkit.lisp` / `metal.lisp` / `scene.lisp`, `objc.lisp` and the
  documented examples send, the 60 most common shapes of a census over 29 core AppKit/Foundation
  classes (13,065 methods, 90.6% reached), plus `NSTimer`'s `scheduledTimerWithTimeInterval:...`. A
  selector outside it signals with the exact entry to add; the JVM registers nothing, so `java -jar`
  is where a program discovers what it sends. **A new selector in `appkit.lisp`, `metal.lisp`,
  `scene.lisp`, `objc.lisp`, the `examples/macos` programs the test names, or the docs is a row in
  `ObjcNativeImageForeignConfigTest`'s table.**
- **Variadic sends are a 144-entry grid** generated from a RULE the same test restates and pins in
  both directions: three fixed halves (`void*(void*,void*,void*)` and `void(void*,void*,void*)`
  splitting at 3, `void(void*,void*,void*,void*)` -- `raise:format:` -- at 4) crossed with 1-12
  variadic arguments, every carrier combination up to 4 and `void*` only past it; the test reads the
  selector names off `objc.lisp`.
- Every Metal object is PROTOCOL-typed (`id<MTLDevice>`) with a private concrete class, so the test
  has a `proto(...)` row (resolved through `protocol_getMethodDescription`, which the test binds
  itself); Metal's DESCRIPTOR classes are the reverse (`alloc` answers `...Internal`), so it falls
  back to that name.
- **Upcalls (methods, blocks) are a closed table too**, under `foreign.upcalls`; any other shape is
  refused at definition / when the block is made, naming the entry (`ObjcRuntime.upcall`).
  Run-time handle combinators work in the image (GraalVM 25.0.3, 2026-09-28). Registered: the
  shipped layers' method shapes (`v@:@`, `B@:@`, `v@:`, the root methods), `v@:@@`, `q@:@`, the
  guide's class examples (`jint(void*,void*,jint,jint)`, `jint(void*,void*)`,
  `struct(jfloat,jfloat)(void*,void*)`), the guide's blocks (adder `jint(void*,jint,jint)`,
  enumerator `void(void*,void*,jlong,void*)`; the method shapes already cover a comparator, a work
  item, a three-object completion handler and the helpers) and `audio.lisp`'s render block
  (`EXAMPLE_BLOCKS`). The test reads the shipped layers' `define-objc-method` forms and checks their
  shapes. Decided over a generated grid: exact integer widths are part of an upcall's shape (a
  32-bit argument's upper register half is garbage on arm64), so a grid over widths, floats and
  structs has no useful bound.
- Every send through the table is INTERPRETED by SubstrateVM's method-handle interpreter (a handle
  created at run time has no AOT code): ~1.7 us a call plus ~0.4 us per argument on top of
  `invokeWithArguments`' boxing ([gpu.md](gpu.md), "An FFM downcall inside a native image costs";
  measured on Linux/aarch64, not macOS). The AOT route ([native-downcalls.md](native-downcalls.md))
  is not applied: it is one `@InvokeCFunctionPointer` method per SHAPE, and the send table is a
  generic per-selector shape set no macOS box in reach can measure.

## The JVM backend: the binding travels beside the class, and calls back into it
`-o Prog.class` / `-o lib.jar` uses the `--gpu` route ([gpu.md](gpu.md),
[template-class-embedding.md](template-class-embedding.md)): every class file of `am.ik.objc` is
renamed by one prefix rule (`am/ik/objc/` -> `<Program>$Objc`) and ships beside the program through
`runtimeClassFiles()` as `<Program>$Objc*`, with `JvmObjcPrimitivesTemplate` ->
`<Program>$ObjcPrimitives`; the emitted `_objcInit` only binds, on the first primitive call.
`JvmObjcRuntimeBuilder` owns the list (pinned by
`JvmObjcBaseCompilerTest#theProgramShipsTheWholeLibrary`). A class a program defines is marked,
so a second copy in one JVM reuses it ("Class definition").

- **It makes UPCALLS into the program**: a method, a block and an `on-main` body are applied through
  `_apply`, handed over by `bind(Class)` from `_objcInit` -- so the objc runtime forces `usesEval`
  and roots `_apply` for the shaker. `bind` hands over `_strv` too (nullable: absent exactly when the
  program has no array runtime).
- **The gate is the primitive layer**, qualified (`JvmObjcPrimitivesCompiler.names()`): a program
  whose pruned `objc.lisp` calls no primitive (`(objc:objectp 1)`) carries no binding.
- **The same gate adds the thread-0 hand-over to the compiled `main`**: the sized-worker launcher
  ([interpreter-stack.md](interpreter-stack.md), `JvmSizedMainBuilder`) is headed by
  `RontoLispCli.main`'s question -- when `<Program>$ObjcMainThread.handOverRequired()` answers true,
  `main` marks the launcher instance `_main$exit`, starts the worker and parks in
  `get().runLoop()`; the worker ends the process -- `System.exit(0)`, or the throwable dispatched to
  its thread's uncaught-exception handler (the `Exception in thread "main"` echo) and
  `System.exit(1)`. Under `java` the answer is false before anything native is bound (no
  `org.graalvm.nativeimage.imagecode`) and `main` joins the worker. Pinned by `JvmSizedMainTest`
  (the shape; on macOS the hand-over simulated: `-XstartOnFirstThread` puts `main` on thread 0,
  `-Dorg.graalvm.nativeimage.imagecode=runtime` makes the question true -- an `appkit:timer` fires
  only once thread 0 is handed over, an uncaught condition still reports and exits 1) and
  `ShippedBridgeNativeImageE2eTest#anObjcJarRunsAsANativeImageThatHandsThreadZeroToAppKit` (macOS,
  opt-in: a real image, a timer clicking and closing its own window, 60 s deadline).
- A bare `.class` without `--enable-native-access=ALL-UNNAMED` gets the JDK's one-time warning; a
  `.jar` carries `Enable-Native-Access: ALL-UNNAMED` (`JvmJarWriter`).
- **An image of a compiled `objc:` program needs no configuration**: built from the user's jar or
  class directory, which holds none of rontolisp's `META-INF`, so `JvmObjcRuntimeBuilder` ships two
  registrations beside the `$Objc*` classes, each in a directory named after the program:
  `META-INF/native-image/rontolisp-objc/<program>/` (a verbatim copy of the `rontolisp-objc` file)
  and `rontolisp-objc-bridge/<program>/` (the reflection entries `JvmObjcPrimitivesTemplate.bind`
  finds by name: `_apply(Object,Object)`, `_strv(Object)`). Without the first every send refuses its
  stub; without `_apply`: `objc: no _apply method`; without `_strv` the first string the program
  BUILT dies in `MissingReflectionRegistrationError`. native-image skips a registered method the
  class lacks. That file stands ALONE, so it repeats shapes it shares with the main file;
  `ObjcNativeImageForeignConfigTest` checks every layer against it alone
  (`NativeImageDowncalls.OBJC`), and `ShippedBridgeClassFilesTest` pins the shipped bytes and the
  reflection entry.

## `--native`: the runner is the Objective-C host
`--native -o prog` (`macos-aarch64` only: `CompileFrontend` accepts the packages when the target is
`NativeTarget.OBJC_PLATFORM`): `ObjcNativeLibrary` splices `objc-native-primitives.lisp` -- the
primitive layer over `rontolisp:wasm-import`s from module `rlobjc` (`p_*`) -- right OUTSIDE
`ObjcLibrary`, and the runner (`rontolisp-native/runner/src/objc`) answers them over libobjc/AppKit,
dlopened on the first `rlobjc` call (an output that makes none starts as before). The host keeps a
small send of its own, `Api::msg`, for `pump` and the application start.

- **THE decision: the module runs ON thread 0.** A wasmtime `Store` is not `Sync`, and a callback
  arriving on thread 0 while the module ran elsewhere could not enter it (a nested call from another
  thread would leave its wasm frames' GC roots unwalked; wasmtime walks the CURRENT thread's
  activations). So `objc:on-main` is a plain call, and a callback arrives INSIDE a host call -- a
  send that made AppKit call out, or `pump` -- and re-enters through that call's `Caller`, published
  in the `CALLER` thread-local (`Entered`).
- **`sleep` is the event loop.** Every `sleep` of such a program compiles to `objc::%sleep`
  (`WasmExprCompiler`, keyed on the spliced defun `LispNames.OBJC_SLEEP_INTERNAL`) -> `p_pump`:
  `nextEventMatchingMask:` + `sendEvent:` once the application started, else `CFRunLoopRunInMode`.
  `appkit:wait`'s 50 ms poll keeps a window live at ~0% CPU; a blocking stdin read freezes it.
- **A fetch's wait turns the event loop too** (network runner, [fetch-http.md](fetch-http.md),
  "--native"): once the application started, the wait for a reply's head or next body chunk runs
  `pump` in 20 ms turns (`objc::pump_until`, called from `http::wait`), and a callback meanwhile
  re-enters through the call's `Caller`. Before that it blocks on its condition variable. Verified
  2026-09-26 (M4 Max): a window with a 50 ms relabelling timer posting mouse down/up pairs on its
  button, awaiting a reply held 2 s, got 40 relabels and 4 clicks at 0.16 s CPU.
- **`-[NSApplication run]` is never started** (it never returns; the thread is the module's).
  `appkit::%app`'s `performSelectorOnMainThread:withObject:waitUntilDone:` of `run` to an
  `NSApplication` is answered by marking the application started (and
  `activateIgnoringOtherApps:`), after which `pump` dispatches -- the one place the runner reads a
  selector's meaning (`prim.rs`); `appkit.lisp` is unchanged.
- **Sends: one generic import, no shape table.** The primitive layer pushes each raw argument
  (`p_arg_*`; `:s64` for an address), then `p_send(receiver, sel, types, fixed, mode)` answers a
  result KIND fetched with `p_result_*`. The host marshals by the encoding (`encoding.rs`, the twin
  of `TypeEncoding`, same refusals and messages) and calls `objc_msgSend` through `rl_objc_call`
  (`call.rs`): an assembly frame loading x0-x7, d0-d7, x8 and a stack area, with Apple's AArch64
  classification (HFA -> SIMD registers, struct > 16 B by reference, struct result through x8, stack
  arguments at natural alignment, variadic ones in 8-byte slots) -- so EVERY parseable selector is
  callable.
- **Ownership through `:extern`.** wasm-GC has no finalizer, but wasmtime's copying collector DROPS
  an `externref`'s host data when the reference dies (`sweep_extern_refs`, 49.0.0 source). A
  pointer's handle is the externref `p_new_handle` returns, whose host data holds the counts and
  queues the release of the `gc` share on drop. The queue runs only from the OUTERMOST host call --
  the end of a send, a `pump` turn -- never inside a callback (it could free the object whose method
  is running), nor between a send's argument pushes.
- Clicks can be checked without a hand by posting `NSEvent mouseEventWithType:...` down/up pairs
  through `postEvent:atStart:` (`pump` -> `sendEvent:` -> the button's tracking loop -> the action
  IMP). A human click on `counter.lisp` is still the GUI rule's check.

## appkit: where the line goes (widgets ship, layout stays an example)
The rungs (catalogue: `doc/**/reference/functions/appkit*.md`) are `appkit:` functions, not a second
built-in package: a package name is taken for good (`cocoa` is LispWorks' `COCOA`; the examples'
grid package is `board`). LAYOUT stays out (`examples/macos/board.lisp` is the grid alone).

- Two rungs no obvious `objc:invoke` reaches: a centred label needs the font's line height MEASURED
  (`appkit::%line-height`, a throwaway `sizeToFit` field once per font, cached by font pointer), and
  a clickable view needs a subclass (`appkit::panel-view` / `appkit::label-view` -- a panel is an
  `NSBox` --
  `RontoLispAppKitPanel` / `RontoLispAppKitLabel`, over the mixin `appkit::clickable`, whose
  `mouseDown:` / `rightMouseDown:` run the handler the view's Lisp object holds -- a panel and its
  label share one handler with no event forwarding).
- **Per-object state lives in a Lisp class, not an address-keyed table**: a button's or menu item's
  target is an `appkit::action` (`RontoLispAppKitAction`, one per control, its closure in a slot;
  AppKit holds a target weakly and the Lisp object keeps it, since `make-instance`'s reference is
  never given up), a timer's an `appkit::ticker` (`RontoLispAppKitTimer`). `on-click` on a button
  reuses its target (`objc-object-from-pointer` of `target`).
- `:dock nil` sets activation policy 1 on the shared `%app` started with policy 0. `set-text` /
  `text` test for the status item AHEAD of the button test (`appkit::%status-item-p`); a menu-bar
  program has no window, so `quit` sends `terminate:`. `on-click`'s handler takes the BUTTON NUMBER
  (the `java.awt.event` numbers, as a Swing handler reads); a button's own `:on-click` closure takes
  none.
- A rung costs a `PackageRegistry.APPKIT_FUNCTIONS` entry (library and registry agree EXACTLY,
  `AppKitLibraryTest`), a per-operator page + `_catalog.yaml` entry + guide-table row in BOTH
  languages, and blob growth: `AppKitLibrary.process` prepends the whole library, UNPRUNED, to every
  compiled `appkit:` program -- with the class half of `objc`, since it defines classes.

## Metal
`metal.lisp` is `webgl-common/gl.lisp`'s twin in substance, loaded lazily on the first `metal:`
resolution; no Java (Metal is Objective-C end to end). The exported names are in
`doc/**/reference/functions/metal*.md`; pixel formats, load/store actions, blend factors and storage
modes stay INTERNAL.

- Its splice runs BEFORE `AppKitLibrary`'s in `CompileFrontend` (`metal:run`'s clock is
  `appkit:timer`); `LibraryDefunPruner` keys it by name (`MetalLibraryTest`).
- **OpenGL cannot be reached**: `glClear` / `glDrawArrays` are plain C functions outside
  `objc_msgSend`. `MTLCreateSystemDefaultDevice()` is avoided --
  **`[[CAMetalLayer layer] preferredDevice]` is a property** -- else `am.ik.gpu`'s `MetalDriver`
  would have to be reached from `eval`, which the package graph forbids.
- The surface is a `CAMetalLayer` on `appkit:window`'s `contentView` -- **`setLayer:` BEFORE
  `setWantsLayer:`**, or AppKit makes its own layer and the one handed over never backs the view.
- `metal:attach :depth t` allocates a private `Depth32Float` texture that every pipeline drawing into
  it must DECLARE, so `metal:pipeline` reads the format off the context. Geometry rewritten every
  frame uses `metal:shared-buffer` + `metal:upload`, rotating THREE copies.
- **The per-frame byte paths skip the NSData**: `metal:upload` writes straight to the buffer's
  `contents`, `metal:uniform` stages in one growing scratch block (`metal::*scratch*`, used only
  inside a frame, on thread 0), both through `objc::%octets` / `objc::%write-octets` -- one send
  where the NSData route took five. `metal:buffer` (not per frame) goes through `objc:data`.
- **The mouse is `objc:define-objc-class`**: an `NSView` subclass defining `mouseDown:` /
  `mouseDragged:` / `mouseUp:` / `scrollWheel:` / `acceptsFirstMouse:`, set as the content view
  BEFORE `metal:attach` puts the layer on it (the examples and `scene::input-view`).
- A `linalg` result reaches the GPU with NO conversion (`:element-type 'single-float` is float32,
  every linalg transform preserves the width, [linalg.md](linalg.md)); one `linalg:transpose`
  bridges row-major storage to Metal's column-major `float4x4`.
- Checkable with NO display: `metal:offscreen` / `metal:pixels` render into an `MTLTexture` read back
  through `objc:bytes`; `metal:frame` takes the drawable's texture or the context's own, so there is
  ONE encoding path ([geom.md](geom.md)).
- **A frame that signals**: the callback guard answers zero, but an encoder released without
  `endEncoding` is a Metal ASSERTION (`abort()`, below any handler). `metal:frame` ends the encoder,
  presents and commits from an `unwind-protect` cleanup; the pin is a COMMIT
  (`SceneOffscreenRenderTest.aFrameWhoseBodySignalsIsStillEndedAndCommitted`). **General rule: a
  callback touching a native object with a begin/end protocol closes it on the signalling path
  too.**

## Package rules and the web build
`am.ik.objc -> (nothing)`; `eval -> am.ik.objc` through ONE class, `eval/ObjcPrimitives`, reached
only via `eval/ObjcInterop`'s five entry points (the `LinalgGpu` / `LinalgGpuKernels` shape), so
`src/web/java/.../Target_ObjcInterop.java` substitutes `ObjcInterop.registerPrimitives` and the
browser build carries no FFM. `cli` reaches the hand-over through `ObjcInterop`, never the library.
`MetalDriver` is the same runtime through a hand-written shape table and could ride on
`am.ik.objc`; it does not yet.

## Measurements
- **Per send** (macOS 26 arm64 / M4 Max, 20,000 sends after 2,000 warm-up; length / self /
  rangeOfString:), 2026-09-28: `java -jar` 23.0 / 30.8 / 31.2 us; JVM class 8.2 / 7.9 / 13.1;
  native binary 45.2 / 52.0 / 77.0; `--native` 1.10 / 0.85 / 1.95. Compiled, the Lisp layer is lost
  in the hop; interpreted, `objc.lisp` costs 12-23 us a send, on `--native` about 1 us.
  The deleted per-backend Java bridge measured 10.5 / 7.8 / 9.4 us (`java -jar`) and
  0.45 / 0.25 / 0.70 (`--native`); the single Lisp layer was chosen anyway: no per-frame loop in the
  tree is near those numbers, and the per-frame paths that would be (`metal:uniform`,
  `metal:upload`) copy straight into foreign memory.
- **The catching frame** (2026-09-29, `java -cp target/classes`, same method): before 22.7-23.5 /
  22.6-23.5 / 27.6-28.7 us, after 23.4-24.4 / 22.8-23.7 / 28.1-29.7, the same with
  `-Drontolisp.objc.catch=0` -- the frame costs nothing measurable; the ~0.5 us is `objc.lisp`'s.
- **`audio.lisp` live** (2026-09-29, macOS 26, M4 Max): the deadline is ~22.7 us a sample at
  44.1 kHz; frames rendered in 2 s (88,200 due; the engine takes a moment to start pulling):

  | target | one `(setf fli:dereference)` of a `:float` | frames |
  |---|---|---|
  | `java -jar` | 35 us -> 3.1 | 30,106-42,807 -> 87,495 (x3) |
  | native binary | 35 us -> 4.2 | 23,050-28,695 -> 87,495 (x3) |
  | JVM class | 0.195 us -> 0.051 | 88,436 / 87,965 |
  | `--native` | 1.39 us -> 0.34 | (live refused: a value-answering block off thread 0) |

  Method: 1,000,000 writes at `(logand i 1023)` after 200,000 warm-up, `get-internal-real-time`.
  Before, `%element` re-derived the size (`fli:size-of` -> `%fli-layout` -> `%parse-type`, ~15
  us; `%parse-type "f"` alone ~8.5 us, 6.7 of it one interpreted `find` over `"rnNoORVA"`) and
  `%store` parsed again. Now the pointer carries its element ("FLI"), the interpreter's keyword
  helpers are Java ([lambda-lists.md](lambda-lists.md)): 35 -> 3.5 us, and two per-call costs
  went ([interpreter-expansion-memo.md](interpreter-expansion-memo.md)): -> 3.1. What remains
  (JFR) is the evaluator's per-form work -- environments, dispatch, `setf` re-expanded per
  evaluation (~0.45 us). Measured and not taken: a `%peek` / `%poke` by kind code instead of
  encoding -- the primitive is ~0.1 us of the write interpreted (0.24 on the native binary), not
  worth three hosts' primitive layers.
- **Runner stub** (`cargo build --profile release-runner -p rlrun`, 2026-09-29): 1,849,072 B; the
  primitive layer's `p_*` imports cost 115,904 of it.

## Tests
Corpora (each against its `.expected`, interpreter / JVM class / `--native` byte for byte, printing
nothing address-dependent): `src/test/resources/objc-base-corpus.lisp`
(pinned as `objc-base-corpus.expected`; the manual's call-side examples, conversions,
ownership, identity -- every comparison and hash-table test, a method specializer, `subtypep` --
byte copies, `on-main` incl. "on-main propagates an error", refusals, the FLI forms: 1.3.5 / 1.3.7's
`scanInt:` by reference; `ensure-objc-initialized`'s `:modules` are `SymbolLookup.libraryLookup` /
`dlopen`), `objc-class-corpus.lisp` (1.3.7's `getValueInto:`, 1.4's `pair` result
variable, ivars, lifecycle, observers, a tail-padded nested structure through a Lisp method
`nested:` -- by `invoke`, into a foreign object and by `NSInvocation`, whose C-side call pins the
layout), `objc-block-corpus.lisp` (a block stopping through `BOOL *stop`; `--native` against
`objc-block-corpus-native.expected`, differing exactly on the lines naming the thread a block ran on
and whether a `dispatch_async` ran before the wait), `objc-exception-corpus.lisp`. The corpora's
methods use registered shapes so the native binary can run them.

- `eval/ObjcBaseTest` -- the base corpus; the recorded LispWorks answers
  (`src/test/resources/objc-lispworks-answers.lisp`, by hand from LispWorks Personal 8.1.2 on arm64,
  which cannot be scripted) in `ObjcBaseTest#theRecordedLispWorksAnswersHold`: `invoke` answers
  1/0 for a `BOOL` even encoded `B`; Foundation structures are doubles and 64-bit integers; `fli:size-of`;
  `objc-class-method-signature` names `(:struct cocoa:ns-range)` and prefers the instance method; a
  missing method is a `simple-error` reading
  `No method "x" for object #<Pointer: ...>, class "__NSCFString".` (the RUNTIME class).
- `eval/ObjcClassTest` (the macros on a machine with no runtime; no primitive redefined),
  `ObjcBlockTest` (declarations need no runtime; a worker sees the global values),
  `ObjcExceptionTest` (and the escaping method's stderr line) -- each also signals off-Mac;
  `eval/ObjcLibraryTest`.
- `codegen/jvm/JvmObjcBaseCompilerTest` -- the corpora compiled, the embedded class list, a
  calling-only program carries no class half, a program making no block no block half, the widget
  layer's gate and headless functions, a producer-built string.
- `codegen/jvm/JvmSizedMainTest`, opt-in `e2e/ShippedBridgeNativeImageE2eTest`,
  `ShippedBridgeClassFilesTest` -- the compiled thread-0 hand-over and the shipped registrations.
- `am.ik.objc.TypeEncodingTest`; `am.ik.objc.ObjcNativeImageForeignConfigTest` (downcall table,
  variadic grid, upcall shapes incl.
  `ObjcNativeImageForeignConfigTest#everyShapeTheDocumentedBlockExamplesUseIsRegistered` and
  `EXAMPLE_BLOCKS`, the `ObjcCatch` functions, every selector the corpus, shipped layers, examples and docs send).
- `eval/AppKitLibraryTest` (registry agreement; no defining macro survives the splice) /
  `eval/MetalLibraryTest` / `eval/SceneLibraryTest`; `SceneOffscreenRenderTest`; `PackageCycleTest`.
- `--native`: `eval/ObjcNativeLibraryTest` (primitives defined, the splice); `e2e/NativeObjcE2eTest`
  (macOS aarch64: the corpora against the interpreter, 300,000 answers of one object leave
  < 100,000 references, a timer during `sleep`, the event loop during a fetch's wait -- a timer
  counts turns during the await, an event posted before it is dequeued by the end -- an exit and a
  `throw` inside a method, a value-answering block called by `dispatch_async_f` on a worker refused,
  `scene:` offscreen pixels); the runner's `call.rs` / `encoding.rs` unit tests (`build.sh --test`),
  incl. `an_objective_c_exception_stops_at_the_call`.
- No test opens a window (CI has no display; the guide uses `console` fences so `DocExamplesTest`
  cannot hang). Last by-hand record (2026-09-29): `counter.lisp` on `java -jar`, the native binary,
  `-o Counter.class`, `-o counter.jar`; `minesweeper-macos.lisp`, `life-macos.lisp`, `menubar.lisp`
  on `java -jar`, the native binary and `-o Life.class`; `audio.lisp` offline on all five, live on
  the four that can run it; every `examples/macos` program under `--native`; the native binary
  (`-Pnative`) ran the base, class, block and exception corpora (stopping, where a shape is
  unregistered, with the entry to add -- `sum:plus:`, `jdouble(void*,jdouble,jdouble)`) and the
  guide's blocks (adder, comparator, enumerator on thread 0; `dispatch_async` and an `NSURLSession`
  completion handler on a worker). A widget-layer change travels into every compiled `appkit:`
  program.

## Open items
- No MAIN menu (a process with no bundle sets none), so no Cmd-Q on a windowed program.
- A variadic selector a PROGRAM sends in the string form is served only for the names in
  `objc::*variadic-selectors*`; the runtime offers no way to recognise another.
- x86_64: `objc_msgSend_stret` (struct returns wider than 16 bytes) has not been exercised.
- x86_64 (`java -jar`, a JVM class) and macOS before 14: `ObjcCatch` writes no frame, so an
  Objective-C exception inside a call still ends the process.
- `--native`: a macos-x86_64 runner has no Objective-C host (`call.rs` is Apple's AArch64
  convention, and there is no release stub).
- An interpreted render block writes sample by sample: `(setf fli:dereference)` is ~3 us
  (~4 on the native binary) of a 22.7 us budget ("Measurements"); a one-call copy of a Lisp
  array (~0.01 us a float) has no public spelling yet.
