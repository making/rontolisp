# 93. Compact `--no-gc` + `--component` output (tiny component-model export)

## Goal / selling point

Make `--no-gc --component` produce a **very compact WASM component** whose
`wasm-export` functions are callable through the component model
(`wasmtime run ... --invoke 'sumsquared(2, 3)'`, jco, wasmCloud) -- with **no
wasm-GC requirement** and **no WASI adapter machinery**.

Framing: once **#92** (component-model exports on the GC backend) and **#93**
(this, the non-GC compact path) are both done, rontolisp can emit a *tiny*
component-model module that a host invokes with typed WIT signatures and WAVE
syntax -- a distinct rontolisp selling point (compact + typed + no experimental
`--invoke` warning + runs on any component host, GC not needed).

Depends on / after: **#92** (reuse the per-export `canonLift` + type-mapping
wiring introduced there; this issue is the non-GC, adapter-free variant).

## Current state

- `--no-gc --component` is a **hard error today**:
  `RontoLispCli.java:217-219` -> "--no-gc cannot be combined with --component
  (the component path requires wasm-GC)". Relaxing this is part of the work.
- `--no-gc` selects the separate `NoGcWasmCompiler` (plain MVP module) and
  **implies `--no-wasi`** (RontoLispCli comment at ~line 216).
- **Since todo-110 (2026-07-10)** the 0-import property is CONDITIONAL: a
  program using `print`/`princ`/`terpri` gains ONE
  `wasi_snapshot_preview1.fd_write` import, funneled through the single
  `__write_stdout(ptr,len)` helper (the ONLY caller of the import -- the seam
  left for this todo). Function indices shift by `Mem.funcBase()` (1 when
  printing); ALL index math flows through the `Mem.funcIndex()`/`*Index()`
  accessors, so the per-export `aliasCoreFunc` wiring here must read indices
  from those accessors, never hardcode. **Release-1 decision (recorded in
  110):** make `print` under `--no-gc --component` a clear compile error; a
  later phase can add a micro-adapter (a minuscule core module implementing
  fd_write over `wasi:cli/stdout`, the adapter-serve-p1 pattern in miniature)
  by swapping the `__write_stdout` implementation only. A print-free program
  still has 0 imports, so the adapter-free wrap below applies verbatim.

## Key insight: the heavy fixed-blob set is NOT needed here

Measured 2026-07-07 on `--no-gc` output:

| export shape | imports | memory | allocator |
|---|---|---|---|
| scalar (`sumsquared :int,:int -> :int`) | **0** | none | none |
| string (`count_a :string -> :int`) | **0** | self, `export "memory"` | `__ronto_alloc` (+ `_mark`/`_reset`) exported |

The existing `WasmComponentBuilder` fixed blobs exist **only** to serve the GC
backend:

- `import-block.bin` -- declares the GC module's 8 WASI 0.3 imports. A `--no-gc`
  module has **0 imports** -> not needed.
- `adapter.wasm` -- bridges those preview1-style imports to WASI 0.3 async ->
  **not needed** (nothing to adapt).
- `mem.wasm` -- supplies shared canonical linear memory + `cabi_realloc` (the GC
  module keeps cons on the GC heap, so it has no linear memory of its own). The
  scalar module **already exports its own `memory` + `__ronto_alloc`** -> not
  needed.

So a `--no-gc` component is a **reactor with only exports and no imports** --
much simpler than the GC path. This is a NEW small builder, not a clone of
`WasmComponentBuilder`.

## Work (small dedicated builder / mode)

1. **Relax the CLI gate**: allow `--no-gc --component` (remove/adjust the
   `RontoLispCli.java:217` error); route to the new builder.
2. **Wrap the single MVP core module** emitted by `NoGcWasmCompiler` -- no
   adapter, no import-block, no mem module. A `ComponentWriter` with:
   `SEC_CORE_MODULE` (the scalar module) + `SEC_CORE_INSTANCE` +
   per-export `aliasCoreFunc` + `canonLift` + `SEC_EXPORT`.
3. **Scalar exports** (`:int`/`:long`/`:float`/`:bool`/`:void`): trivial
   **synchronous** `canonLift`, no canonical memory needed. Note `:long`<->i64
   (s64) IS valid here (the scalar backend computes in i64) -- unlike the GC
   component in #92 which rejects `:long`.
4. **String / s-expr exports**: use the module's own exported `memory` as the
   canonical memory + a `cabi_realloc`-signature function. `__ronto_alloc` is a
   1-arg bump allocator, but the canonical ABI `realloc` is 4-arg
   `(old, oldsz, align, newsz)` -> add a tiny **realloc shim** (wrap
   `__ronto_alloc`; the `_mark`/`_reset` arena ops already exist). Then use the
   utf8 canon options (`canonLift...Utf8` counterpart of the existing
   `canonLowerMemoryReallocUtf8`).
5. **World / init**: reactor-style (no `run`; `_initialize` if any top-level
   init is required -- for pure exports likely none).
6. **(optional) WIT output** so hosts / jco can generate bindings.

## Non-goal / trade-off to record in docs

`--no-gc`'s whole value is a plain MVP module that **any** engine runs, callable
via a raw core `(func)` through a simple embedding API (no component tooling, no
warning). Wrapping it as a component **re-introduces a component-model-capable
host requirement**, trading that portability for typed WIT + WAVE `--invoke`.
So keep BOTH outputs available: raw `--no-gc` (max portability, embedding API)
and `--no-gc --component` (typed component export). Do not make component the
default for `--no-gc`.

## Testing

- E2E: build `--no-gc --component`, call via
  `wasmtime run --invoke 'sumsquared(2, 3)'` (NO `-W gc` flag needed -- assert
  that) and via `jco`. Confirm the module is markedly smaller than the GC
  component (size assertion / note in docs).
- Round-trip a `:string` export (`count_a`) through the canonical string ABI.

## Files

- `cli/RontoLispCli.java` (relax the `--no-gc + --component` gate; route to the
  new builder)
- new `codegen/wasm/ScalarWasmComponentBuilder.java` (or a `--no-gc` mode wired
  off `WasmComponentBuilder`) -- adapter-free single-module wrap
- `codegen/wasm/NoGcWasmCompiler.java` (emit the `cabi_realloc` shim for string
  exports; ensure export wrappers survive)
- `am.ik.wasm.ComponentWriter` (only if a lift variant is missing)
- Docs: `doc/{en,ja}/compiling/wasm.md`,
  `doc/{en,ja}/reference/functions/rontolisp-wasm-export.md`,
  `.kb/no-gc-scalar-wasm.md`, `.kb/wasi-component.md`.

## Related

- **#92** (component exports on the GC backend -- prerequisite; shares the
  per-export canonLift + type-mapping design)
- `.kb/no-gc-scalar-wasm.md` (scalar backend: memory header, `__ronto_alloc`,
  string primitives)
- `.kb/wasm-export-no-wasi.md` (`:long`<->i64 is `--no-gc`-only; `--no-wasi`
  reactor `_initialize` ABI)
- `.kb/wasi-component.md` (GC component blob set this path deliberately avoids)
