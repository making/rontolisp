# 705. No suite asserts on decoded text for any model

Difficulty: Medium

Filed 2026-09-05 from `.todo/670`'s week. **The incident that produced it was not a
defect, and the correction is why the item is worth more, not less.**

What was reported: a `src/main` change had altered Qwen3.5-0.8B's f32 greedy decode --
"### Barnaby the Cat" on one jar against "Here is a short story about a cat named **" on
another, same prompt ids, same weights. What was actually happening: those are the HEAD
of a 34-token run and the TAIL of a 64-token run **of one identical text**. The full
64-token output is "Here is a short story about a cat named **Barnaby**. ... ### Barnaby
the Cat ...". Two views of one string, read as two strings.

Established since, and recorded so nobody re-opens it: both clean jars (`2275c000` and
`1cb95b03`) against the original source, serial and `--parallel` at 32 and 1 threads,
twice each -- all identical; and an f32 `vec:` kernel probe (sum / dot / matvec /
matvec-into / element-wise, four shapes, both backends) is **bit-identical between the
jars**. `.todo/488` is exonerated by measurement as well as by its diff.

**The gap this item names survives the retraction untouched, and the episode is now its
best argument.** A lane spent a build-and-bisect cycle deciding whether the decoder had
changed, and could not answer it from the repo -- because there is nothing to run that
says yes or no. With the assertion below, the first command would have printed green and
the question would have closed in seconds. The hole is not "a defect got through"; it is
**"we cannot cheaply tell whether one did"**, which is the condition that makes both a
real change and a phantom one expensive.

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
  look, which is strictly weaker than detectable.

Three ways to close THAT half, and the third is probably cheapest. Price them before
starting:

1. A small real DeltaNet checkpoint. None exists at a size the repo can carry.
2. Carry a checkpoint hash and skip when the file is absent. Cheap, but the pin only runs
   where someone has downloaded 3 GB, so CI never runs it.
3. **SYNTHESISE one.** A regression pin does not need a TRAINED model, it needs
   deterministic output through the DeltaNet path: a toy Gated DeltaNet from a fixed seed
   -- a few layers, a tiny hidden size, a few hundred vocabulary entries -- a fixed
   prompt, and an assertion on the exact token ids. **The text will be garbage, and that
   is fine**: garbage that changes when a reduction order moves is the same alarm the real
   model would give. A few hundred KB, or generated deterministically at test time and
   never committed.

**The obstacle to (3), which is what to price first: we cannot write a GGUF.**
`gguf.lisp` exports `read`, `version`, `metadata`, `metadata-value`, `tensor-names`,
`tensor-info`, `tensor`, `tokenizer-fields` -- all read-side, and a grep for a write side
across `src/main` finds nothing. 670's "stories15M converted to GGUF" was llama.cpp's
converter, not us. So (3) needs a minimal GGUF **writer** first.

**But a Lisp writer may not be needed at all, and there is now precedent in the repo.**
2026-09-05, the same day: the added-token fix's pin is
`examples/llama2/checkpoint-tokenizer-check.lisp` over a CHECKED-IN fixture built by
`tokenizer-fixture.py` -- one tiny byte-level BPE emitted both as `tokenizer.json` +
config AND as a GGUF metadata block, with ids taken from the Python `tokenizers` library
as the oracle. Generated once, offline, committed as bytes; the repo reads it and never
writes one.

That is the shape (3) wants. A synthetic DeltaNet fixture can be generated the same way
-- a script that is a development tool, not a dependency, and a few hundred KB of
committed bytes. **So the writer question is about the SECOND use, not this one**: a Lisp
GGUF writer is how `.todo/672`'s Q8_0 reader and any future architecture get fixtures
generated in-language, and it may still be worth building for that. It is not a
precondition for this pin.

Check the precedent before assuming it transfers: `tokenizer-fixture.py` emits a metadata
block, not tensor data, and a DeltaNet fixture needs real tensors in a real tensor table.

State the limitation in whatever lands. A fixture assertion that is described as covering
decoded text, and does not cover the architecture whose defect motivated it, is the shape
`.todo/670` rule 6 warns about -- a suite that looks more exhaustive than it is.

## Not in scope

- Fixing the Qwen3.5 f32 decode difference. **There is no difference** -- see the
  retraction above. `.todo/488` changed no f32 reduction order, lane count or accumulator
  structure by its diff, and the kernel probe agrees bit for bit.
- Any new test surface for the large checkpoints. They are not in the repo and must not be.
