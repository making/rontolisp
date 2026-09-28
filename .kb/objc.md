# `objc:` and `appkit:`: a native macOS window from the REPL, through FFM

- **`objc`** binds the Objective-C runtime and AppKit through `java.lang.foreign` --
  `am.ik.objc`, a language-independent library beside `am.ik.gpu`; the analogue of `java:`
  ([java-interop.md](java-interop.md)) minus reflection. Its vocabulary is LispWorks 8.1's `OBJC` /
  `COCOA` (*Objective-C and Cocoa Interface User Guide and Reference Manual*) and the part of its
  `FLI` the manual's examples use, plus this package's own `on-main`, `data`, `bytes`, `objectp`,
  blocks, exceptions and `invoke-with-error`.
- **`appkit`** is a widget layer written in rontolisp over `objc` (`appkit.lisp`), shipped inside
  the interpreter like `linalg.lisp`; **`metal`** (`eval/metal.lisp` + `MetalLibrary`) and
  **`scene`** ([geom.md](geom.md)) ship the same way.
- Docs: `doc/{en,ja}/guides/objc-appkit.md`. Examples: `examples/macos/*.lisp`, not in
  `examples.yaml` (whose `os: [mac]` field gates only RUN legs) except the two that open no window.

**Scope**: macOS only, on the interpreter, on JVM class output and in a `--native` executable for
`macos-aarch64` (below). Every other WASM output REFUSES a program referencing any of the macOS
packages (`CompileFrontend`, after load inlining, naming the reference) -- permanently: no WASM
runtime offers FFM or AppKit; only the runner stub of a native executable answers the imports. A
machine without the runtime SIGNALS at the call. Why it exists: the native binary is the REPL
people run, and `java:` cannot be INTERPRETED there (no reflection metadata); FFM needs none.

**History (2026-09-28/29)**: the package was rebuilt on this vocabulary. The first base -- ten
names (`class`, `send`, `define-class`, `string`, `data`, `bytes`, `address`, `objectp`, `object`,
`on-main`) over a Java bridge per backend (`ObjcBridge`, `JvmObjcTemplate`, `objc-native.lisp`'s
verbs, `ObjcClasses`' closed IMP shapes) -- was deleted once the libraries were ported. `data`,
`bytes` and `objectp` were rewritten in `objc.lisp`; `on-main` is the primitive layer's.

## The primitive layer: one semantics in Lisp over thin per-backend primitives
`eval/objc.lisp` (+ `ObjcLibrary`) is the WHOLE vocabulary -- argument and result conversion,
ownership, pools, tracing, the encoding parser, the variadic table, `cocoa:` -- written once and
run on every target: the interpreter loads it on the first resolution of an `objc:` / `cocoa:`
name, and the compile path splices it (`ObjcLibrary.process`, right OUTSIDE `AppKitLibrary`) for
a JVM class or a `--native` executable. Under it, per backend, the primitive layer:
`objc::%get-class`, `%class-name`, `%object-class`, `%class-p`, `%register-selector`,
`%selector-name`, `%method-types`, `%send`, `%new-handle`, `%refs`, `%interned`, `%intern`,
`%load-module`, `%initialize`, `%raised`, the byte copies `%octets`, `%write-octets`,
`%read-octets` (`LispNames.OBJC_PRIMITIVES`; the class and block halves add their own, below), and
the public `objc:on-main`.

- Interpreter: `eval/ObjcPrimitives` over `am.ik.objc` (`ObjcRuntime.sendRaw`,
  `ObjcReference`); JVM class output: `codegen/jvm/JvmObjcPrimitivesTemplate` (ships as
  `<Program>$ObjcPrimitives` beside the renamed library) compiled to by
  `JvmObjcPrimitivesCompiler`; `--native`: `eval/objc-native-primitives.lisp` over the `rlobjc`
  `p_*` imports (`runner/src/objc/prim.rs`). What differs between them is the value
  representation, never a rule.
- **`%send` is the whole call**: receiver address, SEL address, the encoding string (the method's
  own, or one built from a list-form method's FLI types), the variadic split (`-1`, or the number
  of FIXED method arguments), the raw arguments, and a mode. Raw means: an integer for every
  integral or address kind (BOOL as 0/1), a float for `f`/`d`, a list of leaves in memory order
  for a struct, and a Lisp STRING where the encoding says `@` (an autoreleased `NSString`) or `*` (a
  C string) -- made INSIDE the hop, alive for the call, gone after it, which is the manual's
  "released when the function returns". The answer is equally raw: integer / float / list of
  leaves / nil, except `*`, which is read into a Lisp string inside the hop (Foundation frees a
  `UTF8String` buffer with its pool). Mode bit 1: retain an `@` result inside the hop; bit 2: hand
  a `*` result back as an address; bit 4: retain what the last argument's slot holds (below).
- **Every `%send` runs in its own autorelease pool on thread 0** (interp/JVM: `MainThread.sync`
  plus push/pop; `--native`: the module is on thread 0, the host pushes/pops). Deterministic: an
  autoreleased result is dead the moment the send returns unless the hop retained it. This hop is
  why ownership cannot be LispWorks' raw manual retain/release (below): LispWorks sends on the
  calling thread, whose pool outlives the call.
- **Where the rules live in `objc.lisp`**: a PLAN per (lookup class, method name) caches the parsed
  encoding, the SEL, the mode and the family flags (`objc::%lookup-plan`; the lookup class is
  `object_getClass` of the receiver, so a class receiver finds class methods through its
  metaclass). The encoding parser maps to LispWorks' FLI descriptors (`objc::%fli-type`), and a
  block (`@?`) or function pointer (`^?`) is re-spelled `^v` before `%send` so every host parser
  takes it. A variadic argument of a list-form method travels promoted and WIDENED to a 64-bit slot
  (`q`/`d`/`@`), which is ABI-equivalent on both macOS ABIs and keeps the shape inside the native
  binary's variadic grid. The string form of a selector in `objc::*variadic-selectors*` types its
  extra arguments by value and appends the nil terminator; a list-form variadic call gets the same
  trailing nil, which keeps its shape in the native binary's grid (every entry there ends in
  `void*`). An integer argument past 2^63 travels as its two's complement (`objc::%bits64`: a wasm
  `:s64` import traps on a bignum); an unsigned 64-bit result or struct leaf comes back fixed up
  from the parsed type.
- **Two expansion traps the built-in macro `objc:with-autorelease-pool` hit** (it expands to
  `(objc::%call-with-autorelease-pool (lambda () ...))`): it is registered in
  `LispMacroExpander.expandBuiltinMacro` AND walked in both `FreeVarAnalyzer` walks, or the JVM
  compile of a body naming an outer variable dies with "closure over X whose binding left it
  unboxed" (the analysis never saw the lambda). And the interpreter's print routing is decided
  BEFORE a `print`'s argument runs, while the argument is what loads `objc.lisp` and its
  `print-object` methods: the print site loads the library first when the form names it
  (`ObjcLibrary.references`), as it does for torch and geom.
- **A pointer prints as LispWorks prints one**, `#<Pointer: OBJC:OBJC-OBJECT-POINTER =
  #x0000600000C04000>` (`OBJC:OBJC-CLASS`, `OBJC:SEL`): the address only. A first cut printed the
  class name through `object_getClass` and SIGSEGV'd when the message of a refused release printed
  the freed object.
- **Measured per send (2026-09-28, macOS 26 arm64, 20,000 sends after 2,000 warm-up;
  length / self / rangeOfString:)**: `java -jar` 23.0 / 30.8 / 31.2 us; JVM class 8.2 / 7.9 /
  13.1; native binary 45.2 / 52.0 / 77.0; `--native` 1.10 / 0.85 / 1.95. On a compiled class the
  Lisp layer is lost in the hop; interpreted, `objc.lisp` costs 12-23 us a send (the 15 us an
  uncached ARC family check cost is why the plan caches it), and on `--native` about 1 us. The
  deleted first base measured 10.5 / 7.8 / 9.4 us under `java -jar` and 0.45 / 0.25 / 0.70 on
  `--native`; decided for the single implementation: no per-frame loop in the tree is near those
  numbers, and the hop dominated `java -jar` either way. The per-frame paths that WOULD be
  (`metal:uniform`, `metal:upload`) skip the NSData and copy bytes straight into foreign memory
  ("Metal").
