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
  above the bits it set; `fromNativeReturn` masks). `jfloat`/`jdouble` stay distinct and
  pointers/strings are already `void*`: four carriers per parameter. Three cases keep
  their EXACT layouts, ABI-correct everywhere and simply outside the grid: a narrow
  integer past the SIXTH integer-class position (`FfiRuntime.REGISTER_WINDOW` -- past
  the register window both ABIs guarantee, and Apple's AArch64 packs stack arguments, so
  widening one there would shift the frame), a variadic call (each `firstVariadicArg`
  index is its own stub, and the Apple variadic path is the unverifiable one), and a
  by-value struct (the member list is part of the shape). Upcalls follow the same rule
  in the other direction. The grid itself lives in `reachability-metadata.json` (all
  `void*`/`jlong` combinations at arity 0-6, plus `jdouble` at 1-4, plus `jfloat` at
  1-2, x five return carriers, every entry with `captureCallState`; upcalls to arity 4)
  -- `FfiNativeImageForeignConfigTest` generates and pins it, and its class comment is
  the coverage log. A miss signals the ONE metadata entry that would register the shape,
  verbatim, plus "or run it on java -jar" -- and the cffi backend's `%call-symbol`
  re-signals that one error with the function name in front (`handler-case` gated on the
  `no foreign-call stub` marker), so a binding failing in the binary and not on the JVM
  never looks like a bug in the binding.
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
forces the eval runtime and roots `_apply` in the shaker), and the compiled printer
gains an `_ffiInited`-guarded print hook beside the objc one. In a native image the
verbs interpret against the registered shape grid above. The browser build cuts the
whole binding by substituting `FfiInterop`'s three bridge-touching methods
(`Target_FfiInterop`); `firstFfiReference` is pure AST and stays.

## Tests

| what | where |
|---|---|
| the type model, pure: designator aliases, the C struct padding rule | `am.ik.ffi.FfiTypeTest` |
| the verbs end to end: libm/libsqlite3/the process, every scalar through peek/poke, a string round trip, a struct return by slot (`div`), qsort through a Lisp callback, a callback called as a plain address + the escaped-error-answers-zero rule, varargs (`snprintf`), errno from a failed `open`, the signal texts, the WASM refusal | `eval/FfiTest` |
| `eval -> am.ik.ffi`, and the library imports nothing | `PackageCycleTest` |
| the verbs compiled to a `.class`, case for case with the interpreter (run on any Linux/mac with native access); the blob gated on the reference; the class list pinned against the build; `ffi:%apply-call`; the fboundp-of-a-macro fold; the `(setf (apply #'aref ...))` place | `codegen/jvm/JvmFfiInteropCompilerTest` |
| the native-image grid: every canonical shape registered (captureCallState included), malloc/free's capture-free shapes, the bundled consumers' shapes landing inside it, the canonicalisation rules themselves | `am.ik.ffi.FfiNativeImageForeignConfigTest` |
