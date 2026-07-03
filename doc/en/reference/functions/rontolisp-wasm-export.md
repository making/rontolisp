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
| `:float` | `f64` | |
| `:bool` | `i32` | `0` is `nil`, any non-zero value is `t` |
| `:string` | `(ptr, len)` | UTF-8 bytes in linear memory |
| `:s-expr` | `(ptr, len)` | s-expression text in linear memory (any value except a function) |

## Limitations

- Applies to the Preview 1 core module only. Under `--component` it is a no-op
  (no wrapper is emitted), and on the interpreter and JVM it just returns the
  named symbol.
- Only a top-level `defun` can be exported; the declared parameter count must
  match its arity, and functions that take or return function values are out of
  scope.
- The exported function is pure-compute: any I/O (printing, reading, time,
  random, or a top-level I/O form) traps under `--no-wasi` and is otherwise
  unsupported.
- The non-GC backend (`--no-gc`) supports `:int`/`:float`/`:bool`/`:string` but
  not `:s-expr`, which needs the cons/reader/printer runtime.
