# 670. Run a published SLM checkpoint: what Hugging Face ships, loaded as shipped

Difficulty: High (the umbrella; the children are sized individually)

Filed 2026-09-03 from the re-verification of `.todo/482` (`bfloat16`), whose README "Round
2" is the measurement record; `.todo/482` stays the width half of this.

**The goal: a small language model that someone downloaded from Hugging Face runs on
rontolisp from the file they downloaded** -- no Python, no `export.py`, no conversion step
outside the language. `examples/llm` runs karpathy's `.bin`, a format one project writes;
the models people actually run are published as **safetensors** (bf16, one JSON header and
raw tensors) and **GGUF** (F32 / F16 / BF16 / Q8_0 / Q4_K_M, tokenizer and hyperparameters
in the same file). Checked 2026-09-03: SmolLM2-135M, TinyLlama-1.1B-Chat and Qwen2.5-0.5B
are each 100% BF16 in `model.safetensors`; no current small model is f16.

**This file is the plan and the rules. Every number it quotes has a child that owns it**
(rule 9) -- go there to change one.

## What the measurements decided, width by width

| width | verdict | where |
| --- | --- | --- |
| **bf16** | THE width. 1.5-2.1x f32 on one thread (Graal / C2), 1.6x on 20; widening exact; every checkpoint is in it | `.todo/482` (483-490) |
| **IEEE f16** | not a width -- a **load-time conversion** into `#f` / `#bf16`. A fused f16 GEMV is 0.30-0.58x on either JIT | `.todo/671` |
| **Q8_0** (32 int8 + a scale) | a **read-only weight matrix** type with an integer-dot GEMV: 2.0x f32 / 1.15x bf16 on one thread, 1.9x on 20, a quarter of f32's bytes | `.todo/672`, closed; follow-up `.todo/706` |
| **Q4_0 / Q4_K** | not a CPU item: the nibble unpack is ALU-bound at 5.7 GB/s (1.1x f32 for 8.5% error). A device width | `.todo/490` |

Two facts under all four: **the width is bandwidth, not fitting** -- 4.4 GB of f32 fits an
8 GB laptop -- and **every kernel number is JIT-dependent**: the spike's fused kernel fell
to 0.20x under C2 from an inlining cliff, so `.todo/488` takes its numbers under both JITs.

## Children, and the order

| item | what | difficulty | state |
| --- | --- | --- | --- |
| `671` | f16 / bf16 **bits** widened in bulk into an existing width, every backend | Low | **closed** 09-03 |
| `673` | read a GGUF: metadata, tensor table, F32 / F16 / BF16 / Q8_0, tokenizer fields | Medium | **closed** 09-03 |
| `674` | the byte-level BPE tokenizer from GGUF fields or `tokenizer.json` | Medium | **closed** 09-03 |
| `676` | the forward pass as a table of layer kinds: QK-norm, NoPE, gates, partial RoPE | Medium | **closed** 09-03 |
| `678` | the LFM2 gated short-conv layer: LFM2.5-1.2B-Instruct | Medium | **closed** 09-05, byte-identical to `llama.cpp` |
| `672` | the Q8_0 weight matrix and its integer-dot `vec:matvec` | High | **closed** 09-05; one-thread follow-up is `.todo/706` |
| `675` | read a safetensors file (+ `config.json`) | Medium | reader done; the `#bf16` target was waiting on `487`, which closed 09-05 |
| `677` | the Gated DeltaNet layer: Qwen3.5-0.8B, and every Qwen 3.5-3.8 dense model | High | runs from both formats; bf16 `tok/s` unblocked since `488` |
| `489` | the model rungs: TinyLlama / SmolLM2, Qwen3-0.6B, LFM2.5-1.2B, Qwen3.5-0.8B | High | **f32 and bf16 measured on six models 09-05**; result and reading in `489`. The fused pairing is bf16 weights against f32 activations only, every other pairing declining to the scalar defun (`.todo/696`) |
| `490` | bf16 on the device | High | not started; GB10 only |

**Order: 671 -> 673 / 675 -> 674 -> 489 rung 0 at f32 -> 676 -> 678 -> 677 -> 487 -> 489 at
bf16 -> 672 -> 490.** The point of it: 671 needs no new array type, so a BF16 checkpoint
loads into `#f` BEFORE the bf16 width exists and the readers are debugged at f32 with the
kernels out of the picture.

## What runs today

**A published checkpoint runs, in three formats, no Python and no conversion step.**
Qwen3.5-0.8B from its BF16 safetensors AND from ggml-org's BF16 GGUF, **token for token
identical between the two**; TinyLlama-1.1B-Chat from safetensors and an F16 GGUF, same
forty tokens; stories15M converted to GGUF answers with `run.c`'s own text token for token
-- the one EXTERNAL oracle, and the one that caught a live bug.

