# 701. Diff every checkpoint's own `tokenizer.chat_template` against the hand-written one -- a measurement, not a renderer

Difficulty: Low

Filed 2026-09-05, rewritten the same day. **The evidence this item was filed on has been
reassigned**: it was three temperature-0 comparisons against `llama.cpp` in which Qwen3.5
and Qwen3-0.6B agreed in raw completion and diverged in chat mode while LFM2.5 agreed in
chat mode, first read as "our Qwen template is wrong" and then widened to "hand-written
templates are the defect". A token trace showed the divergence was never in the template
RENDERING but in the TOKENIZATION of the rendered string, and that defect is fixed under
`.todo/489` and recorded in `.kb/tokenizers.md` -- the section below keeps the account.
**This item does not cite those three divergences.** What it asks is narrower and cheap.

## The measurement

Every checkpoint ships its own `tokenizer.chat_template` (`tokenizer_config.json`; a
GGUF's `tokenizer.chat_template`). `examples/llama2/llama2.lisp` renders a hand-written
per-family approximation (`*chatml*`, `*chatml-think-off*`, the row's `:chat`).

**As of the tokenizer fix (`495c4a6b`, 2026-09-05) there IS a demonstrated rendering
divergence, and tokenization is ruled out.** Both Qwen models now answer the chat prompt
with no think-aloud preamble, safetensors and GGUF legs byte-identical to each other,
while `llama-cli`'s chat mode still thinks aloud on both **even at
`--reasoning-budget 0`**. Tokenization cannot account for it: after the fix our ids match
the Python `tokenizers` library exactly for the string we render -- Qwen3.5 21 ids,
Qwen3-0.6B 21, LFM2.5 18, SmolLM2 18. **So what remains between us and `llama.cpp` on
those two models is the rendered string and nothing else.** That is one positive case,
found before this item was worked, and it is what the script below now has to explain --
not a hunch. `.todo/678`'s lane separately verified LFM2.5's template by reading its
jinja, so at least one family is known correct.

One script settles the rest: render each model's own template for the one-user-turn case (Python's
`jinja2`, or `llama-cli --verbose-prompt`, whichever is on the box) and diff against what
`LLAMA2_TRACE=1` shows we feed, across every model on disk -- Qwen3.5-0.8B, Qwen3-0.6B,
LFM2.5-1.2B, TinyLlama-1.1B-Chat, SmolLM2-135M/360M-Instruct.

- **No template diverges** -> close this item with that as the finding. We then KNOW the
  approximations are right for every family we support, rather than believing it because
  nothing has caught fire.
- **One diverges** -> the item has evidence, and it arrived before a user did. Whether the
  answer is then to fix that template or to render the model's own (a jinja subset) is
  decided on that evidence, not on this item's history.

Owner: unassigned for the next wave; the other orchestrator has offered to take it.

## What the original evidence turned out to be (kept so nobody re-derives it)

| model | raw completion (no template either side) | `-m chat` against `llama-cli`'s jinja |
| --- | --- | --- |
| LFM2.5-1.2B-Instruct | -- | identical, 581 characters (`.todo/678`) |
| Qwen3.5-0.8B | identical, 64 ids, prompt ids proven equal first (`.todo/677`) | diverged after the first sentence |
| Qwen3-0.6B | identical, 64 tokens of text (`.todo/489`) | diverged after eleven words |

`LLAMA2_TRACE=1` on Qwen3.5-0.8B in chat mode fed ids 13314 `<th`, 741 `ink`, 29 `>`
where `llama.cpp` feeds the one id of `<think>`. `load-hf-bpe-tokenizer` matched whole
only the `added_tokens` flagged `"special": true`; the `tokenizers` library matches EVERY
added token whole (`special` only governs `skip_special_tokens` on decode). Qwen3-0.6B
and Qwen3.5 carry `<think>`, `</think>` and `<tool_call>` with `"special": false` (12 of
26 added tokens). **The families that matched are the ones with nothing for this bug to
bite -- LFM2.5's six non-special added tokens are absent from its template, SmolLM2 has
none at all.** The GGUF reader had the same hole one level down: token type 3 (control)
taken as special and type 4 (user-defined) not, both of which `llama.cpp` matches whole.
The signature that found it -- raw completion agrees, chat diverges -- is still the
one-run test for the next instance: the think block only appears in the chat path. The
orchestrators' two earlier framings ("the Qwen arm is wrong", then "hand-written
templates are the defect") were both written before the trace and did not survive it.
