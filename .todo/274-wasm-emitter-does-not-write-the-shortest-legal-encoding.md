# The wasm emitter does not write the shortest legal encoding

Difficulty: Medium

Round-tripping any rontolisp wasm output through `wasm-tools print` and back
produces a **byte-for-byte equivalent module that is 2.4%-5.7% smaller**, and it
still validates and runs. Measured 2026-08-06, wasm-tools 1.254 / wasmtime 47:

| module | ours | round-tripped | delta |
| --- | ---: | ---: | ---: |
| `(print "Hello World!")` Preview 1 `--optimize` | 645 | 622 | -23 (3.6%) |
| fib 20, Preview 1 `--optimize` | 7,507 | 7,386 | -121 (1.6%) |
| string `wasm-export`, Preview 1 `--optimize` | 16,983 | 16,482 | -501 (2.9%) |
| file+env+clock+random+dir, Preview 1 `--optimize` | 91,723 | 88,099 | **-3,624 (4.0%)** |
| the same, `--component --optimize` | 97,102 | 93,455 | -3,647 (3.8%) |
| `(print "Hello World!")` Preview 1, no flag | 337,748 | 318,592 | **-19,156 (5.7%)** |

This is not a component question -- it is every wasm byte the project emits,
Preview 1 and `--component`, optimized and not.

## Where the bytes are

### 1. A nullable reference to an ABSTRACT heap type is written long (the bulk)

`WasmWriter.writeRefType(nullable, heapType)` always emits the constructor byte
plus the heap type: `(ref null eq)` goes out as `63 6D`. The wasm-GC binary format
has a one-byte shorthand for exactly this case -- `6D` IS `eqref` -- and the same
holds for `i31ref` `6C`, `anyref` `6E`, `structref` `6B`, `arrayref` `6A`,
`nullref` `71`, `nullfuncref` `73`, `nullexternref` `72`, `nullexnref` `74`.
`funcref` `70` / `externref` `6F` / `exnref` `69` already go out short.

It costs one byte per occurrence, and the occurrences are everywhere a valtype
appears: **function-body LOCAL declarations, blocktypes, globals, struct fields,
function signatures**. Attributed on the 91,723-byte module above -- the delta is
`types -81, globals -4, code -3,539`, the code share sits ENTIRELY inside function
bodies (every body-size prefix is already minimal), and 55 of its 110 functions are
byte-identical, i.e. exactly the reftype-free ones. One body (`func 71`, 33,578 B)
alone contains 545 `63 6D` pairs and shrinks by 1,512.

**A NON-nullable `(ref eq)` must stay `64 6D`, and a concrete type index must stay
long.** The shorthand exists only for nullable + abstract.

### 2. `sub final` wrapped around a struct that has no supertype

`RecTypeDef` writes `4F 00` ("sub final, 0 supertypes") before every struct and
array (three call sites). A bare `structtype` in a rec group already MEANS
sub-final with no supertype, so the wrapper is two dead bytes per type. Five
structs in the hello module = 10 B. Emit it only when there IS a supertype --
today nothing has one.

### 3. Sections that are emitted empty, and sections emitted twice

- The `--component` core module emits a **memory section with count 0** (3 B): in
  component mode the memory is imported, so the section has no entries.
- `WasmTreeShaker` writes a section back even when everything in it was dropped:
  the shaken shared-memory module of a print-only component carries empty `type`,
  `function` and `code` sections (9 B). This one arrived with todo-273, which is
  what made that module shakeable in the first place.
- `WasmComponentBuilder` emits **two alias sections and three core-instance
  sections** where one of each would do (~3 B on hello; the format allows repeats,
  so this is style, not correctness).

## What to do

Fix 1 first -- it is one method and it is nearly all of the win. Then 2 and 3.

**The trap that makes this Medium rather than Low: `WasmTreeShaker` cannot
currently PARSE the shorthand it would then have to read.** `isValTypeStart`
(the blocktype disambiguator, `scanBlockType`) lists only `0x7B-0x7F`, `0x6F`,
`0x70`, `0x69`, `0x63`, `0x64` -- so a `block (result eqref)` written as the single
byte `6D` falls through to the s33 type-index arm, is misread as index -19 and gets
renumbered. That is a SILENTLY corrupt module, not a throw, which is the one
failure mode the shaker's decoder is built to avoid
(`.kb/optimize-dead-code-elimination.md`, "Decoder correctness"). Widen the
predicate in the same commit as the encoder, never after it.

## How to verify

The round-trip is the metric and the oracle: `wasm-tools parse (wasm-tools print M)`
should converge on `M` itself. Drive the delta to zero on the corpus above, and for
every byte that still differs, record WHY in the `.kb` -- a remaining difference is
either another non-minimal form or a place wasm-tools normalizes something we
deliberately do not.

This changes the bytes of **every** wasm output, so:

- the measurement tables in `.kb/optimize-dead-code-elimination.md` (the literal-fold
  table, the `SIZE`-level table, the component breakdown) all move and must be
  re-measured, not adjusted;
- the size pins move: `WasmTreeShakerTest.everySpellingOfHelloWorldReachesTheSameFloor`
  and `…ComponentFloor`, `orphanedCaseFoldTableSegmentsAreDropped`;
- all four backends must be re-run, and the native `CiSpecE2eTest` with them.

## Non-goals

- The component wrapper's own floor (`.todo/275`) and the core's fixed string
  prologue (`.todo/271`); both are about WHAT is emitted, this is about how it is
  spelled.
- Matching wasm-tools on anything that is not smaller. The goal is the shortest
  legal encoding, not imitation.
