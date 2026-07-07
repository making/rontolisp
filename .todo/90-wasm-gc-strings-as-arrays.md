# 90 - WASM GC backend: represent strings as wasm-GC arrays (retire the linear-memory string heap)

## STATUS: NOT STARTED (root-and-branch; scope carefully before starting)

## Difficulty: LARGE (high risk, cross-cutting; the biggest of the three)

Touches essentially every string surface in the GC WASM backend plus the
reader/printer runtime, `equal`/`_hash`, the intern/dedup model, the `:string`
host boundary, and the byte-sensitive component path. It cannot be done
half-way (once the struct layout changes, every consumer must change in the same
breath -- offset arithmetic and array access don't coexist). Plan it as one
focused branch with the full four-backend + native + component test matrix at the
end. Estimate: multi-day. This is the "抜根工事" -- do it only when the resident/edge
GC-backend leak is worth the investment; [[88-no-gc-export-wrapper-heap-reset]] +
[[89-no-gc-heap-mark-release]] give cheaper relief for the `--no-gc` case first.

## The problem it solves (the actual root cause)

On the GC backend a string is `TYPE_STRING` (rec-group type 4), a struct of two
i32 fields `{offset, length}` -- the struct is GC'd, but the BYTES it points at
live in **linear memory** (StringTable static data for literals; the bump heap at
`HEAP_PTR_ADDR` for runtime-built strings via `concatenate`/`subseq`/printer/
reader). That linear string heap never frees (only grows -- see
[[27-wasm-gc-heap-never-grows]]), so a long-lived/resident instance that builds
strings across many calls grows without bound. `serve` masks it by resetting the
bump allocators per request, but a plain `wasm-export` reactor called repeatedly,
or any cross-call retained string, still accumulates.

Cons cells, lists, vectors, symbols-as-structs and closures are already true
wasm-GC objects and are reclaimed by the engine. Strings are the one value type
left in linear memory. Moving them onto the GC heap makes them reclaimed like
everything else -- **no manual free, no arena, resident-safe** -- and lets the
linear string heap (and the todo-27 grow hack) be retired entirely.

## Target representation

Change `TYPE_STRING` from `{i32 offset, i32 length}` to a struct wrapping a
wasm-GC byte array:

- `TYPE_STRING = struct { (ref $str_bytes) data }` where
  `$str_bytes = (array (mut i8))` (a new rec-group array type). Length is
  `array.len`, so the separate length field can go (or keep a cached i32 length
  if profiling wants it -- decide once).
- Decide UTF-8 (`array i8`, matches today's byte model + the host boundary) vs
  UTF-16 (`array i16`, matches interpreter/JVM indexing). **Recommend `array i8`
  / UTF-8**: it keeps `char`/`char-code`/byte indexing identical to today and the
  `:string` boundary is already UTF-8. (Interpreter/JVM stay UTF-16 internally;
  the cross-backend contract is already "same characters", not "same code units".)
- The `symbolp`/`stringp` discriminator (leading `"` marker) and the shared
  symbol/string representation must be preserved -- keep the marker as the first
  array byte, or move it to a struct tag field. Pick one and thread it through
  `WasmStringpCompiler` / `WasmSymbolApiRuntimeBuilder`.

## Surfaces to change (all under codegen/wasm)

- **Struct decl + literals**: `WasmLispCompiler` rec-group (add `$str_bytes`,
  change type 4), and `StringTable` -- literals/interned symbols become GC arrays
  built at startup (`array.new_data` from a data segment, or an init loop) instead
  of static linear-memory entries + the intern blob (`buildInternBlob`). Preserve
  dedup so interned symbols remain `eq` (same array instance) -- symbol eq
  currently rides on same-offset; it must become same-array-reference.
- **Builders/primitives**: `WasmStringConcatCompiler`, `WasmStringRuntimeBuilder`
  (subseq/upcase/downcase/capitalize/trim), `WasmStringEqCompiler`,
  `WasmStringUpcaseCompiler`/`Trim`/`Capitalize`, `WasmCharCompiler` (byte load ->
  `array.get_u`), `WasmPrincToStringCompiler`/`WasmPrin1ToStringCompiler` +
  `WasmRuntimeBuilder` toString/write-string (bump-alloc + byte store ->
  `array.new`/`array.set`), `WasmWriteStringCompiler`.
- **Reader**: `WasmReadRuntimeBuilder`, `WasmReadCompiler`/`ReadFromString`/
  `ReadLine`/`ReadChar`/`ReadByte` -- these read into linear memory today; they
  still need a linear I/O buffer (WASI reads bytes into memory) but must then copy
  into a GC array.
- **Equality/hash**: `WasmEmitHelper` (the offset-compare fast path ->
  content/array compare or reference-eq for interned), `WasmEqualCompiler`,
  `WasmHashTableCompiler` (`_hash`/`equal` over string content -- already
  content-aware, re-point at array bytes).
- **String streams**: `WasmStringStreamRuntimeBuilder` (output chunk lists ->
  arrays; input cursor over an array).
- **Host boundary (keep a linear scratch, but fixed/reused)**: `WasmExportCompiler`
  / `WasmExportRuntimeBuilder` -- a `:string`/`:s-expr` param arrives as
  `(ptr,len)` in linear memory (host writes there) and a result must be written
  back to linear memory for the host to read; marshal by copying between the GC
  array and a linear staging buffer. That buffer is a fixed reused region (or the
  `__ronto_alloc` output buffer), NOT an accumulating string heap. `_string_from_mem`
  becomes "linear bytes -> GC array"; the result path "GC array -> linear bytes".
- **eval/gensym/getenv/fetch**: `WasmEvalRuntimeBuilder`, `WasmGensymRuntimeBuilder`,
  `WasmGetenvRuntimeBuilder`, `WasmFetchRuntimeBuilder` -- any place that builds a
  string in linear memory.

## Invariants that must survive (do not regress)

- `symbolp`/`stringp` discrimination; symbol/string shared representation.
- Symbol `eq` (interned dedup) and `equal`/`equalp` string semantics; hash-table
  string keys (WASM `equal`/`_hash` compare content -- must keep working for
  runtime-built keys).
- Component path byte-sensitivity: the import blocks / serve+fetch adapters wrap
  the CORE module; confirm the core's boundary helpers still line up and re-verify
  every prebuilt blob/wiring (CLAUDE.md "component path is byte-sensitive").
- `--no-wasi` reactor, `--optimize` tree-shaker (must decode the new `array.*`
  opcodes -- extend `WasmTreeShaker`'s scanner if needed), `--dynamic`, wasm-export
  `:string`/`:s-expr`.
- wasmtime/jco/wasmCloud all run it under `-W gc` (array types are part of the GC
  proposal these already enable).

## Payoff / cleanups enabled

- Resident/edge GC instances stop leaking string memory (engine GC reclaims).
- The linear-memory string bump heap and the [[27-wasm-gc-heap-never-grows]]
  grow-on-bump guards at string sites can be removed (linear memory stays only for
  WASI I/O staging + the `:string` boundary).
- `手段1` (arena reset) becomes unnecessary ON THE GC BACKEND -- it remains only
  for `--no-gc` ([[88-no-gc-export-wrapper-heap-reset]]/[[89-no-gc-heap-mark-release]]).

## Scope boundary

- **GC backend only.** `--no-gc` has no engine GC and cannot use array types --
  it keeps 88/89. Interpreter/JVM are unaffected (JVM-GC'd strings already).
- Not incremental behind a flag (the struct layout is shared by every consumer);
  land it as one coordinated change.

## Acceptance criteria

- [ ] `TYPE_STRING` holds a wasm-GC byte array; no string bytes are written to the
  linear-memory bump heap on the GC backend (literals, `concatenate`, `subseq`,
  printer, reader, string streams, gensym all produce GC arrays).
- [ ] A resident host loop that builds/returns strings across N=100000 calls on ONE
  instance shows bounded/steady linear memory (the string heap no longer grows;
  only GC arrays churn, reclaimed by the engine). Behavioral test on a persistent
  instance (Node / Chicory-with-GC or wasmtime embedding).
- [ ] Full parity: interpreter / JVM / WASM-GC Preview1 / WASM component produce
  identical output on the whole suite; `stringp`/`symbolp`, symbol `eq`,
  `equal`/`equalp`, hash-table string keys, `char`/`char-code`, string streams,
  `read`/`print`, `format`, `:string`/`:s-expr` wasm-export all pass.
- [ ] Component path re-verified (serve + fetch): blobs/wiring line up, byte-exact.
- [ ] `--optimize` still tree-shakes correctly (array opcodes decoded); `--no-wasi`
  reactor works; native image + `CiSpecE2eTest` green.
- [ ] todo-27 grow guards at string sites removed (or documented why kept);
  `.kb/no-gc-scalar-wasm.md` is untouched but `.kb` for the GC string rep + a new
  KB note added; CLAUDE.md string-representation constraints updated.

## Related

- [[27-wasm-gc-heap-never-grows]] (the linear string heap this retires; grow hack).
- [[88-no-gc-export-wrapper-heap-reset]] / [[89-no-gc-heap-mark-release]] (手段1,
  the `--no-gc` counterparts that stay).
- [[21-wasm-export-memory-abi-ci-coverage]] (boundary ABI E2E).
- `.kb/no-gc-scalar-wasm.md`, `.kb/hash-tables.md` (string-key hashing).
