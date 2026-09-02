# `ffi:`: the foreign primitives CFFI's backend stands on

One built-in package (todo-538, 2026-08-26). **`ffi`** binds plain C through
`java.lang.foreign` -- `am.ik.ffi`, a language-independent library beside `am.ik.objc`
and shaped exactly like it: a handful of generic verbs named after the foreign system,
no JNI, no bundled artifact, no reflection, which is what lets the `rontolisp` native
binary interpret it. It exists for ONE consumer: upstream CFFI's `cffi-sys` layer
(`.kb/cffi.md`) is written over these verbs, and nothing else in the tree should be -- a
binding wants `cffi:defcfun`, not `ffi:call`.

```
am.ik.ffi                     imports NOTHING (the am.ik.gpu / am.ik.objc rule; PackageCycleTest pins it)
  -> eval/FfiInterop          the ONLY entry, so src/web/java substitutes it whole (Target_FfiInterop)
    -> eval/FfiBridge         the ffi: function bodies, Lisp<->protocol marshalling, the one am.ik.ffi reference
```

`FfiCaller` is its own type so the bridge and the entry class reference each other in no
direction, the `ObjcCaller` shape. `LispEvaluator.registerFfi()` sits beside
`registerObjc()` (a callback applies a user function, so it needs the evaluator's
`apply`). Every failure SIGNALS with a message starting `ffi:` -- denied native access, a
library that will not open, a symbol that is not there, an operand that does not fit, an
unregistered native-image shape -- never a decline.

## The verbs

`ffi:open` (dlopen by name/path -> an integer handle; no argument = the process's own
symbols, handle 0), `ffi:symbol` (address or nil), `ffi:call` (address + return type +
argument-type list + arguments -- the whole calling convention, decided at RUN time),
`ffi:callback` (a Lisp function + a shape -> a code address), `ffi:alloc` / `ffi:free`,
`ffi:peek` / `ffi:poke` (`(ffi:peek ptr type [offset])`, `(ffi:poke ptr type value
[offset])`; `:string` peek reads the NUL-terminated UTF-8 at the location, `:string`
poke is refused), `ffi:size` / `ffi:align`, `ffi:pointerp`, `ffi:address`, `ffi:errno` --
plus ONE internal member, `ffi:%apply-call`, which is `ffi:call` with the arguments as a
single list. It exists for the cffi backend, whose argument lists are runtime values:
`(apply #'ffi:call ...)` would need the operator as a first-class value, which the
compiled backends do not give the `ffi` package (the `objc:` precedent -- no
`BuiltinFunctionWrappers` entries), and the fixed-arity spelling compiles everywhere.

Type designators are the CFFI keywords (`:char :uchar :short :ushort :int :uint :long
:ulong :llong :ullong :float :double :pointer :string :void`, plus `:int8`..`:uint64`),
parsed at run time by `FfiType.of`; the C integer names are LP64 aliases of the fixed
widths (`:long` is 8 -- Linux and macOS are the platforms the linker serves). A struct
passed or returned BY VALUE is `(:struct member...)`, nested allowed, laid out with the
C padding rule (`FfiType.Struct.layout()`) -- which is what lets `cffi:*foreign-structures-by-value*` be the ordinary
call path and leaves `cffi-libffi` unloadable forever (`.kb/cffi.md`). A returned
struct is copied into `malloc`'d memory and answered as a pointer the caller frees.

## The decisions that are load-bearing

