# rontolisp:wasm-export

`(rontolisp:wasm-export 'name :as "alias" :params '(type...) :returns type)`

Marks a top-level `defun` as host-callable when compiling to a WebAssembly core
module, declaring the WASM-boundary types of its parameters and result. It is a
compile-time directive, not an ordinary function: on the **interpreter** and
**JVM** backends it is a no-op that simply returns the named symbol, so the same
source runs on every backend. See
[Compiling to WebAssembly](../../compiling/wasm.md) for the full guide.

```lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(rontolisp:wasm-export 'fact :params '(:int) :returns :int)   ; => fact
```

## Arguments

- A quoted symbol naming the top-level `defun` to export. It resolves in the
  current [package](../packages.md) like a `defun` name.
- `:as` — the WASM export name, as a string (e.g. `"factorial"`, or a
  camelCase name for a JavaScript-facing API). Defaults to the bare Lisp name
  (`fact`, without any package qualifier).
- `:params` — a list of boundary type designators, one per parameter. Omitted,
  `nil` or `'()` means no arguments.
- `:returns` — the result boundary type designator. Omitted, `nil`, `'()` or
  `:void` declares a void result (the Lisp return value is discarded).

The type designators and their boundary representations are:

| Designator | WASM boundary | Notes |
| --- | --- | --- |
| `:int` | `i32` | 31-bit signed range (the internal `i31ref`) |
| `:long` | `i64` | `--no-gc` only; full 64-bit signed range, matching the scalar backend's internal `i64` (no `wrap`/`extend` at the boundary) |
| `:float` | `f64` | |
| `:bool` | `i32` | `0` is `nil`, any non-zero value is `t` |
| `:string` | `(ptr, len)` | UTF-8 bytes in linear memory |
| `:s-expr` | `(ptr, len)` | s-expression text in linear memory (any value except a function) |

With the default (GC) backend, `:int` crosses the boundary as `i32` but the
internal integer is an `i31ref`, so a value returned through `:int` is truncated
past the 32-bit boundary. On the non-GC backend (`--no-gc`) integers are computed
as `i64`, so use `:long` when a parameter or result can exceed the 32-bit range —
it exposes the full width with no `wrap`/`extend` conversion.

## Limitations

- Under `--component`, a scalar export (`:int`/`:float`/`:bool`/void — plus
  `:long` and `:string` with `--no-gc`) becomes a **typed component-model
  export** callable with WAVE syntax (`wasmtime run --invoke 'name(args)'`);
  `:string` on the GC component path and `:s-expr` on both are a compile error
  for now, the export must be pure-compute (I/O inside it traps; under
  `--no-gc --component`, printing is a compile error),
  and its name must be lower-kebab-case (rename with `:as` otherwise). See
  [Component-model function exports](../../compiling/wasm.md#component-model-function-exports-wasm-export)
  and [Compact component output](../../compiling/wasm.md#compact-component-output---no-gc---component).
  On the interpreter and JVM the directive just returns the named symbol.
- Only a top-level `defun` can be exported; the declared parameter count must
  match its arity, and functions that take or return function values are out of
  scope.
- The exported function is pure-compute: any I/O (printing, reading, time,
  random, or a top-level I/O form) traps under `--no-wasi` and is otherwise
  unsupported. One exception: under `--no-gc`, `print`/`princ`/`terpri` work
  through a single `fd_write` import that is added only when the program
  prints (see
  [Printing](../../compiling/wasm.md#printing-print--princ--terpri)).
- The non-GC backend (`--no-gc`) supports `:int`/`:long`/`:float`/`:bool`/`:string`
  but not `:s-expr`, which needs the cons/reader/printer runtime. `:long` is
  `--no-gc`-only: the GC backend rejects it (its integers are `i31ref`, which
  cannot hold an `i64`), pointing you at `--no-gc`.
