# 487. `bfloat16` conversion: the bits pair, `coerce`, and reading a bf16 file

Difficulty: Medium

Part of `.todo/482`. Depends on `.todo/484` (and on `.todo/485` for the JVM side).

Getting data in and out at bf16 is what makes `.todo/489`'s 1B-class model loadable at
all: a 1.1B-parameter checkpoint is 2.2 GB of bf16, and it must arrive in one bulk
transfer per tensor, never through a per-element Lisp loop.

Not here: an IEEE f16 file (GGUF `F16`, an older fp16 checkpoint) is `.todo/671` -- a
bulk widening of `(unsigned-byte 16)` bits into an existing width, which needs no f16
array and lands on every backend. Step 3 below and 671 share the staging shape (a u16
chunk, widened into the destination at an offset); build it once.

## Progress

**Step 1 is DONE**, landed 2026-09-03 by the commit carrying this note (a commit cannot
name its own hash; everything else below is untouched). What landed: `bfloat16-bits` /
`bits-bfloat16` on ALL FOUR backends (`am.ik.rontolisp.BFloat16` is the single Java
authority, the two compile backends emit the same arithmetic inline because it does not
travel), and `FloatText.bfloat16Text` -- step 4 of `.todo/484`'s printer half, landed with
the pair so the two are pinned together. The full mechanics and the decision record are
`.kb/bfloat16.md`.

Symbol ownership, agreed with the `.todo/671` lane on 2026-09-03: this pair and
`bfloat16Text` are OURS; 671 adds `float16-bits` / `bits-float16` and the bulk primitives
`widen-float-bits` / `narrow-float-bits`. The sentence in 671 offering to take this pair
if it landed first is void.

Verification, 2026-09-03:

- **The f32 -> bfloat16 narrowing is right on all 2^32 float32 inputs**: 4,278,190,082
  non-NaN inputs swept against an independently written textbook round-to-nearest-even
  oracle (compare the dropped sixteen bits against half an ulp, break the tie towards the
  even survivor), **0 mismatches**, plus 0 NaN-contract violations over the 16,777,214 NaN
  patterns (a NaN stays a NaN of the same sign, never an infinity). Harness:
  `.todo/487-bfloat16-conversion-and-bulk-width-change/Sweep.java`, ~4 s single-threaded.
  It is NOT in the suite -- `BFloat16Test` keeps the representative cases instead (both
  tie directions and the values either side, subnormals, signed zeros, both infinities,
  the overflow-to-infinity boundary, and every NaN payload).
- Widening is exact over all 65536 patterns and the pair is an involution on them,
  signalling NaNs included; the printed text re-reads to the same pattern for all 65536.
- The `bfloat16-bits` case in `ci-spec.yaml` runs the pair on all four backends. Its
  top-level names are `bf16-`-prefixed so the concatenated program cannot collide with
  671's cases.

Three things found in `.todo/671`'s freshly landed code while merging. Two are fixed
here: its bulk `:bfloat16` narrowing carried a PRIVATE COPY of the same rounding on each
backend (both now go through `BFloat16.bits`, the interpreter by calling it and the JVM by
emitting it instruction for instruction), and all four of its built-ins were registered in
`Environment` under their UNQUALIFIED names while `PackageRegistry` exports them from
`rontolisp`, so `rontolisp:widen-float-bits` and its three siblings were undefined on the
interpreter -- `LispEvaluatorTest` and `JvmLispCompilerTest` now call them over all 65536
patterns.

The third was a CEILING on the bulk pair, and it is closed: a bulk widen into a packed
single-float array went through a `double` and so quieted a signalling NaN. It writes
`Float.intBitsToFloat(bits << 16)` straight into the `float[]` now, and widen-then-narrow
is the identity on all 65536 patterns. Two tests here briefly asserted the ceiling instead
of the identity; that expectation is stale and is being corrected by the lane that closed
it. The measurement to keep: BOTH float/double conversions quiet a signalling NaN, `f2d`
as much as `d2f`, while `Float.intBitsToFloat` is bit-preserving for every pattern -- so
at this width a NaN must never cross a `double` in either direction.

Two findings worth carrying into steps 2-5, both in `.kb/bfloat16.md`: `(float)(double)`
QUIETS a signalling NaN (126 of the 65536 patterns broke the round trip until the payload
was carried across by hand), and WASM's `f32.demote_f64` is free by specification to
invent any NaN payload at all -- so neither direction may route a NaN through the f32.

## Progress, 2026-09-05: step 3 landed, and what the census found under it

