# The `tokenizer` package (the BPE a checkpoint ships with)

One hand-written Lisp library, `src/main/resources/am/ik/rontolisp/eval/tokenizers.lisp`
(the `geom.lisp` / `linalg.lisp` pattern, `.kb/geom.md`), so one implementation runs
identically on every backend: **GPT-2-style byte-level BPE** (`tokenizer:make-bpe` --
SmolLM2, Qwen 2.5/3/3.5, Llama 3, LFM2.5) and **SentencePiece-style BPE with per-piece
scores** (`tokenizer:make-sentencepiece` -- Llama 2, TinyLlama) behind one
`tokenizer:encode` / `tokenizer:decode`. Eleven exported functions; the record is the
internal `tokenizer::%tk` defstruct, so no type name resolves in `PackageRegistry`.

**Invariant: this package does no I/O and reaches for nothing but `cl`** -- not `linalg:`,
`objc:`, `java:`, `SourceLoader` or `open`. **The vocabulary is an ARGUMENT** (from a GGUF
reader's `tokenizer.ggml.tokens`/`merges`, a `tokenizer.json`'s `vocab`/`merges`, or a
fixture), which is what lets it run in the browser playground and compile to both WASM
backends. Pinned by `TokenizersLibraryTest.theLibraryReachesForNothingButCommonLisp`.

## Wiring (the `geom` pair)
- `eval/TokenizersLibrary` -- `forms()` (parsed once, cached), `process(program)` (compile
  splice, fired by any `tokenizer:` symbol), `isTokenizerQualified`.
- `LispEvaluator#resolveFunction` -- lazy load on first `tokenizer:`-qualified resolution.
- `cli/CompileFrontend` -- INNERMOST pass of the splice chain; order-free (references no
  other library and none references it).
- `web/RontoPlayground` -- same splice.
- `LibraryDefunPruner` -- registered, so a `tokenizer:pre-tokenize`-only program drops the
  merge loop.
- `LispNames.TOKENIZER_PKG` + `PackageRegistry` (`TOKENIZER_FUNCTIONS`,
  `tokenizerFunctionNames()`, `BUILTIN_PACKAGE_NAMES`).

## Byte-level BPE, in run order
1. **Special tokens first** (`<|im_start|>`, `<|begin_of_text|>`, `<|endoftext|>`, Llama 3's
   256 reserved): matched whole, leftmost-longest, BEFORE pre-tokenization; text between two
   goes through the model. Gated on a one-character first-set.
2. **Pre-tokenization** into words (five shapes, below).
3. **Byte-level mapping**: word's UTF-8 bytes -> GPT-2 characters (188 printable Latin-1
   bytes map to themselves, the other 68 to U+0100.. in increasing byte order).
4. **Merge loop**: repeatedly take the adjacent pair with the LOWEST rank, merge every
   occurrence left to right, until no adjacent pair has a rank; then look each piece up.
   Llama 3's `ignore_merges` short-circuits when the whole word is in the vocabulary.

`tokenizer:decode` = reverse table + UTF-8 decode over the WHOLE id list (a multi-byte
character straddles tokens routinely). `tokenizer:decode-bytes` is the streaming half: raw
bytes, untouched.

