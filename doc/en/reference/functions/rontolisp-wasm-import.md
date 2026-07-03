# rontolisp:wasm-import

`(rontolisp:wasm-import 'name :from "module" :as "field" :params '(type...) :returns type)`

Declares a function the WASM host provides (JavaScript in a browser, or another
module preloaded into wasmtime) and makes it callable from Lisp under `name`
exactly like a top-level `defun` — including `#'name`, `funcall`, `mapcar` and
`eval`. It is a compile-time directive, not an ordinary function: on the
**interpreter** and **JVM** backends it defines a stub that signals an error
when called (there is no host to call), so the same source still loads on every
backend. See [Compiling to WebAssembly](../../compiling/wasm.md) for the full
guide and the [WebGL galaxy example](https://github.com/making/rontolisp/tree/develop/examples/webgl-galaxy)
for a complete browser program.

```lisp
(rontolisp:wasm-import 'draw-pixel :from "gl" :as "drawPixel"
                       :params '(:int :int :int) :returns :void)   ; => draw-pixel
```

## Arguments

- A quoted symbol naming the Lisp-visible function. It resolves in the
  current [package](../packages.md) like a `defun` name, so a directive after
  `(in-package mylib)` defines `mylib:name`.
- `:from` — the import module name (the import-object key on the JavaScript
  side, or the `--preload` name in wasmtime). Defaults to `"env"`.
- `:as` — the import field name (the property inside that module object).
  Defaults to the bare Lisp name (without any package qualifier).
- `:params` — a list of boundary type designators, one per parameter. Omitted,
  `nil` or `'()` means no arguments.
- `:returns` — the result boundary type designator. Omitted, `nil`, `'()` or
  `:void` declares a void result (Lisp receives `nil`).

The type designators are shared with
[`rontolisp:wasm-export`](rontolisp-wasm-export.md):

| Designator | WASM boundary | Notes |
| --- | --- | --- |
| `:int` | `i32` | 31-bit signed range (the internal `i31ref`) |
| `:float` | `f64` | an int or ratio argument is converted like the arithmetic built-ins |
| `:bool` | `i32` | `nil` crosses as `0`, anything else as `1`; a non-zero result reads back as `t` |
| `:string` | `(ptr, len)` | UTF-8 bytes in linear memory |
| `:s-expr` | `(ptr, len)` | the argument is printed to readable text; a result is parsed by the embedded reader |

A `:string` result must be written into linear memory by the host (reserve the
buffer with the exported `__ronto_alloc`) and returned as a `(ptr, len)` pair
(a two-element array from JavaScript).

## Limitations

- Applies to the default (wasm-GC) Preview 1 core module only; `--component`
  and `--no-gc` reject the directive with an error. On the interpreter and JVM
  the declared name signals an error when called.
- The directive must appear at top level, before use like a `defun`.
- Instantiating the compiled module requires the host to provide every declared
  import; `wasmtime run` needs a `--preload <module>=<file>.wasm` for each
  import module name, and a JavaScript host passes an import object.
- At most 7 parameters (the general WASM-backend arity limit).
