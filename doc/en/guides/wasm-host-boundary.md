# WASM Host Boundary (`wasm-export` / `wasm-import`)

Two complementary directives declare what crosses the module/host boundary in
rontolisp's own type designators. Both work in every WASM output shape (the
same source runs on every backend — the directives are no-ops or defun stubs
on the interpreter and the JVM).

For the typed WIT-driven boundary, see the
[WIT contracts guide](wit-contracts.md).

## Exporting Lisp Functions

By default a compiled module only exposes its entry point (`_start`). To make
an individual Lisp function callable directly from a host (`wasmtime --invoke`,
JavaScript, or another module), mark it with the `rontolisp:wasm-export`
directive, declaring the WASM-boundary types of its parameters and result:

```lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
```

```bash
rontolisp fact.lisp -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5
```

```
120
```

The directive itself is the same in every output shape; what changes per shape
is the **host contract** of the export — a raw core function on the core-module
shapes, a typed component-model export under `--component`. On the interpreter
and JVM backends the directive is a no-op (it just returns the named symbol),
so the same source runs on every backend.

The type designators and their boundary representations are:

| Designator | WASM boundary | Notes |
| --- | --- | --- |
| `:int` | `i32` | full 32-bit signed range |
| `:long` | `i64` | full 64-bit signed range on every backend |
| `:float` | `f64` | |
| `:bool` | `i32` | `0` is `nil`, any non-zero value is `t` |
| `:string` | `(ptr, len)` | UTF-8 bytes in linear memory; a component-model `string` under `--component` |
| `:s-expr` | `(ptr, len)` | s-expression text (any value except a function); GC value model only |

A side-effecting function can declare a **void** result by omitting `:returns`
(or giving it as `nil`, `'()` or `:void`); the wrapper then discards the Lisp
return value and has no WASM result. Likewise an omitted, `nil` or `'()`
`:params` means no arguments.

```lisp
(defun log-it (n) (print n))
(rontolisp:wasm-export 'log-it :params '(:int))           ; (i32) -> () , prints n
```

`:as` renames the export — useful when the host-facing API wants a name that is
not an idiomatic Lisp symbol, e.g. camelCase for JavaScript:

```lisp
(defun draw-board (w h) (* w h))
(rontolisp:wasm-export 'draw-board :as "drawBoard" :params '(:int :int) :returns :int)
```

Limitations shared by every shape:

- Only a top-level `defun` can be exported, the declared parameter count must
  match its arity, and functions that take or return function values are out
  of scope.
- The exported name defaults to the bare Lisp name (`fact`) and can be renamed
  with `:as`; how arguments are written depends on the host
  (`wasmtime --invoke fact module.wasm 5`, `instance.exports.fact(5)`, ...).