**Step 3 is DONE for the primitive** -- `read-sequence` / `write-sequence` move a `#bf16`
array in ONE bulk transfer of its STORED PATTERNS (two little-endian bytes an element, what
a BF16 safetensors or GGUF tensor holds), interpreter `PackedBuffer` and JVM
`_readSeqPacked` / `_writeSeqPacked`. 2^21 elements round-trip with zero mismatches, 5 ms on
the JVM and 10 ms on the interpreter. **This delivers `.todo/675`'s frozen-interface bullets
1 and 2 verbatim** -- 675 can point here rather than restate them. Bullets 3-5 (the
`checkpoint:stage-float-bits` bf16 arm, `safetensors:read` / `gguf:read` accepting
`:element-type 'bfloat16`, and their docs) are the rest of step 3 and are NOT done.

**The census found the blocker under it, and it is the finding this item exists for.** A
`make-array` whose `:element-type` is a runtime VALUE -- which is what
`checkpoint:make-tensor` does, so it is the only way a checkpoint reader allocates -- has to
be turned back into literal spellings, one arm per `ArrayElementTypes` code. That list was
TRANSCRIBED FOUR TIMES: the program-scan mask, the inline lowering's arms, `%make-array-et`
and `%make-array-et-fp`. Every copy was documented as covering seven codes and every copy
spelled six. `bfloat16` was missing from all four from the day the width landed, so:

- interpreter `BFLOAT16` (it reads the designator at run time, and is therefore the one
  engine that could not see the defect),
- JVM class output `T` -- a boxed general array that does not even remember the width,
- wasm `T` -- **past a guard whose own comment says it exists to stop exactly that**.
  `WasmArrayCompiler` refuses the LITERAL `:element-type 'bfloat16` where the representation
  is chosen, so that "an unrefused request falls through to the general BOXED array below and
  the program answers different numbers here than on the interpreter -- a wrong number rather
  than a crash". The runtime designator reached the same allocation by another route. A guard
  on one spelling of an operation is not a guard.

Fixed by DERIVING rather than adding a seventh copy: `ArrayElementTypes` grew
`specializedCodes()` / `ALL_SPECIALIZED_MASK` / `CHARACTER_SPELLINGS`, and all four sites read
them (both prelude helpers are now GENERATED from the code list, character-for-character what
they were plus the missing arm). An eighth width is one entry in that class. The pins are
keyed to the same method on all three engines, never to a list of widths -- a hand-written
list of seven would be a fifth transcription with a green tick on it:
`LispPreludeLibraryTest#bothMakeArrayElementTypeHelpersCoverEverySpecializedCode`,
`LispEvaluatorTest#evalMakeArrayWithARuntimeElementTypeMatchesTheLiteralSpelling`,
`JvmLispCompilerTest#compileAndRunMakeArrayWithARuntimeElementTypeMatchesTheLiteralSpelling`
(both lowerings, through the helper and inline), and
`WasmLispCompilerIntegrationTest#aRuntimeElementTypeDesignatorAnswersWhatTheLiteralSpellingAnswers`
(where a refused width must be refused through BOTH spellings). Verified the prelude pin
fails when the generator is made to skip a width. `.todo/703`'s mechanism question is
untouched and its evidence is stronger; the entry there says so.

**Steps 3 and 4 are now DONE end to end**, which is all five of `.todo/675`'s frozen
bullets. `checkpoint:stage-float-bits` takes BF16 bits into a `#bf16` destination as ONE
`read-sequence` with no widen and no staging buffer; `:float16` bits into one go through a
chunk-sized f32 scratch; `checkpoint:stage-float32` narrows f32 words into a `#bf16`
destination AS THEY STREAM (step 4), never through a whole-tensor transient, and both
readers hand a `bfloat16` destination straight to it. `safetensors:read` and `gguf:read`
take `:element-type 'bfloat16`; docs mirrored en/ja on the five pages;
`.kb/checkpoint-readers.md` carries the source-by-destination table.

**One correction to 675's frozen text, measured**: it said a `'bfloat16` read would be EQUAL
to the `'single-float` one "because widening is exact". That holds for a BF16 SOURCE and for
nothing else -- bfloat16 has eight mantissa bits, so an F16 or F32 value outside them
changes. The f16 maximum 65504 rounds to the pattern `0x4780` (value 65536), which prints as
`65500.0` because `FloatText.bfloat16Text` answers the shortest decimal that reads back as
the same bfloat16. The tests assert the narrowed value.