- **A pointer is its own value.** `LispForeignPointer(long address)`, a `LispVal`
  permittee (prints `#<pointer #x7f...>`, `equal` by address, address 0 is a legal NULL).
  `ffi:pointerp` therefore answers nil for `42` and a wrong operand at the boundary is a
  type error -- but a plain integer is still ACCEPTED wherever an address is expected,
  and `ffi:address` is its own inverse (pointer -> integer, integer -> pointer, so
  cffi's `make-pointer`/`pointer-address`/`null-pointer` all fall out of one verb). An
  address is UNSIGNED 64-bit on both sides of that inverse: an operand at or above 2^63
  arrives as a BIGNUM and wraps to the raw 64 bits, and `ffi:address` of a pointer
  answers the unsigned integer (`:uint64`'s own rule, one helper). Without it the
  sentinel addresses real bindings pass would not round-trip -- cl-sqlite's
  `SQLITE_TRANSIENT` is literally `(mod -1 (expt 2 64))`.
- **`ffi:alloc` is `malloc`.** CFFI's contract is explicit `foreign-free`; no `Arena`
  models that without side bookkeeping. Foreign memory outlives every Lisp scope. The
  only arena anywhere is the call-scoped confined one inside `FfiRuntime.call` that
  `:string` arguments marshal through.
- **The handle cache is keyed by shape.** ~24 us to build a downcall handle, ~0.5 us to
  call one. `FfiRuntime` caches UNBOUND handles (the address is the leading argument) in
  a `static final` map keyed by descriptor spelling + variadic index, so one handle
  serves every symbol of a shape. The `.todo/476` trap -- a handle the JIT cannot
  constant-fold -- still applies (`invokeWithArguments` through a map fetch); a
  constant-`invokeExact` twin remains future work.
- **The carriers are canonicalised, so the native binary's shape grid is finite.** A
  native image compiles a stub per foreign SHAPE at build time and refuses any other --
  and `cffi:defcfun` invents shapes at run time, after the binary was built. So before
  the descriptor is built, every narrow integer argument is widened to `jlong` (the C
  callee reads the low bits of its register -- SysV and AAPCS64 both; verified on the
  Linux stack-passed path too, whose slots both ABIs round to 8 bytes) and every integer
  return is read as `jlong` and narrowed by the DECLARED type (the callee leaves garbage
  above the bits it set; `fromNativeReturn` masks). `jfloat`/`jdouble` stay distinct (a
  float is not a narrowed double), and an ADDRESS is that same `jlong`: THREE carriers
  per parameter. Three cases keep their EXACT layouts, ABI-correct everywhere: a narrow
  integer past the SIXTH integer-class position (`FfiRuntime.REGISTER_WINDOW` -- past
  the register window both ABIs guarantee, and Apple's AArch64 packs stack arguments, so
  widening one there would shift the frame), a variadic call (each `firstVariadicArg`
  index is its own stub, and the Apple variadic path is the unverifiable one), and a
  by-value struct -- whose member list IS the shape, since a member's width and offset
  decide how the ABI classifies the whole aggregate. Upcalls follow the same rule in the
  other direction. The grid itself lives in `reachability-metadata.json` (`jlong`
  arguments at arity 0-10, all `jlong`/`jdouble` combinations at 1-4, all three carriers
  at 1-2, x four return carriers, every entry with `captureCallState`; upcalls `jlong`
  to arity 6 and `jlong`/`jdouble` to 2) -- `FfiNativeImageForeignConfigTest` generates
  and pins it, and its class comment is the coverage log. A miss signals the ONE
  metadata entry that would register the shape, verbatim, plus "or run it on java -jar"
  -- and the cffi backend's `%call-symbol` re-signals that one error with the function
  name in front (`handler-case` gated on the `no foreign-call stub` marker), so a
  binding failing in the binary and not on the JVM never looks like a bug in the
  binding.
- **A pointer is not a carrier of its own; it is the `jlong` one** (todo-638,
  2026-09-02). FFM spells an address `ValueLayout.ADDRESS`, but on both platforms the
  linker serves, a pointer and a 64-bit integer are the SAME parameter -- one
  integer-class register (SysV) / one general register (AAPCS64), same width, same
  alignment. Native Image does NOT agree (`struct(jlong,jlong)(jlong,jlong)` was refused
  by a binary already carrying `struct(jlong,jlong)(void*,void*)`), so the two spellings
  were two registered entries for one calling convention and the grid's parameter
  dimension was `4^arity` where `3^arity` would do. `FfiType.Scalar.POINTER` and
  `STRING` therefore carry `JAVA_LONG` as their `layout()`, and nothing in the `ffi:`
  half of `reachability-metadata.json` says `void*` any more (the `void*` entries above
  it are the objc, Metal, CUDA and CBLAS bindings, which build their own descriptors and
  are not canonicalised). The file went from 668,532 to 157,411 bytes and the `ffi:`
  downcalls from 4,277 to 984 (upcalls 148 -> 33) -- with WIDER coverage, since the
  pointer/integer tier now reaches arity 10 where it reached 6: the scalar grid is 1,125
  -> 172 entries and the struct-return family 3,150 -> 810. What it costs: a
  `:pointer`/`:string` argument is handed to `invokeWithArguments` as a `Long`, so a
  `:string`'s confined-arena copy travels as a bare address and FFM no longer checks
  that the segment is alive -- the try-with-resources in `FfiRuntime.call` closes that
  arena AFTER the handle returns, so it is the call's own structure that makes it
  correct. `fromNativeReturn` reads a `:pointer` return as a `Long` and rebuilds the
  segment a `:string` return is read from
  (`MemorySegment.ofAddress(x).reinterpret(...)`, what `peek` already does), and
  `dispatch` / `toCallbackReturn` do the same in the other direction so the two grids
  agree.
- **A by-value struct RETURN does not stop the ARGUMENTS canonicalising, and the grid
  carries a bounded family of such returns** (todo-632, 2026-09-02). The two halves are
  separate: `argumentsCanonicalisable` asks only about the arguments (fixed, no struct
  ARGUMENT -- a struct eats an ABI-defined number of register-class slots, so the register
  window cannot be counted past one), while the return keeps its exact layout. Where the
  ABI returns a struct indirectly it does so through a register of its own -- `x8` on
  AAPCS64, and on SysV the hidden pointer in `rdi` pushes at most the sixth integer
  argument onto the stack, whose slots Linux rounds to 8 bytes -- so widening an argument
  inside the window still moves nothing. That is what lets the struct-return family reuse
  the parameter tuples the grid already carries: `div`, `ldiv` and `imaxdiv` are one entry
  rather than three, and `(cffi:defcfun ("div" c-div) (:struct div-t) (numer :int) (denom
  :int))` -- the guide's own example, which failed in the shipped binary and worked on
  `java -jar` -- lands on `struct(jint,jint)(jlong,jlong)`. The family itself is 54 return
  shapes (every member sequence of length 1-2 over the six member layouts, plus the
  homogeneous ones of length 3-4) x 15 parameter tuples (all three carriers at arity 0-2,
  `jlong` at 3-4) = 810 entries -- 70 x 45 = 3,150 until todo-638 merged the pointer
  carrier away, which took a `void*` member and a `void*` parameter out of both dimensions
  without narrowing what the family covers. A NESTED struct is spliced into the layout FLAT
  (`FfiType.Struct.layout()`): same offsets, same size, and both ABIs classify an
  aggregate by flattening it anyway, so a struct of two `CGPoint`s and one of four doubles
  are ONE registered shape.
- **A registered shape is not a compiled stub of its own -- the granularity is the
  ABI-LOWERED signature.** Measured 2026-09-02 (GraalVM 25.0.4, Linux x86-64): adding
  3,150 downcall entries moved the image by 80 methods and ~30 KB of code area, and the
  binary size (80,808,200 bytes) and the build time (1m 23s) NOT AT ALL. Graal keys a
  downcall stub by the entry-point info the ABI lowers a descriptor to, so one entry
  serves every descriptor that lowers the same way -- which is why the family covers more
  than it enumerates: a 40-byte five-member struct return, never enumerated anywhere,
  binds through the indirect-return lowering a registered 4-double return already brought
  in, and so does a by-value struct ARGUMENT small enough to travel in one integer
  register. It is also why the family is enumerated by MEMBERS rather than by
  classification: the lowering is the platform's (SysV merges eightbytes, AAPCS64 has its
  own HFA rule), so enumerating members lets each platform's linker collapse them its own
  way instead of hard-coding one ABI's table here. What bounds the family is the
  checked-in file -- those 3,150 entries were 444 KB of JSON -- not the image.
- **An entry costs image only when it is a NEW lowering, which is what bounds the
  ARITIES** (todo-638, 2026-09-02, GraalVM 25.0.4, Linux x86-64). The corollary of the
  bullet above, measured on the pointer merge. Four binaries from one tree: the `void*`
  grid (4,277 downcalls, 668,532 bytes of JSON) is 80,808,200 bytes; the merged grid at
  the SAME coverage (968, 154,058) is 80,873,736; the merged grid with the jlong-only
  tier deepened from arity 6 to 10 (984, 157,411) is 80,873,736 again, byte for byte --
  those 16 entries lower onto stubs the grid already had; and pushing `jdouble` to arity
  6 and `jfloat` to 3 as well (1,436, 229,386) is 81,332,488, +524,288 over the baseline.
  Binary size moves in 64 KiB quanta here, so the first two differ by one quantum -- noise
  -- while the last is seven of them, real code for ~476 register-class assignments the
  image had never lowered. So the file is what bounds a grid that RE-SPELLS shapes, and
  the image is what bounds one that reaches into new arities; ~450 KB for mixed
  double/pointer calls past arity 4 is not the shape most C APIs have, which is why that
  tier stayed where it was.
- **A struct's PADDING is part of its metadata spelling.** The image builder rebuilds the
  layout with `MemoryLayout.structLayout`, which REFUSES a member that does not sit at its
  own alignment: a padding-free `struct(jbyte,jint)` does not register a wrong shape, it
  ABORTS the build with `Invalid alignment constraint for member layout: i4`. So
  `metadataType` spells `struct(jbyte,padding(3),jint)` -- in the generated file and in
  the miss message, which until todo-632 handed the user an entry that broke their build
  (verified by building with it). Graal's own grammar is `struct(...)`, `union(...)`,
  `sequence(n,...)`, `align(n,...)`, `padding(n)` (`MemoryLayoutParser`).
- **`errno` is captured, not fetched.** Every downcall handle carries
  `Linker.Option.captureCallState("errno")` into a PER-THREAD capture segment;
  `ffi:errno` reads the value the calling thread's last call left. Correct under
  threads, and the segment is allocated once per thread (an auto arena held by the
  ThreadLocal).
- **Varargs by marker.** `:varargs` in `ffi:call`'s argument-type list is where the
  variadic tail starts (`Linker.Option.firstVariadicArg`); without the option the call
  is silently wrong on AArch64/Apple. The marker contributes no layout and may appear
  once.
- **An upcall never throws.** The one dispatcher (`FfiRuntime.dispatch`, bound with a
  CONSTANT `findStatic` -- the `ObjcClasses` rule for native images) catches everything
  a Lisp handler lets escape, prints `ffi: error in a callback: ...` through the
  bridge-installed handler, and answers zero of the declared type -- unwinding into the
  native frame above an upcall ends the process (`.kb/objc.md` states the same for
  Cocoa callbacks). Stub lifetime is the program's (`Arena.global()`). `:string` and
  struct types are refused in callback shapes (take a `:pointer`).

## What refuses it, and where it runs

Both WASM backends refuse a program that references `ffi:` -- permanently, by name, in
`CompileFrontend` after load inlining (`FfiInterop.firstFfiReference`, the
`AppKitLibrary.firstObjcReference` shape) -- there is no foreign function API in any
WASM runtime. The JVM class output CARRIES the binding: `JvmFfiRuntimeBuilder` (the
`JvmObjcRuntimeBuilder` mechanism) embeds every class file of `am.ik.ffi` plus the
bridge (`JvmFfiTemplate`, the hand-kept twin of `eval/FfiBridge` -- KEEP IN SYNC) and
the pointer value (`JvmFfiHandle`, `LispForeignPointer`'s twin: prints
`#<pointer #x...>`, equal by address), renamed into the program's own package and
defined lazily by the emitted `_ffiInit`; the gate is any `ffi:` verb surviving load
inlining (`programUsesAnyFfiOp`), which a `cffi:` program passes through the spliced
backend. An `ffi:callback` applies its Lisp function through `_apply` (so `usesFfi`
forces the eval runtime and roots `_apply` in the shaker) -- and `bind` hands over
`_strv` beside it (nullable, absent without the array runtime), through which the
template's `lispString` renders a mutable character vector once, so a
`concatenate`/`format nil` result names a library, a symbol or a `:string` argument
like a literal does (`.kb/string-write-runtime.md`, the boundary-set paragraph) --
and the compiled printer
gains an `_ffiInited`-guarded print hook beside the objc one. In a native image the
verbs interpret against the registered shape grid above. The browser build cuts the
whole binding by substituting `FfiInterop`'s three bridge-touching methods
(`Target_FfiInterop`); `firstFfiReference` is pure AST and stays.

## Tests

| what | where |
|---|---|
| the type model, pure: designator aliases, the C struct padding rule, a nested struct spliced in flat | `am.ik.ffi.FfiTypeTest` |
| the verbs end to end: libm/libsqlite3/the process, every scalar through peek/poke, a string round trip, a struct return by slot (`div`), qsort through a Lisp callback, a callback called as a plain address + the escaped-error-answers-zero rule, varargs (`snprintf`), errno from a failed `open`, the signal texts, the WASM refusal | `eval/FfiTest` |
| `eval -> am.ik.ffi`, and the library imports nothing | `PackageCycleTest` |
| the verbs compiled to a `.class`, case for case with the interpreter (run on any Linux/mac with native access); the blob gated on the reference; the class list pinned against the build; `ffi:%apply-call`; the fboundp-of-a-macro fold; the `(setf (apply #'aref ...))` place | `codegen/jvm/JvmFfiInteropCompilerTest` |
| the native-image grid: every canonical shape registered (captureCallState included), malloc/free's capture-free shapes, the bundled consumers' shapes landing inside it, the by-value struct-RETURN family (the cffi guide's own `div` among them), the canonicalisation rules themselves (an address taking the `jlong` carrier among them, in a descriptor, in a struct member and in a variadic tail), a struct spelling carrying its padding | `am.ik.ffi.FfiNativeImageForeignConfigTest` |
