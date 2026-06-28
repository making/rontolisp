# Optimize (Tree Shaking)

By default a compiled module embeds the **entire** runtime (printer, rational, string,
reader and `eval` helpers, the WASI import slots, …) regardless of what the program
actually uses, because function indices are held fixed. Add `--optimize` to drop every
function unreachable from the module's roots (its exports and the `_start`/`_initialize`
entry) and renumber the survivors. Unused WASI imports are removed too, so a pure-compute reactor module shrinks to
a handful of functions:

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar fact.lisp --no-wasi --optimize -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120, from a ~1 KB module
```

For the `fact` example above the module drops from ~26 KB to ~1.3 KB. `--optimize`
is opt-in and behavior-preserving: it walks the call graph from the actual `call`
instructions, so anything reachable (including code an embedded `eval`/`load` dispatches
to) is kept. It is WASM only and has **no effect** under `--component` (the WASI 0.3
adapter relies on the core's fixed import/index layout). JVM dead-code elimination is not
yet implemented.
