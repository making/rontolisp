# rontolisp:wasm-import

`(rontolisp:wasm-import 'name :from "module" :as "field" :params '(type...) [:param-names '(name...)] :returns type [:async t])`

Declares a function the WASM host provides (JavaScript in a browser, or another
module preloaded into wasmtime) and makes it callable from Lisp under `name`
exactly like a top-level `defun` — including `#'name`, `funcall`, `mapcar` and
`eval`. It is a compile-time directive, not an ordinary function: on the
**interpreter** and **JVM** backends it defines a stub that signals an error
when called (there is no host to call), so the same source still loads on every
backend. See the [WASM host boundary guide](../../guides/wasm-host-boundary.md)
for the full guide and the [WebGL galaxy example](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-galaxy)
for a complete browser program.

```lisp
(rontolisp:wasm-import 'draw-pixel :from "gl" :as "drawPixel"
                       :params '(:int :int :int) :returns :void)   ; => DRAW-PIXEL
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
- `:param-names` — the parameter names of the **component-model** signature,
  one per `:params` entry, as symbols or strings; defaults to `p0`, `p1`, ….
  Read only by
  [`--no-gc --component`](../../guides/wasm-nogc.md#host-imports-in-a-component),
  where they are the labels of the imported function's type (a WIT world's
  names, when the import stands for one); a core module's imports have no
  parameter names.
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
| `:bytes` | `(ptr, len)` argument / `(ptr, cap) -> len` result | an `(unsigned-byte 8)` vector as raw bytes — no UTF-8 in either direction |

A `:string`/`:s-expr` **argument** is a `(ptr, len)` view into memory the call
already holds — read it, but do not write through it. See [the boundary
guide](../../guides/wasm-host-boundary.md#importing-host-functions) for what
backs the pointer on each backend and why writing through it is unsafe.

A `:string` result must be written into linear memory by the host (reserve the
buffer with the exported `__ronto_alloc`) and returned as a `(ptr, len)` pair
(a two-element array from JavaScript).

A `:bytes` **result** is caller-buffered (the `read(2)` shape): the Lisp
signature gains one trailing parameter — the `(unsigned-byte 8)` vector to
receive into — and the host function is called with a trailing `(ptr, cap)`
pair: *write up to `cap` bytes at `ptr` and return the value's full length*.
The Lisp call answers that full length, so a result longer than the buffer is
a retry with a bigger buffer, never a silent truncation; the wrapper's staging
is popped on return, so a pull loop over one reused buffer keeps linear memory
flat.

```lisp
(rontolisp:wasm-import 'read-chunk :from "env" :as "readChunk"
                       :params '() :returns :bytes)   ; => READ-CHUNK
;; (read-chunk buf) => the chunk's full length; up to (length buf) bytes
;; of buf are overwritten. On the JS side: readChunk(ptr, cap) -> n.
```

## `:async t` — a host function that may suspend

`:async t` declares that the host may implement the function
**asynchronously** — on a JavaScript host, a `WebAssembly.Suspending`-wrapped
function (JSPI). The call then returns a **future** that
[`rontolisp:await`](../special-forms/rontolisp-await.md) resolves, so the source says at the
call site that the boundary is asynchronous — the same reading as an
`async func` member of a [`rontolisp:wit-import`](rontolisp-wit-import.md),
which lowers to exactly this option on this backend. (The word deliberately
matches [`rontolisp:wasm-export`](rontolisp-wasm-export.md)'s `:async`: WIT
spells both directions `async func`, and the directive carries the direction.)

```lisp
(rontolisp:wasm-import 'host-fetch :from "env" :as "fetch"
                       :params '(:string) :returns :string :async t)   ; => HOST-FETCH
```

- On this backend the future is **settled at creation**: the host call blocks
  the wasm stack — synchronously, or suspended through JSPI — so the value is
  ready when the call returns and `await` never actually suspends. The option
  buys one source that reads the same everywhere, not concurrency.
- The build prints what the host now owes: wrap the import in
  `WebAssembly.Suspending`, enter every export that can reach it through
  `WebAssembly.promising` (the build lists them), and serialise calls — a
  suspended module can be re-entered, and a re-entered export **refuses with a
  trap** instead of silently corrupting both calls (every export wrapper of a
  module that can suspend carries a re-entry guard, unless it was compiled
  [`--reentrant`](../../guides/wasm-host-boundary.md#overlapping-calls---reentrant),
  which lets a JSPI host overlap calls instead). A host that answers
  synchronously is equally valid; the call returns an already-settled future
  either way. `--emit-js-glue` WRITES that half rather than describing it (the
  [host boundary guide](../../guides/wasm-host-boundary.md#generating-the-host-glue---emit-js-glue)):
  the host is then left with what its functions do, and says which of them
  suspend.
- Under `--no-wasi`, a call reachable from a top-level form is a **compile
  error**: `_initialize` runs on a stack no `promising` entered, so a
  suspension there traps naming nobody. Move the call behind an export, or
  drop `:async t` if the host answers synchronously.

## Limitations

- On the default (wasm-GC) backend, core modules only: `--component` rejects
  the directive with an error (a component binds interfaces with
  [`rontolisp:wit-import`](rontolisp-wit-import.md)). On the interpreter and
  JVM the declared name signals an error when called.
- [`--no-gc`](../../guides/wasm-nogc.md#host-imports-rontolispwasm-import) takes
  the directive, with its own type vocabulary: the whole fixed-width integer
  family (its house integer is `i64`), `:float`, `:bool`, `:string` and `:void`,
  but not `:s-expr`, not `:bytes` and not `:async t`. Under
  [`--no-gc --component`](../../guides/wasm-nogc.md#host-imports-in-a-component)
  the reached imports become the component's imports, so `:from` must be a
  lower-kebab-case label or a WIT interface id, and `:as` and every
  `:param-names` entry a lower-kebab-case label.
- The directive must appear at top level, before use like a `defun`.
- Instantiating the compiled module requires the host to provide every declared
  import; `wasmtime run` needs a `--preload <module>=<file>.wasm` for each
  import module name, and a JavaScript host passes an import object.
- At most 10 parameters (the general WASM-backend arity limit).
