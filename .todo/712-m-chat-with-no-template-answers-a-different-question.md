# 712. `-m chat` on a checkpoint with no chat template answers a different question

Difficulty: Low

Found 2026-09-05 while measuring `.todo/489`'s bf16 rungs. It is not a crash and not a
wrong answer: **it is a well-formed number for a question nobody asked.**

## What happens

`examples/llm/llm.lisp:1548-1556`:

```lisp
(template
  ;; the family's, or ChatML for a checkpoint whose vocabulary has
  ;; its turn marker (SmolLM2 is model_type llama, and llama has none)
  (or (getf model :chat)
      (and (tokenizer:token-id tk "<|im_start|>") *chatml*)))
(prompt
  (if (and (string= *mode* "chat") template)
      (format nil template *prompt*)
      *prompt*))
```

When `-m chat` is given and `template` is `nil`, the `if` falls through to `*prompt*`
**silently**. The user asked for chat mode and got generate mode, with no diagnostic.

Downstream that is worse than a plain mode swap. On TinyLlama-1.1B-Chat (the `llama` row,
which carries no template) the raw instruction is not a continuation the model recognises,
so it emits EOS (id 2) at the FIRST sampled position. Generation stops immediately, and the
printed `tok/s` is then computed over the nine PROMPT positions alone. **The number that
comes out looks like a throughput figure, is in the right range, and measures prompt
ingestion instead of generation.**

## Evidence

Twelve timed runs were discarded on 2026-09-05 when this was found, and TinyLlama was
re-taken on the raw `Once upon a time` completion; the table in `.todo/489` says so. The
same file marks the previously recorded 2026-09-05 f32 TinyLlama rows (1.86 / 8.84,
labelled "chat prompt") as suspect for this reason -- marked, deliberately not reconciled
and not deleted.

Note which half of the harness noticed: nothing did. It was caught by a human reading
`weights=` and the token count, not by any check. The condition is mechanical and cheap --
mode is `chat`, template is `nil` -- and it is checked nowhere.

## Do

1. **Failing test first.** `examples/llm/stories260K.bin` + `tok512.bin` are checked in, are
   a plain llama, and have no `<|im_start|>` in a 512-token vocabulary, so they reproduce
   it without a download. A case that runs the example with `-m chat` and asserts the run
   FAILS with a diagnostic naming the checkpoint is red today.
2. Refuse instead of falling through: `-m chat` with no template is a usage error, not a
   default. Name the checkpoint and say that `-m generate` is what it supports, so the
   message tells the user which of the two to change.
3. **Separately, decide what the `tok/s` line should do when zero tokens were generated.**
   Refusing chat mode fixes this instance; a run that generates nothing and still prints a
   rate is the general shape, and it is reachable any time a model emits EOS immediately.
   A rate over zero generated tokens is not a rate. This half is the one that would have
   caught the defect without knowing about templates.
4. `ci-spec.yaml` if the refusal deserves cross-backend coverage; it is CLI-surface
   behaviour, so probably not.

## Testing

The new failing test, plus `ExamplesE2eTest -Drontolisp.examples.only=llm/` -- and read its
SKIP COUNT against the prior run's, not just its total (`.todo/708`).

## Done means

`-m chat` on a template-less checkpoint stops with a message that names the checkpoint,
and a test would go red if it silently continued again.