- The exported function may be a [`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md):
  the boundary resolves the future it answers, so the host receives the
  declared type and never a future.

### Export Modes at a Glance

The same directive compiles into four different host contracts depending on
the `--no-gc` / `--component` flags:

| | GC core module (default / `--no-wasi`) | GC `--component` | `--no-gc` core module | `--no-gc --component` |
| --- | --- | --- | --- | --- |
| Host requirements | wasm-GC engine (`wasmtime -W gc`, Node 22+, current browsers) | wasmtime 46+ (`-W gc=y`) or a component host with wasm-GC + JSPI (a [browser via jco](wasm-browser.md) loads and computes, but cannot print yet) | **any** WebAssembly engine | any component-model host, **no flags** — including a [browser via jco](wasm-browser.md), with no dependencies at all |
| Export shape | raw core function | typed component-model export (WAVE `--invoke`, jco) | raw core function | typed component-model export (WAVE `--invoke`, jco) |
| Scalars | `:int`/`:long`/`:float`/`:bool`/void | `:int`/`:long`/`:float`/`:bool`/void | `:int`/`:long`/`:float`/`:bool`/void | `:int`/`:long`/`:float`/`:bool`/void |
| `:string` | manual `(ptr,len)` + `__ronto_alloc` | component-model `string` (canonical ABI) | manual `(ptr,len)` + `__ronto_alloc` | component-model `string` (canonical ABI) |
| `:s-expr` | manual `(ptr,len)` | component-model `string` (printed text) | not supported | not supported |
| Function body may use | the full language | the full language | the [non-GC subset](wasm-nogc.md#eligible-subset) | the [non-GC subset](wasm-nogc.md#eligible-subset) |
| I/O inside the export | works (real WASI imports; under `--no-wasi` output is discarded, `random` runs on a built-in generator, `getenv`/file lookups answer nothing, the clock is the one the host wrote through `__ronto_set_time` and input traps) | usually works even in a sync export; [`:async t`](wasm-component.md#component-model-function-exports-wasm-export) removes the residual trap risk | `print` only (one `fd_write` import) | `print` only (built-in WASI 0.3 stdout bridge; the exports become async lifts) |
| Program top level | runs as `_start` | co-exists as `wasi:cli/run` | `defun` + directives only | `defun` + directives only |
| Per-call string memory | host-managed (`__ronto_alloc` + the [arena API](wasm-gc-module.md#reclaiming-the-hosts-buffer-the-arena-api); the Lisp side is the engine's) | freed by the canonical post-return | host-managed (`__ronto_alloc` + the [arena API](wasm-nogc.md#reclaiming-memory-the-arena-api); automatic for scalar returns) | freed by the canonical post-return |
| Typical size | ~100 KB (~2 KB with [`--optimize`](../compiling/wasm.md#optimize-tree-shaking)) | ~110 KB | tens of bytes to a few KB | hundreds of bytes to a few KB |

Each shape's own guide details how its exports are called, what runs inside
them, and what each host must provide:
[wasm-GC core module](wasm-gc-module.md),
[WASI 0.3 component](wasm-component.md),
[--no-gc output and its compact component wrap](wasm-nogc.md).

## Importing Host Functions

`rontolisp:wasm-import` is the reverse of `wasm-export`: it declares a function
the **host** provides and makes it callable from Lisp under the given name
exactly like a top-level `defun` — including `#'name`, `funcall`, `mapcar` and
`eval`. `:from` names the import module (default `"env"`), `:as` names the
field inside it (default: the Lisp name), and the type designators are the
same table as above:

```lisp
; main.lisp
(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)
(defun add10 (n) (add n 10))
(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
```

In wasmtime, satisfy the imports by preloading another module that exports
them — here a host module that is itself written in Lisp, exporting its
function under the `:as` alias `add`:

```console
$ cat host.lisp
(defun host-add (a b) (+ a b))
(rontolisp:wasm-export 'host-add :as "add" :params '(:int :int) :returns :int)
$ rontolisp host.lisp -o host.wasm --no-wasi
$ rontolisp main.lisp -o main.wasm --no-wasi
$ wasmtime run -W gc --preload host=host.wasm --invoke add10 main.wasm 32
42
```

In a browser (or Node) the import object *is* the module table — one key per
`:from` name, one property per `:as` name. This is also the escape hatch for
anything the WASM backend does not provide; for example it has no
trigonometric built-ins, so borrow JavaScript's:

```lisp
(rontolisp:wasm-import 'sin :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'cos :from "math" :params '(:float) :returns :float)
```

```js
const imports = { math: { sin: Math.sin, cos: Math.cos } };
const { instance } = await WebAssembly.instantiate(bytes, imports);
```

The [WebGL triangle example](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-triangle)
is the hello world of this pattern: ten imported functions, no exports, and a
colored triangle drawn entirely from Lisp. The
[WebGL cube example](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-cube)
adds 3D: the perspective and rotation matrices are computed in Lisp every
frame. The
[WebGL galaxy example](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-galaxy)
is the same idea grown into a complete browser program: the entire WebGL
pipeline is driven from Lisp — the GLSL shaders live in the Lisp source, and
Lisp compiles, links, buffers and issues every draw call through 32 imported
host functions, while JavaScript supplies only one-line bindings over a handle
table -- generated from the [WIT](wit-contracts.md#importing-a-wit-interface-wit-import)
that declares the boundary.

Boundary details beyond the scalar types:

- A `:string`/`:s-expr` **argument** reaches the host as a `(ptr, len)` pair
  into the module's exported `memory` (an `:s-expr` argument is printed to
  readable text first).
- A `:string` **result** must be written into linear memory by the host —
  reserve the buffer with the exported `__ronto_alloc`, then return the
  `(ptr, len)` pair (a two-element array in JavaScript).
- An `:s-expr` **result** is parsed with the embedded reader, so the host can
  hand back a whole list structure as text.
- An **asynchronous** host function — a `WebAssembly.Suspending`-wrapped
  import under JSPI — is declared with `:async t`: the call then returns a
  future that `rontolisp:await` resolves, the build prints the host's
  obligations (`Suspending` on the import, `promising` on the exports that can
  reach it, serialised calls — a re-entered export refuses with a trap instead
  of corrupting both calls), and a call reachable from a top-level form of a
  `--no-wasi` module is a compile error (`_initialize` cannot suspend). The
  [reference page](../reference/functions/rontolisp-wasm-import.md) has the
  full contract.

Limitations:

- Default (wasm-GC) Preview 1 output only: `--component` and `--no-gc` reject
  the directive with an error.
- On the interpreter and JVM backends the directive defines a stub that
  signals an error when called, so a shared source still loads everywhere, but
  actually calling an import needs the WASM host.
- Imported functions have the same 10-parameter arity limit as other functions
  under the wasm-GC value model.
- Instantiating the module requires every declared import to be provided:
  `wasmtime run` needs a `--preload <module>=<file>.wasm` per import module
  name, and a JavaScript host passes an import object.
