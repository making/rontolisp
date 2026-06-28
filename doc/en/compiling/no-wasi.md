# No-WASI (Reactor) Mode

Add `--no-wasi` to emit a Preview 1 module that imports **no** WASI functions, so a host
can instantiate it with no import object at all — a "reactor"/library module whose only
surface is the exported Lisp functions:

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar fact.lisp --no-wasi -o fact.wasm
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
