# Compile to WASM

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar hello.lisp -o hello.wasm
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

See also: [Exporting Lisp Functions](wasm-export.md), [No-WASI mode](no-wasi.md), [Optimize](optimize.md), [WASI 0.3 Component](component.md).
