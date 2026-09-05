# 701. Our Qwen chat template diverges from `llama.cpp`'s jinja rendering, twice; LFM2.5's does not

Difficulty: Medium

Filed 2026-09-05 from three temperature-0 comparisons against `llama.cpp` on the same
GGUF, taken by three lanes for three other reasons:

| model | raw completion (no template either side) | `-m chat` against `llama-cli`'s jinja |
| --- | --- | --- |
| LFM2.5-1.2B-Instruct | -- | **identical**, 581 characters (`.todo/678`) |
| Qwen3.5-0.8B | **identical**, 64 ids, prompt ids proven equal first (`.todo/677`) | diverges after the first sentence ("the same Barnaby, a different story") |
| Qwen3-0.6B | **identical**, 64 tokens of text (`.todo/489`) | diverges after eleven words |

The signature is raw-identical / chat-divergent, on the Qwen family only. That is not
"two harnesses render a template differently" -- it is our hand-written ChatML-with-think
template (`*chatml-think-off*` in `examples/llama2/llama2.lisp`,
`<|im_start|>user~%~a<|im_end|>~%<|im_start|>assistant~%<think>~%~%</think>~%~%`) being
wrong for the Qwen models, reproducibly, with the model's own `tokenizer.chat_template`
rendered by `llama.cpp`'s jinja engine as the oracle. The likeliest suspect is the think
block: what `enable_thinking=False` renders, where the newlines fall, and whether a
system turn is injected by default -- LFM2.5's template has none of that and is the one
that matches.

## Do

1. **Diff the two rendered prompts before touching anything.** `llama-cli --verbose-prompt
   -st` prints the ids of what it fed the model; `LLAMA2_TRACE=1` prints ours. Same model,
   same user message, thinking off on both sides. The first differing id is the defect.
2. Fix the template (or the id sequence) so the two are equal, then re-run the chat-mode
   comparison for Qwen3-0.6B and Qwen3.5-0.8B: identical ids in, identical ids out is the
   bar, at temperature 0, `--repeat-penalty 1.0 --top-k 0 --top-p 1.0 --min-p 0`.
3. Update the README's Qwen3.5 paragraph, which today explains the divergence as "what
   two implementations at temperature 0 give each other". It is not; the raw runs prove
   the arithmetic identical.
4. Cross-reference from `.todo/677` when it closes (rule 9: the child that owns the fact
   carries it).
