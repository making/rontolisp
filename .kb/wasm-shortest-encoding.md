# Every wasm byte this project emits is in its SHORTEST LEGAL encoding

Invariant: for every core module and component (Preview 1 and `--component`, `--optimize` and
not, `--no-gc` too), `wasm-tools parse (wasm-tools print M)` returns `M` byte for byte. The
round trip IS the specification and the pinning test:
`WasmTreeShakerCorpusTest.roundTripIsAFixpoint`, over the 321-case `ci-spec.yaml` corpus in
both WASI modes at `NONE` and `DEFAULT`; it needs `wasm-tools` on `PATH` and skips without it.
Padding validates and runs, so nothing else notices; the slack was 2.4%-5.7% per module.

## Rule 1: nullable reference to an ABSTRACT heap type is one byte

`WasmWriter.writeRefType(nullable, heapType)` is the only way emitters write a reference type.
The heap type's code IS the value type (`6D` = `eqref`), so `(ref null eq)` is one byte, not
`63 6D`. Only for nullable + abstract: non-nullable `(ref eq)` keeps its `64` constructor
byte, and a concrete type index keeps its constructor byte in either nullability (an s33 would
otherwise be indistinguishable from an abstract code). Abstract codes are the contiguous range
`0x69-0x74` (`exn`, `array`, `struct`, `i31`, `eq`, `any`, `extern`, `func`, `none`,
`noextern`, `nofunc`, `noexn`); `writeRefType` range-tests the endpoints, so `am.ik.wasm.Type`
must keep the set complete.

## Rule 2: every index, count and length is a `u32`

Not s32. The forms differ when the top seven-bit group has bit 6 set, so an index in [64,127]
costs 2 bytes instead of 1, [8192,16383] 3 instead of 2. Pick by the FIELD, not the value's
sign. Genuinely signed fields, exactly: `i32.const`/`i64.const` immediates; the s33 of a heap
type or blocktype (`WasmWriter.writeHeapType`); the component model's `valtype`, whose SIGN
discriminates primitive from type index (`ComponentWriter` -- do not "fix"). Everything else
(funcidx, localidx, globalidx, typeidx, fieldidx, labelidx, tagidx, memarg align/offset,
vector counts, section sizes, code-entry body sizes) uses `writeUnsignedLeb128`.

`WasmTreeShaker` was already correct (rewritten immediates go through `writeU`), so a SHAKEN
module's `call` immediates were minimal while the same module at `NONE` was not. The
unboxed-locals pass emits i64 local references as fixed-width placeholders, now SPLICED OUT
for the minimal LEB rather than overwritten in place (`WasmLispCompiler.buildLocalsAndPatch`,
`.kb/wasm-unboxed-locals.md`).

## Rule 3: `sub final` with no supertype is the BARE comptype

`subtype ::= 0x50 x* comptype | 0x4F x* comptype | comptype`; the third alternative IS
`sub final` with no supertype. `RecTypeDef` writes it, 2 bytes/type cheaper. Types stay FINAL,
load-bearing for cast lowering (`.kb/wasm-gc-final-types.md`).

Adjacent local declarations of the same type fold into one run. Previously one-per-local in
`WasmRuntimeBuilder`'s hand-written bodies (`_print_i32`, `_print_f64`, `_read_line`,
`_princ_val`) and `NoGcWasmCompiler.withLocalsRaw`. Locals keep ALLOCATION order, so folding
changes no index.

## Rule 4: no empty section; adjacent same-kind sections merge

- `WasmWriter.writeSection` skips a `CountingDef` left empty (a call site may just leave it
  empty). Case: `--component` imports the memory, so the core memory section had nothing.
- `WasmTreeShaker` drops a rebuilt section it emptied instead of a zero-entry vector
  (`addVector`).
- `ComponentWriter.rawSection` holds a section back until the next call proves it cannot be
  extended, merging consecutive VECTOR sections of the same kind (index spaces advance in
  declaration order, so numbering is preserved). The four non-vector kinds (custom, core
  module, nested component, start) never merge.

## Decoder trap

`WasmTreeShaker` must PARSE what the writer emits, and `blocktype ::= 0x40 | valtype | s33
typeidx`. `isValTypeStart` is the disambiguator: `block (result eqref)` written as the single
byte `6D` must be read as a valtype, else it goes to the s33 arm as a type reference at index
-19 -- symptom is an out-of-range crash in the rewriter far from the cause, and NO symptom on
a module with nothing to drop (early return). The predicate lists the whole `0x69-0x74` range;
unambiguous because a blocktype's s33 is a non-negative type index, so its first byte is
`0x00-0x3F` or has the continuation bit set, never `0x40-0x7F`. Widen the predicate in the
SAME commit as any encoder change producing a new short form. Pinned by
`WasmTreeShakerTest.decodesTheOneByteAbstractReferenceBlockType`.

## Coverage and re-evaluation

`WasmTreeShakerCorpusTest` compiles the GC backend only; the other emission paths were checked
by hand -- base component wrapper, `WasmServeComponentBuilder`, `NoGcWasmComponentBuilder`,
`wit-import`/`wit-export` components, `fetch`, TCP sockets, `--simd`, `--dynamic`, `--no-wasi`,
`wasm-export` (`--no-gc` was the path the first sweep missed).

`wasm-tools print` output must stay byte-identical across an encoding change; if the printed
text moves, it was not an encoding change.

A `roundTripIsAFixpoint` failure is a finding: either a newly-emitted field in a non-minimal
encoding (fix the emitter), or `wasm-tools` newly normalizing something this project
deliberately does not (record the reason here and relax the assertion to a size comparison).
Do not relax it first. Rule 2's signed-field list must stay COMPLETE -- a future proposal
adding an s32 immediate joins it.