**Still open here**: step 2 (`coerce` and the bulk width change -- `widen-float-bits` /
`narrow-float-bits` still decline a bf16 source or destination, which nothing above needed),
step 5 (the widen-once scratch), the signalling-NaN reachability question below, and the
remaining census rows (the three travelling templates and
`NoGcWasmCompiler.compileFloatArrayLiteral`).

## Do

1. **The bits pair.** `LispNames` already carries `SINGLE_FLOAT_BITS` /
   `BITS_SINGLE_FLOAT` and the double pair; add `bfloat16-bits` / `bits-bfloat16` over the
   two conversions defined in `.todo/484`. These are `double -> double` with no array
   involved, so unlike the array width they are **portable to every backend** -- follow
   the full "Adding a Built-in Function" checklist in `CLAUDE.md` including step 4 (wasm).
   `bfloat16-bits` must round to nearest even, not truncate.
2. **`coerce` and the bulk width change.** `(coerce v '(array bfloat16))` and back, and the
   `vec:`/`linalg:` constructor `:element-type` route, must go through one bulk converter,
   not an element loop through the generic setter. Both directions vectorize directly --
   widening is `IntVector.lanewise(LSHL, 16).reinterpretAsFloats()` after a
   `S2I` widen, narrowing is the round-to-nearest-even add and a shift -- so a single
   vectorized pair serves every caller. Measured widening throughput: **11.9 Gelem/s**,
   above the 7.5 Gelem/s f32-to-f32 copy ceiling for the same shape
   (`.todo/482-bfloat16-a-narrow-width-that-pays/Dec.java`, variant E).
3. **Reading a bf16 file.** `read-sequence` into a `bfloat16` packed array, mirroring the
   bulk transfer `examples/llama2/llama2.lisp` already does into single-float arrays
   (`.kb/binary-sequence-io.md`). This is the path `.todo/489` needs and it must move
   whole tensors, not elements. Endianness must match what the existing f32 bulk read
   does, and the test must cover a tensor larger than any internal buffer so a chunked
   read is exercised.
4. **f32 -> bf16 at load.** Also support reading an existing f32 checkpoint and narrowing
   as it streams, so a bf16 run needs no new file and no offline conversion step to try.
   Narrow in the read buffer, never by materializing the whole f32 tensor first -- at
   1B parameters that transient is 4.4 GB.
5. **Widen-once helper.** For the paths `.todo/488` does not fuse, one place that widens a
   `bfloat16` array into a reusable f32 scratch. `Worth.java` puts the crossover at a
   reuse of ~16 on a 67 MB matrix, so this is the minority path; keep it simple and do not
   allocate the scratch per call.

## The census covers WIDTH DECISIONS, not only NaN handling

The three travelling templates (`.todo/687`) and `NoGcWasmCompiler.compileFloatArrayLiteral`
are transcriptions of a width DECISION with no differential test, which is why an edit to
the width wire could break them silently -- the same class of duplicate as the seven copies
of the bf16 arithmetic, found by a different symptom. Count both.

One open-reachability site, recorded as unchecked rather than asserted as a bug:
`compileFloatArrayLiteral` crosses a `double` TWICE per element -- `elementAt` is an
implicit f32 -> f64 widening (`LispSingleFloatArray:56` returns `data()[flat]`), then a
`(float)` cast, then the emitted `f64.const` + `f32.demote_f64` at run time. Its comment
claims the round trip is lossless, which is true of every value except a signalling NaN
(`.kb/bfloat16.md`: a NaN must never cross a `double` in either direction). **Whether a
signalling NaN can reach an `#f(...)` literal through the reader is UNCHECKED** -- settle
that before deciding whether the comment is wrong or merely unreachable.

## Verify

- `(bits-bfloat16 (bfloat16-bits x))` is the bf16-rounded `x` for a sweep including
  subnormals, +/-0, both infinities, and NaN.
- **Round-to-nearest-even, explicitly.** Pin both tie directions and the values on either
  side. A truncating `>>> 16` passes a casual test, biases every sum downward, and shows
  up only as drift in a model's output.
- Widening is exact for all 65536 patterns: `bits-bfloat16` of the widened value returns
  the original bits, NaN payloads included.
- The bulk converters agree bit-for-bit with the scalar pair over all 65536 patterns.
- A bf16 file written by `bfloat16-bits` and read back by `read-sequence` compares equal
  on every backend that carries the width.
- Load a tensor of >2^20 elements and check the first, last and a middle element -- the
  chunked-read boundary is where a bulk path breaks.
