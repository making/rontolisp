# 705. No suite asserts on decoded text for any model

Difficulty: Medium

Filed 2026-09-05 from `.todo/670`'s week. The finding that produced it: a `src/main`
change altered **Qwen3.5-0.8B's f32 greedy decode** -- the same prompt ids and the same
f32 weights produced "### Barnaby the Cat" on one jar and "Here is a short story about a
cat named **" on another. Both outputs are coherent English. The full suite was green on
both sides, and it was found only because a lane happened to compare two traces by hand.

**The hole, stated exactly: no test anywhere asserts on the DECODED TEXT of any model.**
Every existing check is a token count, a tensor shape, a refusal message, or a comparison
of two of our own outputs against each other -- all of which agree happily while the text
underneath them changes. A change to a reduction order, an accumulator boundary, or a
routing decision moves one argmax, and one moved argmax is a different sentence that
still reads like English.

## What to build

**A decoded-text assertion on a fixture the repo can actually run**: `stories260K` or
`stories15M`, greedy, a fixed prompt, the exact expected text written into
`src/test/resources/ci-spec.yaml`. Small enough to live in CI, runs on every backend the
spec already drives, and fails loudly the moment an argmax moves.

`ci-spec.yaml` is the right home rather than a unit test: cases there run on all four
backends and are already the single source of truth for cross-backend output
(`CLAUDE.md`, "Requirements"). A moved argmax that appears on one backend and not another
is precisely what that harness exists to catch.

## The honest limitation, which is why this is not the whole answer

A tiny model exercises fewer paths. **`stories15M` is a plain llama and not a Gated
DeltaNet, so this fixture would NOT have caught the defect that prompted it.** The
affected path was DeltaNet-only: TinyLlama, SmolLM2 and Qwen3-0.6B were all unchanged
across the two jars, and only Qwen3.5 moved.

So the shape is two halves and only one of them is testable today:

- **This item**: a fixture assertion that catches the common paths automatically, on
  every backend, forever. Cheap, and it makes the class rare.
- **Recorded observation, not a pin**: the Qwen3.5-0.8B greedy output recorded in
  `.todo/489` beside the token counts, for the DeltaNet path. The checkpoint is ~3 GB and
  external to the repo, so no suite can run it -- it is findable by someone who thinks to
  look, which is strictly weaker than detectable. Closing THAT half needs either a small
  DeltaNet fixture or a way to carry a checkpoint hash, and neither is in scope here.

State the limitation in whatever lands. A fixture assertion that is described as covering
decoded text, and does not cover the architecture whose defect motivated it, is the shape
`.todo/670` rule 6 warns about -- a suite that looks more exhaustive than it is.

## Not in scope

- Fixing the Qwen3.5 f32 decode difference. As of filing it is not established to BE a
  defect: `.todo/488`'s diff changes no f32 reduction order, lane count or accumulator
  structure, and the two live hypotheses are a mixed/incrementally-built `target/` and a
  call site emitting different bytecode. That investigation belongs where it lands.
- Any new test surface for the large checkpoints. They are not in the repo and must not be.