**The parallel leg is bound by the parallel machinery, not by DRAM.** Distribution,
barriers and per-row dispatch, and **how much a model pays depends on how its work is cut
up**: Qwen3.5's Gated DeltaNet does 576 small 128x128 GEMVs per token, LFM2.5 does ~30 big
matvecs, so the one paying more dispatch peaks earlier. The signature is a model-specific
SATURATION POINT, carried by within-model scaling only -- tok/s over tok/s, no byte
estimate anywhere.

Three independent routes reach it:

1. **The knee did not move when the bytes halved** (`489`, six models, two widths) -- flat
   by 16 threads in both arms for Qwen3.5, TinyLlama and both SmolLM2s, still climbing at
   32 in both for LFM2.5. A DRAM cap would have pushed the knee outward. **This leg is
   byte-estimate-free and is the strongest.**
2. Dorian's knee at f32 alone: 1 -> 32 threads is **4.37x** for LFM2.5 against **2.95x**
   for Qwen3.5.
3. GB10 measuring 41-42 Gelem/s at BOTH 4.2 MB and 67 MB of weights, which a DRAM ceiling
   has no reason to bind identically (`488`'s README).

A cross-model GB/s comparison is NOT among them: it divides by an activation-blind
parameter-count estimate that omits exactly Qwen3.5's recurrent state.

**The clean discriminator is still unrun** and is now confirmatory rather than sole: a
parallel f32 GEMV at 256x256, unambiguously cache-resident. Same rate means the machinery,
with no model in it at all -- `.todo/702`.

Carry one consequence: **a parallel GEMV rate is a property of how the work was cut up**,
not of the machine and not of the weights. The 10x collapse seen while two lanes shared a
box is `.todo/697`'s mechanism, not a property of anything measured here.

## The two machines, because every number here is one of them

- **`dorian`** -- Xeon E5-2697A v4, Broadwell x86-64, 64 threads, 251 GB, GraalVM 25.0.4,
  AVX2 256-bit, **no avx512**. Orchestrator A's box; no GPU.
- **GB10** -- aarch64 Cortex-X925, 20 cores, 121 GB, NEON 128-bit, CUDA. Orchestrator B's
  box, and the only one that can run the GPU legs.

A measurement without its base commit, JIT, machine and load average is not comparable to
another; a quiet window is per-box and each side takes its own.

**Checkpoints, per box.** On **dorian**, `/home/administrator/models/`; what each file is
lives in `.todo/677`'s "Checkpoint on dorian" block (rule 9). On **GB10**,
`/home/maki/models/qwen35-gguf/` and `qwen35-hf/`, hash-verified 09-05; the HF cache holds
only `unsloth/Qwen3.8-Flash-Next-GGUF`, so any other GB10 lane re-fetches. None of it
belongs in the repo, and `examples/llm/.gitignore` is what keeps the two `stories15M`
artefacts out -- see rule 12.

## The certification record

**Head certified: `d4225aa5`.** Both boxes green there: GB10 full suite 10607 / 0 / 0 with
231 reports, native `CiSpecE2eTest` 2000 / 0 / 0; dorian full suite 0 failures with 231
reports. Rule 8 decides when that lapses.

**What a run certifies is failures, errors and the REPORT-FILE COUNT -- never the totals.**
A differing report count means a class was DROPPED rather than skipped, which no skip
accounting reveals.

Three things a reader needs before comparing any two runs, **all measured and all recorded
in full in `.todo/708`** (rule 9 -- go there, do not re-derive them here):

1. **Totals are not comparable across boxes** until 708 lands: `LispFormatterTest` walks
   `Path.of(".")` and formats every `.lisp` under `.claude/worktrees/`, so one term of the
   comparison is how many agents ran on that box recently.
2. **Skips ARE comparable**, and the 09-03 accounting of them was wrong by SELECTION, not
   arithmetic: diffing both complete censuses gives seventeen differing classes netting
   exactly 87. Contamination does not reach skips.
3. **A skip count is only a signal against a prior skip count for the same slice.** Its
   designed meaning and its defect meaning are the same integer. `Tests run` is invariant
   under skipping and not under deletion, so a skipped leg keeps the headline total while
   removing the coverage -- which is how `682` came to be accepted by a run that skipped
   the part of the suite the rename was most likely to break.

Twice on 09-05 coverage fell into the SEAM between two correct plans -- nobody skipped an
assigned step, and the combination was what nothing covered. **A verification owed by one
party and skipped by everyone else is a gap that looks exactly like coverage until someone
checks who actually ran it.** `.todo/709` Part 2 keeps that kind separate from record
failures.

## Findings from the run, and where each one now lives

Pointers, not records -- the home is where it gets updated.

- **`483`'s rule is stated wrong in 483**: not "never write a `default`" but **"an arm
  matching two or more permits IS a default, whatever it is spelled"**. In `.kb/vec.md`.
- **`%la-gather-strided` has five readers** and grepping the name finds two. `.todo/687`.
- **Seven sites hand-write the bf16 conversion arithmetic** and only
  `am.ik.rontolisp.BFloat16` is the authority. Census: `.todo/487`'s remainder.
- **`.kb/string-index-cost.md`** records what `690`'s 340x is and is not.

## Lanes for the week of 2026-09-15: ONE worker per orchestrator

The two-worker arrangement ran 2026-09-08 to 09-05 and is over. **From here each
orchestrator drives ONE lane at a time, serialized: an item completes, is committed and
pushed, and only then does the next start.** What that buys is the thing two lanes cost --
the surface-accounting overhead in `.todo/709` exists entirely because two lanes on one box
can touch one mechanism without either seeing the other.

Model by difficulty, `effort=high` throughout: **High -> Fable, Medium -> Opus, Low ->
Sonnet.** A dead worker is RESUMED, never respawned.

**Orchestrator A -- dorian, the model side, no GPU.** `489`'s bf16 rungs finished 09-05:
six models at both widths, the reading beside the prediction in `489`, the summary in
`examples/llm/README.md`. In order:

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| A-1 | close `489` | Low | Done criteria met; the lane deliberately left closing to lane design. It needs the child table above, the `675` / `677` cross-references, and rule 11's sweep for items waiting on the CAPABILITY ("a 1B-class model runs at bf16"), which no grep for the number finds |
| A-2 | `712` `-m chat` with no template answers a different question | Low | Cost twelve discarded timed runs and left a suspect pair of rows on develop. A runs every future rung, so A pays again until it is fixed. One condition plus a failing test on the checked-in `stories260K` |
| A-3 | `675` read a safetensors checkpoint | Medium | Unblocked: the `#bf16` target was waiting on `487`, which closed 09-05. Read the two JIT cliffs the item points at before writing lane code -- `488`'s C2 inlining cliff, and `672`'s finding that Graal 25 does not intrinsify the int-to-double lane conversion |
| A-4 | `677` the Gated DeltaNet layer | High | Runs from both formats already and the bf16 `tok/s` leg is unblocked, so the remaining work is this layer's own |
| A-5 | `711` what a directory rename breaks outside its own diff | Medium | The `.kb` card owed from `708`'s account. Last deliberately: its value does NOT decay, because `708` holds its evidence durably, so it loses nothing by waiting behind work that does |

**`489` stays OPEN until A-1** -- not because anything is unmeasured, but because closing it
IS the sweep, and the lane that measured it was right to refuse to do that from a worktree.

**Orchestrator B -- GB10, the width chain and the device.** In order:

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| B-1 | `708` the formatter corpus walks `.claude/worktrees/` | Low | Two blocks of this file say "do not compare totals until 708 lands". One filter line plus a pin; it lifts the standing caveat off every future certification. Do #3 (25 stale worktrees) is cleanup, NOT part of the fix -- the fix must work with them present |
| B-2 | `702` is the parallel cap machinery or memory | Low | One benchmark on a cleared GB10. `489`'s knee-invariance already answers it at a second width, so this is no longer sole evidence -- but it is the only leg with NO model in it. Needs a verified-quiet box: load < 1.5, checked, not assumed |
| B-3 | `707` `coerce` / `concatenate` drop a packed FLOAT element type | Medium | A live correctness defect at all three float widths, on the width chain B owns |
| B-4 | `710` a closed item's artefacts and an open item share one namespace | Medium | The path-citation link check FIRST -- it is what makes the rename safe and earns its place alone. **Fold in the live duplicate: `338-ansi-conformance-the-ranked-gap.md` and `338-string-concat-renders-through-the-value-printer.md` are both open on one number**; `.todo/.history.md` says the later commit's side renumbers |
| B-5 | `490` bf16 on the device | High | The last child of the width chain, and GB10 is the only box that can run it |
| B-6 | `706` the Q8_0 integer-dot GEMV is instruction-bound on one thread | High | Falls straight out of `672`'s closure and is a kernel item |

**Decisions to take at planning, before either lane starts:**

- **`.todo/709` is an explicit DRAFT and needs co-signing or cutting by both
  orchestrators.** It is process, so one side adopting it unilaterally is the failure it is
  written about.
- **The one-thread bf16 ratio** (1.10x-1.26x across six models against `489`'s "does not
  move much") is B's to take into `489` rather than to run as a lane.
- `711` deliberately does NOT carry the general reading disciplines -- diff the lists rather
  than reasoning about which terms ought to differ; a sum that closes is not evidence about
  its terms; relay a census from the file with its total AND its class count. Those are
  process: they belong to the `709` co-sign or nowhere.

**Unassigned pool, neither side's yet:** `693`, `694`, `683`, `684`, `686`, `687`, `689`,
`696`, `697`, `698`, `699`, `700`, `701`, `703`, `704`, `705`, `597`, `695`.

## Standing rules this run earned, in the order they cost the most

Cited by number from other items -- **the numbering is fixed.**

1. **Only the closer can write back a dependency.** Six items closed 09-03 and twelve open
   todos still read as blocked by them that afternoon. The grep for items naming the number
   belongs beside the history row in the close procedure.
2. **A count an item wrote down is not a completion test.** A stale dependency line delays a
   start; **a stale count fakes a finish.** Start an audit from the grep, never the number.
3. **Sort every "Remaining" into blocked / not-done / deferred.** Only the first is a real
   remainder; the second is unstarted work in a blocker's clothes; the third evaporates
   without an owner and a date. Two of nine were truly blocked.
4. **One session runs the full suite on `develop`, the other runs the GPU legs.** Three reds
   were invisible from every lane's own worktree.
5. **Never two device-touching runs at once, separately from who owns what.** `./mvnw test`
   includes `GpuTest`, so a full suite IS device-touching. **Ownership says who takes a
   result; exclusion says what may run at once** -- fusing the two produced a
   self-contradictory instruction to one lane.
6. **A suite can hold a defect invisibly while every case sits on one side of its
   condition, and the half that looks more exhaustive is the half that hides it.** Three in
   one day, including `692` against a `671` that closed claiming all four backends while its
   tests counted backends and never `--simd` on each (`.todo/694`).
7. **A rule one lane derives from one measurement is a hypothesis until the other lane has
   tried to break it.** Three corrections in one day, each of which would otherwise have
   entered `.kb` as a law. What survives from the first: a failure count's SIZE narrows the
   SEARCH, never the VERDICT (`.kb/measurement-probes.md`). **Say it to the other lane
   before writing it into `.kb`.**
8. **A run certifies a head it did not run against when the FILE SET says so, never the
   elapsed time.** `git diff --stat <ran-at> <head> -- src/` empty means a re-run would only
   re-measure `.todo/` edits. One command, and it is the whole argument.
9. **An umbrella's status paragraph is evidence only where no child covers the same fact.**
   Where a child does, the child wins and the umbrella POINTS. This file once said "the
   checkpoints are gone" while `.todo/677` carried the correct paths, and two lanes were
   sent to re-download 12 GB that was on disk. **A restated fact also decays PER BOX**, and
   that direction is worse: it skips a needed re-fetch rather than repeating an unneeded
   one.
10. **Record a checkpoint's SIZE and sha256 beside its path**, because provenance is
    recoverable from the file but not the file from the provenance. Hugging Face answers
    `/api/models/<id>?blobs=true` with the LFS sha256, so a checkpoint whose repo path was
    lost is re-identified by matching bytes already held. **The digest and the refusal to
    guess are two independent goods**: refusing to guess is why an id is VERIFIED, the
    digest is why a guess would have been SURVIVABLE. What a guess costs is the QUESTION --
    a written repo id reads as known, so nobody queries the manifest. State the mechanical
    half first, because "do not guess" is advice about judgement and the next person under
    time pressure will violate it.
11. **A closer must check for items waiting on an EVENT, not only for items naming its
    number, and no grep finds those.** `.todo/682` was gated on "the first published
    checkpoint that runs end to end"; it fired THREE times unnoticed, because the trigger
    lived in 682 while the people firing it were closing `677` and `678`. What works: when a
    Done section describes a capability arriving for the first time, **grep `.todo/` for the
    CAPABILITY** -- the format, the model class, the surface -- not the number.
12. **A directory-local ignore rule protects by LOCATION, so moving the rule stops
    protecting whatever stayed** -- and what stayed is invisible to the rename precisely
    because being ignored is what kept it out of it. 682 moved `examples/llama2/.gitignore`
    correctly and the next `git add` swept 61 MB onto develop. **The tree has 18
    directory-local `.gitignore` files**, several covering whole build trees, so this is a
    standing property. The check is one command at the one moment the files are visible:
    **after moving a directory that contains a `.gitignore`, run `git status --porcelain`
    for untracked files at the OLD path before the next `git add`.** And the fix is free
    only on the box that makes it -- untracking DELETES the file for every puller who had
    it. Full account, with the other three things that rename broke outside its own diff:
    `.todo/708`, and the card owed from it is `.todo/711`.

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
