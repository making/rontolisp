# 670. Run a published SLM checkpoint: what Hugging Face ships, loaded as shipped

Difficulty: High (the umbrella; the children are sized individually)

Filed 2026-09-03 from the re-verification of `.todo/482` (`bfloat16`). The measurement
record is `.todo/482-bfloat16-a-narrow-width-that-pays/README.md`, "Round 2"; this item is
the plan those numbers imply, and `.todo/482` stays the width half of it.

**Compacted 2026-09-05.** Roughly 190 lines of certification prose were replaced by the run
table plus pointers, under this file's own rule 9: where a child carries a fact, the child
wins and the umbrella points. Nothing was dropped -- the skip semantics, the 87-skip
accounting and the rename's four consequences are in `.todo/708`; the process draft is
`.todo/709`.

**The goal: a small language model that someone downloaded from Hugging Face runs on
rontolisp from the file they downloaded** -- no Python, no `export.py`, no conversion step
outside the language. `examples/llm` runs karpathy's `.bin`, a format one project writes;
the models people actually run are published as **safetensors** (bf16, one JSON header and
raw tensors) and **GGUF** (F32 / F16 / BF16 / Q8_0 / Q4_K_M, tokenizer and hyperparameters
in the same file). Checked 2026-09-03: SmolLM2-135M, TinyLlama-1.1B-Chat and Qwen2.5-0.5B
are each 100% BF16 in `model.safetensors`; no current small model is f16.

## What the measurements decided, width by width

| width | verdict | where |
| --- | --- | --- |
| **bf16** | THE width. 1.5-2.1x f32 on one thread (Graal / C2), 1.6x on 20; widening exact; every checkpoint is in it | `.todo/482` (483-490) |
| **IEEE f16** | not a width -- a **load-time conversion** into `#f` / `#bf16`. A fused f16 GEMV is 0.30-0.58x on either JIT; converting 1.1B elements costs 0.11-0.57 s | `.todo/671` |
| **Q8_0** (32 int8 + a scale) | a **read-only weight matrix** type with an integer-dot GEMV: 2.0x f32 / 1.15x bf16 on one thread, 1.9x on 20, a quarter of f32's bytes, 7.6e-3 GEMV error | `.todo/672`, closed; follow-up `.todo/706` |
| **Q4_0 / Q4_K** | not a CPU item: the nibble unpack is ALU-bound at 5.7 GB/s (1.1x f32 for 8.5% error). A device width | `.todo/490` |

Two facts under all four: **the width is bandwidth, not fitting** -- 4.4 GB of f32 fits
this 121 GB box and an 8 GB laptop, and a 1B decode is ~7 / 12 / 14 tok/s on one thread at
f32 / bf16 / Q8_0, ~21 / 34 / 39 on twenty -- and **every kernel number is JIT-dependent**:
the spike's fused kernel fell to 0.20x under C2 from an inlining cliff, so `.todo/488` takes
its numbers under both JITs.

## Children, and the order

| item | what | difficulty | state |
| --- | --- | --- | --- |
| `671` | f16 / bf16 **bits** widened in bulk into an existing width, every backend | Low | **closed 2026-09-03** |
| `673` | read a GGUF: metadata, tensor table, F32 / F16 / BF16 / Q8_0, tokenizer fields | Medium | **closed 2026-09-03** |
| `674` | the byte-level BPE tokenizer from GGUF fields or `tokenizer.json` | Medium | **closed 2026-09-03** |
| `676` | the forward pass as a table of layer kinds: QK-norm, NoPE, gates, partial RoPE | Medium | **closed 2026-09-03** |
| `678` | the LFM2 gated short-conv layer: LFM2.5-1.2B-Instruct | Medium | **closed 2026-09-05**, byte-identical to `llama.cpp` |
| `672` | the Q8_0 weight matrix and its integer-dot `vec:matvec` | High | **closed 2026-09-05**; one-thread follow-up is `.todo/706` |
| `675` | read a safetensors file (+ `config.json`) | Medium | reader done; the `#bf16` target waits on `487` steps 3-5 |
| `677` | the Gated DeltaNet layer: Qwen3.5-0.8B, and every Qwen 3.5-3.8 dense model | High | runs from both formats; bf16 `tok/s` unblocked since `488` landed |
| `489` | the model rungs: TinyLlama / SmolLM2, Qwen3-0.6B, LFM2.5-1.2B, Qwen3.5-0.8B | High | **f32 AND bf16 rungs measured on six models 2026-09-05** -- the result and its reading are in `489`, beside the prediction. The precondition was `488`'s wiring, landed 09-05 at `5eebb771`; the fused pairing is bf16 weights against f32 activations only, every other pairing declining to the scalar defun (`.todo/696`) |
| `490` | bf16 on the device | High | not started; GB10 only |

