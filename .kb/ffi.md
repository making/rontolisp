# `ffi:`: the foreign primitives CFFI's backend stands on

`ffi` binds plain C through `java.lang.foreign` -- `am.ik.ffi`, a language-independent library
shaped like `am.ik.objc`: generic verbs, no JNI, no bundled artifact, no reflection, so the
native binary can interpret it. ONE consumer: upstream CFFI's `cffi-sys` layer
(`.kb/cffi.md`); everything else wants `cffi:defcfun`, not `ffi:call`.

```
am.ik.ffi          imports NOTHING (am.ik.gpu / am.ik.objc rule; PackageCycleTest pins it)
  -> eval/FfiInterop   the ONLY entry, so src/web/java substitutes it whole (Target_FfiInterop)
    -> eval/FfiBridge  the ffi: bodies, marshalling, the one am.ik.ffi reference
```

`FfiCaller` is its own type so bridge and entry reference each other in no direction
(`ObjcCaller` shape). `LispEvaluator.registerFfi()` sits beside `registerObjc()` (a callback
applies a user function, so it needs the evaluator's `apply`). Every failure SIGNALS with a
message starting `ffi:`, never a decline.

## Verbs

`ffi:open` (dlopen -> integer handle; no argument = the process's own symbols, handle 0),
`ffi:symbol`, `ffi:call` (address + return type + argument-type list + arguments; the calling
convention is decided at RUN time), `ffi:callback`, `ffi:alloc`/`ffi:free`,
`ffi:peek`/`ffi:poke` (`(ffi:peek ptr type [offset])`; `:string` peek reads NUL-terminated
UTF-8, `:string` poke is refused), `ffi:size`/`ffi:align`, `ffi:pointerp`, `ffi:address`,
`ffi:errno`, plus internal `ffi:%apply-call` (= `ffi:call` with the arguments as one list; the
cffi backend's argument lists are runtime values and `(apply #'ffi:call ...)` would need the
operator as a first-class value, which the `ffi` package has no `BuiltinFunctionWrappers`
entries for -- the `objc:` precedent).

Types are the CFFI keywords (`:char :uchar :short :ushort :int :uint :long :ulong :llong
:ullong :float :double :pointer :string :void`, plus `:int8`..`:uint64`), parsed at run time by
`FfiType.of`; C integer names are LP64 aliases (`:long` is 8). A by-value struct is
`(:struct member...)`, nesting allowed, laid out with the C padding rule
(`FfiType.Struct.layout()`) -- which makes `cffi:*foreign-structures-by-value*` the ordinary
call path and leaves `cffi-libffi` unloadable forever. A returned struct is copied into
`malloc`'d memory and answered as a pointer the caller frees.

## Load-bearing decisions

- **A pointer is its own value**: `LispForeignPointer(long address)`, a `LispVal` permittee
  (prints `#<pointer #x7f...>`, `equal` by address, 0 is a legal NULL). A plain integer is still
  accepted wherever an address is expected; `ffi:address` is its own inverse (pointer <->
  integer), so cffi's `make-pointer`/`pointer-address`/`null-pointer` fall out of it. Addresses
  are UNSIGNED 64-bit both ways: an operand >= 2^63 arrives as a BIGNUM and wraps to the raw 64
  bits (`:uint64`'s rule, one helper) -- cl-sqlite's `SQLITE_TRANSIENT` is `(mod -1 (expt 2 64))`.
- **`ffi:alloc` is `malloc`** -- CFFI's contract is explicit `foreign-free`, so foreign memory
  outlives every Lisp scope. The only arena is the call-scoped confined one in `FfiRuntime.call`
  that `:string` arguments marshal through.
- **Handle cache keyed by shape**: ~24 us to build a downcall handle, ~0.5 us to call one.
  `FfiRuntime` caches UNBOUND handles (address is the leading argument) in a `static final` map
  keyed by descriptor spelling + variadic index. Trap: the handle comes from a map fetch, so the
  JIT cannot constant-fold it (`invokeWithArguments`); a constant-`invokeExact` twin is future
  work.
- **Carriers are canonicalised so the native binary's shape grid is finite.** A native image
  compiles a stub per foreign SHAPE at build time and refuses any other, while `cffi:defcfun`
  invents shapes at run time. Every narrow integer argument is widened to `jlong`, every integer
  return read as `jlong` and narrowed by the DECLARED type (`fromNativeReturn` masks).
  `jfloat`/`jdouble` stay distinct; an ADDRESS is that same `jlong`
  (`FfiType.Scalar.POINTER`/`STRING` carry `JAVA_LONG` as their `layout()`, and no `ffi:` entry
  in `reachability-metadata.json` says `void*` -- the `void*` entries there are objc, Metal,
  CUDA and CBLAS, which build their own descriptors). THREE carriers per parameter. Cost of the
  pointer merge: a `:pointer`/`:string` argument reaches `invokeWithArguments` as a `Long`, so a
  `:string`'s confined-arena copy travels as a bare address and FFM no longer checks the segment
  is alive -- correctness rests on `FfiRuntime.call`'s try-with-resources closing that arena
  AFTER the handle returns. `fromNativeReturn` rebuilds the segment a `:string` return is read
  from (`MemorySegment.ofAddress(x).reinterpret(...)`); `dispatch`/`toCallbackReturn` mirror it.
- **Three cases keep EXACT layouts**: a narrow integer past the SIXTH integer-class position
  (`FfiRuntime.REGISTER_WINDOW` -- Apple's AArch64 packs stack arguments, so widening there
  shifts the frame), a variadic call (each `firstVariadicArg` index is its own stub; the Apple
  variadic path is unverifiable), and a by-value struct (its member list IS the shape). Upcalls
  follow the same rule in the other direction.
- **A by-value struct RETURN does not stop the ARGUMENTS canonicalising.**
  `argumentsCanonicalisable` asks only about the arguments (fixed, no struct ARGUMENT -- a
  struct eats an ABI-defined number of register-class slots, so the register window cannot be
  counted past one); the return keeps its exact layout. The struct-return family is 54 return
  shapes (every member sequence of length 1-2 over the six member layouts, plus homogeneous
  length 3-4) x 15 parameter tuples (all three carriers at arity 0-2, `jlong` at 3-4) = 810
  entries. A NESTED struct is spliced FLAT (`FfiType.Struct.layout()`), so a struct of two
  `CGPoint`s and one of four doubles are ONE shape.
- **A registered shape is not a compiled stub of its own -- the granularity is the ABI-LOWERED
  signature.** One entry serves every descriptor that lowers the same way, so the family covers
  more than it enumerates. Hence enumeration by MEMBERS, not by classification: each platform's
  linker collapses them its own way. Corollary: an entry costs image size only when it is a NEW
  lowering -- the checked-in JSON bounds a grid that RE-SPELLS shapes, image size bounds one that
  reaches new ARITIES (deepening the jlong tier from arity 6 to 10 was byte-for-byte free;
  pushing `jdouble` to 6 and `jfloat` to 3 cost ~450 KB, so that tier stayed put).
- **The grid** in `reachability-metadata.json`: `jlong` arguments at arity 0-10, all
  `jlong`/`jdouble` combinations at 1-4, all three carriers at 1-2, x four return carriers, every
  entry with `captureCallState`; upcalls `jlong` to arity 6 and `jlong`/`jdouble` to 2.
  `FfiNativeImageForeignConfigTest` generates and pins it; its class comment is the coverage log.
  A miss signals the ONE metadata entry that would register the shape, verbatim, plus "or run it
  on java -jar"; the cffi backend's `%call-symbol` re-signals it with the function name in front
  (`handler-case` gated on the `no foreign-call stub` marker).
- **A struct's PADDING is part of its metadata spelling.** The image builder rebuilds the layout
  with `MemoryLayout.structLayout`, which REFUSES a member off its own alignment and ABORTS the
  build (`Invalid alignment constraint for member layout: i4`). `metadataType` spells
  `struct(jbyte,padding(3),jint)`. Graal's grammar: `struct(...)`, `union(...)`,
  `sequence(n,...)`, `align(n,...)`, `padding(n)` (`MemoryLayoutParser`).
- **`errno` is captured, not fetched**: every downcall handle carries
  `Linker.Option.captureCallState("errno")` into a PER-THREAD capture segment (allocated once
  per thread, auto arena held by the ThreadLocal); `ffi:errno` reads the calling thread's last.
- **Varargs by marker**: `:varargs` marks where the variadic tail starts
  (`Linker.Option.firstVariadicArg`); without the option the call is silently wrong on
  AArch64/Apple. It contributes no layout and may appear once.
- **An upcall never throws**: the one dispatcher (`FfiRuntime.dispatch`, bound with a CONSTANT
  `findStatic` -- the `ObjcClasses` rule for native images) catches everything, prints
  `ffi: error in a callback: ...`, and answers zero of the declared type; unwinding into the
  native frame above an upcall ends the process. Stub lifetime is `Arena.global()`. `:string`
  and struct types are refused in callback shapes (take a `:pointer`).

## Where it runs

Both WASM backends refuse a program referencing `ffi:` -- permanently, by name, in
`CompileFrontend` after load inlining (`FfiInterop.firstFfiReference`, the
`AppKitLibrary.firstObjcReference` shape).

The JVM class output CARRIES the binding: `JvmFfiRuntimeBuilder` (the `JvmObjcRuntimeBuilder`
mechanism) embeds every `am.ik.ffi` class file plus the bridge (`JvmFfiTemplate`, the hand-kept
twin of `eval/FfiBridge` -- KEEP IN SYNC) and the pointer value (`JvmFfiHandle`,
`LispForeignPointer`'s twin), renamed into the program's package and defined lazily by the
emitted `_ffiInit`; the gate is any `ffi:` verb surviving load inlining (`programUsesAnyFfiOp`),
which a `cffi:` program passes through the spliced backend. An `ffi:callback` applies through
`_apply` (so `usesFfi` forces the eval runtime and roots `_apply` in the shaker), and `bind`
hands over `_strv` beside it (nullable, absent without the array runtime) so the template's
`lispString` renders a mutable character vector once and a `concatenate`/`format nil` result can
name a library, symbol or `:string` argument (`.kb/string-write-runtime.md`). The compiled
printer gains an `_ffiInited`-guarded print hook beside the objc one. In a native image the verbs
interpret against the shape grid. The browser build substitutes `FfiInterop`'s three
bridge-touching methods (`Target_FfiInterop`); `firstFfiReference` is pure AST and stays.

## Tests

| what | where |
|---|---|
| type model: designator aliases, C struct padding, nested struct spliced flat | `am.ik.ffi.FfiTypeTest` |
| verbs end to end: libm/libsqlite3/the process, every scalar through peek/poke, string round trip, struct return by slot (`div`), qsort through a Lisp callback, callback as a plain address + escaped-error-answers-zero, varargs (`snprintf`), errno from a failed `open`, signal texts, the WASM refusal | `eval/FfiTest` |
| `eval -> am.ik.ffi`, and the library imports nothing | `PackageCycleTest` |
| verbs compiled to a `.class` case for case with the interpreter; blob gated on the reference; class list pinned; `ffi:%apply-call`; fboundp-of-a-macro fold; `(setf (apply #'aref ...))` place | `codegen/jvm/JvmFfiInteropCompilerTest` |
| native-image grid: every canonical shape registered (captureCallState included), malloc/free's capture-free shapes, bundled consumers' shapes inside it, the struct-RETURN family (incl. the cffi guide's `div`), the canonicalisation rules (address on the `jlong` carrier in a descriptor, a struct member and a variadic tail), a struct spelling with its padding | `am.ik.ffi.FfiNativeImageForeignConfigTest` |
