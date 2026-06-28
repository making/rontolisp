# Compile to WASM

```bash
rontolisp hello.lisp -o hello.wasm
wasmtime run -W gc hello.wasm
```

```
3
```

The generated `.wasm` binary uses:

- **wasm-GC** -- Integers are represented as `i31ref`. Floating-point numbers are boxed in a `float_struct { f64 }`. All values on the stack are typed as `(ref eq)`.
- **WASI Preview 1** -- `fd_write` for stdout output.

Requires a wasm-GC capable runtime such as wasmtime 14+.

On the WASM backend a function (`defun` or `lambda`) may take at most **seven
parameters**; a larger arity is a compile error (the interpreter and JVM backends have no
such limit). Bundle the extra arguments into a list to stay within it.

The default output is a Preview 1 core module that exposes only the WASI `_start` entry
point. The sections below cover the WASM-specific options: marking individual functions as
host-callable (`rontolisp:wasm-export`), dropping the WASI imports for a reactor/library
module (`--no-wasi`), shrinking the module by tree shaking (`--optimize`), and emitting a
WASI 0.3 component (`--component`).

## Exporting Lisp Functions

By default a compiled module only exposes the WASI `_start` entry point. To make an
individual Lisp function callable directly from a host (`wasmtime --invoke`, JavaScript,
or another module), mark it with the `rontolisp:wasm-export` directive,
declaring the WASM-boundary types of its parameters and result:

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
  supply no-op stubs for a pure-compute function. Add `--no-wasi`
  ([below](#no-wasi-reactor-mode)) to drop them.

## No-WASI (Reactor) Mode

Add `--no-wasi` to emit a Preview 1 module that imports **no** WASI functions, so a host
can instantiate it with no import object at all — a "reactor"/library module whose only
surface is the exported Lisp functions:

```bash
rontolisp fact.lisp --no-wasi -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120
```

```js
// No import object needed.
const { instance } = await WebAssembly.instantiate(bytes);
instance.exports.fact(5);                         // => 120
// :string / :sexpr still round-trip through the exported memory + __ronto_alloc.
```

The eight WASI import slots are filled with internal trap stubs so every function index
stays fixed (no other codegen changes). This mode is for **pure-compute** exports only:
any I/O (`print`/`read`/`open`/`getenv`/time/`random`, including a top-level form that
prints) hits a stub and **traps**. It is Preview 1 only — `--no-wasi` is ignored under
`--component`.

Because the module is a reactor (not a WASI command), its top-level initializer is
exported as **`_initialize`** rather than `_start`. A host should call `_initialize` once
after instantiation to run top-level forms (`defvar`/`defparameter`/`setq` globals that an
exported function reads); pure-compute reactors that hold no top-level state can skip it.

## Optimize (Tree Shaking)

By default a compiled module embeds the **entire** runtime (printer, rational, string,
reader and `eval` helpers, the WASI import slots, …) regardless of what the program
actually uses, because function indices are held fixed. Add `--optimize` to drop every
function unreachable from the module's roots (its exports and the `_start`/`_initialize`
entry) and renumber the survivors. Unused WASI imports are removed too, so a pure-compute reactor module shrinks to
a handful of functions:

```bash
rontolisp fact.lisp --no-wasi --optimize -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120, from a ~1 KB module
```

For the `fact` example above the module drops from ~26 KB to ~1.3 KB. `--optimize`
is opt-in and behavior-preserving: it walks the call graph from the actual `call`
instructions, so anything reachable (including code an embedded `eval`/`load` dispatches
to) is kept. It is WASM only and has **no effect** under `--component` (the WASI 0.3
adapter relies on the core's fixed import/index layout). JVM dead-code elimination is not
yet implemented.

## WASI 0.3 Component

Add `--component` to emit a WASI 0.3 (Preview 3) **component** instead of a Preview 1 core module. The component prints through `wasi:cli/stdout@0.3.0`:

```bash
rontolisp hello.lisp --component -o hello.wasm
wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y hello.wasm
```

```
3
```

In WASI 0.3 all byte I/O flows through the built-in component-model `stream<u8>` / `future<T>` types and the async canonical ABI. rontolisp keeps the same Preview 1 core module unchanged — it still imports the eight `wasi_snapshot_preview1` functions — and an **adapter** core module implements them over WASI 0.3 (`wasi:cli`, `wasi:filesystem`, `wasi:clocks`, `wasi:random`) using `stream.new`/`stream.read`/`stream.write` and `future.read`. The component's `wasi:cli/run@0.3.0` export (an `async func`) is lifted as a **stackful** async export, so the synchronous stream/future built-ins block cooperatively and the adapter stays straight-line code. The three `component-model-async*` flags enable those features (stackful async lift + synchronous stream/future built-ins).

The wasmtime invocation does **not** select the output kind. `wasmtime run` is wasmtime's default subcommand and auto-detects a core module vs a component, so `wasmtime run -W gc` runs the Preview 1 `hello.wasm` from the previous section just as well. Only the `--component` compile flag decides whether a Preview 1 core module or a WASI 0.3 component is produced. (The practical difference shows up on a component-only runtime, which runs the component but not the Preview 1 core module.)

The default output (without `--component`) stays a Preview 1 core module, so nothing changes for existing usage.

File I/O works in component mode too — it is implemented over `wasi:filesystem@0.3.0` (`read-via-stream` / `append-via-stream`, driven through `stream`/`future`). As in Preview 1, file access needs `--dir`:

```bash
cat > fileio.lisp <<'EOF'
(with-open-file (out "greeting.txt" :direction :output)
  (write-line "hello" out))
(with-open-file (in "greeting.txt")
  (print (read-line in)))
EOF
rontolisp fileio.lisp --component -o fileio.wasm
wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y --dir . fileio.wasm
# "hello"
```

Notes and current limitations of component mode:

- Requires a runtime with WASI 0.3 component-model async support: **wasmtime 46+** (pass `-W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y`).
- `print`/stdout, stdin (`read`, 0-argument `read-line`, over `wasi:cli/stdin@0.3.0`), and file I/O (`open`, `close`, `write-line`, stream `read-line`, `load`, `with-open-file`) all work. File access requires `--dir` (paths resolve against the first preopened directory).
- `random` draws real entropy from `wasi:random@0.3.0` (Preview 1 uses the host's `random_get`), so `(random N)` differs each run. `get-universal-time` / `get-internal-real-time` / `get-internal-run-time` read `wasi:clocks@0.3.0` (`system-clock`/`monotonic-clock`), and `getenv` reads `wasi:cli/environment@0.3.0`.
- Outgoing HTTP (`rontolisp:fetch`) works in component mode, but is a **hybrid**: the base I/O stays WASI 0.3 while fetch itself imports `wasi:http@0.2` + `wasi:io@0.2` (async `wasi:http@0.3` does not exist upstream yet — see `.todo/02-upgrade-fetch-to-wasi-http-0.3.md`). Run a fetch component with `-S http=y` in addition to the async flags. Non-fetch components do not import `wasi:http`, so they do not need `-S http`.
- The compiled Lisp otherwise behaves identically to the Preview 1 output for the supported features.
