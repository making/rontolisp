# 670. Run a published SLM checkpoint: what Hugging Face ships, loaded as shipped

Difficulty: High (the umbrella; the children are sized individually)

Filed 2026-09-03 from the re-verification of `.todo/482` (`bfloat16`). The measurement
record is `.todo/482-bfloat16-a-narrow-width-that-pays/README.md`, "Round 2"; this item
is the plan those numbers imply, and `.todo/482` stays the width half of it.

**The goal: a small language model that someone downloaded from Hugging Face runs on
rontolisp from the file they downloaded** -- no Python, no `export.py`, no conversion
step outside the language. `examples/llama2` runs karpathy's `.bin`, a format one
project writes; the models people actually run are published in two formats,
**safetensors** (bf16, one JSON header and raw tensors) and **GGUF** (F32 / F16 / BF16 /
Q8_0 / Q4_K_M, the tokenizer and the hyperparameters in the same file), and in the widths
those formats carry. Checked 2026-09-03: SmolLM2-135M, TinyLlama-1.1B-Chat and
Qwen2.5-0.5B are each 100% BF16 in `model.safetensors`; no current small model is f16;
the GGUF already in this box's cache is Q4_K_XL with a BF16 companion.

## What the measurements decided, width by width

| width | verdict | where |
| --- | --- | --- |
| **bf16** | THE width. 1.5-2.1x f32 on one thread (Graal / C2), 1.6x on 20; widening exact; every checkpoint is in it | `.todo/482` (483-490), unchanged |
| **IEEE f16** | not a width -- a **load-time conversion** into `#f` / `#bf16`. A fused f16 GEMV is 0.30-0.58x on either JIT; converting 1.1B elements costs 0.11-0.57 s | `.todo/671` |
| **Q8_0** (32 int8 + a scale) | a **read-only weight matrix** type with an integer-dot GEMV: 2.0x f32 / 1.15x bf16 on one thread, 1.9x on 20, a quarter of f32's bytes, 7.6e-3 GEMV error; what half the published GGUFs are | `.todo/672` |
| **Q4_0 / Q4_K** | not a CPU item: the nibble unpack is ALU-bound at 5.7 GB/s (1.1x f32 for 8.5% error). A device width | `.todo/490`'s successor |

And the two facts under all four: **the width is bandwidth, not fitting** -- 4.4 GB of
f32 fits this 121 GB box and an 8 GB laptop, and a 1B decode is ~7 / 12 / 14 tok/s on
one thread at f32 / bf16 / Q8_0, ~21 / 34 / 39 on twenty -- and **every kernel number is
JIT-dependent**: the spike's fused kernel fell to 0.20x under C2 from an inlining cliff,
so `.todo/488` now takes its numbers under both JITs.

## Children, and the order

| item | what | difficulty | state |
| --- | --- | --- | --- |
| `671` | f16 and bf16 **bits** widened in bulk into an existing width, on every backend | Low | **closed 2026-09-03** |
| `673` | read a GGUF: metadata, tensor table, F32 / F16 / BF16 / Q8_0 tensors, tokenizer fields | Medium | **closed 2026-09-03** |
| `675` | read a safetensors file (+ `config.json`) | Low | reader done; the `#bf16` target now waits on `487` steps 3-5, not on `485` (closed) |
| `674` | the byte-level BPE tokenizer (SmolLM2, Qwen, Llama 3) from the GGUF fields or `tokenizer.json` | Medium | **closed 2026-09-03** |
| `672` | the Q8_0 quantized weight matrix and its integer-dot `vec:matvec` | High | not started; `673` leaves it one branch to replace |
| `676` | the forward pass as a table of layer kinds: QK-norm, NoPE, gates, partial RoPE, multipliers (Qwen3, SmolLM3, Granite) | Medium | **closed 2026-09-03** |
| `677` | the Gated DeltaNet layer: Qwen3.5-0.8B, and with it every Qwen 3.5-3.8 dense model | High | Qwen3.5-0.8B runs from both formats; bf16 `tok/s` waits on `488`'s wiring, not on `485` (closed) |
| `678` | the LFM2 gated short-conv layer: LFM2.5-1.2B-Instruct, the newest ~1B model | Medium | not started; the LFM2.5 files are downloaded |
| `489` | the model rungs: TinyLlama / SmolLM2 (loader shakeout), Qwen3-0.6B, LFM2.5-1.2B, Qwen3.5-0.8B | High | f32 rungs measured for two models; bf16 rungs wait on `488`'s wiring alone (`485` closed) |

