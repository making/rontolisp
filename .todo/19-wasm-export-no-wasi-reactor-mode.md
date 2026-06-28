# `wasm:export`: optional WASI-free (reactor) module output

**Status:** open. Follow-up to the `wasm:export` feature (interpreter/JVM no-op;
WASM Preview 1 exports typed wrapper functions — see README "Exporting Lisp
functions"). Raised in the `claude-opus` session 2026-06-28.

## Motivation

Today a compiled Preview 1 module **unconditionally imports the eight
`wasi_snapshot_preview1` functions** and exports `_start` + `memory`. So even to
call a pure-compute export (e.g. `fact`) from a host, the host must satisfy all
eight WASI imports (`wasmtime run` does it automatically; a browser must pass
no-op stubs — see the README JS example's `wasi_snapshot_preview1: stubs`).

We want an **opt-in mode that emits a module with no WASI imports**, so a host
can `WebAssembly.instantiate(bytes)` with no import object — a "reactor"/library
module whose only surface is the exported Lisp functions. Intended for
pure-compute exports; programs that do I/O are out of scope for this mode.

## Why it is not trivial: fixed function indices

`WasmLispCompiler` pins every runtime function to a **fixed index constant**
(`FUNC_FETCH=8`, `FUNC_START=9`, ... up to `FUNC_USER_BASE`), and those constants
assume the eight WASI imports occupy function-index slots **0–7** (imports always
precede defined functions in the WASM index space). Simply dropping the imports
shifts every defined-function index and breaks all `FUNC_*` constants and every
`call` to them. (This is the same index-stability invariant that keeps the
`--component` blobs valid — see CLAUDE.md "Index stability".)

## Recommended approach: replace imports with internal stubs (index-stable)

Keep indices 0–7 occupied by **defined** functions instead of imported ones:

1. **Import section** (gated on the new flag): emit no `wasi_snapshot_preview1`
   imports. (`--component` is unaffected; this mode is Preview-1 only.)
2. **Function + code sections**: when no imports are present, prepend eight stub
   functions at indices 0–7 with the **same type indices** the imports used
   (`TYPE_FD_WRITE`, `TYPE_PATH_OPEN`, `TYPE_LOOKUP`, `TYPE_INTERN`,
   `TYPE_CLOCK_TIME_GET`). Body = `unreachable` (trap if ever called) — see
   "stub semantics" below. All existing `FUNC_*` constants then stay valid with
   zero other changes.
3. **Export section**: keep the `wasm:export` wrappers and `memory`. Decide
   whether to still export `_start` (a reactor arguably should not — but a host
   that wants to run the top-level program loses that; consider exporting it only
   when it is meaningful, or always and document that calling it traps if it hits
   a stub).

This is localized to the import/function/code-section emission in
`WasmLispCompiler` plus one boolean threaded through `Ctx` (mirror how `dynamic`
/ `component` are threaded), and a CLI flag in `CliOptions`/`RontoLispCli`
(`noValueKeys`).

### Stub semantics: trap, not no-op

Make the stubs `unreachable` (trap) rather than no-op-returning-0. A no-op
`fd_write` that does not set `nwritten` can make the `print` write loop spin
(it loops until all bytes are reported written). A trap gives a clear failure if
a no-wasi module actually attempts I/O, which is the correct contract for this
mode. Document: **I/O (`print`/`read`/`open`/`getenv`/time/`random`) is
unsupported in no-wasi mode; only pure-compute exports work.**

## Open design questions

- **Flag name + auto-detect.** Explicit flag (`--no-wasi` / `--reactor` /
  `--standalone`)? Or auto-emit WASI-free when the program provably uses no
  WASI-backed feature? Explicit flag is simpler and predictable; auto-detect is
  friendlier but needs a reliable "uses no I/O/time/random" analysis.
- **`_start`/`memory` exports** in reactor mode (see step 3).
- **`random`/time stubs**: even pure code may call `random`; trapping is fine but
  surprising. Document.

## Verification

- `wasm-tools validate` the output; confirm the import section is empty
  (`wasm-tools objdump` shows 0 imports).
- Node/JS: `WebAssembly.instantiate(bytes)` with **no** import object succeeds,
  and a scalar export (`fact(5) => 120`) and a memory export (`shout`/`rev`)
  round-trip — reuse the harness shape from the session's `host.mjs`.
- Re-run the four-backend matrix for a normal (WASI) build to confirm no
  regression to the default path.

## Touch points

- `codegen/wasm/WasmLispCompiler.java` — import/function/code sections, `Ctx`
  flag, the `FUNC_*` constants (no value change, just the new stub block).
- `cli/CliOptions.java` (`noValueKeys`), `cli/RontoLispCli.java` (thread the flag
  into the `WasmLispCompiler` constructor).
- README "Exporting Lisp functions" (document the mode + the no-I/O limitation),
  `WasmLispCompilerIntegrationTest` / a JS harness test.
- CLAUDE.md "Index stability" note (explain the stub-swap keeps indices fixed).