- **The recorded LispWorks answers** (`src/test/resources/objc-lispworks-answers.lisp`, recorded by
  hand from LispWorks Personal 8.1.2 on arm64 -- the Personal edition cannot be scripted) settle
  what the manual leaves open: `invoke` answers 1/0 for a `BOOL` even where it encodes as `B`;
  the Foundation structures are doubles and 64-bit integers; `objc-class-method-signature` names
  a structure `(:struct cocoa:ns-range)` and prefers the instance method; a missing method is a
  `simple-error` reading `No method "x" for object #<Pointer: ...>, class "__NSCFString".`
  (the RUNTIME class). `ObjcBaseTest#theRecordedLispWorksAnswersHold` asserts every entry.

## Ownership (the rule that makes manual-style code safe)
Each `objc-object-pointer` value carries a HANDLE (host object: interp/JVM `ObjcReference`,
`--native` an `externref` whose host data holds the counts) with three counts:

- `gc` -- references released on thread 0 when the handle is collected. An `@` result arrives with
  one: retained in the hop, or taken over from the `alloc` / `new` / `copy` / `mutableCopy` families
  (ARC's family rule: the word at the start, ignoring leading underscores, followed by the end or a
  non-lowercase character). The `init` family CONSUMES the receiver's reference (one `gc`, else one
  `manual`, taken without a message) and answers +1.
- `manual` -- `objc:retain` sends `retain` and counts one here. NOT released at collection: an
  explicit retain is the program's to release, and is how a program keeps an object (a delegate)
  alive after dropping the pointer, as in LispWorks.
- `pooled` -- references an emulated pool holds (below).
- A class pointer owns nothing: `retain` / `release` / `autorelease` on one change no count.

`objc:release` gives up one `manual`, else one `gc`, and sends `release`; a pointer holding
neither SIGNALS instead of over-releasing -- the double release manual-style code would otherwise
commit against the collector's own release. `objc:autorelease` gives up one the same way and hands
it to the innermost live pool, or, with none, back to `gc` (released at collection).
`(objc:invoke p "retain" | "release" | "autorelease")` routes to these three, so no spelling
bypasses the counts. Mutable state lives in the handle, never in a struct slot: `equal` and
`equalp` hash tables hash an instance by its slots on the interpreter and the JVM.

**Pools are emulated in Lisp**: a real `NSAutoreleasePool` pushed in one hop and popped in another
would interleave with the pools thread 0's own loop pushes. `with-autorelease-pool` binds
`objc::*autorelease-pools*`; `make-autorelease-pool` pushes one and answers it; `(release pool)`
drains it (and every pool made after it). Draining releases each pooled reference.

- **A window must still get `setReleasedWhenClosed:` NO** (`appkit:window` does): the close would
  otherwise release a reference the pointer's `gc` count still holds. Leaking is the safe
  direction everywhere here.
- **A long-lived value accumulates `gc` counts on the interpreter and the JVM**: every answer for
  its address adds one to the ONE live value, released only when it is collected -- so a button a
  program keeps sees its `retainCount` grow by one per callback that receives it. Harmless (the
  object lives anyway); what the counts guarantee is that nothing is released early.

## One representation, equal by address
`objc:objc-object-pointer` is a defstruct (address + handle) in `objc.lisp`, the same on all four
targets; `objc:objc-class` INCLUDES it (a Class is an object) and `objc:sel` is its own. Classes and
selectors are immortal and interned per address / name in Lisp tables. Object pointers: the
interpreter and the JVM INTERN them per address in a weak table (`ObjcReference`, `%interned` /
`%intern`), so `eq` holds by identity; `--native` cannot (no weak references in wasm-GC) and
compares by the address slot: `WasmLispCompiler.addressKeyedLayout` holds the
`objc-object-pointer` layout when the program carries it, and the eql arm (`_eql_tail`), `_equal`'s
and `_hash`'s instance arms and `_ihash` compare and hash such an instance by that first slot
alone. A module without the layout is byte-identical. A result whose object is a class answers the
interned `objc-class`. None of the three is a `structure-object`
(`LispMacroExpander.FOREIGN_POINTER_STRUCT_TAGS`, `isStructureObjectLayout`).

- **A pointer is a hash key**: with no address accessor in the vocabulary, a table keyed by an
  object uses the pointer itself (`eql` on every target). The shipped layers keep per-object Lisp
  state in a slot of a class they define instead ("Where the line goes").
- **Interning changes the bookkeeping, not the answers**: on the interpreter and the JVM every
  answer for an address adds a `gc` reference to the ONE live value, on `--native` each answer is a
  value of its own with one. So a program that releases a pointer more often than it received it is
  refused at a different call there; a program releasing what it owns sees the same `retainCount`
  deltas everywhere, which is what the corpus prints.
- **`fboundp` loads no library**: `(fboundp 'objc:invoke)` in a fresh interpreter answers nil until
  an `objc:` name was resolved (true of `appkit:` and `linalg:` too).
- The web build substitutes `ObjcInterop.registerPrimitives`, so `ObjcPrimitives`, the one
  `am.ik.objc` reference in `eval`, leaves the browser build.

## Bytes: `objc:data`, `objc:bytes`
`objc:data` answers an **`NSMutableData`** -- mutable so one value serves both directions (`bytes`
for a `^v` parameter, `mutableBytes` as writable scratch) -- holding a packed buffer's bytes
exactly as `write-sequence` writes them (little-endian, row-major, the elements only,
[binary-sequence-io.md](binary-sequence-io.md)) or a string's UTF-8; anything else signals.
`objc:bytes` answers an NSData's contents as a fresh `(unsigned-byte 8)` vector. Both are Lisp in
`objc.lisp` (`dataWithLength:` + `mutableBytes`; `length` + `bytes`) over three primitives:

- `%octets` -- the bytes of a value, as an octet vector, or nil. It is the representation's
  question, so it is the backend's: `eval/PackedBuffer` on the interpreter (also bfloat16 arrays and
  quantized matrices), `JvmObjcPrimitivesTemplate.bufferBytes` on the JVM (**a packed float array
  carries its dimension header IN the array** -- `[rank, dim_0..., e_0...]` -- and a packed integer
  vector is `byte[]{8, e_0, ...}` / `long[]{width, e_0, ...}`, so only the elements go on the wire,
  which is what `LispSingleFloatArray.data()` is), and Lisp on `--native`
  (`objc-native-primitives.lisp`: `%ieee754-single-bits`, lowered on wasm-GC for this; bfloat16
  and quantized refused).
- `%write-octets` / `%read-octets` -- a block copy between an octet vector and foreign memory
  (`ObjcRuntime.writeBytes` / `readBytes`; `--native`: `p_write_bytes` / `p_read_bytes`, a `:bytes`
  parameter and a `:bytes` result into a buffer the Lisp side allocates).

## Tests and where it runs
The corpus `src/test/resources/objc-base-corpus.lisp` is the manual's call-side examples in a
package that uses `objc`, plus conversions, ownership, identity (every comparison and hash-table
test, a method specializer, `subtypep`), the byte copies, `on-main` and refusals, printing nothing
address-dependent. `ObjcBaseTest` pins the interpreter's output (`objc-base-corpus.expected`) and
the LispWorks answers; `JvmObjcBaseCompilerTest` runs it compiled; `NativeObjcE2eTest` runs it as a
`--native` executable (and pins that 300,000 answers of one object leave < 100,000 references).
`ObjcNativeImageForeignConfigTest` names every selector the corpus, the shipped layers and the
docs send. `ensure-objc-initialized`'s `:modules` are `SymbolLookup.libraryLookup` / `dlopen`.

## FLI: foreign objects and pointers (2026-09-29)
`fli:with-dynamic-foreign-objects`, `allocate-foreign-object`, `free-foreign-object`,
`dereference` (+ `setf`), `foreign-slot-value` (+ `setf`), `size-of`, `pointerp`,
`pointer-address`, `make-pointer`, `null-pointer-p`, `pointer-eq` -- in `objc.lisp` (the macro in
`objc-macros.lisp`) over primitives that already existed: `calloc` / `free` through
`%symbol-address` + `%call-function`, reads and writes through `%peek` / `%poke` by encoding. No
new primitive and no new `rlobjc` import, so `--native` allocates in the runner's heap (never the
module's linear memory) exactly as the interpreter and the JVM do.