**Order: 671 -> 673 / 675 -> 674 -> 489 rung 0 at f32 -> 676 -> 678 -> 677 ->
`.todo/482`'s 483-488 (only `487`'s remainder and `488` are left) -> 489 at bf16 -> 672 -> 490.** (676-678 are pure Lisp over the
readers and can overlap the width chain; they touch no Java.) The point of that order: 671 needs no new array type and lands on every
backend, which lets a BF16 checkpoint load into `#f` BEFORE the bf16 width exists, so
the readers and the model are debugged at f32 (4.4 GB, fits) with the kernels out of the
picture, and the width then halves a run that already works. 672 comes after the width
because its scalar oracle and its `dequantize` target are `#bf16`.

## What landed on 2026-09-03

**A published checkpoint runs, in three formats, no Python and no conversion step.**
Qwen3.5-0.8B from its BF16 safetensors AND from ggml-org's BF16 GGUF, **token for token
identical between the two**; TinyLlama-1.1B-Chat from safetensors and an F16 GGUF, same
forty tokens; stories15M converted to GGUF answers with `run.c`'s own text token for
token -- the one EXTERNAL oracle among these, and the one that caught a live bug (the
architecture row was matched by which options it carried, so the all-defaults `llama` row
was rejected as unsupported). `llama.cpp` on the same file tells the same story in
different words -- bf16 ggml kernels against an f32 GEMV, jinja against a hand-written
template; byte equality is `.todo/672`'s check, not this one's.

Closed: `671`, `673`, `674`, `676`, and on the width side `480`, `484`, `485`, `486`,
`487` step 1, `692`.

**The f32 rungs say what bf16 has to beat** (dorian, JVM class output, `--simd`):

| | 1 thread | `--parallel` | bytes/token | parallel bandwidth |
| --- | --- | --- | --- | --- |
| Qwen3.5-0.8B | 2.00-2.92 tok/s | 8.56 tok/s | 3.2 GB | **27.0 GB/s** |
| TinyLlama-1.1B | 1.58-1.91 tok/s | 6.97 tok/s | 4.4 GB | **30.7 GB/s** |

**Two independent models on one ceiling**: the parallel leg is DRAM-bound, not
thread-bound, which is why 64 threads buy about 4x over one; the single-thread leg is at
8.4 and 5.0 GB/s and is bound by something else. `.todo/489` carries the prediction that
makes -- and its precondition: `eval/VecSimd` declines a bf16 array at every dispatch
point until `.todo/488`'s wiring lands, so measuring earlier falsifies a correct
diagnosis for the wrong reason.

## The two machines, because every number here is one of them

- **`dorian`** -- Xeon E5-2697A v4, Broadwell x86-64, 64 threads, 251 GB, GraalVM 25.0.4,
  AVX2 256-bit, **no avx512**. Orchestrator A's box; no GPU.
- **GB10** -- aarch64 Cortex-X925, 20 cores, 121 GB, NEON 128-bit, CUDA. Orchestrator B's
  box, and the only one that can run the GPU legs.

A measurement without its base commit, JIT, machine and load average is not comparable to
another; a quiet window is per-box and each side takes its own.

**The checkpoints are gone.** They lived in a session scratchpad under `/tmp/claude-1000/`
and were not preserved: Qwen3.5-0.8B (safetensors + BF16/Q8_0 GGUF), TinyLlama-1.1B-Chat
(safetensors + F16 GGUF), LFM2.5-1.2B-Instruct, stories15M as GGUF, and a built
`llama.cpp`. **Re-fetching them is the first step of `678` and of `489`'s bf16 rungs**;
`llama.cpp` takes about three minutes to build. None of it belongs in the repo.

## Certified green, 2026-09-03, at develop `080b3d75`

Both orchestrators stopped here, every worktree clean.

| run | box | result | ran at |
| --- | --- | --- | --- |
| full suite | dorian | 9977 / 0 / 0, 276 skipped, 227 reports | `656c170d` |
| full suite | GB10 | 9976 / 0 / 0, 189 skipped, 227 reports | `281fda90` |
| native `CiSpecE2eTest` | dorian | 1972 / 0 / 0, incl. the bf16 `refusedOn` legs on both wasm backends | `656c170d` |
| llama2 e2e slices | dorian | 35 / 35 | `656c170d` |
| GPU legs, 6 classes | GB10 | 205 / 0 / 0, with `486` in | `281fda90` |

