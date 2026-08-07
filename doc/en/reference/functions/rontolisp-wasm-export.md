# rontolisp:wasm-export

`(rontolisp:wasm-export 'name :as "alias" :params '(type...) :param-names '(name...) :returns type :async t)`

Marks a top-level `defun` as host-callable when compiling to a WebAssembly core
module, declaring the WASM-boundary types of its parameters and result. It is a
compile-time directive, not an ordinary function: on the **interpreter** and
**JVM** backends it is a no-op that simply returns the named symbol, so the same
source runs on every backend. See
[Compiling to WebAssembly](../../compiling/wasm.md) for the full guide.

```lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(rontolisp:wasm-export 'fact :params '(:int) :returns :int)   ; => FACT
```

## Arguments

- A quoted symbol naming the top-level `defun` to export. It resolves in the
  current [package](../packages.md) like a `defun` name.
- `:as` — the WASM export name, as a string (e.g. `"factorial"`, or a
  camelCase name for a JavaScript-facing API). Defaults to the bare Lisp name
  (`fact`, without any package qualifier).
- `:params` — a list of boundary type designators, one per parameter. Omitted,
  `nil` or `'()` means no arguments.
- `:param-names` — the parameter names of the **component-model** signature, one
  per `:params` entry, as symbols or strings. Each must be a component-model
  label (lower-kebab-case words). Defaults to `p0`, `p1`, ... — the names a host
  or a binding generator sees in the component's type, and therefore the names
  [`--emit-wit`](../../guides/wit-contracts.md#emitting-the-wit-world---emit-wit) prints. It is
  ignored outside `--component` (a core WASM parameter has no name), and a
  program that implements a WIT world with
  [`rontolisp:wit-export`](rontolisp-wit-export.md) gets these from the world
  instead of declaring them.
- `:returns` — the result boundary type designator. Omitted, `nil`, `'()` or
  `:void` declares a void result (the Lisp return value is discarded).
- `:async` — `t` lifts the export as an **async** component-model function under
  `--component`, so I/O inside it (`print`, `rontolisp:fetch`, ...) works instead
  of trapping. Defaults to `nil` (a synchronous, pure-compute lift). Meaningful
  only under `--component`: Preview 1 / `--no-wasi` core exports ignore it, and
  `--no-gc --component` rejects it.

The type designators and their boundary representations are:

| Designator | WIT type | WASM boundary | Notes |
| --- | --- | --- | --- |
| `:s8` `:s16` `:s32` | `s8` `s16` `s32` | `i32` | `:int` is a permanent alias of `:s32` |
| `:u8` `:u16` `:u32` | `u8` `u16` `u32` | `i32` | |
| `:s64` `:u64` | `s64` `u64` | `i64` | `:long` is a permanent alias of `:s64`; a `:u64` value of 2^63 or more traps (it has no exact representation in the signed 64-bit integers every backend computes with) |
| `:float` | `f64` | `f64` | rontolisp has no single-precision float, so `f32` is not a boundary type |
| `:bool` | `bool` | `i32` | `0` is `nil`, any non-zero value is `t` |
| `:string` | `string` | `(ptr, len)` | UTF-8 bytes in linear memory |
| `:s-expr` | `string` | `(ptr, len)` | s-expression text in linear memory (any value except a function); no WIT type of its own |

**The boundary carries the value exactly, or the call traps.** A value the
declared type cannot state — a negative returned through `:u32`, `300` through
`:u8`, anything past the 32-bit range through `:s32` — stops the call instead of
arriving silently wrapped. Nothing is masked, which is also what keeps a
component behaving the same under `wasmtime` and under stricter binding
generators such as `jco`.

The representable range is the declared type's own, on every backend. With the
default (GC) backend an incoming integer arrives as an exact integer (a fixnum
when it fits, a boxed 64-bit integer past that), so a `:u32` argument of
`3000000000` reaches the Lisp code as the exact integer `3000000000`; integer
arithmetic inside the Lisp code is exact at any magnitude
(`(+ x 1)` on a `:u32` argument of `1073741823` returns `1073741824` exactly),
and only a result the declared type cannot state traps at the boundary. On the
non-GC backend (`--no-gc`) integers are computed as `i64`, crossing the same
way.

## Limitations

- Under `--component`, an export becomes a **typed component-model export**
  callable with WAVE syntax (`wasmtime run --invoke 'name(args)'`): the whole
  fixed-width integer family (`:long` included), `:float`/`:bool`/void,
  `:string` and `:s-expr` as component-model `string` (`--no-gc` has no
  `:s-expr`). A sync
  (default) export must be pure-compute — I/O inside it traps; declare
  `:async t` when the export prints or fetches. Under `--no-gc --component`,
  `:async` is rejected but printing still works, through a built-in WASI 0.3
  stdout micro-adapter wired in only when the program prints — every export of
  a printing program is then lifted `async` automatically.
  The export name must be lower-kebab-case (rename with `:as` otherwise), and
  adding `--emit-wit` writes the component's WIT world (with every export's typed
  signature) next to the `.wasm`. See
  [Component-model function exports](../../guides/wasm-component.md#component-model-function-exports-wasm-export)
  and [Compact component output](../../guides/wasm-nogc.md#compact-component-output---no-gc---component).
  On the interpreter and JVM the directive just returns the named symbol.
- Only a top-level `defun` can be exported; the declared parameter count must
  match its arity, and functions that take or return function values are out of
  scope.
- Outside `--component`, the exported function is pure-compute: reading, time,
  `random` and file access (from the function or from a top-level form) trap
  under `--no-wasi` and are otherwise unsupported. Printing is the exception —
  under `--no-wasi` standard output and standard error are a sink, so a print
  discards its bytes instead of trapping. One more exception: under `--no-gc`,
  `print`/`princ`/`terpri` work through a single `fd_write` import that is
  added only when the program prints (see
  [Printing](../../guides/wasm-nogc.md#printing-print--princ--terpri)).
- The non-GC backend (`--no-gc`) supports `:int`/`:long`/`:float`/`:bool`/`:string`
  but not `:s-expr`, which needs the cons/reader/printer runtime.