**Order: 671 -> 673 / 675 -> 674 -> 489 rung 0 at f32 -> 676 -> 678 -> 677 -> 482's
483-488 (only `487`'s remainder left) -> 489 at bf16 -> 672 (done) -> 490.** The point of
that order: 671 needs no new array type, so a BF16 checkpoint loads into `#f` BEFORE the
bf16 width exists and the readers are debugged at f32 with the kernels out of the picture.

## What runs today

**A published checkpoint runs, in three formats, no Python and no conversion step.**
Qwen3.5-0.8B from its BF16 safetensors AND from ggml-org's BF16 GGUF, **token for token
identical between the two**; TinyLlama-1.1B-Chat from safetensors and an F16 GGUF, same
forty tokens; stories15M converted to GGUF answers with `run.c`'s own text token for token
-- the one EXTERNAL oracle, and the one that caught a live bug (the architecture row was
matched by which options it carried, so the all-defaults `llama` row was rejected).

**The f32 rungs say what bf16 has to beat** (dorian, JVM class output, `--simd`):

| | 1 thread | `--parallel` | bytes/token | parallel bandwidth |
| --- | --- | --- | --- | --- |
| Qwen3.5-0.8B | 2.00-2.92 tok/s | 8.56 tok/s | 3.2 GB | 27.0 GB/s |
| TinyLlama-1.1B | 1.58-1.91 tok/s | 6.97 tok/s | 4.4 GB | 30.7 GB/s |

**"Two independent models on one ceiling" was wrong; corrected 2026-09-05 on four models,
quiet dorian.** At 32 threads LFM2.5-1.2B reaches 43.5 GB/s, TinyLlama-1.1B 39,
Qwen3.5-0.8B 26.9-29, Qwen3-0.6B 22. No box ceiling lives in that spread. What replaces it:

- **The parallel leg is bound by the parallel machinery** -- distribution, barriers,
  per-row dispatch -- **and how much a model pays depends on how its work is cut up.**
  Qwen3.5's Gated DeltaNet does 576 small 128x128 GEMVs per token; LFM2.5 does ~30 big
  matvecs. Dispatch cost rises with thread count while per-thread work shrinks, so a model
  paying more of it peaks earlier.
- **The signature is a model-specific SATURATION POINT**, carried entirely by within-model
  scaling -- tok/s over tok/s, same model, same binary, no byte estimate anywhere. 1 -> 32
  threads is 2.13 -> 9.30 for LFM2.5 (**4.37x**) against 2.95 -> 8.71 for Qwen3.5
  (**2.95x**); 8 -> 16 is x1.225 against x1.129, and 16 -> 32 is x1.053 against x0.994.
  **Qwen3.5 is saturated by 16 threads; LFM2.5 is still climbing at 32.** Say saturated,
  NOT "loses ground": 0.6% on a median of 3-7 runs with co-tenants is a plateau with noise,
  not a turnover.
- One-thread figures agree but cannot carry weight alone: 9.97 against 9.12 GB/s with
  overlapping spreads, and that is a cross-model GB/s comparison, so it divides by an
  activation-blind parameter-count estimate that omits exactly Qwen3.5's recurrent state.
  The error runs AGAINST the conclusion, which is why it is named here.

Three routes reach this now. Dorian's knee; GB10 measuring 41-42 Gelem/s at BOTH 4.2 MB
and 67 MB of weights, which a DRAM ceiling has no reason to bind identically (`.todo/488`'s
README); and **the strongest one, added 2026-09-05 by `489`'s bf16 rungs: the saturation
point did not move when the bytes halved.** On any of six models -- flat by 16 threads in
both arms for Qwen3.5, TinyLlama and both SmolLM2s, still climbing at 32 in both arms for
LFM2.5. If the parallel cap were DRAM, halving the bytes streamed per token would push the
knee outward; it did not budge. That leg is byte-estimate-free and it is a second WIDTH as
well as a second model set, so it is independent of the activation-blind estimate that
weakens the one-thread GB/s comparison.

**The clean discriminator is still unrun** and is now confirmatory rather than sole: a
parallel f32 GEMV at 256x256, small enough to be unambiguously cache-resident. If it lands
on the same rate, the cap is the machinery with no model involved at all -- `.todo/702`.

Carry two consequences: a parallel GEMV rate is a property of how the work was cut up, not
of the machine and not of the weights; and the f32 rows above are SOUND, re-measured quiet
and corroborated twice (8.92 / 8.71 against the recorded 8.56). The 10x collapse seen while
two lanes shared the box is `.todo/697`'s mechanism, not what 2026-09-03 recorded.

**Closed 2026-09-05, and it was not B's to take after all: `489` measured all six models
at bf16 the same day.** The prediction missed on both legs and the item had said in advance
what each miss meant. One thread was to "not move much" and moved 1.10x-1.26x; the parallel
leg was to roughly double and gave 1.03x-1.65x at 32 threads, nowhere near 2x. The fork the
item wrote for that case -- bandwidth diagnosis wrong, or a bf16 limit that is not
bandwidth -- is decided by the knee, above. **Numbers, conditions and reading are in
`489`; do not restate them here** (rule 9).

## The two machines, because every number here is one of them

- **`dorian`** -- Xeon E5-2697A v4, Broadwell x86-64, 64 threads, 251 GB, GraalVM 25.0.4,
  AVX2 256-bit, **no avx512**. Orchestrator A's box; no GPU.
- **GB10** -- aarch64 Cortex-X925, 20 cores, 121 GB, NEON 128-bit, CUDA. Orchestrator B's
  box, and the only one that can run the GPU legs.

A measurement without its base commit, JIT, machine and load average is not comparable to
another; a quiet window is per-box and each side takes its own.

**Checkpoints, per box.** On **dorian**, `/home/administrator/models/`; what each file is
lives in `.todo/677`'s "Checkpoint on dorian" block -- read it there (rule 9). On **GB10**,
`/home/maki/models/qwen35-gguf/` and `qwen35-hf/`, staged and hash-verified 2026-09-05; the
105 GB HF cache holds only `unsloth/Qwen3.8-Flash-Next-GGUF`, so any other GB10 lane
re-fetches (`llama.cpp` builds in about three minutes). None of it belongs in the repo, and
`examples/llm/.gitignore` is what keeps the two `stories15M` artefacts out -- see rule 12.

## The certification record

Head certified: **`d4225aa5`**, re-taken after `.todo/682`'s rename landed at `6a319b6a`,
which was AFTER both boxes' authoritative runs. The previous certified head was `080b3d75`
(09-03). Rule 8 is what decides a re-take: `git diff --stat 4358af09 <head> -- src/` gave 11
files and 22/22 lines, three in `src/main`, so the certification had lapsed.

| date | run | box | result | ran at |
| --- | --- | --- | --- | --- |
| 09-03 | full suite | dorian | 9977 / 0 / 0, 276 skipped, 227 reports | `656c170d` |
| 09-03 | full suite | GB10 | 9976 / 0 / 0, 189 skipped, 227 reports | `281fda90` |
| 09-03 | native `CiSpecE2eTest` | dorian | 1972 / 0 / 0, incl. bf16 `refusedOn` on both wasm backends | `656c170d` |
| 09-03 | llama2 e2e slices | dorian | 35 / 35 | `656c170d` |
| 09-03 | GPU legs, 6 classes | GB10 | 205 / 0 / 0, with `486` in | `281fda90` |
| 09-05 | full suite | GB10 | 10607 / 0 / 0, 189 skipped, 231 reports | `4358af09` |
| 09-05 | native `CiSpecE2eTest` | GB10 | 2000 / 0 / 0 | `4358af09` |
| 09-05 | `ExamplesE2eTest` llama2 slice | GB10 | 39 / 0 / 0 | `4358af09` |
| 09-05 | GPU classes within the suite | GB10 | `GpuTest` 57, `LinalgGpuTest` 40, 0 skipped | `4358af09` |
| 09-05 | full suite | dorian | 26359 / 0 / 0, 276 skipped, 231 reports | `b87aed25` |
| 09-05 | full suite, post-rename | GB10 | 10607 / 0 / 0, 189 skipped, 231 reports | `b87aed25` |
| 09-05 | native, post-rename | GB10 | 2000 / 0 / 0 | `d4225aa5` |
| 09-05 | `ExamplesE2eTest` `only=llm/` | dorian | 39 / 0 / 0, 3 skipped | `6a319b6a` |

**What these certify is failures, errors and the report-file count -- not the totals.** A
differing report count means a class was DROPPED rather than skipped, which no skip
accounting reveals; that is the check worth keeping.

Three things a reader needs before using this table, all of them measured and all of them
recorded in full in **`.todo/708`**:

1. **Totals are not comparable across boxes.** `LispFormatterTest` walks `Path.of(".")`
   filtering only `/target/` and `/ansi-test/suite/`, so it formats every `.lisp` under
   `.claude/worktrees/` -- dorian's 26359 against GB10's 10607 on the same source is 25
   stale agent worktrees against 1. One term of the comparison is how many agents ran on
   that box recently.
2. **Skips ARE comparable and the 09-03 accounting of them was wrong by selection, not
   arithmetic.** `276 - 189 = 87` is sound; the five classes named to explain it summed to
   111. Diffing both complete censuses gives **seventeen** differing classes, +120 / -33,
   netting exactly 87. `MetalGpuTest` skips 54 on both boxes and cancels. Contamination
   does not reach skips: between two runs whose totals differ by 16510 the skip counts did
   not move at all.
3. **A skip count is only a signal against a prior skip count for the same slice.** The two
   `39 / 0 / 0` rows above differ only by `3 skipped`, and those three legs are the ones
   loading the renamed example's real 60 MB checkpoint -- so **682 was accepted by a run
   that skipped the part of the suite the rename was most likely to break**. `Tests run` is
   invariant under skipping and not under deletion (surefire counts a skipped test as run:
   `MetalGpuTest`, `Tests run: 54, Skipped: 54`), so a deletion would have read `36` and
   put the delta in the number everyone reads.

**Why the 09-05 gap existed is worth more than the numbers.** Nobody skipped an assigned
step: one box ran the suite before the last change landed, the other ran an acceptance slice
covering that change's own surface, and the COMBINATION was what nothing covered. Second
instance in one day of coverage falling into the seam between two correct plans; `.todo/709`
Part 2 keeps those separate from record failures.

The 09-05 native pass had the same shape from the other direction: `672` and `690` each
added a `ci-spec.yaml` case and `691` changed one, and NONE had run on a native binary --
every lane was told to skip it because one merged pass would cover all four, and the
session owing that pass ended. **A verification owed by one party and skipped by everyone
else is a gap that looks exactly like coverage until someone checks who actually ran it.**

## Findings from the run, and where each one now lives

**Pointers, not records** -- the home is where it gets updated.

- **`.todo/483`'s rule is stated wrong in 483** -- not "never write a `default`" but **"an
  arm matching two or more permits IS a default, whatever it is spelled"**. Nine sites
  audited, seven correct, two wrong. In `.kb/vec.md`; 483's own text still states the old
  rule.
- **`%la-gather-strided` has five readers** and grepping the name finds two; one miss
  returned a `float[]` for a double gather. Account and pinning hole: `.todo/687`.
- **Seven sites hand-write the bf16 conversion arithmetic**, only
  `am.ik.rontolisp.BFloat16` is the authority, and three of the seven lost the same 126
  signalling-NaN patterns on one day for three different reasons. Census: `.todo/487`'s
  remainder, which now also covers transcribed WIDTH DECISIONS.
- **`.kb/string-index-cost.md`** records what `690`'s 340x is and is not: `llama2.lisp`'s
  load did not get faster, because it carried its own byte reader precisely because
  `json-parse` could not finish.

## Lanes for the week of 2026-09-15: ONE worker per orchestrator

The two-worker arrangement ran 2026-09-08 to 09-05 and is over. **From here each
orchestrator drives ONE lane at a time, serialized: an item completes, is committed and
pushed, and only then does the next start.** What that buys is the thing two lanes cost --
the surface-accounting overhead in `.todo/709` exists entirely because two lanes on one box
can touch one mechanism without either seeing the other.

Model by difficulty, `effort=high` throughout: **High -> Fable, Medium -> Opus, Low ->
Sonnet.** A dead worker is RESUMED, never respawned.

**Orchestrator B -- GB10, the width chain and the device.** In order:

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| B-1 | `708` the formatter corpus walks `.claude/worktrees/` | Low | Two blocks of this file say "do not compare totals until 708 lands". One filter line plus a pin; it also lifts the standing caveat off every future certification. Do #3 (25 stale worktrees) is cleanup, NOT part of the fix -- the fix must work with them present |
| B-2 | `702` is the parallel cap machinery or memory | Low | One benchmark on a cleared GB10. `489`'s knee-invariance already answers it at a second width, so this is no longer the sole evidence -- but it is the only leg with NO model in it, and a 256x256 f32 GEMV landing on the same 41-42 Gelem/s closes the question outright. Needs a verified-quiet box: load < 1.5, checked, not assumed |
| B-3 | `707` `coerce` / `concatenate` drop a packed FLOAT element type | Medium | A live correctness defect at all three float widths, on the width chain B owns. Verified independently 09-05 |
| B-4 | `710` a closed item's artefacts and an open item share one namespace | Medium | The path-citation link check FIRST -- it is what makes the rename safe and earns its place alone. **Fold in the live duplicate found 09-05: `338-ansi-conformance-the-ranked-gap.md` and `338-string-concat-renders-through-the-value-printer.md` are both open on one number**; `.todo/.history.md` says the later commit's side renumbers |
| B-5 | `490` bf16 on the device | High | The last child of the width chain, and GB10 is the only box that can run it |
| B-6 | `706` the Q8_0 integer-dot GEMV is instruction-bound on one thread | High | Falls straight out of `672`'s closure and is a kernel item, so it is B's |

**Not lanes, decisions to take at planning before B-1:**

- **`.todo/709` is an explicit DRAFT and needs co-signing or cutting by both
  orchestrators.** It is process, so one side adopting it unilaterally is the failure it
  is written about.
- **A's unfiled item is now filed as `711`** (the four mechanisms as a `.kb` card) and
  `712` (the `-m chat` defect). `711` deliberately does NOT carry the reading disciplines
  that caught them -- diff the lists rather than reasoning about which terms ought to
  differ; a sum that closes is not evidence about its terms; relay a census from the file
  with its total AND its class count. Those are process, not rename-specific, and they
  belong to the `709` co-sign above or nowhere.
- **The one-thread bf16 ratio** (1.18x / 1.26x against `489`'s prediction) is B's to take
  into `489` rather than to run as a lane.

**Orchestrator A -- dorian, the model side, no GPU.** `489`'s bf16 rungs FINISHED on
09-05 after this section was drafted: six models at both widths, pushed at `8ba82c8d`, the
reading beside the prediction in `489` and the summary in `examples/llm/README.md`. In
order:

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| A-1 | close `489` | Low | The Done criteria are met and the lane deliberately left closing to lane design: it needs the child table above, the `675` / `677` cross-references, and standing rule 11's sweep for items waiting on the CAPABILITY ("a 1B-class model runs at bf16"), which no grep for the number will find. Small, and it unblocks reading the chain's state |
| A-2 | `712` `-m chat` with no template answers a different question | Low | Filed 09-05 out of the rungs. It cost twelve discarded timed runs and left a suspect pair of rows on develop; A runs every future rung, so A pays again until it is fixed. One condition plus a failing test on the checked-in `stories260K` |
| A-3 | `675` read a safetensors checkpoint | Medium | Unblocked: the `#bf16` target was waiting on `487` steps 3-5, and `487` closed 09-05 at `5eebb771`. Read the two JIT cliffs pointed at from the item before writing lane code -- `488`'s C2 inlining cliff and `672`'s finding that Graal 25 does not intrinsify the int-to-double lane conversion |
| A-4 | `677` the Gated DeltaNet layer | High | Runs from both formats already; the bf16 `tok/s` leg has been unblocked since `488` landed, and `489` has now measured the width on six models, so the remaining work is this layer's own |
| A-5 | `711` what a directory rename breaks outside its own diff | Medium | Filed 09-05, the `.kb` card owed from `708`'s account. Last deliberately: its value does NOT decay, because `708` holds the evidence durably -- so it loses nothing by waiting behind work that does |

**`489` stays OPEN until A-1**, not because anything is unmeasured but because closing it is
the sweep, and the lane that measured it was right to refuse to do that from inside a
worktree.

**Unassigned pool, neither side's yet:** `693` and `694` (filed 09-03, never in a wave),
`683`, `684`, `686`, `687`, `689`, `696`, `697`, `698`, `699`, `700`, `701`, `703`, `704`,
`705`, `597`, `695`.

## Standing rules this run earned, in the order they cost the most

1. **Only the closer can write back a dependency.** Six items closed 09-03 and twelve open
   todos still read as blocked by them that afternoon. The grep for items naming the number
   now sits beside the history row in the close procedure.
2. **A count an item wrote down is not a completion test.** A stale dependency line delays a
   start; **a stale count fakes a finish**. Fix the 86 sites `.todo/683` names and it reads
   as done, though two more appeared while `485` was worked. Start an audit from the grep,
   never the number.
3. **Sort every "Remaining" into blocked / not-done / deferred.** Only the first is a real
   remainder; the second is unstarted work in a blocker's clothes; the third evaporates
   without an owner and a date. Two of nine were truly blocked.
4. **One session runs the full suite on `develop`, the other runs the GPU legs.** Three reds
   were invisible from every lane's own worktree.
5. **Never two device-touching runs at once, separately from who owns what.** `./mvnw test`
   includes `GpuTest`, so a full suite IS device-touching. Fused with rule 4 it produced a
   self-contradictory instruction to one lane on 09-03. Ownership says who takes a result;
   exclusion says what may run at once.
6. **A suite can hold a defect invisibly while every case sits on one side of its
   condition, and the half that looks more exhaustive is the half that hides it.** Three in
   one day: the 1496-error regression, `PRINT_OBJECT_VECTOR_ARM` excluding a packed width by
   name, and `692` against a `671` that closed claiming all four backends while its tests
   counted backends and never `--simd` on each (`.todo/694`).
7. **A rule one lane derives from one measurement is a hypothesis until the other lane has
   tried to break it.** Three corrections in one day, each of which would have entered `.kb`
   as a law with only one lane working. What survives from the first: a failure count's SIZE
   narrows the SEARCH, never the VERDICT. `.kb/measurement-probes.md`. **Say it to the other
   lane before writing it into `.kb`.**
8. **A run certifies a head it did not run against when the FILE SET says so, never the
   elapsed time.** `git diff --stat <ran-at> <head> -- src/` empty means a re-run would only
   re-measure `.todo/` edits. One command, and it is the whole argument.
9. **An umbrella's status paragraph is evidence only where no child covers the same fact.**
   Where a child does, the child wins and the umbrella POINTS. The expensive direction of
   rule 1: this file said "the checkpoints are gone" while `.todo/677` had carried the
   correct paths the whole time, and two lanes were sent to re-download 12 GB that was on
   disk. A restated fact also decays PER BOX -- rewritten as "they survived" it would have
   been wrong for GB10, and that direction is worse, because it skips a needed re-fetch
   rather than repeating an unneeded one.
10. **Record a checkpoint's SIZE and sha256 beside its path**, because provenance is
    recoverable from the file but not the file from the provenance. Hugging Face answers
    `/api/models/<id>?blobs=true` with the LFS sha256, so a checkpoint whose repo path was
    lost is re-identified by matching bytes already held. `ggml-org/Qwen3.5-0.8B-GGUF` came
    back that way 09-05: two digests off dorian matched that manifest, and GB10's
    independent fetch hashed to the same two. **The digest and the refusal to guess are two
    independent goods** -- refusing to guess is why the id is VERIFIED; the digest is why a
    guess would have been SURVIVABLE. What a guess costs is the QUESTION: a written repo id
    reads as known, so nobody queries the manifest. State the mechanical half first, because
    "do not guess" is advice about judgement and the next person under time pressure will
    violate it.
11. **A closer must check for items waiting on an EVENT, not only for items naming its
    number, and no grep finds those.** `.todo/682` was gated on "the first published
    checkpoint that runs end to end". It fired THREE times and nobody noticed, because the
    trigger lived in 682 while the people firing it were closing `677` and `678`. What
    works: when a Done section describes a capability arriving for the first time, **grep
    `.todo/` for the CAPABILITY** -- the format, the model class, the surface -- not the
    number.
12. **A directory-local ignore rule protects by LOCATION, so moving the rule stops
    protecting whatever stayed** -- and what stayed is invisible to the rename precisely
    because being ignored is what kept it out of the rename. 682 moved
    `examples/llama2/.gitignore` correctly and the next `git add` swept 61 MB of untracked
    checkpoint onto develop. **The tree has 18 directory-local `.gitignore` files**, several
    covering whole build trees, so this is a standing property and not a llama2 anecdote.
    The check is one command at the one moment the files are visible: **after moving a
    directory that contains a `.gitignore`, run `git status --porcelain` for untracked files
    at the OLD path before the next `git add`.** And the fix is free only on the box that
    makes it -- untracking DELETES the file for every puller who had it. Full account, with
    the other three things that rename broke outside its own diff: `.todo/708`.

## What is deliberately not in the plan

- **Not an inference framework.** The forward pass stays one Lisp file; the layer became a
  KIND with options (`676`) and two more kinds joined it (`677`, `678`) only because the
  newest small models are hybrids. Gemma 4 waits until asked for.
- **Not mixed-precision training.** `torch:` stays f32/f64; bf16 is a storage width for
  weights, and nothing here changes what an activation is.
- **Not the device.** `--gpu` declines every new type until `.todo/490`; declining correctly
  is what `.todo/483`'s exhaustive switches buy.
- **Not fp8 / int4 on the CPU.** Measured out; re-measure only when the Vector API grows a
  dot-product or a narrower conversion, or on a host whose JIT beats 1 op/element for the
  unpack.
