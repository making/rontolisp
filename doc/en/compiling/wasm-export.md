# Exporting Lisp Functions

By default a compiled module only exposes the WASI `_start` entry point. To make an
individual Lisp function callable directly from a host (`wasmtime --invoke`, JavaScript,
or another module), mark it with the `rontolisp:wasm-export` directive,
declaring the WASM-boundary types of its parameters and result:

```lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
```

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar fact.lisp -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5
```

```
120
```

The type designators and their boundary representations are:

| Designator | WASM boundary | Notes |
| --- | --- | --- |
| `:int` | `i32` | 31-bit signed range (the internal `i31ref`) |
| `:float` | `f64` | |
| `:bool` | `i32` | `0` is `nil`, any non-zero value is `t` |
| `:string` | `(ptr, len)` | UTF-8 bytes in linear memory |
| `:sexpr` | `(ptr, len)` | s-expression text in linear memory (any value except a function) |

A side-effecting function can declare a **void** result by omitting `:returns` (or giving it
as `nil`, `'()` or `:void`); the wrapper then discards the Lisp return value and has no WASM
result. Likewise an omitted, `nil` or `'()` `:params` means no arguments.

```lisp
(defun log-it (n) (print n))
(rontolisp:wasm-export 'log-it :params '(:int))           ; (i32) -> () , prints n
```

Functions whose parameters and result are all scalar (`:int`/`:float`/`:bool`) get a plain
numeric signature, so they can be called straight from `wasmtime --invoke`. The
memory-backed `:string` and `:sexpr` designators pass a pointer/length pair through the
module's exported `memory`, so they need a host that can read and write it (e.g.
JavaScript). For input, the module also exports a bump allocator `__ronto_alloc(size)`
that returns a scratch offset to write the argument bytes into:

```js
const { instance } = await WebAssembly.instantiate(bytes, { wasi_snapshot_preview1: stubs });
const ex = instance.exports, mem = ex.memory;
const b = new TextEncoder().encode('("a" "b" "c")');
const ptr = ex.__ronto_alloc(b.length);
new Uint8Array(mem.buffer, ptr, b.length).set(b);
const [rptr, rlen] = ex.rev(ptr, b.length);          // (rontolisp:wasm-export 'rev :params '(:sexpr) :returns :sexpr)
new TextDecoder().decode(new Uint8Array(mem.buffer, rptr, rlen)); // => ("c" "b" "a")
```

Limitations:

- The directive applies to the Preview 1 core module only; under `--component` it is a
  no-op (no wrapper is emitted). On the interpreter and JVM backends it is also a no-op
  (it just returns the named symbol), so the same source runs on every backend.
- Only a top-level `defun` can be exported, the declared parameter count must match its
  arity, and functions that take or return function values are out of scope.
- The exported name is the bare Lisp name (`fact`); how arguments are written depends on
  the host (`wasmtime --invoke fact module.wasm 5`, `instance.exports.fact(5)`, ...).
- By default, instantiating the module still needs the eight `wasi_snapshot_preview1`
  imports satisfied; `wasmtime run` provides them automatically, and a browser host can
  supply no-op stubs for a pure-compute function. Add `--no-wasi` ([below](no-wasi.md)) to drop them.
