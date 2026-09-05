# 701. Chat templates are hand-written guesses that nothing checks; two of three families diverge from the model's own

Difficulty: Medium

Filed 2026-09-05 from three temperature-0 comparisons against `llama.cpp` on the same
GGUF, taken by three lanes for three other reasons:

| model | raw completion (no template either side) | `-m chat` against `llama-cli`'s jinja |
| --- | --- | --- |
| LFM2.5-1.2B-Instruct | -- | **identical**, 581 characters (`.todo/678`) |
| Qwen3.5-0.8B | **identical**, 64 ids, prompt ids proven equal first (`.todo/677`) | diverges after the first sentence ("the same Barnaby, a different story") |
| Qwen3-0.6B | **identical**, 64 tokens of text (`.todo/489`) | diverges after eleven words |

**The signature: raw completion agrees, chat diverges.** It localises the fault to prompt
construction with no arithmetic investigation at all, and it finds the next instance in
a single run -- one raw comparison and one chat comparison per model is the test.

**The defect is not "the Qwen arm is wrong"; it is that the templates are hand-written
at all.** Every checkpoint ships its own `tokenizer.chat_template` (in
`tokenizer_config.json`, and in a GGUF's `tokenizer.chat_template`); `llama.cpp` renders
that; `examples/llama2/llama2.lisp` renders a per-family approximation from the
architecture row (`*chatml*`, `*chatml-think-off*`, `:chat`), and nothing at the point of
use says whether the two agree. LFM2.5 passing is not evidence that its template is
right by construction -- `.todo/678`'s lane established it by reading LFM2.5's jinja
AFTERWARDS and showing it renders the same string. Two of three families diverge; the
third matches by a coincidence someone verified retroactively. Fixing Qwen alone would
leave the mechanism that produced the bug, and make the next family a coin flip that
reads as done (`.todo/670` rule 2).

## Three options -- the next lane picks, with the tradeoff visible

1. **Narrow**: fix the Qwen rendering. The likeliest suspect is the think block --
   what `enable_thinking=False` renders, where the newlines fall, whether a system turn
   is injected by default. Cheap, and leaves the mechanism intact.
2. **Real**: render the model's own `tokenizer.chat_template`. Needs a jinja subset (the
   templates use `for`, `if`/`elif`, `set`, string methods, `|tojson`, `loop.first`,
   `messages[0].role`) -- genuine work, and the only version where a new family is
   correct by construction rather than by someone remembering to diff two prompts.
3. **Cheap middle, possibly the right first step**: keep the hand-written templates but
   VERIFY them -- render the model's own template at load (a subset is enough for the
   one-user-turn case), diff against the row's, and fail loudly on disagreement. Turns a
   silent wrong answer into a startup error without a full renderer, and turns the
   raw-vs-chat asymmetry into a test rather than a discovery.

## Whichever option

- **Diff the two rendered prompts before touching anything.** `llama-cli --verbose-prompt
  -st` prints the ids of what it fed the model; `LLAMA2_TRACE=1` prints ours. Same model,
  same user message, thinking off on both sides. The first differing id is the defect.
- The bar afterwards: identical ids in, identical ids out, at temperature 0 with
  `--repeat-penalty 1.0 --top-k 0 --top-p 1.0 --min-p 0`, for Qwen3-0.6B, Qwen3.5-0.8B
  AND LFM2.5 (the one that passes today must keep passing by construction, not by luck).
- The README's Qwen3.5 paragraph explains the divergence as "what two implementations at
  temperature 0 give each other". It is not; the raw runs prove the arithmetic identical.
  Rewrite it.
- Cross-reference from `.todo/677` when it closes (rule 9: the child that owns the fact
  carries it).
