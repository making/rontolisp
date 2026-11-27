# n02. A run-mode component's wit-import of wasi:filesystem@0.3.0

Difficulty: High

Left by m99 (2026-11-27). A program that calls `wasi:filesystem/types@0.3.0` and
`wasi:filesystem/preopens@0.3.0` itself (a Java `@WitImport` interface, or
`rontolisp:wit-import`) is refused on `--component`:

```console
rontolisp:wit-import cannot bind 'wasi:filesystem/types@0.3.0': this component already
imports that interface as part of its own WASI surface, ...
```

`WasmComponentBuilder.rejectAdapterImportCollisions` refuses an interface the BASE variant's
world names (`WasiWitDefinitions`), with `--optimize` too, where the pruned component would
import none of it. `FIXED_BLOCK_IFACES` binds a user import from the fixed block for
`monotonic-clock`, `stdin` and `environment` alone, and refuses drops there, so a
`descriptor`'s `close()` has no way through either. A serve component's surface has no
filesystem, and there the import works
(`WasmLispCompilerIntegrationTest#httpHandlerReadsADirectoryAsAStreamOfValuesUnderWasmtimeServe`).

m99 wrote such a program and could not compile it: a notebook, `create-directory-at` as a
`WitResult`, `open-at` as its ok arm, `read-directory`'s `WitStream<DirectoryEntry>`,
`read-via-stream`'s `InputStream`, `write-via-stream`'s `Consumer<OutputStream>`, run under
`wasmtime run --dir .`, a provider over `java.nio.file` elsewhere. It is not kept; the WIT
is upstream's `src/wasm-component/deps/filesystem/`, trimmed.

To decide first, by measurement:

- Bind the user import from the fixed block (the block's `descriptor` type, its drop):
  one import, but `FIXED_BLOCK_IFACES` grows to the members a program names, and the
  runtime's own file I/O and the program's share one resource type.
- Or drop the surface's filesystem when the pruned component would import none of it, and
  import the program's WIT instead: the collision check then runs on the pruned world. What
  does a program that ALSO opens a file through the runtime (`java.nio.file`, `open`) get?
- Byte-identical outputs for programs binding no filesystem (the base components).

Then an example of a Java program on the host's own filesystem, in `examples.yaml` as a
`wasm-component-run` leg (`wasmtime run --dir .`), the interpreter and the JVM against a
provider of its own.
