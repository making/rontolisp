# 696. The narrow-width element-wise kernels, the operand pairing, and x64

Difficulty: Medium

What `.todo/488` left when it closed (2026-09-05). All three are about the same seam --
the `--simd` interception of a NARROW storage width -- and none of them blocks
`.todo/489`'s decode path, which is GEMV and reductions.

## 1. Element-wise `vec:` kernels over a narrow width

`vec:add`..`vec:clip` and their `-into` siblings DECLINE a `bfloat16` operand today: the
interpreter's `eval/VecSimd` asks `anyBf16` before every width switch, and the JVM's
`JvmSimdCompiler.emitLaneWidthGuard` admits a `short[]` only at the position
`BF16_OPERAND` names. The scalar `vec.lisp` defun answers, correctly.

The shape a kernel would take is settled and written down (`.todo/488`, "Do" step 4):
**widen, compute in f32, narrow on store.** Never keep an intermediate at the narrow
width -- 8 mantissa bits compounds fast, and the width is for storage.

**Why it was not worth doing with the GEMV kernels.** The decode is one shift and
vectorizes; the NARROWING does not. `floatToBf16` is round-to-nearest-ties-to-even with a
guarded NaN arm (`.kb/bfloat16.md`), which is why `narrowBf16Into` is a scalar loop in
both kernel files while `widenBf16Into` is a lane loop. An element-wise kernel stores
every element, so it would be a scalar store loop wearing a vector load -- close to the
defun, at the cost of ~40 new kernels mirrored across two files. Measure a narrowing lane
form (a mask for the NaN arm, the bias-add for the rest) BEFORE writing any of them: if it
does not vectorize, the answer is to leave the decline in place and record that here.

## 2. Can the bridge take a NARROW x NARROW pairing?

Asked by the orchestrator on 2026-09-05, recorded because a later reader cannot
reconstruct it. `.todo/490` (bf16 on the device) and `.todo/672` (Q8_0) may both want it.

Today every fused kernel is **narrow weights against f32 activations** -- which is a PLAN
decision, not an artifact: `.todo/670` line 259 ("bf16 is a storage width for weights, and
nothing here changes what an activation is"), `.todo/488`'s table and `.todo/482`'s record
all measure f32 activations, and `.todo/672`'s Q8_0 rows do too (its activation-quantizing
`q8int` variant is a separate row at its own 7.6e-3 error).

**The answer: it is an extension, not a rewrite.** Nothing in the design forecloses it.

- The **total-bridge property does NOT depend on exactly one operand being narrow.** It
  depends on the guard admitting only combinations that have a kernel. `BF16_OPERAND` maps
  a member to the ONE position that may hold a `short[]`; taking a pairing means widening
  that to the SET of positions that may, and adding one `instanceof` chain in the bridge
  entry to pick the kernel. The guard stays a pair of exclusive arms, both ending at a
  kernel call, so no call site grows a null-check rung.
- The **kernel** side is a sibling method per combination (`dotBf16Bf16` beside
  `dotBf16`), decoding both lane groups into the same four pinned lanes. Keep one small
  method per combination -- the C2 inlining cliff (`.todo/482` round 2) is a rule about
  method size, and a decoder shared behind a flag is what tripped it.
- The **one real cost** is the RESULT width. `vec:matvec`'s product follows `x`
  (`vec::%make-like`), so a narrow `x` means a narrow result and a NARROWING store per
  row -- cheap at one per row, unlike the element-wise case above. A narrow `-into`
  destination is the same store.

So the question, when it comes up, is a plan decision to revisit and not a rewrite. This
paragraph is the answer for `bfloat16` AND for whatever `.todo/672` lands as Q8_0: the
guard and the bridge are width-agnostic, the pairing is what the plan restricts.

## 3. x64

Every `.todo/488` number is aarch64 (GB10). A left shift is a left shift, so the SHAPE of
the result should hold, but the cache-resident crossover moves with the hierarchy and
`.todo/482`'s x64 host runs the same f32 GEMV 2.6-2.9x slower in absolute terms. Run
`.todo/488-the-fused-bfloat16-gemv-kernels/bench.sh both` on an x64 box and add the table
beside the aarch64 one, with load average and base commit (`.todo/670`).

Bit-identity is NOT at risk either way: `FSPECIES_REDUCE` is `SPECIES_128` and the decode
species is pinned to match it, so the fold order is the same on an AVX-512 host as on
NEON. What moves is the crossover, which is a PERFORMANCE number.

## 4. The width is not in `doc/` at all

The packed `#bf16` array has no entry in `doc/{en,ja}/reference/data-types.md` and no
paragraph in `guides/simd-acceleration.md` -- only the scalar `bfloat16-bits` /
`bits-bfloat16` pair and the bulk float-bits pair are documented. That predates
`.todo/488`, which is why the fused kernels landed with `.kb` coverage and no `doc/`
change: documenting a width's `--simd` behaviour before the width itself would be a page
about something the guides never introduced. Document the two together -- the width, its
literal, its interpreter/JVM-only support, and the one pairing `--simd` fuses -- mirrored
across `doc/en` and `doc/ja` in one commit.