**Neither pair of numbers matches and every difference is accounted for**: 9977 against
9976 is the regression test `692` added, and the 87-skip gap is the device (`GpuTest` 57 +
`MetalGpuTest` 54 skip where there is neither). **The numbers that should NOT match are
the evidence that the rest do** -- two suites agreeing exactly across boxes with different
hardware would be the result to distrust.

## Findings from the run, and where each one now lives

Carried in a per-session file outside the repo until 2026-09-03. **The entry below is a
pointer, not the record**; the home is where it gets updated.

- **`.todo/483`'s rule is stated wrong in 483** -- not "never write a `default`" but **"an
  arm matching two or more permits IS a default, whatever it is spelled"**; a supertype
  pattern over a sealed umbrella is correct only when the answer does not depend on the
  width. Nine sites audited, seven correct, two wrong. In `.kb/vec.md`; **483's own text
  still states the old rule and should be corrected when it is next opened.**
- **`%la-gather-strided` has five readers** and grepping the name finds two; one of the
  three grep misses returned a `float[]` for a double gather. Fixed during `485`; the
  account and the pinning hole it left are in `.todo/687`.
- **Seven sites hand-write the bf16 conversion arithmetic**, only
  `am.ik.rontolisp.BFloat16` is the authority (the root package cannot travel into
  compiled output), and three of the seven lost the same 126 signalling-NaN patterns on
  one day for three different reasons. The census is `.todo/487`'s remainder, which now
  also covers transcribed WIDTH DECISIONS, not only NaN branches.

## Lanes for the week of 2026-09-08: two orchestrators, TWO workers each

What is left is A's model work and B's width work, crossing at exactly one point in each
direction -- three lanes a side is more parallelism than the graph has. **The one hand-off
that gates the week: B lands `488`'s interception, and A's bf16 rungs cannot be measured
until it is in.** Until then A works the items that need neither. The cross-side ordering
constraint that used to sit here (`692` before `488`, so the wiring is not laid over a
known `--simd` trap) is DISCHARGED: `692` closed 2026-09-03, which is why A1 starts at
`678`.

**Orchestrator A -- the model side, no GPU:**

| wave | lane A1 | lane A2 |
| --- | --- | --- |
| 1 | `678` LFM2.5-1.2B: the gated short-conv layer end to end, both formats, the way `677` went (Medium) -- `692`, which used to lead this cell and gate B's `488` wiring, closed 2026-09-03 | `489` f32 rungs, finishing the set: **Qwen3-0.6B has not been run at all**, then SmolLM2 (High) |
| 2 | `682` rename `examples/llama2` -> `examples/llm` -- **its trigger fired twice on 2026-09-03** and nobody noticed (Medium) | `489` at bf16, against the prediction already written there, the moment B's `488` wiring lands; then close `675` and `677` (High) |
| 3 | `688` the corpus tests' duplicated splice chain, and the "a test that prints a compiler warning must assert on it" rule (Medium) | `490`'s A-side numbers if B gets that far (High) |

A1 owns `examples/llm/*` in wave 2 (the rename), so A2 must not edit those files that
wave; A2 owns the measurements and the `.todo/489` record throughout.

