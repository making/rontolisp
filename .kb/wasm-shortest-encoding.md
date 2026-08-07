# Every wasm byte this project emits is in its SHORTEST LEGAL encoding

**Invariant**: for every core module and every component the project writes -- Preview 1
and `--component`, `--optimize` and not, `--no-gc` too -- `wasm-tools parse (wasm-tools
print M)` returns `M` byte for byte. The tool re-encodes from the decoded module, so any
field spelled non-minimally comes back shorter and the comparison fails. That round trip
IS the specification of this invariant and it is the pinning test
(`WasmTreeShakerCorpusTest.roundTripIsAFixpoint`, run over the whole 321-case
`ci-spec.yaml` corpus in both WASI modes at `NONE` and at `DEFAULT`).

**Why it needs an invariant at all.** The binary format tolerates padding: a LEB may
carry redundant continuation bytes up to its type's width, a nullable abstract reference
may be spelled long, a `sub final` wrapper may be written out, an empty section may be
declared. All of it validates and runs, so nothing in the normal test suite notices.
Measured 2026-08-06, the accumulated slack was **2.4%-5.7% of every module the project
emitted** -- roughly the size of the whole `--optimize` type-section win, spent on
nothing.

## The four rules

### 1. A nullable reference to an ABSTRACT heap type is one byte

`WasmWriter.writeRefType(nullable, heapType)` owns this and is the only way the emitters
write a reference type. The heap type's own code IS the value type -- `6D` is `eqref`,
not just `eq` -- so `(ref null eq)` costs one byte, never the two of `63 6D`. The
shorthand exists **only** for nullable + abstract: a non-nullable `(ref eq)` keeps its
`64` constructor byte, and a concrete type index keeps its constructor byte in either
nullability (an index is an s33 that would otherwise be indistinguishable from an
abstract code).

The abstract codes are the contiguous range `0x69-0x74` (`exn`, `array`, `struct`,
`i31`, `eq`, `any`, `extern`, `func`, `none`, `noextern`, `nofunc`, `noexn`) and
`writeRefType` range-tests against the two endpoints, so `am.ik.wasm.Type` has to keep
the set complete -- a comment there says so.

This is the biggest single item, because a reference type appears in every function-body
LOCAL declaration, every blocktype, every global, every struct field and every function
signature. It used to be spelled at ~220 call sites as an explicit `write(REFNULL)`
followed by `writeHeapType`; they are all one `writeRefType(true, ...)` now, which is
what makes the rule enforceable rather than a convention.

### 2. Every index, count and length is a `u32`

Not an s32. The two encodings agree except when the value's TOP seven-bit group has bit
6 set, where the signed form needs one more byte to keep the sign clear -- so **every
index in [64, 127] costs two bytes instead of one**, every index in [8192, 16383] three
instead of two, and so on. With a few hundred functions in a module, most `call`
immediates land in the first of those bands.

The emitters used to write `writeSignedLeb128` for everything; ~2,500 sites now write
`writeUnsignedLeb128`. The rule for a new one, and the javadoc on both methods says it:
**pick by the FIELD, not by the value's sign.** The genuinely signed fields are exactly

- the `i32.const` / `i64.const` immediates,
- the `s33` of a heap type or blocktype (`WasmWriter.writeHeapType`),
- the component model's `valtype`, whose SIGN is the discriminator between a primitive
  and a type index (`ComponentWriter` -- do not "fix" those).

Everything else -- funcidx, localidx, globalidx, typeidx, fieldidx, labelidx, tagidx,
memarg align/offset, vector counts, section sizes, code-entry body sizes -- is a `u32`.

Two consequences worth naming. `WasmTreeShaker` was already correct here (it re-encodes
the immediates it rewrites through `writeU`), which is why a SHAKEN module's `call`
immediates were minimal while the same module at `NONE` was not. And the unboxed-locals
pass emits its i64 local references as fixed-width placeholders; those are now SPLICED
OUT for the minimal LEB instead of being overwritten in place
(`WasmLispCompiler.buildLocalsAndPatch`, `.kb/wasm-unboxed-locals.md`).

### 3. `sub final` with no supertype is the BARE comptype

`subtype ::= 0x50 x* comptype | 0x4F x* comptype | comptype`, and the third alternative
means exactly `sub final` with no supertype. `RecTypeDef` writes it, two bytes per type
cheaper. The types are still FINAL and that is load-bearing for cast lowering, not
hygiene -- `.kb/wasm-gc-final-types.md` owns that half.

Adjacent local declaration RUNS of the same type are one run for the same reason. Two
emitters spelled them one-per-local: the hand-written runtime bodies that named each
local in a comment beside its own run (`WasmRuntimeBuilder`: `_print_i32`, `_print_f64`,
`_read_line`, `_princ_val`) and `NoGcWasmCompiler.withLocalsRaw`, which did it for the
whole `--no-gc` backend. Locals keep their ALLOCATION order either way -- a run can only
cover a maximal stretch of consecutive same-typed locals -- so the folding is mechanical
and changes no index.

