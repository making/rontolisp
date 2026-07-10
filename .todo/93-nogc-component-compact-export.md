# 93. Compact `--no-gc` + `--component` output (tiny component-model export)

## STATUS: Release 1 + Tier 2 (`:string`) DONE (2026-07-11) -- remaining tasks below

Scalar release complete. `--no-gc --component` emits a **run-less reactor
component**: the byte-identical MVP core module (core module 0, instance 0, no
args) + per-export `aliasCoreFunc`/`funcTypeScalars`/sync `canonLift`/`exportFunc`
-- NO import block, NO adapter, NO mem module, NO `wasi:cli/run`.

Measured 2026-07-11 (wasmtime 46.0.1 local, jco 1.25.2):

- 4-export program (`:int`/`:long`/`:float`/`:bool`): plain module 221 bytes,
  component **406 bytes** (single-export ~250 bytes; structural pin asserts
  < 1 KB). Kilobyte order confirmed vs the GC component's fixed multi-KB blob set.
- `wasmtime run --invoke 'sumsquared(2, 3)' comp.wasm` works with **ZERO extra
  flags** (no `-W gc`, no async flags -- the early probe confirmed a run-less
  component invokes fine) and no experimental warning.
- jco transpile + Node: all types round-trip; `:long` surfaces as BigInt.
- `wasm-tools component wit` shows the typed world (`s32`/`s64`/`f64`/`bool`).

### What was built

- `NoGcWasmCompiler(optimize, simd, component)` third ctor arg (2-arg delegates
  `false`); validation only in `compile()`, codegen UNTOUCHED -- `--no-gc`
  non-component output proven byte-identical by stash dance (scalar + `--simd`).
- new `codegen/wasm/NoGcWasmComponentBuilder` (adapter-free wrap; all existing
  `ComponentWriter` encoders sufficed, no `am.ik.wasm` change).
- `WasmExportCompiler.componentValType` maps `:long` -> `VT_S64` (0x78); the GC
  path still rejects `:long` before reaching it. Kebab-name pattern
  `COMPONENT_EXPORT_NAME` moved from `WasmLispCompiler` to `WasmExportCompiler`
  (shared).
- CLI gate removed (`RontoLispCli` routes `--no-gc --component` to the new ctor);
  usage text updated.
- Component-mode compile errors: `print`/`princ`/`terpri` (todo-110 Release-1
  decision; `mem.printUsed()`), `:string` boundary (Tier 2 below), non-kebab
  names. Internal string use stays fine. `--optimize` composes (shake before
  wrap), unlike the GC path where `--optimize` is a no-op under `--component`.
- Tests: `NoGcWasmCompilerTest` (verbatim-core/size/S64/error pins),
  `WasmLispCompilerIntegrationTest` (`noGcComponentExportsCallableViaWaveInvokeWithNoFlags`,
  `noGcComponentHonorsAsAliasAndComposesWithOptimize`); #92 GC export tests
  unchanged and green. ci-spec NOT extended (the driver does not run `--no-gc`).
- Docs: `doc/{en,ja}/compiling/wasm.md` new "Compact Component Output" section
  + cross-links, `rontolisp-wasm-export.md` limitation bullet, CLI usage,
  `.kb/no-gc-scalar-wasm.md`, `.kb/wasi-component.md`, CLAUDE.md.

### Tier 2 (`:string` exports) -- DONE 2026-07-11

Canonical string lift over the module's OWN exported `memory`, all appends
gated on component mode + a `:string` boundary (scalar-only components and every
non-component output stay byte-identical -- stash-dance-proven for scalar
component, `--no-gc` plain/string/string+optimize/`--simd`, component+optimize):

- **Return shape (the MAX_FLAT_RESULTS=1 trap)**: a per-`:string`-returning-export
  **retptr shim** appended after all existing functions -- forwards params to the
  untouched two-value wrapper, `__alloc`s an 8-byte `(ptr,len)` record, returns
  its address; core-exported under the export name in place of the wrapper.
- **`cabi_realloc`** = `local.get 3; call __alloc` (old/oldsz/align ignored --
  bump arena, strings are align 1 and __alloc 4-aligns).
- **Heap reclamation: post-return ADOPTED** (not the todo-88 leak): one shared
  `cabi_post_<i32|i64|f64|void>` per flat-result signature resets heap global 0
  to `heapBase` -- nothing in a `--no-gc` instance outlives one call, so no
  saved-mark global is needed. Verified BOTH hosts call it: wasmtime by an
  `unreachable`-probe toy component, jco by 200k-call Node loop on the raw core
  (memory 65536 -> 65536, flat) + `postReturn` present in the transpiled glue.
- **Lift encoder**: `ComponentWriter.canonLiftMemoryReallocUtf8PostReturn`,
  options in wasm-tools' canonical order `(memory 0) (realloc N) utf8
  (post-return M)` -- byte-pinned in `ComponentWriterTest` against a
  `wasm-tools dump` golden (`000002040300040000050100`).
- `WasmExportCompiler.componentValType` maps `:string` -> VT_STRING (0x73); the
  GC component path still rejects `:string` before reaching it. `:s-expr` stays
  rejected by `validateScalarTypes` (all `--no-gc`).
- Measured (wasmtime 46.0.1, jco 1.25.2 via npx): 1-string-export component
  975 bytes (plain core 753), the 3-export count-a/shout/greet program
  1498 bytes (1218 with `--optimize`). `wasmtime run --invoke
  'count-a("banana")'` / `'greet("世界")'` work with ZERO flags; UTF-8
  multi-byte round-trips; `wasm-tools component wit` shows `string`.
- Tests: `componentStringExportAppendsTheCanonicalStringAbi`,
  `componentSharesOnePostReturnPerFlatResultSignature`,
  `componentWithoutStringExportsOmitsTheStringAbi` (NoGcWasmCompilerTest);
  `noGcComponentStringExportsLiftThroughTheCanonicalAbi` (integration);
  encoder byte pin (ComponentWriterTest). 3187/0 (2 skipped).

## Remaining tasks (later phases)

1. **print micro-adapter**: a minuscule core module implementing `fd_write` over
   `wasi:cli/stdout` (adapter-serve-p1 pattern in miniature), swapping only the
   `__write_stdout` funnel implementation (the todo-110 seam). Un-errors print
   under `--no-gc --component`.
2. **(optional) WIT output** (`.wit` file next to the `.wasm`) so hosts / jco
   generate bindings without `wasm-tools component wit`.

## Non-goal / trade-off (recorded in docs)

`--no-gc`'s whole value is a plain MVP module that **any** engine runs via a raw
core `(func)`. Wrapping as a component re-introduces a component-model-capable
host requirement, trading portability for typed WIT + WAVE `--invoke`. BOTH
outputs stay available; component is NOT the default for `--no-gc`.

## Related

- **#92** (GC-backend component exports; Tier 2/3 live in `.todo/92`)
- `.kb/no-gc-scalar-wasm.md` (the authoritative mechanics paragraph)
- `.kb/wasi-component.md` (GC blob set this path deliberately avoids)
- `.kb/wasm-export-no-wasi.md` (`:long`<->i64; reactor ABI)
