# 93. Compact `--no-gc` + `--component` output (tiny component-model export)

## STATUS: Release 1 DONE (2026-07-11) -- remaining tasks below

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

## Remaining tasks (later phases)

1. **`:string` exports (Tier 2)**: canonical string lift over the module's OWN
   exported `memory` + a `cabi_realloc`-signature shim wrapping `__ronto_alloc`
   (4-arg `(old, oldsz, align, newsz)`); utf8 canon options on lift and lower.
   The `(ptr,len)` core ABI stays available without `--component`.
2. **print micro-adapter**: a minuscule core module implementing `fd_write` over
   `wasi:cli/stdout` (adapter-serve-p1 pattern in miniature), swapping only the
   `__write_stdout` funnel implementation (the todo-110 seam). Un-errors print
   under `--no-gc --component`.
3. **(optional) WIT output** (`.wit` file next to the `.wasm`) so hosts / jco
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
