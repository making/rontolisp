# `wasm:export`: typed function exports in `--component` (WASI 0.3) mode

**Status:** open. Follow-up to the `wasm:export` feature. Raised in the
`claude-opus` session 2026-06-28.

## Current behavior

`(wasm:export ...)` is **Preview 1 only**. In `--component` mode the directive is
a deliberate no-op: the wrapper functions and the `__ronto_alloc` helper are not
emitted, the heap-seed data segment is not written, and the component still
exports only `wasi:cli/run@0.3.0` (see `WasmExportCompiler` / the
`exportsPresent = (!this.component) && ...` guard in `WasmLispCompiler`). So a
component cannot expose individual Lisp functions to a host.

## What it would take

A component's exports are described by a **WIT interface**, not raw core
function exports. To surface `wasm:export`'d functions from a component we need
to:

1. Generate a WIT world that declares each exported function with WIT types
   mapped from the directive's designators (`:int -> s32`/`u32`, `:float -> f64`,
   `:bool -> bool`, `:string -> string`, `:sexpr -> string`, void -> no result).
2. Lift each core wrapper through the canonical ABI in `WasmComponentBuilder` /
   `am.ik.wasm.ComponentWriter` (canon lift, with `cabi_realloc` for `string`
   results — the component already imports a shared canonical memory + realloc).
3. Decide async vs sync: the existing `run` export is a stackful async lift;
   pure-compute exports can be plain sync `canon lift`. Confirm wasmtime accepts a
   mix.

This is substantially more work than the Preview 1 path (new WIT emission + per
-export canon lift), which is why it was deferred. The Preview 1 core module
already covers `wasmtime --invoke` and browser/JS hosts; the component path is
mainly for component-native runtimes and typed host bindings.

## Touch points

- `codegen/wasm/component/WasmComponentBuilder.java`,
  `am.ik.wasm.ComponentWriter` (general async-canon-ABI encoder),
  `codegen/wasm/WasmExportCompiler.java` (reuse the parsed `Decl`s + wrappers),
  the `this.component` gate in `WasmLispCompiler`.
- README "Compile to a WASI 0.3 component" + "Exporting Lisp functions"
  (currently states component mode ignores the directive).

## Dependencies / interaction

Independent of `.todo/19` (no-wasi reactor mode), but both are about the
host-facing surface of a compiled module; if both land, reconcile how a host
chooses between a Preview 1 reactor and a typed component.
