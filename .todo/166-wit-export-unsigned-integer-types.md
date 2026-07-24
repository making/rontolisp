# wit-export: accept the unsigned integer types (`u32` first)

The component-model tutorial world that every newcomer starts from is

```wit
package docs:adder@0.1.0;
interface add { add: func(x: u32, y: u32) -> u32; }
world adder { export add; }
```

and `rontolisp:wit-export` rejects it: `WitExportDirective.designator()`
accepts exactly `s32` / `s64` / `f64` / `bool` / `string`, so the canonical
first example of the ecosystem cannot be implemented in rontolisp without
editing the upstream `.wit`. That is the wrong first impression, and the WIT
world is supposed to be the authoritative, unedited contract.

The **import** side already handles the whole small-int family: `WitTypeMapper`
maps `s8`/`s16`/`s32`/`u8`/`u16`/`u32` to `Rep.INT`, `WitCanonicalAbi` gives
them `Type.I32` with the right size/alignment, and `WitComponentTypeEncoder`
already emits `ComponentWriter.VT_U32` (0x79). The gap is only the **export
designator vocabulary**, which predates WIT (it is the `rontolisp:wasm-export`
`:int`/`:long`/`:float`/`:bool`/`:string` set) and has no unsigned member.

## Phase 1 — `u32` at the export boundary

Four code points; the codegen bodies need no new instructions, because the core
representation of `u32` and `s32` is the same `i32` (the canonical ABI flattens
both to one `i32`).

- `codegen/wasm/WasmExportCompiler.java`: add `T_UINT = ":UINT"`, register it in
  `KNOWN_TYPES`, map it in `componentValType` (-> `ComponentWriter.VT_U32`), in
  `coreType`/the `Type.I32` case, and in `emitBoxParam`/`emitUnboxResult`
  (identical to `T_INT`: `i31.new` / `castI31GetS`, modulo the Phase 2 decision).
- `compiler/WitExportDirective.java` `designator()`: `case "u32" -> ":UINT"`.
  The rejection message keeps naming the settled house representation.
- `codegen/wasm/WitEmitter.java` `witTypeOf()`: `VT_U32 -> "u32"`, so an
  implemented world round-trips through `--emit-wit` with its own type spelling
  intact (the same property `:param-names` gives the labels).
- `codegen/wasm/NoGcWasmCompiler.java` (export wrapper, around the
  `WasmExportCompiler.T_INT` case): the outbound `i32.wrap_i64` is unchanged;
  the inbound extend must become `i64.extend_i32_u` for `:UINT` so the full
  0..2^32-1 range arrives exactly (the `--no-gc` house int is `i64`).

Additivity is a hard requirement: a program that uses no unsigned type must
produce a **byte-identical** artifact on every variant (the stash dance used for
the Tier 2/3 export work).

## Phase 2 — decide the range semantics, and write the reason down

Two things have to be settled and recorded in `.kb/wit.md` (with the *why*, so a
later visitor can tell whether the reason still holds):

- **Representable range.** On the wasm-GC backends integers are `i31ref`, so the
  honest range is 0..2^30-1 -- exactly the limitation `s32` already carries and
  already documents ("an integer (31-bit signed range)",
  `doc/{en,ja}/reference/functions/rontolisp-wit-export.md`). Under `--no-gc` the
  house int is `i64` and the full `u32` range crosses. Document per backend
  rather than pretending to a uniform 32 bits.
- **A negative Lisp value returned from a `u32` export.** Either pass it through
  (the host reads the two's-complement value -- silent, and the boundary then
  lies about its own contract) or range-check and signal a condition. Preference:
  signal, matching the existing posture of failing loudly at the boundary
  (`s64` on wasm-GC is a hard error rather than a truncation). Whichever is
  chosen, it must behave the same on `--no-gc` and both GC variants, and the
  check must not appear in artifacts that use no unsigned type.

## Phase 3 — the rest of the family

`u8` / `u16` / `s8` / `s16` are the same `i32` core representation and the same
four code points (`VT_U8` / `VT_U16` / `VT_S8` / `VT_S16` all exist in
`ComponentWriter`); `u64` rides the `:LONG` path (`VT_U64`), so it inherits the
existing "`--no-gc` only, the GC backends' integers are `i31ref`" rule. Adding
them is nearly free once Phase 1+2 fix the shape, and it makes the export
boundary's accepted set match the import side's, which is the state the two
should have been in all along.

## Verification

- The tutorial world above, verbatim and unedited, as an E2E fixture:
  `wit-export` it, run the exported `add` on **all four backends** (the
  interpreter/JVM contract check is inert but must accept the world; both WASM
  component variants must be callable via `wasmtime run --invoke 'add(2, 3)'`).
- `--emit-wit` on the implemented world prints `u32` back, re-parses through
  `WitParser`, and (where `wasm-tools` is on PATH) byte-matches
  `wasm-tools component wit` -- `WitEmitterTest` line pins plus the round-trip
  suite and `WitOracleE2eTest`.
- Byte-identity of every existing component fixture (no unsigned type used ->
  unchanged bytes).
- A `ci-spec.yaml` case if the boundary behavior is observable end-to-end, then
  the native-image E2E run.
- Docs: the `wit-export` type table and the `wit-import` table in both
  `doc/en/**` and `doc/ja/**`, plus the `.kb/wit.md` boundary-subset paragraphs
  (the "supported: s32, s64, f64, bool, string" wording appears in the error
  message, the kb and the docs -- all three move together).

## Note

The Java-side unsigned helpers (`Integer.toUnsignedLong`,
`Integer.compareUnsigned`, `Integer.divideUnsigned`) are not part of this work:
`wit-export` is inert on `Backend.OTHER` (interpreter / JVM) and the arithmetic
runs in the emitted wasm, not in the compiler. They would only be reached if a
compile-time range check or constant fold is implemented in Phase 2.
