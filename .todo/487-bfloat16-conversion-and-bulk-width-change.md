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

Two things found in `.todo/671`'s freshly landed code while merging, both fixed here:
its bulk `:bfloat16` narrowing carried a PRIVATE COPY of the same rounding (now
`BFloat16.bits`, which is also what made the bulk widen-then-narrow round trip exact for
the 126 signalling NaNs its force-quiet copy lost), and all four of its built-ins were
registered in `Environment` under their UNQUALIFIED names while `PackageRegistry` exports
them from `rontolisp` -- so `rontolisp:widen-float-bits` and its three siblings were
undefined on the interpreter. `LispEvaluatorTest` now calls them.

Two findings worth carrying into steps 2-5, both in `.kb/bfloat16.md`: `(float)(double)`
QUIETS a signalling NaN (126 of the 65536 patterns broke the round trip until the payload
was carried across by hand), and WASM's `f32.demote_f64` is free by specification to
invent any NaN payload at all -- so neither direction may route a NaN through the f32.

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