- **A foreign pointer is `fli::pointer`**, a defstruct of address, FLI type and the pointee's
  encoding (what `%peek` / `%poke` take; the FLI type's own, else `%unparse` of the parsed
  pointee for a structure the program never declared). Not a `structure-object`
  (`FOREIGN_POINTER_STRUCT_TAGS`), not interned, not `eql` by address -- `fli:pointer-eq` is.
  **Trap: rontolisp interns a defstruct's `:conc-name` accessors in the STRUCTURE NAME's package**,
  so `(:conc-name objc::%fp-)` on `fli::pointer` defined `FLI::%FP-ADDRESS` and every
  `objc::%fp-address` call was undefined; the accessors are `fli::%pointer-...`.
- **Every pointer Objective-C hands Lisp is one**, typed by its declaration: an `invoke` / C
  function result (`%result`'s `:pointer` arm -- it was an integer), a callback argument declared
  `(:pointer T)` (`%convert-argument`), a dereferenced pointer. Everything that takes an address
  also takes one (`%raw-address`, `:object`, `:cstring`), and a structure parameter takes one
  and COPIES it (the manual's "otherwise it is assumed to be a foreign pointer ... and is copied"),
  which is also what a method answering a structure may return. The byte primitives still take an
  integer: `objc:data`, `objc:bytes`, `metal:upload` and `metal::%stage` unwrap with
  `fli:pointer-address`.
- `invoke-into` a foreign object pokes the result into it (a `char *` result as its address,
  mode 2). A result variable (a non-keyword result style of `define-objc-method`) is a foreign
  object of the result type, answered and freed when the body returns. A structure ARGUMENT of a
  method or block still arrives as its Lisp value (vector / cons).
- `cocoa:set-ns-*` fill a foreign object as well as a vector / cons; the four structures' slots
  (`objc::*struct-slots*`) are `x y`, `width height`, `origin size`, `location length`, matched by
  symbol name. `define-objc-struct` records its slots there.
- **Layout is the C rule over named slots** (`objc::%fli-layout`): `fli:size-of` answers the
  recorded LispWorks sizes (asserted directly by `theRecordedLispWorksAnswersHold`), and an ivar
  of a declared structure type is sized by it. The hosts lay a PARSED structure out over its
  flattened leaves, which differs for a nested structure with tail padding (`.todo/a79`).
- Refused, never a crash: a null pointer, a `:void` pointee without `:type`, an aggregate
  dereferenced without `:copy-foreign-object` (LispWorks' `:error` default; `nil` answers a
  pointer, `t` a `calloc`'d copy), a Lisp string or vector stored where an object goes (it would
  not outlive the store).
- **Not `ffi:`'s pointer**: `ffi` is the interpreter's and the JVM's only (a Java
  `LispForeignPointer`), while `fli` must run on `--native`, where no `ffi` exists; one value
  would need a wasm-GC representation of a Java value. The bridge is the integer
  (`fli:pointer-address`), which both accept.
- `with-dynamic-foreign-objects` allocates on the heap (LispWorks: the stack) and frees on every
  exit.

Tests: the corpora -- the manual's 1.3.5 and 1.3.7 forms (`scanInt:` by reference in the base
corpus, the literal `getValueInto:` defined in the class corpus), the 1.4 `pair` result variable,
a block stopping through `BOOL *stop`, and every verb and refusal -- on the interpreter, a JVM
class and `--native`. The native binary (`-Pnative`, 2026-09-29) printed the base corpus
byte for byte, and ran the manual's forms, the `pair` result variable and the stopping block.

## Class definition (2026-09-28)
`define-objc-class` / `-method` / `-class-method`, `current-super`, `standard-objc-object`,
`objc-object-var-value`, `objc-object-copied` / `-destroyed`, `define-objc-struct` / `-typedef` /
`-protocol`, `cocoa:add-observer` / `remove-observer`. Two more files beside `objc.lisp`:

- `objc-macros.lisp` -- the defining macros, pure `cl`, each expanding into a call of
  `objc-class.lisp` with its types QUOTED (conversion is decided at run time, per call). **The
  compile path expands user macros BEFORE it splices libraries**, so `ObjcLibrary.withMacros`
  puts these `defmacro`s in front of `UserMacroExpander` (which drops them); the interpreter
  evaluates them with the library and loads it when a call names one (`definesMacro`, checked
  just before the user-macro lookup). The macro-time evaluator also runs the expansion's
  `defclass`, so `mentionsType` counts `objc:standard-objc-object` and that evaluator loads the
  library too. A method body becomes `(lambda (%current-super object pointer result args) ...)`
  -- ONE argument list, since a wasm lambda takes at most ten parameters; `current-super` is a
  macro expanding to that variable.
- **A shipped library that defines classes** (`appkit.lisp`, `scene.lisp`) is spliced AFTER that
  expansion, so its `process` splices `ObjcLibrary.expandDefinitions` of its forms -- the same
  expander over the same macros, run once and cached (`AppKitLibrary.expandedForms`); the
  interpreter evaluates the raw forms. `AppKitLibraryTest` pins that no defining macro survives.
- `objc-class.lisp` -- everything else, spliced only when the expanded program names one of its
  definitions or `standard-objc-object` (`referencesClassHalf`): a program that only calls
  carries no CLOS init protocol (an `appkit:` program always does). It plugs into `objc.lisp`
  through three hook variables (`*object-pointer-hook*`, `*pointer-object-hook*`,
  `*registered-pointer-hook*`) and `*realize-hook*`, so `objc.lisp` never names it. **A defun there
  must not reuse a primitive's name** -- a helper once called `%method-types` replaced the
  primitive (`ObjcClassTest`).

The primitive layer grows by eleven (`LispNames.OBJC_PRIMITIVES`): `%allocate-class`,
`%add-ivar`, `%register-class`, `%add-method`, `%add-protocol`, `%superclass`, `%send-super`,
`%ivar-offset`, `%ivar-types`, `%peek`, `%poke`.

- **`%add-method` (cls sel types function flags)**: the host installs an IMP that calls
  `function` with the receiver's address and the RAW arguments (the `%send` answer conventions;
  an object argument arrives RETAINED, which `%wrap-object` takes over) and marshals its raw
  answer by the encoding; flags 1 retains an object answer, 3 retains and autoreleases it (ARC's
  families decide which, `%result-flags`). Root methods pass 0: `+allocWithZone:` answers the
  super call's +1 as it is.
- **One IMP per METHOD, never per shape**: a super send runs the superclass's IMP for a receiver
  whose class defines the same selector, so a (receiver class, selector) lookup would find the
  subclass's body and recurse.
- JVM / interpreter: `am.ik.objc.ObjcMethods` binds `dispatch(Object, Object[])` once (a
  CONSTANT `findStatic`) and adapts it per method with `insertArguments` / `asCollector` /
  `asType` to the encoding's `FunctionDescriptor` -- ANY shape under `java`, struct arguments
  and results included. **The native binary serves the upcall shapes in the `rontolisp-objc`
  metadata and refuses any other at definition** (`ObjcRuntime.upcall`'s message names the
  entry); run-time handle combinators work in the image (verified 2026-09-28, GraalVM 25.0.3).
  Registered: the shapes the shipped layers' methods take (`v@:@`, `B@:@`, `v@:`, the root
  methods), `v@:@@`, `q@:@`, and the guide's class examples (`jint(void*,void*,jint,jint)`,
  `jint(void*,void*)`, `struct(jfloat,jfloat)(void*,void*)`), pinned by
  `ObjcNativeImageForeignConfigTest` (the shipped layers' `define-objc-method` forms are read and
  their shapes checked). Decided over a generated grid: exact integer widths are part of an
  upcall's shape (a 32-bit argument's upper register half is garbage on arm64), so a grid over
  widths, floats and structs has no useful bound.
- `--native`: `runner/src/objc/class.rs`. The IMP is `imp_implementationWithBlock` over a GLOBAL
  block whose invoke is the assembly `rl_objc_block_imp`: libobjc's trampoline puts the block in
  x0 and the receiver in x1 and leaves x2-x7, d0-d7, x8 and the stack as the caller laid them, so
  one entry saves them all and `Reader` takes each argument back by `call.rs`'s classification in
  reverse (HFA in d registers, struct > 16 B by reference, stack at natural alignment); the answer
  is written to x0/x1, d0-d3 or through x8. The block carries the method's index. The module's
  export `rlobjc_method(index, self)` reads the arguments with `p_cb_count` / `p_cb_arg` (answered
  like a send, fetched with `p_result_*`) and pushes its answer with `p_arg_*`; the pending
  arguments of the send in progress are saved around it.
