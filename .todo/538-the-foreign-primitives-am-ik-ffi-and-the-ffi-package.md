# 538. `am.ik.ffi` and the `ffi:` package: the primitives CFFI's backend stands on

Difficulty: High

Part of `.todo/537`. Independent of everything else in that item; nothing else can start
without it. The shape is settled by precedent -- this is `objc:` with C instead of
Objective-C -- so the work is mechanical where the precedent reaches and only the marked
decisions are open.

## Where the code goes

```
am.ik.ffi                     language-independent, imports NOTHING (the am.ik.gpu / am.ik.objc rule)
  -> eval/FfiInterop          the ONLY entry, so src/web/java can substitute it whole
    -> eval/FfiBridge         the ffi: function bodies, marshalling, the one reference to am.ik.ffi
```

`PackageRegistry` gets a built-in `ffi` package that does not use `cl`, beside `objc`, and
`LispEvaluator` registers it beside `registerObjc()` (a callback applies a user function,
so it needs the evaluator's `apply`). Every failure SIGNALS -- a machine without native
access, a library that will not open, a symbol that is not there, an operand that does not
fit -- with a message starting `ffi:`, never a decline.

## The verbs

Small and generic, named after the foreign system, exactly as `objc:` is. Upstream CFFI's
`cffi-sys` layer (`.todo/539`) is written over these, and nothing else in the tree is:

| verb | meaning |
| --- | --- |
| `ffi:open` | dlopen a library by name/path, answer a handle; the process's own symbols are the default handle |
| `ffi:symbol` | the address of a symbol, or nil -- `%foreign-symbol-pointer` and `defcvar` |
| `ffi:call` | address + return type + argument-type list + arguments; the whole calling convention |
| `ffi:callback` | a Lisp function + a shape, answering a code address -- `defcallback` |
| `ffi:alloc` / `ffi:free` | `malloc` / `free`, reached as ordinary downcalls (see below) |
| `ffi:peek` / `ffi:poke` | a typed load/store at address + offset -- `%mem-ref` / `%mem-set` |
| `ffi:size` / `ffi:align` | the byte size and alignment of a type |
| `ffi:pointerp` / `ffi:address` | the pointer predicate and its integer address |

Type designators are the CFFI keywords (`:char :uchar :short :ushort :int :uint :long
:ulong :llong :ullong :float :double :pointer :string :void`), decided at RUN time -- the
spike confirms a descriptor built from strings at run time is fine, in the native image
too.

## The four decisions

1. **A pointer is its own value.** Add `LispForeignPointer(long address)` to the sealed
   `LispVal` (prints `#<pointer #x7f...>`), the `LispObjcObject` shape -- that record cost
   seven files. Representing a pointer as a plain integer, as the spike does, makes
   `cffi:pointerp` answer true for `42` and loses every type error at the boundary. The
   JVM class output needs the twin (`codegen.jvm.JvmObjcHandle` is the model) --
   `.todo/541`.
2. **`foreign-alloc` is `malloc`.** CFFI's contract is explicit `foreign-free`, which no
   `Arena` models without side bookkeeping; `malloc`/`free` reached as ordinary downcalls
   match it exactly and cost nothing to keep. Foreign memory then outlives every Lisp
   scope, which is what a binding expects. Use an `Arena` only for the call-scoped
   temporaries `:string` arguments need.
3. **The handle cache is keyed by shape, and read like a constant.** ~24 µs to build a
   downcall handle, ~0.5 µs to call one (spike). Cache on (library, symbol, descriptor).
   Read `.todo/476` first: a handle in a non-`static final` instance field costs the JIT
   its constant folding and shows up in a profile as `Invokers.checkCustomized` -- the same
   trap is one field away here, and this is the hot path of every binding.
4. **`errno` is captured, not fetched.** `Linker.Option.captureCallState("errno")` on the
   handles that want it; a binding that reads `errno` after the call gets the value that
   call left, which is the only version of this that is correct under threads.

## Also in the primitives

- **Varargs**: `Linker.Option.firstVariadicArg(n)`. CFFI hands the backend a fixed prefix
  and a variadic tail (`%foreign-funcall-varargs`), so `n` is known; without the option the
  call is silently wrong on AArch64/Apple.
- **Upcalls**: `Linker#upcallStub` over a target that ADAPTS a Lisp function
  (`Spike.java`'s `dispatch` shape). An error the Lisp handler does not catch must be
  printed and swallowed -- unwinding into the native frame above an upcall ends the
  process, which `.kb/objc.md` already states for callbacks. The stub's lifetime is the
  program's.
- **Structures by value**: `FunctionDescriptor` over a run-time `StructLayout`. This is
  what lets `.todo/539` fill `*foreign-structures-by-value*` and leave `cffi-libffi`
  unloadable forever.

## Acceptance

An `FfiTest` that opens `libm`/`libsqlite3` and the process itself, calls at every type,
round-trips a string, writes and reads a struct's slots through `ffi:peek`/`ffi:poke`,
sorts through a callback (`qsort`), calls a varargs function, and reads `errno` from a
failed `open`. `PackageCycleTest` unchanged, `am.ik.ffi` importing nothing, and the web
profile (`./mvnw -Pweb compile`) still cutting the binding out through the single entry
class.