### 4. An empty section is not written, and adjacent same-kind sections are one

An absent section and a section holding zero entries mean the same thing, so:

- `WasmWriter.writeSection` skips a `CountingDef` the consumer left empty (a call site
  may therefore just leave it empty instead of guarding the whole call). The case that
  drove it: in `--component` mode the memory is imported, so the core module's memory
  section had nothing to declare.
- `WasmTreeShaker` drops a rebuilt section it emptied rather than writing back a
  zero-entry vector (`addVector`). A print-only component's shared-memory module carried
  empty `type`, `function` and `code` sections after the shake.
- `ComponentWriter.rawSection` holds a section back until the next call proves it cannot
  be extended, and merges consecutive VECTOR sections of the same kind. Index spaces
  advance in declaration order, so merging preserves numbering exactly. The four
  non-vector kinds (custom, core module, nested component, start) are never merged. This
  is what lets `WasmComponentBuilder` emit one group per thing it computes without
  paying a section header for each.

## The decoder trap

**`WasmTreeShaker` has to PARSE what the writer emits, and the blocktype is where that
bites.** `blocktype ::= 0x40 | valtype | s33 typeidx`, so `isValTypeStart` is the
disambiguator: a `block (result eqref)` written as the single byte `6D` must be
recognized as a valtype, or it falls through to the s33 arm and is recorded as a type
reference at index -19 -- a read that does not describe the module, whose symptom is an
out-of-range crash in the rewriter far from the cause (and no symptom at all on a module
with nothing to drop, which returns early). The predicate therefore lists the WHOLE
`0x69-0x74` range, and the
range is unambiguous because a blocktype's s33 is a type index, hence non-negative, so
its first byte is either `0x00-0x3F` or has the continuation bit set -- never
`0x40-0x7F`. **Widen the predicate in the same commit as any encoder change that can
produce a new short form**, never after it. Pinned by
`WasmTreeShakerTest.decodesTheOneByteAbstractReferenceBlockType`.

## Measured (2026-08-07, wasm-tools 1.254 / wasmtime 47)

Every row is the same program before and after, and every "after" is a round-trip
fixpoint:

| module | before | after | delta |
| --- | ---: | ---: | ---: |
| `(print "Hello World!")` Preview 1 `--optimize` | 645 | 622 | -23 (3.6%) |
| fib 20, Preview 1 `--optimize` | 7,507 | 7,386 | -121 (1.6%) |
| string `wasm-export`, Preview 1 `--optimize` | 16,999 | 16,498 | -501 (2.9%) |
| file+env+clock+random+dir, Preview 1 `--optimize` | 91,532 | 87,913 | -3,619 (4.0%) |
| the same, `--component --optimize` | 96,910 | 93,268 | -3,642 (3.8%) |
| `(print "Hello World!")` Preview 1, no flag | 337,748 | 318,592 | -19,156 (5.7%) |
| the whole `ci-spec.yaml` corpus, `--optimize` | 6,073,627 | 5,819,288 | -254,339 (4.2%) |

Attributed on the no-flag row, which is the biggest for a reason worth knowing: rule 1
-5,536, rule 2 **-8,782**, the spliced i64 local references plus rules 3 and 4 -4,838.
Rule 2 dominates precisely where `--optimize` is NOT in play -- an unshaken module has
~370 functions, so most of its `call` immediates sit in the first padding band, whereas
`WasmTreeShaker` had already re-encoded the immediates it rewrote in a shaken one.

The fixpoint was checked by hand across every emission path there is, not only the ones
the corpus test covers: the base component wrapper, the SERVE wrapper
(`WasmServeComponentBuilder`), the `--no-gc` wrapper (`NoGcWasmComponentBuilder`), the
`wit-import` and `wit-export` components, `fetch`, TCP sockets, `--simd`, `--dynamic`,
`--no-wasi` and `wasm-export`. The `--no-gc` backend was the one path the first sweep
caught (rule 3's locals runs, `-32 B` on `count-vowels`) -- a reminder that
`WasmTreeShakerCorpusTest` compiles the GC backend only.

**Nothing about what the module COMPUTES changed, and that was checked rather than
assumed**: through every step of the work, `wasm-tools print` output stayed byte-identical
to the pre-change build's. That is the property to re-establish for any future change
here -- if the printed text moves, the change was not an encoding change.

## Re-evaluation triggers

- **A round-trip difference is a finding, not noise.** When
  `roundTripIsAFixpoint` fails, it is one of two things and the message says so: a
  newly-emitted field in a non-minimal encoding (fix the emitter), or a place where
  `wasm-tools` started normalizing something this project deliberately does not (record
  the reason here and relax the assertion to a size comparison). Do not relax it first.
- The test needs `wasm-tools` on `PATH` and skips without it, so a CI job without the
  tool proves nothing about this invariant.
- Rule 2's list of genuinely-signed fields is a list being COMPLETE. A future proposal
  that adds an s32 immediate joins it, and the round trip is what will say so.