**Orchestrator B -- the GB10 box, the width chain and the device** (B's own call; this is
what A's half needs from it, in the order A needs it):

| wave | lane B1 | lane B2 |
| --- | --- | --- |
| 1 | `488`'s interception into `--simd` / `--parallel` -- **the kernels already exist in `VecSimdKernels`; only the wiring is missing**, and A's bf16 rungs are blocked on it (High) | `691` then `690`: one `octets-to-string` builtin, the three hand-written UTF-8 decoders folded onto it, and the character-index walk that makes a 13 MB `tokenizer.json` unparseable (Low, then Medium) |
| 2 | `672` Q8_0: `.todo/673` leaves exactly one branch and one ci-spec `handler-case` to replace, over a reader whose metadata and directory already work. **It does NOT wait on `691`** -- that was said before `673` landed, and the GGUF metadata and vocabulary now demonstrably read; `691` matters to `tokenizer.json`, which is `674`'s side (High) | `487`'s remainder, including the duplicate census -- its scope is written into 487 itself (Medium) |
| 3 | `490` bf16 on the device (High) | `687` first, then `683`: `486` already landed 687's width wire, so what is left there is the constructors carrying a third width plus one pinning hole; `683` still needs a reflection-test design (Medium) |

Sizing: A1 Medium, A2 High (a Fable-class model); B1 High, B2 Low-to-Medium then Medium.
Unassigned: **`694`** (the `--simd` axis missing from cross-backend E2E) and **`693`** (the
component leg of `safetensors-check`), both filed 2026-09-03 and neither in a wave.

**Standing rules this run earned, in the order they cost the most:**

1. **Only the closer can write back a dependency.** Six items closed on 2026-09-03 and
   twelve open todos still read as blocked by them that afternoon. The `grep` for items
   naming the number now sits beside the history row in the close procedure.
2. **A count an item wrote down is not a completion test.** A stale dependency line only
   delays a start and someone eventually notices the work is startable; **a stale count
   fakes a finish** -- fix the 86 sites `.todo/683` names and it reads as done, though two
   more appeared while `485` was worked. Start an audit from the grep, never the number.
3. **Sort every "Remaining" into blocked / not-done / deferred.** Only the first is a real
   remainder; the second is unstarted work wearing a blocker's clothes; the third
   evaporates without an owner and a date. Two of nine Remaining lines were truly blocked.
4. **One session runs the full suite on `develop`, the other runs the GPU legs.** Three
   reds were invisible from every lane's own worktree -- one because a shipped library
   changed an existing test's input, two because two lanes' changes were each correct
   alone.
5. **Separately from who owns what: never two device-touching runs at once.** `./mvnw
   test` includes `GpuTest`, so a full suite IS a device-touching run. Stated as one rule
   with the line above, it produced a self-contradictory instruction on 2026-09-03 ("do
   not run the GPU tests" and "run the full suite", to the same lane). Ownership says who
   takes which result; exclusion says what may run at once.
6. **A suite can hold a defect invisibly while every case sits on one side of its
   condition**, and the half that looks more exhaustive is the half that hides it. Three
   in one day: the 1496-error regression (the corpus called the function, the standalone
   cases did not); `PRINT_OBJECT_VECTOR_ARM` excluding a packed width by name, visible
   only in a program containing `read-from-string`; and `692`, against a `671` that closed
   claiming all four backends -- its tests counted backends and never `--simd` on each
   (`.todo/694`).
7. **A rule one lane derives from one measurement is a hypothesis until the other lane has
   tried to break it.** Three corrections in one day, each of which would have entered
   `.kb` as a law with only one lane working: B wrote "1496 errors cannot be explained by
   a defect in the object" and A adopted it, and the clean re-run reproduced 1496 from ONE
   cause and refuted both (what survives: a failure count's SIZE narrows the SEARCH, never
   the VERDICT); A blamed a stale `target/classes`, re-ran from `clean`, got the identical
   number and retracted their own retraction; a lane reported "the interpreter does not
   narrow on `aset`" and requiring identification before the fix produced a retraction
   instead -- the oracle was hand-written, so the "parity test" was two assertions and its
   failure named neither (`.kb/measurement-probes.md`). **Say it to the other lane before
   writing it into `.kb`.**
8. **A run certifies a head it did not run against when the FILE SET says so, never the
   elapsed time.** Every run in the table above did: `git diff --stat <ran-at> <head> --
   src/` empty means a re-run would only re-measure `.todo/` edits. One command, and it is
   the whole argument.

## What is deliberately not in the plan

- **Not an inference framework.** The forward pass stays one Lisp file; the layer became a
  KIND with options (`676`) and two more kinds joined it (`677`, `678`) only because the
  newest small models are hybrids -- Qwen 3.5-3.8 is 3 Gated DeltaNet layers per attention
  layer, LFM2.5 is 10 short-conv per 6 attention. Gemma 4 (gated licence, KV sharing,
  per-layer embeddings) waits until asked for.
- **Not mixed-precision training.** `torch:` stays f32/f64; bf16 is a storage width for
  weights, and nothing here changes what an activation is.
- **Not the device.** `--gpu` declines every new type until `.todo/490` and its successor;
  declining correctly is what `.todo/483`'s exhaustive switches buy.
- **Not fp8 / int4 on the CPU.** Measured out (above); re-measure only when the Vector API
  grows a dot-product or a narrower conversion, or on a host whose JIT does better than
  1 op/element for the unpack.
