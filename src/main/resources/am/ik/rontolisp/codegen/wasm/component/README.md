# Generated WASI 0.2 component blobs

These three files are **generated artifacts** loaded at runtime by
`am.ik.rontolisp.codegen.wasm.WasmComponentBuilder` (and registered for GraalVM native
image in `META-INF/native-image/am.ik.rontolisp/rontolisp/resource-config.json`):

- `import-block.bin` — the unified WASI 0.2 type/import sections for all imported
  interfaces.
- `mem.wasm` — the shared memory core module.
- `adapter.wasm` — the preview1-to-0.2 adapter core module.

Do not edit them by hand. Their **sources** and the regeneration script live outside the
resources tree (so they are not packaged into the runtime jar) at:

```
src/wasm-component/      (mem.wat, adapter.wat, uni.wit, deps/, core.wat, regen.sh, README.md)
```

Regenerate everything in this directory with:

```bash
src/wasm-component/regen.sh
```

See `src/wasm-component/README.md` for the full provenance and how the unified import
block is built from the WIT world.
