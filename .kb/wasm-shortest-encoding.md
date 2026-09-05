# Every wasm byte this project emits is in its SHORTEST LEGAL encoding

Invariant: for every core module and component (Preview 1 and `--component`, `--optimize` and
not, `--no-gc` too), `wasm-tools parse (wasm-tools print M)` returns `M` byte for byte. The round
trip IS the specification and the pinning test: `WasmTreeShakerCorpusTest.roundTripIsAFixpoint`
over the 321-case `ci-spec.yaml` corpus in both WASI modes at `NONE` and `DEFAULT`; needs
`wasm-tools` on `PATH`, skips without it. Padding validates and runs; the slack was 2.4%-5.7%.

## Rules
1. **Nullable reference to an ABSTRACT heap type is one byte.** `WasmWriter.writeRefType` is the
   only way emitters write a reference type: the heap-type code IS the value type (`6D` =
   `eqref`), so `(ref null eq)` is 1 byte, not `63 6D`. Non-nullable `(ref eq)` keeps its `64`
   constructor byte and a concrete type index keeps its byte in either nullability. Abstract codes
   are the contiguous range `0x69-0x74`; `writeRefType` range-tests the endpoints, so
   `am.ik.wasm.Type` must keep the set complete.
2. **Every index, count and length is a `u32`**, not s32 — pick by the FIELD, not the value's sign
   (an index in [64,127] costs 2 bytes as s32). Genuinely signed fields, exactly:
   `i32.const`/`i64.const` immediates; the s33 of a heap type or blocktype
   (`WasmWriter.writeHeapType`); the component model's `valtype`, whose SIGN discriminates
   primitive from type index (`ComponentWriter` — do not "fix"). Everything else goes through
   `writeUnsignedLeb128`. The unboxed-locals pass's fixed-width placeholders are now SPLICED OUT
   (`WasmLispCompiler.buildLocalsAndPatch`, `.kb/wasm-unboxed-locals.md`).
3. **`sub final` with no supertype is the BARE comptype** — `RecTypeDef` writes it, 2 bytes/type
   cheaper, types stay FINAL (`.kb/wasm-gc-final-types.md`). Adjacent local declarations of the
   same type fold into one run (`WasmRuntimeBuilder` hand-written bodies,
   `NoGcWasmCompiler.withLocalsRaw`); locals keep ALLOCATION order, so folding changes no index.
4. **No empty section; adjacent same-kind sections merge.** `WasmWriter.writeSection` skips an
   empty `CountingDef`; `WasmTreeShaker` drops a section it emptied (`addVector`);
   `ComponentWriter.rawSection` holds a section back to merge consecutive VECTOR sections of the
   same kind. The four non-vector kinds (custom, core module, nested component, start) never merge.

## Decoder trap
`WasmTreeShaker` must PARSE what the writer emits, and `blocktype ::= 0x40 | valtype | s33
typeidx`. `isValTypeStart` is the disambiguator: `block (result eqref)` written as the single byte
`6D` must be read as a valtype, else it goes to the s33 arm as type index -19 — symptom is an
out-of-range crash far from the cause, and NO symptom on a module with nothing to drop. Widen the
predicate in the SAME commit as any encoder change producing a new short form. Pinned by
`WasmTreeShakerTest.decodesTheOneByteAbstractReferenceBlockType`.

## Coverage
`WasmTreeShakerCorpusTest` compiles the GC backend only; the other paths were checked by hand —
base component wrapper, `WasmServeComponentBuilder`, `NoGcWasmComponentBuilder`,
`wit-import`/`wit-export`, `fetch`, TCP sockets, `--simd`, `--dynamic`, `--no-wasi`,
`wasm-export`. A `roundTripIsAFixpoint` failure is a finding: either a new non-minimal emission
(fix the emitter) or `wasm-tools` newly normalizing something deliberate (record it here and relax
to a size comparison). Do not relax it first. Rule 2's signed-field list must stay COMPLETE.