- **A class the process defined is marked, not remembered**: every class pair gets the ivar
  `rontolispDefinedClass`, so a later definition of the same name -- another interpreter in the
  JVM, a compiled program's renamed copy of `am.ik.objc`, a re-evaluated form -- reuses it
  (replacing methods) while a class Lisp did not define is refused. A host-side table would not
  cross those copies; the runtime cannot remove a class.
- **Definitions wait for the runtime** (the manual allows the macros before
  `ensure-objc-initialized`): `%ready` -- `ensure-objc-initialized`, or the first `invoke` /
  class lookup -- realizes the queued classes in definition order and installs each recorded
  method on its class and on every subclass of a mixin ancestor.

### `standard-objc-object`, ownership, and `objc-object-destroyed`
- The instance has one slot, `objc::%objc-pointer%` (slots are matched by base name, so the name
  avoids a user's). `+allocWithZone:` (a root method, installed on the root of each Lisp-defined
  hierarchy only) makes or ADOPTS the Lisp object: `make-instance`'s `initialize-instance :around`
  sets the global `*adopting*` (a global value, not a binding -- the method runs on thread 0),
  sends `alloc`, then `init` or the `:init-function`, and keeps the final pointer; an object
  Objective-C allocated gets `(make-instance class :%objc-pointer address)`.
- `*lisp-objects*` (address -> instance) is STRONG, LispWorks' rule: the Lisp object lives until
  the reference count reaches zero, when `-dealloc` runs `objc-object-destroyed`, removes the
  entry and sends `dealloc` to super. The reference `make-instance` took (the `init` answer's
  `gc`) is the program's to `objc:release`. **The `Cleaner` cannot run first**: the pointer value
  is reachable from the registered instance until `-dealloc`, and after it the value holds no
  reference (the count reached zero), so the collector releases nothing.