## The five pre-tokenizer shapes (hand-coded scanners, no regex engine)
Keyed by `tokenizer.ggml.pre` (or `tokenizer.json`'s `pre_tokenizer`). Each is an ALTERNATION
matched **leftmost-FIRST** (Rust `regex` semantics), so alternative order is load-bearing.

| kind | alternation | models |
| --- | --- | --- |
| `:gpt2` | `'s\|'t\|'re\|'ve\|'m\|'ll\|'d` (case-SENSITIVE) \| ` ?\p{L}+` \| ` ?\p{N}+` \| ` ?[^\s\p{L}\p{N}]+` \| `\s+(?!\S)` \| `\s+` | GPT-2 |
| `:smollm` | every `\p{N}` character split off on its own, THEN `:gpt2` | SmolLM2 |
| `:llama3` | `(?i:'s\|...)` \| `[^\r\n\p{L}\p{N}]?\p{L}+` \| `\p{N}{1,3}` \| ` ?[^\s\p{L}\p{N}]+[\r\n]*` \| `\s*[\r\n]+` \| `\s+(?!\S)` \| `\s+` | Llama 3, LFM2.5 |
| `:qwen2` | `:llama3` with `\p{N}` -- one digit at a time | Qwen 2.5, Qwen 3 |
| `:qwen35` | `:qwen2` with `[\p{L}\p{M}]+` as the word class (`\p{M}` out of the symbol class) | Qwen 3.5 - 3.8 |

GGUF spellings (`gpt-2`, `smollm`, `llama-bpe`, `qwen2`, ...) are accepted as strings
wherever a keyword is.

Three traps a straight reading of the patterns gets wrong:
- **SmolLM2 is not plain GPT-2**: its `tokenizer.json` puts `Digits(individual_digits)` IN
  FRONT of ByteLevel, and the split is on `\p{N}` (Rust `char::is_numeric`), not the decimal
  digits -- `½` and `Ⅻ` split off too.
- **The GPT-2 alternation has no `\s*[\r\n]+` alternative.** `"a\n\n  b"` cuts as
  `("a" "\n\n " " b")` under `:gpt2` but `("a" "\n\n" " " " b")` under `:qwen2`.
  `tokenizer::%whitespace-length` spells the three greedy-backtracking outcomes instead of
  backtracking: a run holding a newline (where the rule exists) ends just after its LAST
  newline; a run reaching the end of the text is taken whole; any other run gives up its last
  character (the space the next word carries).
- **`\s` is `\p{White_Space}`, 25 code points** -- NOT a language's `isspace`, which adds
  U+001C..U+001F; in a Rust regex those four are control characters and fall in the SYMBOL
  class.

## Unicode tables
`\p{L}`, `\p{N}`, `\p{M}` only: three delta-encoded range tables (gap from the previous
range's end, then length), expanded into flat `#(start end ...)` vectors at load and
binary-searched. 659 + 137 + 310 = 1106 ranges, **6.4 KB of source**; the small-integer deltas
collapse to a few hundred distinct pool constants, far under either JVM ceiling. Generated
from Unicode 15.0.0 (CPython 3.12 `unicodedata`) -- regenerate against the reference
tokenizer's Unicode version if a newly assigned code point matters. Works identically on every
backend because a character IS a code point (`.kb/characters-code-points.md`).

## Deliberately NOT here
- **Unicode normalization.** Qwen's `tokenizer.json` declares NFC; llama.cpp does not
  implement it either. NFC changes ids only for raw combining marks; natural text from a
  keyboard/file/chat template is already composed. Fixtures are stored NFC-composed; the marks
  NFC does not compose (Devanagari virama cluster, `o` + combining cedilla) still exercise
  `\p{M}`.
- **Chat templates** -- the demo program spells out an instruct model's template.
- **Reading a file** -- see the invariant.

## Fixtures / oracle
`src/test/resources/tokenizers/*.lisp`: one per shape (`gpt2`, `smollm2`, `qwen25`,
`llama32`, `qwen35`) plus `sentencepiece`. Each carries the corpus, trimmed vocabulary and
merge list, and **the ids the Python `tokenizers` library produced** with that model's own
`tokenizer.json`. The corpus is stored as CODE-POINT LISTS, not string literals, so no editor,
encoding or line ending can rewrite a case; it covers ASCII, contractions in both cases,
spaces/tabs/newlines/CRLF, decimal and non-decimal numerals, CJK, hangul, emoji (ZWJ, flags),
accents, combining marks, symbols, source code, Cyrillic/Greek/Arabic, and the model's special
tokens.

**The trim is exact**: keep every merge the encode SELECTED (in original relative rank order)
and every string it looked up and found. A never-selected merge cannot change an outcome,
because the loop runs until no adjacent pair has a rank. 16-22 KB per fixture instead of 1-4
MB.

Regenerating needs the model's `tokenizer.json` and the Python `tokenizers` library, neither
in the repo: encode the corpus, record the selected merges and found vocabulary entries, emit
the ids. Nothing in the build needs Python.

The SentencePiece fixture is the WHOLE 512-piece vocabulary of
`examples/llama2/tok512.bin`, untrimmed; expectations are run.c's `encode()`, cross-checked
against `llama2.lisp`'s own copy of that encoder (pinned to run.c token for token).

## Pins
- `TokenizersLibraryTest` -- interpreter half: six fixtures (ids AND `decode(encode(s)) == s`),
  the pre-tokenizers alone, accessors, `:bos`/`:eos`, the splice, the no-I/O invariant.
- `ci-spec.yaml` `tokenizer-cross-backend` -- same shapes on all four backends over a 25-token
  vocabulary written out in the case (deliberately not a model's: what differs per backend is
  the scanner, range tables, string indexing and hash keys).

## Consumers
`examples/llama2/llama2.lisp` still carries its own SentencePiece encoder; this package
reproduces rather than replaces it, and the `sentencepiece` fixture exists so folding the two
is a deletion rather than a rewrite (ids pinned on both sides). The byte-level half is what
the SmolLM2 / Qwen / LFM2.5 work needs, fed by the GGUF and safetensors readers.
