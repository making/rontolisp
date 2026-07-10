# 92. Host-callable `wasm-export` under `--component` (WASI 0.3 component exports)

## Goal

Let `(rontolisp:wasm-export 'name :params '(...) :returns ...)` produce a
**component-model function export** when compiling with `--component`, so a host
can call it through the canonical ABI (WAVE syntax) instead of only through a
Preview-1 / `--no-gc` core-module export.

Payoff:

- `wasmtime --invoke 'sumsquared(2, 3)' comp.wasm` (WAVE syntax) works, and the
  `--invoke ... experimental` stderr warnings that core-module `--invoke`
  emits go away (WAVE invoke on a component is the supported path).
- Typed signatures are visible to any component host (jco, wasmCloud, etc.), not
  just an untyped raw core `(func)`.

## Current state (why it does NOT work today)

- `wasm-export` is **Preview-1 core module only**. Gated off under `--component`:
  - `WasmLispCompiler.java:1176` `if ((!this.component || this.serve) && !exportDecls.isEmpty())`
    -- export wrapper funcs are not built in component mode.
  - `WasmLispCompiler.java:845` `exportNeedsReader = (!this.component) && ...`
  - `WasmLispCompiler.java:1908` component mode exports only `run`.
- `WasmComponentBuilder` wraps the unchanged core module with **fixed byte blobs**
  (`import-block.bin` / `mem.wasm` / `adapter.wasm`) and lifts exactly ONE export:
  `wasi:cli/run@0.3.0` (`run`), as a **stackful async** `canonLift`.
- Root cause: `wasm-export` is a *core-module ABI* mechanism (`:int`<->i31/i32,
  `:float`<->f64, `:string`/`:s-expr`<->`(ptr,len)` in linear memory). In the
  component model a core export is invisible to the host -- only what a WIT world
  lifts via the canonical ABI is callable. rontolisp's component output is a
  fixed WASI *command* component with no machinery to synthesize per-export lift
  glue or a custom world.

Confirmed empirically (2026-07-07): `--component` build of a `wasm-export`
program then `wasmtime run -W gc=y ... --invoke 'sumsquared(2,3)'` ->
`No exported func named 'sumsquared' in component.`

## Good news: the encoder primitives already exist

`am.ik.wasm.ComponentWriter` already has: `canonLift`, `funcTypeResult`,
value types `VT_S32`/`VT_S64`/`VT_U32`/.../`VT_BOOL`/`VT_STRING`,
`aliasCoreFunc`, `aliasCoreMemory`, `canonLowerMemoryUtf8` /
`canonLowerMemoryReallocUtf8`, and the SEC_EXPORT / SEC_CANON / SEC_TYPE section
ids. So this is *wiring*, not new encoder infrastructure.

## Tier 1 -- scalar-only exports (MVP, do this first)

Support `:int`/`:float`/`:bool`/`:void` only. Small, self-contained.

1. **Un-gate wrapper generation under component** (mirror how `serve` already
   un-gates): keep building the `ExportPlan` wrapper funcs (they bridge the
   internal calling convention to a plain core signature -- reuse as-is), and
   have the core module **core-export** each wrapper so the component can alias +
   lift it. Touch the `(!this.component ...)` gates at
   `WasmLispCompiler.java:1158/1176/1908` (and keep `run` too).
2. **Type mapping** (GC backend): `:int`->`VT_S32`, `:float`->`VT_F64`,
   `:bool`->`VT_BOOL`, `:void`->no result. (`:long`=i64 stays `--no-gc`-only;
   reject under component GC. `:string`/`:s-expr` -> clear "not yet supported
   under --component" error until Tier 2.)
3. **Wire `WasmComponentBuilder`**: for each `ExportPlan` -> `aliasCoreFunc`
   the core wrapper, build a component func type via `funcTypeResult`, emit a
   **synchronous** `canonLift` (NOT the stackful-async lift `run` uses -- a
   pure-compute scalar export needs no memory/realloc/async), add a SEC_EXPORT
   entry under the export name (`Decl.exportName()`, honors `:as`).
4. **World / init decision**: simplest is to **co-exist with `run`** (host can
   call either). Cleaner alternative: a reactor-style component (no `run`,
   `_initialize` init) analogous to `--no-wasi`. Pick one; co-exist is less work.
5. **Likely NO blob regen**: exports are added programmatically via SEC_EXPORT +
   SEC_CANON, not through `import-block.bin` (which only declares *imports*). So
   Tier 1 should not need `src/wasm-component/` regeneration. Verify this
   assumption early.

Caveat to verify: whether the export is callable without `_start`/init having
run (state init). Pure-compute scalar exports should be fine; if globals need
seeding, prefer the reactor `_initialize` shape.

## Tier 2 -- string / s-expr exports (the real cost)

`:string`/`:s-expr` cross core as `(ptr, len)`; component `string`/`list<u8>`
needs canonical lift/lower:

- Use the memory + `cabi_realloc` already exported by `mem.wasm`; lift/lower via
  the `canonLower...Utf8` / a `canonLift...Utf8` counterpart.
- rontolisp strings are quote-framed bytes / GC strings, so the
  reader/printer bridge (the `exportNeedsReader` path at
  `WasmLispCompiler.java:845`) must be threaded into the component path too.
- Medium effort.

## Tier 3 -- async I/O exports + WIT output

- An export that transitively does I/O must be lifted through the stackful async
  adapter like `run` (pure-compute exports stay sync).
- Optionally emit a generated `.wit` world so hosts / jco can generate bindings.

## Testing

- `ComponentWriterTest`: pin any new encoder helper output.
- E2E: build a component with a scalar export; call it via
  `wasmtime run -W gc=y ... --invoke 'sumsquared(2, 3)'` AND via `jco`. Add a
  "component export" case to the 4-backend verification (CLAUDE.md) /
  `ci-spec.yaml` if it fits the driver.

## Files

- `codegen/wasm/WasmLispCompiler.java` (gates 845/1158/1176/1908, ExportPlan
  routing into component export encoding)
- `codegen/wasm/WasmComponentBuilder.java` (per-export canonLift + SEC_EXPORT
  wiring)
- `codegen/wasm/WasmExportCompiler.java` (type-designator validation for the
  component path; reject `:string`/`:s-expr`/`:long` in Tier 1)
- `am.ik.wasm.ComponentWriter` (only if a missing lift variant is needed)
- Docs: `doc/{en,ja}/compiling/wasm.md`,
  `doc/{en,ja}/reference/functions/rontolisp-wasm-export.md`,
  `.kb/wasm-export-no-wasi.md`, `.kb/wasi-component.md`.

## Related

- **#93** (the non-GC, adapter-free variant: `--no-gc --component` compact
  export -- do after this; reuses the per-export canonLift + type-mapping here.
  Together #92 + #93 = "tiny typed component-model export" as a rontolisp
  selling point).
- `.kb/wasm-export-no-wasi.md` (current Preview-1 export mechanics + `--no-wasi`)
- `.kb/wasi-component.md` (`--component` design, fixed blobs, `run` lift)