- **A registered object has one pointer value on every target**: `%live-pointer` asks the
  intern table, then `*registered-pointer-hook*` (the instance's slot), so on `--native` too an
  answer for such an object adds its count to the ONE value `release` later gives up.
- A method's receiver is BORROWED (`%borrow`: the live value, or one holding nothing), never
  retained -- `-dealloc` runs on a receiver no one may retain.
- **A method that lets a Lisp error or a `throw` escape** is contained by
  `objc::%run-method`'s `handler-case` -- printed (`objc: error in a callback: ...`) and answered
  as zero, never an unwind through Objective-C's frames. **An exit inside is different**: it is
  not a Lisp condition (`handler-case` cannot see it), so it unwinds through the Lisp frames as a
  host exception until it reaches the Java-level upcall guard (`am.ik.objc.ObjcMethods` /
  `ObjcBlocks`), which recognizes it (`ProcessExit`) and ends the process with its code right
  there, never reporting it as an error -- the same on `--native`, the interpreter and the JVM.

Tests: `ObjcClassTest` (the corpus `objc-class-corpus.lisp` against `.expected`, the macros on a
machine with no runtime, no primitive redefined), `JvmObjcBaseCompilerTest` (the corpus compiled;
a calling-only program carries no class half), `NativeObjcE2eTest` (the corpus as `--native`, a
`throw` out of a method, an exit inside one), `ObjcLibraryTest`. The native binary (`-Pnative`,
2026-09-28): the manual's examples, ivars, lifecycle and observers print the corpus's lines; a
method of an unregistered shape (the corpus's `sum:plus:`) is refused with the entry to add.

## Blocks and C functions (2026-09-28)
`make-objc-block`, `free-objc-block`, `with-objc-block`, `call-objc-block`,
`define-objc-block-type`, the type `objc-block`, `objc-block-pointer`, `objc-block-live-p`, and
`fli:define-foreign-function`. LispWorks' `OBJC` has no block interface (its FLI makes blocks),
so the block names are this package's own; `fli` is a built-in package ("FLI" above).

- **No implicit wrapping, decided**: a method's encoding says `@?` and never what the block
  takes (the extended `@?<...>` appears in protocol metadata only), so a Lisp function passed
  where a block goes SIGNALS, naming `make-objc-block`. A wrong guess would be a crash.
- `objc-block.lisp` (spliced when the expanded program names one of its definitions or
  `objc-block`, `ObjcLibrary.referencesBlockHalf`) plugs into `objc.lisp` through
  `*block-pointer-hook*`: an `objc-block` stands for its literal's address where a block, a
  pointer or an object goes, and a FREED one signals there. A block's arguments and answer go
  through the conversions a method's do -- `%convert-argument`, `%callback-answer`,
  `%zero-answer`, `%declared-type`, in `objc.lisp` so both halves share them. A Lisp error inside
  prints `objc: error in a callback: ...` and answers zero.
- **Four primitives**: `%make-block (types signature function)` answers the literal's address;
  `%free-block`; `%call-function (address types fixed args mode)` -- a C call through an
  address, the `%send` raw conventions, `types` covering every argument -- which
  `call-objc-block` makes on the invoke pointer (`%peek` of the literal at +16) and
  `fli:define-foreign-function` on a `dlsym` answer; `%symbol-address` (`dlsym(RTLD_DEFAULT)`).
  `types` spells the block itself `^v`; `signature` (the descriptor's, for `_Block_signature`)
  spells it `@?`.
- **The literal** (`am.ik.objc.ObjcBlocks`, `runner/src/objc/block.rs`): isa
  `&_NSConcreteStackBlock`, flags `BLOCK_HAS_COPY_DISPOSE | BLOCK_HAS_SIGNATURE`, invoke,
  descriptor, then an ID. The ID travels INSIDE the literal because `_Block_copy` copies the
  literal to the heap whenever a callee keeps it, and the copy must find the function; IDs are
  never reused, so a block called after its function is gone is reported, never routed to
  another. A STACK isa, not a global one: `_Block_copy` of a global block answers the same
  pointer, so the storage would have to outlive every holder; `_Block_release` of our own storage
  is a no-op (`BLOCK_NEEDS_FREE` clear). The copy/dispose helpers count HOLDERS per ID (the Lisp
  value, plus each live copy); `free-objc-block` gives up the Lisp value's, so freeing a block
  a queue kept is safe -- `with-objc-block` around a `dispatch_async` is the idiom. A block
  never freed leaks (as in LispWorks; no finalizer). The JVM's literal is `malloc`'d, not an
  arena's: a native image serves no shared arena (`Arena.ofShared().close()` threw
  `UnsupportedFeatureError` in the binary), and a block may be freed from any thread.
- **Interpreter / JVM: a block runs on the thread that calls it.** One FFM upcall stub per block
  SHAPE, all landing in `ObjcBlocks.dispatch`, which finds the function by the ID and converts
  by the block's own encoding (two blocks of one shape may convert differently). A libdispatch
  worker is attached by the JVM and runs the function concurrently with the program, with the
  interpreter's rules for a thread (`.kb/threads.md`): no dynamic binding of the program's thread
  is visible, a closure built inside one still reads its capture. Foundation calls a comparator
  or an enumerator on the sending thread, which is thread 0 (every send hops there). The
  helpers are upcalls too (`void(void*,void*)`, `void(void*)`): no Lisp runs in them.
- **`--native`: the module is thread 0's, so only thread 0 enters it.** The invoke function is
  the IMP assembly entry `rl_objc_block_imp` (a method block and a program's block tell apart by
  the descriptor; same header, the ID where the method index is). A block arriving inside a host
  call on thread 0 (`CALLER` set: a send, `dispatch_sync` through `p_call_function`, a pump turn)
  re-enters through the `rlobjc_method` export -- a block's function joins the methods' table,
  called with no receiver. One arriving elsewhere: a `void` block is QUEUED (object arguments
  retained, C strings copied, one holder taken) and a no-op `dispatch_async_f` to the main queue
  makes a pump turn in progress return; `pump` drains the queue each turn, so the block runs at
  the program's next `sleep`. A block answering a value is refused: printed, zero answered.
  Dead IDs are reported to the module by `p_block_reap` and dropped from its table on the next
  make or free.
- **C functions run on the CALLING thread** (`ObjcRuntime.callRaw`, in a pool of that thread),
  never hopped: a function that waits (`dispatch_sync`, a semaphore) must not hold thread 0
  while a block it waits for needs it. `callRaw` binds UNBOUND downcall handles per shape (the
  target an argument). An object result follows Core Foundation's Create rule, which libdispatch
  follows too: a foreign name containing `Create` / `Copy` / `_create` / `_copy` hands over +1,
  anything else is retained in the call.
- **`@?` parses as an object** in `TypeEncoding` and `encoding.rs` (extended `@?<...>` skipped),
  no longer refused: a send only passes the block's address.
- **The native binary** serves the block shapes registered under `foreign.upcalls` (the guide's
  adder `jint(void*,jint,jint)` and enumerator `void(void*,void*,jlong,void*)` joined the method
  shapes, which already cover a comparator, a work item, a three-object completion handler and
  the helpers) and refuses any other when the block is made, naming the entry;
  `ObjcNativeImageForeignConfigTest#everyShapeTheDocumentedBlockExamplesUseIsRegistered`.
  Verified by hand (`-Pnative`, 2026-09-28): the guide's adder, comparator, enumerator (on
  thread 0), a `dispatch_async` and an `NSURLSession` completion handler (on a worker) run; the
  corpus stops at its first unregistered shape (`jdouble(void*,jdouble,jdouble)`) with the entry
  to add.

Tests: `ObjcBlockTest` (the corpus `objc-block-corpus.lisp` against `.expected`; declarations
need no runtime; a worker sees the global values), `JvmObjcBaseCompilerTest` (the same bytes
compiled; a program that makes no block carries no block half), `NativeObjcE2eTest` (the corpus
against `objc-block-corpus-native.expected`, which differs exactly on the lines naming the
thread a block ran on and whether a `dispatch_async` ran before the wait; a value-answering
block called by `dispatch_async_f` on a worker is refused), `ObjcLibraryTest`,
`TypeEncodingTest`, the runner's `encoding.rs` tests.

## Exceptions and NSError (2026-09-29)
`objc:objc-exception` (readers `objc-exception-name`, `-reason`, `-object`), `objc:ns-error`
(`ns-error-domain`, `-code`, `-description`, `-object`) and `objc:invoke-with-error`, all in
`objc.lisp`. Before them an `NSException` raised inside a send ended the process ("Terminating app
due to uncaught exception"): `objc_exception_throw`'s unwinder searches up for a frame whose
personality claims it, reaches the FFM stub / JIT frames or the wasm host frames, which have no
unwind information, and `std::terminate` runs.

- **THE decision: a real `@catch (id)` frame under every call, not the uncaught-exception
  handler.** Every `objc_msgSend`, `objc_msgSendSuper` and `%call-function` target is called
  through a native frame whose call site an LSDA covers -- one handler, type `OBJC_EHTYPE_id`,
  under `__objc_personality_v0`. Phase 1 stops there; phase 2 unwinds the callee's frames WITH
  their cleanups (`@finally`, `@synchronized`, C++ destructors), so **nothing is abandoned between
  throw and catch** -- the frames skipped are exactly the ones Objective-C's own `@catch` would
  unwind. An exception Cocoa catches deeper never reaches the frame; a C++ exception of another type
  passes it and terminates as before. The rejected alternative, `objc_setUncaughtExceptionHandler`
  plus a non-local exit to below the send, abandons the callee's frames without their cleanups and
  leaves libc++abi's caught-exception list stale (one entry per exception), and it only runs once
  the search has FAILED -- on the JVM that search would first have to get through the JIT frames
  above the send (and on thread 0, `_dispatch_client_callout`'s catch-all, which terminates).
- **The landing pad** takes the exception (`objc_begin_catch`), retains it, ends the catch and
  hands the address up; the call answers nothing. The host's `%send` / `%send-super` /
  `%call-function` then answer nil and keep the retained address for `objc::%raised` (nil when the
  last call raised nothing, else the address, 0 for a thrown nil; reading clears it).
  `objc::%checked` wraps EVERY call of those three in `objc.lisp`, `objc-class.lisp` and
  `objc-block.lisp`: a nil answer asks `%raised`, and a raise becomes
  `objc::%signal-exception` -- the object wrapped (taking over the +1), its `name` / `reason` read
  when it `isKindOfClass:` `NSException`, else the class name and no reason. The report names the
  call: `-objectAtIndex: raised NSRangeException: ...` (`+` to a class, a C function by name).
  **A new call site of those primitives must go through `%checked`**, or a raise reads as nil and
  its reference leaks; the hosts clear the record at the start of each call, so it is never
  misattributed.
- **Which frame catches: the innermost call on that thread.** A method or block defined in Lisp
  that lets one escape is a Lisp error inside a callback -- printed (`objc: error in a callback:
  ...`), answered as zero -- never an unwind through Objective-C's frames.
- **`invoke-with-error`**: `calloc`s a pointer slot (`%symbol-address` + `%call-function`), passes
  it as the last argument, and sends with mode bit 4 (`ObjcRuntime.RETAIN_OUT`, `prim.rs`
  `RETAIN_OUT`): the host retains what the slot holds after the call, inside the hop, since the
  `NSError` is autoreleased into the pool the hop drains. It signals `ns-error` when the result
  says failed (nil, `NO`, zero, void) AND an error was written -- Foundation's rule: the RESULT
  says whether a call failed, not the slot; a failure with no error answers the result. The
  method name must end in `error:`. `metal:library` / `metal:pipeline` are written over it, so a
  bad shader's diagnostics are the condition's description.

### Interpreter and JVM class output: machine code written at run time (`am.ik.objc.ObjcCatch`)
rontolisp ships no native code, and the catching frame must be native and sit between the FFM
stub and the callee, so `ObjcCatch` writes one: AArch64 instructions into an `mmap`ped page made
read-execute for good (never written again -- a page rewritten while another thread runs it would
fault, and HotSpot's W^X state is per thread, so `MAP_JIT` toggling from Java would take the JIT's
own code cache away mid-call). `java` carries `allow-unsigned-executable-memory`; a native image
is not hardened. Verified: `mprotect` to `PROT_READ|PROT_EXEC` under both (2026-09-28).

- **A slot forwards the call unchanged**: x0-x7, d0-d7 and x8 untouched; it copies the caller's
  stack-argument area under its own 32-byte frame, sized by an upper bound from the
  `FunctionDescriptor` (every argument at its size rounded to 8, plus 8; in 64-byte steps, at most
  4032 -- a wider shape is called directly, uncaught). The callee, the size and the recorder are
  in a per-slot DATA cell, so a slot is keyed by (target, size) and the code never changes. The
  downcall handle is bound to the slot with the callee's own shape, so the native binary's shape
  table serves it unchanged.
- **The recorder** is an upcall (`void(void*)`, already registered) into the copy of `ObjcCatch`
  that allocated the slot; it keeps the address in a `ThreadLocal`, which `ObjcRuntime` reads
  right after the downcall and turns into `ObjcRaised` (an `ObjcException` carrying the retained
  address). `MainThread.sync` carries it back to the Lisp thread; the primitive layer answers nil
  and keeps it for `%raised`.
- **The unwinder finds the code through `__unw_add_find_dynamic_unwind_sections` (macOS 14)**: a
  finder, also written there, answers for a region's code page with its `.eh_frame` (one CIE with
  `zPLR` -- absolute personality, LSDA and addresses -- and one FDE per slot, all naming one LSDA)
  and a stand-in `mach_header` (arm64, subtype ALL) as `dso_base`. **Trap: `__register_frame` of
  a dynamic FDE registers it with `dso_base` 0, and Apple's `unw_set_reg` reads the CPU subtype of
  the image a new IP lies in -- SIGSEGV at the landing pad** (measured 2026-09-28, macOS 26.3).
- **One chain of regions per PROCESS**: libunwind keeps a short fixed table of finders, and a JVM
  holds a copy of `am.ik.objc` per compiled program it loads (plus the interpreter's) -- a finder
  per copy ran out inside `JvmObjcBaseCompilerTest` and the next raise terminated. The first
  region is published in the system property `rontolisp.objc.catch` (hex address; JVM-wide,
  never inherited by a child process), the rest chained from it, one finder walks them, and every
  copy allocates under one JVM-wide lock (`System.getProperties()`). 120 slots a region, at most
  16 regions; past that, or without the finder API, or on x86_64, a call goes straight to its
  callee and an exception still ends the process.
- Registered for the native binary: `mmap`, `mprotect`, `sys_icache_invalidate` and the finder
  registration (`reachability-metadata.json`, `ObjcNativeImageForeignConfigTest`). Travelling:
  `ObjcRaised`, `ObjcCatch`, `ObjcCatch$Asm`, `ObjcCatch$Bytes` (`JvmObjcRuntimeBuilder`).

### `--native`: the catch is in `rl_objc_call` (`call.rs`)
The same LSDA in `global_asm!`: `.cfi_personality 155, _rl_objc_personality`, `.cfi_lsda` to a
`__gcc_except_tab` table whose one type entry is INDIRECT through `rl_objc_ehtype_slot`. libobjc is
dlopened at run time, so the stub cannot name `__objc_personality_v0` or `OBJC_EHTYPE_id`: the
personality is a forwarder and the slot is filled by `call::install` when the runtime opens
(before that the forwarder answers "continue unwinding"). The landing pad calls `rl_objc_caught`,
which marks the `Frame`; `Call::invoke` answers `Err(Raised(thrown))`; `prim.rs` answers the result
kind 5 (`RAISED`, the address through `p_result_int`), which `objc-native-primitives.lisp` keeps
for its `%raised`. The runner's own test `an_objective_c_exception_stops_at_the_call` throws
through it.

Measured per send (2026-09-29, M4 Max, `java -cp target/classes` interpreted, same method as above:
20,000 after 2,000; length / self / rangeOfString:): before 22.7-23.5 / 22.6-23.5 / 27.6-28.7 us,
after 23.4-24.4 / 22.8-23.7 / 28.1-29.7 us, and the same with catching switched off
(`-Drontolisp.objc.catch=0`, which publishes "no region") -- the frame costs nothing measurable;
the ~0.5 us is `objc.lisp`'s. A first cut that called `%checked` on every answer and took the
slot flag as an `&optional` cost 1.5-3 us: `%checked` runs only on a nil answer, `out` is required.

Tests: `ObjcExceptionTest` (the corpus `objc-exception-corpus.lisp` against `.expected`, and the
escaping method's stderr line), `JvmObjcBaseCompilerTest` (the same bytes compiled),
`NativeObjcE2eTest` (the same bytes as `--native`). The native binary (`-Pnative`, 2026-09-29)
printed the same file by hand; the corpus's methods use registered shapes for that reason.

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
- **Compiled `main`: the same hand-over, in bytecode** (`JvmSizedMainBuilder`, below). A native
  image of an `objc:` jar is the native binary's situation exactly.
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
  ([dynamic-special-variables.md](dynamic-special-variables.md)). An exception inside a `sync`
  body IS carried back and rethrown on the caller's thread, so a Lisp non-local exit through
  `objc:on-main` works (the corpus's "on-main propagates an error").

## Where the line goes: widgets ship, layout stays an example
`appkit` carries `color`, `font`, `panel` (an `NSBox`), `set-color`, `on-click`, `timer`, a
vertically centred `label`, `:background`/`:dark` on `window`, `status-item`, `menu`, `quit`,
`&optional` on `wait`. LAYOUT stays out (`examples/macos/board.lisp` is the grid alone).

- Two rungs cannot be reached by an obvious `objc:invoke`: a centred label needs the font's line
  height MEASURED (`appkit::%line-height`, a throwaway `sizeToFit` field once per font, cached by
  font pointer), and a clickable view needs a subclass (`appkit::panel-view` /
  `appkit::label-view`, `RontoLispAppKitPanel`/`RontoLispAppKitLabel`, over the mixin
  `appkit::clickable`, whose `mouseDown:`/`rightMouseDown:` run the handler the view's Lisp object
  holds -- a panel and its label may share one handler with no event forwarding).
- **Per-object state lives in a Lisp class, not an address-keyed table**: a button's or a menu
  item's target is an `appkit::action` (`RontoLispAppKitAction`, one per control, its closure in a
  slot; AppKit holds a target weakly and the Lisp object keeps it, since `make-instance`'s
  reference is never given up), a timer's target an `appkit::ticker` (`RontoLispAppKitTimer`).
  `on-click` on a button reuses the target it has (`objc-object-from-pointer` of `target`).
  `:dock nil` sets activation policy 1 on the shared `%app` started with policy 0. `set-text`/
  `text` test for the status item AHEAD of the button test (`appkit::%status-item-p`); a menu-bar
  program has no window, so `quit` sends `terminate:`.
- The rungs are `appkit:` functions, NOT a second built-in package: a package name is taken for
  good (`cocoa` is LispWorks' `COCOA`, and the examples' grid package is `board`). `on-click`'s
  handler takes the BUTTON NUMBER (the `java.awt.event` numbers, so a Swing handler reads the
  same); a button's own `:on-click` closure takes none.
- A rung costs a `PackageRegistry.APPKIT_FUNCTIONS` entry (library and registry must agree
  EXACTLY, `AppKitLibraryTest`), a per-operator page + `_catalog.yaml` entry + a guide-table row in
  BOTH languages, and blob growth: `AppKitLibrary.process` prepends the whole library, UNPRUNED,
  to every compiled `appkit:` program -- with the class half of `objc`, since it defines classes.

## The send's shape comes from the encoding; the native binary serves a CLOSED table
`method_getTypeEncoding` describes every selector completely; `TypeEncoding` parses it into a
`FunctionDescriptor` (struct flattened to scalar leaves) and `ObjcRuntime.sendRaw` binds one
`objc_msgSend` handle PER DISTINCT SHAPE -- Apple's arm64 rule; **never through the variadic
declaration, since an `NSRect` through a `long` shape is a SIGBUS** -- calling it with
`invokeWithArguments`, which a native image serves. A wrong selector, arity or operand type is an
`ObjcException` -> a Lisp `error`, never a crash. Unions, bitfields and function pointers are
refused by name; a block (`@?`) is an object.

- **"Describes every selector completely" is false for a VARIADIC one**, which is declared byte
  for byte like its fixed-arity twin (`arrayWithObjects:` and `arrayWithObject:` are both `@@:@`).
  On arm64 a variadic argument goes on the STACK and a fixed one in a register, so the declared
  shape leaves the callee walking its `va_list` off a slot nobody wrote -- SIGSEGV in
  `objc_retain`. Nothing in the runtime marks it, so the family is a TABLE OF NAMES,
  `objc::*variadic-selectors*`: the nil-terminated constructors and the format-string family. A
  selector in it takes arguments PAST the declared arity, each typed by its VALUE (object /
  `q` / `d` -- what `va_arg` reads for `%@`, `%ld`, `%f`); `objc.lisp` appends the nil terminator
  ITSELF and passes the split, which `sendRaw` binds with `Linker.Option.firstVariadicArg`. **The
  appended nil is unconditional**: the nil-terminated half needs it and a `printf`-style callee
  never reads past its format, which is what keeps ONE rule instead of two -- and makes the last
  variadic argument always `void*`, which is what bounds the native table. A variadic selector a
  PROGRAM sends in the string form without being in the table is still the crash; the list form's
  `:variadic-num-of-fixed` is how a program says so. `ObjcRuntime.Signature` (descriptor + split,
  `-1` when fixed) is the `sends` cache key, since the same layouts called variadically are a
  different stub.
- A native image builds a downcall stub only for a shape registered at build time
  (`MissingForeignRegistrationError` at `Linker.downcallHandle`), so the served set is a CLOSED
  TABLE in a file of its own, `META-INF/native-image/am.ik.rontolisp/rontolisp-objc/
  reachability-metadata.json` (rontolisp's binary reads it beside the main file; a compiled
  program carries a copy, "The JVM backend" below): the runtime's own C functions, every shape
  `appkit.lisp` / `metal.lisp` / `scene.lisp`, `objc.lisp` itself and the documented examples
  send, the 60 most common shapes of a census over 29 core AppKit/Foundation classes (13,065
  methods, 90.6% reached), plus `NSTimer`'s `scheduledTimerWithTimeInterval:...`. A selector
  outside the table signals with the exact entry to add; the JVM registers nothing, so `java -jar`
  is where a program discovers what it sends. **A new selector in `appkit.lisp`, `metal.lisp`,
  `scene.lisp`, `objc.lisp`, the `examples/macos` programs the test names, or the docs is a row in
  `ObjcNativeImageForeignConfigTest`'s table.** The variadic sends are their own 144-entry grid,
  generated from a RULE the same test restates and pins in both directions: three fixed halves
  (`void*(void*,void*,void*)` and `void(void*,void*,void*)` splitting at 3,
  `void(void*,void*,void*,void*)` -- `raise:format:` -- at 4) crossed with 1-12 variadic
  arguments, every carrier combination up to 4 and `void*` only past it; the test reads the
  selector names off `objc.lisp`.
- In the native binary every send through that table is INTERPRETED by SubstrateVM's method-handle
  interpreter -- a handle created at run time has no AOT code, ~1.7 us a call plus ~0.4 us per
  argument on top of `invokeWithArguments`' own boxing (`.kb/gpu.md`, "An FFM downcall inside a
  native image costs", `.todo/727`; measured on Linux/aarch64 through the same SVM path, not on
  macOS). The route that takes the floor out exists since `.todo/729` (`src/native/java`,
  `.kb/native-downcalls.md`) and was not applied here: it is one `@InvokeCFunctionPointer` method
  per SHAPE, and the send table is a generic per-selector shape set that no macOS box in reach can
  measure.
- Every Metal object is PROTOCOL-typed (`id<MTLDevice>`) with a private concrete class, so the test
  has a `proto(...)` row (resolved through `protocol_getMethodDescription`, which the test binds
  itself -- the binding never asks a protocol); Metal's DESCRIPTOR classes are the reverse
  (`alloc` answers `...Internal`), so it falls back to that name.

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
  context. Geometry rewritten every frame uses `metal:shared-buffer` + `metal:upload`, rotating
  THREE copies.
- **The per-frame byte paths skip the NSData**: `metal:upload` writes the bytes straight to the
  buffer's `contents` and `metal:uniform` stages them in one growing scratch block
  (`metal::*scratch*`, used only inside a frame, which runs on thread 0), both through the
  primitives `objc:data` is written over (`objc::%octets`, `objc::%write-octets`) -- one send a
  call where the NSData route took five. `metal:buffer` (not per frame) goes through `objc:data`.
- **The mouse is `objc:define-objc-class`**, not an `appkit` rung: an `NSView` subclass whose
  `mouseDown:`/`mouseDragged:`/`mouseUp:`/`scrollWheel:`/`acceptsFirstMouse:` are defined in Lisp,
  set as the content view BEFORE `metal:attach` puts the layer on it (the examples and
  `scene::input-view`).
- A `linalg` result reaches the GPU with NO conversion; `:element-type 'single-float` picks float32
  and every linalg transform preserves the width ([linalg.md](linalg.md)); one `linalg:transpose`
  bridges row-major storage to Metal's column-major `float4x4`.
- Checkable with NO display: `metal:offscreen`/`metal:pixels` render into an `MTLTexture` and read
  back through `objc:bytes`; `metal:frame` takes the drawable's texture or the context's own, so
  there is ONE encoding path ([geom.md](geom.md)).

### A frame that signals: the callback guard is not the last word
A method defined in Lisp catches the error (`objc::%run-method`), prints it and answers the
method's zero value, and that guard holds under native-image too. What kills the process is
DOWNSTREAM: an encoder released without `endEncoding` is a Metal ASSERTION, and an assertion is an
`abort()`, which no handler can be under. Fix: `metal:frame` ends the encoder, presents the
drawable and commits the buffer from an `unwind-protect` cleanup. The pin is a COMMIT, not a crash
(`SceneOffscreenRenderTest.aFrameWhoseBodySignalsIsStillEndedAndCommitted`). **General rule: a
callback that touches a native object with a begin/end protocol must close it on the signalling
path too.**

## The JVM backend: the binding travels beside the class, and calls back into it
`-o Prog.class` / `-o lib.jar` uses the `--gpu` route ([gpu.md](gpu.md),
[template-class-embedding.md](template-class-embedding.md)): every class file of `am.ik.objc` is
renamed by one prefix rule (`am/ik/objc/` -> `<Program>$Objc`) and ships beside the program through
`runtimeClassFiles()`, with `JvmObjcPrimitivesTemplate` -> `<Program>$ObjcPrimitives` (ONE class
file: no nested class, no enum `switch`, which lowers to a synthetic `$1` the builder does not
ship); the emitted `_objcInit` only binds, on the first primitive call. `JvmObjcRuntimeBuilder`
owns the list (pinned by `JvmObjcBaseCompilerTest#theProgramShipsTheWholeLibrary`).

- **It makes UPCALLS into the program**: a method, a block and an `on-main` body are applied
  through `_apply`, handed over by `bind(Class)` from `_objcInit`, which is why the objc runtime
  forces `usesEval` and roots `_apply` for the shaker. `bind` hands over `_strv` the same way
  (nullable -- absent exactly when the program has no array runtime).
- **The gate is the primitive layer**, qualified (`JvmObjcPrimitivesCompiler.names()`):
  `objc.lisp`, spliced by the front end, calls it, and an `appkit:` program reaches it through the
  spliced widget layer. A program whose pruned `objc.lisp` calls no primitive (`(objc:objectp 1)`)
  carries no binding.
- **The same gate adds the thread-0 hand-over to the compiled `main`.** An `objc:` class gets the
  sized-worker launcher every class with a `main` gets ([interpreter-stack.md](interpreter-stack.md),
  `JvmSizedMainBuilder`), headed by the question `RontoLispCli.main` asks: when the program's own
  `<Program>$ObjcMainThread.handOverRequired()` answers true, `main` marks the launcher instance
  `_main$exit`, starts the worker and parks in `get().runLoop()`; the worker ends the process --
  `System.exit(0)`, or the throwable dispatched to its thread's uncaught-exception handler (the
  `Exception in thread "main"` echo) and `System.exit(1)`. Under `java` the answer is false before
  anything native is bound (no `org.graalvm.nativeimage.imagecode`), and `main` joins the worker as
  for any class. Pinned by `JvmSizedMainTest` (the shape; and, macOS, the hand-over simulated on
  the JVM: `-XstartOnFirstThread` puts `main` on thread 0 and
  `-Dorg.graalvm.nativeimage.imagecode=runtime` makes the question answer true -- an
  `appkit:timer` fires only once thread 0 is handed over, and an uncaught condition still reports
  and exits 1) and by `ShippedBridgeNativeImageE2eTest#anObjcJarRunsAsANativeImageThatHandsThreadZeroToAppKit`
  (macOS, opt-in: a real image, a timer clicking and closing its own window, a 60 s deadline).

Under the `java` launcher thread 0 is already parked, so no hand-over arises. A bare `.class`
without `--enable-native-access=ALL-UNNAMED` gets the JDK's one-time warning and works; a `.jar`
carries `Enable-Native-Access: ALL-UNNAMED` in its manifest (`JvmJarWriter`). Each compiled program
ships its own copy as class files named after it (`<Program>$Objc*`,
[template-class-embedding.md](template-class-embedding.md)); a class a program defines is marked,
so a second copy in one JVM reuses it ("Class definition").

**An image of a compiled `objc:` program needs no configuration** (since 2026-09-27). The image is
built from the user's jar or class directory, which holds none of rontolisp's `META-INF`, so
`JvmObjcRuntimeBuilder` ships two registrations beside the `$Objc*` classes, each in a directory
named after the program: `META-INF/native-image/rontolisp-objc/<program>/` is a verbatim copy of the
`rontolisp-objc` file above, and `rontolisp-objc-bridge/<program>/` registers the two program methods
`JvmObjcPrimitivesTemplate.bind` finds by name, `_apply(Object,Object)` and `_strv(Object)`. Without
the first every send refuses its stub; without `_apply` the program stops at `objc: no _apply
method`; without `_strv` the first string the program BUILT (`format nil`, `concatenate`) dies in
`MissingReflectionRegistrationError`. `_strv` exists only with the array runtime, and native-image
skips a registered method the class lacks. That file stands ALONE, so it repeats the shapes it
shares with the main file; `ObjcNativeImageForeignConfigTest` checks every layer against it alone
(`NativeImageDowncalls.OBJC`), and `ShippedBridgeClassFilesTest` pins the shipped bytes and the
reflection entry.

## `--native`: the runner is the Objective-C host
`--native -o prog` (`macos-aarch64` only: `CompileFrontend` accepts them when the native target is
`NativeTarget.OBJC_PLATFORM`) accepts the macOS packages: `ObjcNativeLibrary` splices
`objc-native-primitives.lisp` -- the primitive layer over `rontolisp:wasm-import`s from module
`rlobjc` (`p_*`) -- right OUTSIDE `ObjcLibrary`, and the runner (`rontolisp-native/runner/src/objc`)
answers them over libobjc/AppKit, dlopened on the first `rlobjc` call (an output that makes none
starts as before). Stub size (`cargo build --profile release-runner -p rlrun`, 2026-09-29): 1,948,432
B with the first base's imports beside the primitive layer, 1,849,072 B once they were deleted
(-99,360; the primitive layer's `p_*` imports had cost 115,904, and the host keeps a small send of
its own, `Api::msg`, for `pump` and the application start).

- **THE decision: the module runs ON thread 0.** A wasmtime `Store` is not `Sync`, and a callback
  arriving on thread 0 while the module ran elsewhere could not enter it: that thread's stack holds
  the store, and a nested call from another thread would leave its wasm frames' GC roots unwalked
  (wasmtime walks the CURRENT thread's activations). So there is no hop: `objc:on-main` is a plain
  call, and a callback arrives INSIDE a host call -- a send that made AppKit call out, or `pump` --
  and re-enters through that call's `Caller`, published in the `CALLER` thread-local (`Entered`).
- **`sleep` is the event loop.** Nothing drains thread 0 while the module computes, so every
  `sleep` of such a program compiles to `objc::%sleep` (`WasmExprCompiler`, keyed on the spliced
  defun `LispNames.OBJC_SLEEP_INTERNAL`) -> `p_pump`: `nextEventMatchingMask:` + `sendEvent:` once
  the application started, else `CFRunLoopRunInMode`. `appkit:wait`'s 50 ms poll therefore keeps
  the window live at ~0% CPU. A blocking stdin read still freezes it.
- **A fetch's wait turns the event loop too.** A program that also fetches (the network runner,
  `.kb/fetch-http.md`, "--native") waits in the runner for a reply's head or next body chunk; once
  the application started, that wait runs `pump` in 20 ms turns until the answer is in
  (`objc::pump_until`, called from `http::wait`), so the windows stay live and a callback arriving
  meanwhile re-enters through the call's `Caller`, as during `sleep`. Before the application starts
  nothing is on screen and the wait blocks on its condition variable. Verified 2026-09-26 (M4 Max):
  a window whose 50 ms timer relabels it and posts a mouse down/up pair on its button to the
  application's queue, awaiting a reply held 2 s by the origin, got 40 relabels and 4 clicks
  during the wait at 0.16 s CPU. Pinned without a window by `NativeObjcE2eTest` (a timer counts
  turns during the await; an event posted before it is dequeued by the end).
- **`-[NSApplication run]` is never started**: it never returns, and the thread is the module's.
  `appkit::%app`'s `performSelectorOnMainThread:withObject:waitUntilDone:` of `run` to an
  `NSApplication` is answered by marking the application started (and
  `activateIgnoringOtherApps:`), after which `pump` dispatches. The one place the runner reads a
  selector's meaning (`prim.rs`); `appkit.lisp` is unchanged.
- **Sends: one generic import, no shape table.** The primitive layer pushes each raw argument
  (`p_arg_*`; `:s64` for an address), then `p_send(receiver, sel, types, fixed, mode)` answers a
  result KIND fetched with `p_result_*`. The host marshals by the encoding it is handed
  (`encoding.rs`, the twin of `TypeEncoding`, same refusals and messages) and calls `objc_msgSend`
  through `rl_objc_call` (`call.rs`): an assembly frame that loads x0-x7, d0-d7, x8 and a stack
  area, with Apple's AArch64 classification (HFA -> SIMD registers, struct > 16 B by reference,
  struct result through x8, stack arguments at natural alignment, variadic ones in 8-byte slots)
  -- so EVERY parseable selector is callable, where the native binary serves a closed table.
  Measured 2026-09-25 (M4 Max, the first base's send, the same frame): 0.20 us an integer answer,
  0.66 a struct argument; `metal-cube` over 6 s: 0.51 s CPU against 4.18 s under `java -jar`.
- **Ownership through `:extern`.** A wasm-GC module has no finalizer, but wasmtime's copying
  collector DROPS an `externref`'s host data when the reference dies (`sweep_extern_refs`, read in
  the 49.0.0 source). A pointer's handle is the externref `p_new_handle` returns, whose host data
  holds the counts and queues the release of the `gc` share on drop. The queue runs only from the
  OUTERMOST host call -- the end of a send, a `pump` turn -- never inside a callback, where it
  could free the object whose method is running, nor between a send's argument pushes.
  `NativeObjcE2eTest` pins that 300,000 answers of one object leave < 100,000 references.
- **Two answers for one object are one value** by address ("One representation"), which the
  corpus's identity block pins against the interpreter.
- A method or block defined in Lisp re-enters through `rlobjc_method`; a Lisp error is printed in
  the library (`objc: error in a callback: ...`) and answered as zero; a `proc_exit` inside exits
  with its code; a `throw` to a tag outside the callback (a trap here) is printed the same way and
  contained -- nothing unwinds into the native frame.
- Verified 2026-09-29: the corpora and `scene:` offscreen pixels equal the interpreter's
  (`NativeObjcE2eTest`). Clicks can be checked without a hand by posting `NSEvent
  mouseEventWithType:...` mouse-down/up pairs through `postEvent:atStart:` (the path a trackpad
  takes: `pump` -> `sendEvent:` -> the button's tracking loop -> the action IMP). A human click on
  `counter.lisp` is still the GUI rule's check.

## Package rules and the web build
`am.ik.objc -> (nothing)`; `eval -> am.ik.objc` through ONE class, `eval/ObjcPrimitives`, reached
only via `eval/ObjcInterop`'s five entry points (the `LinalgGpu`/`LinalgGpuKernels` shape), so
`src/web/java/.../Target_ObjcInterop.java` substitutes them and the browser build carries no FFM.
`cli` reaches the hand-over through `ObjcInterop`, never the library. `MetalDriver` is the same
runtime through a hand-written shape table and could ride on `am.ik.objc`; it does not yet.

## Tests
- `am.ik.objc.TypeEncodingTest`; `am.ik.objc.ObjcNativeImageForeignConfigTest`;
  `eval/ObjcBaseTest`, `ObjcClassTest`, `ObjcBlockTest`, `ObjcExceptionTest` (the corpora, the
  signal off-Mac); `eval/ObjcLibraryTest`; `codegen/jvm/JvmObjcBaseCompilerTest` (the corpora
  byte for byte compiled, the embedded class list, the widget layer's gate and headless functions,
  a producer-built string); `codegen/jvm/JvmSizedMainTest` and the opt-in
  `e2e/ShippedBridgeNativeImageE2eTest` (the compiled thread-0 hand-over); `eval/AppKitLibraryTest`
  / `eval/MetalLibraryTest` / `eval/SceneLibraryTest`; `SceneOffscreenRenderTest`;
  `PackageCycleTest`; `--native`: `eval/ObjcNativeLibraryTest` (the primitives defined, the
  splice), `e2e/NativeObjcE2eTest` (macOS aarch64: the corpora against the interpreter, a timer
  during `sleep`, the event loop during a fetch's wait, an exit and a `throw` inside a method,
  release on pointer death, `scene:` pixels), the runner's own `call.rs` / `encoding.rs` unit tests
  (`build.sh --test`).
- No test opens a window (CI has no display; the guide uses `console` fences so `DocExamplesTest`
  cannot hang). **Verified by hand: `counter.lisp` on `java -jar`, the native binary, `-o
  Counter.class` and `-o counter.jar`; `minesweeper-macos.lisp`, `life-macos.lisp` and
  `menubar.lisp` on `java -jar`, the native binary and `-o Life.class`; every `examples/macos`
  program under `--native`** -- what a widget-layer change costs, since it travels into every
  compiled `appkit:` program.

## Open items
- No MAIN menu (a process with no bundle sets none), so no Cmd-Q on a windowed program.
- A variadic selector a PROGRAM sends in the string form is served only for the names in
  `objc::*variadic-selectors*`; the runtime offers no way to recognise another (the list form's
  `:variadic-num-of-fixed` is the program's way).
- x86_64: `objc_msgSend_stret` (struct returns wider than 16 bytes) has not been exercised.
- x86_64 (`java -jar`, a JVM class) and a macOS before 14: `ObjcCatch` writes no trampoline, so an
  Objective-C exception inside a call still ends the process.
- `--native`: a macos-x86_64 runner has no Objective-C host (`call.rs` is Apple's AArch64
  convention, and there is no release stub).
