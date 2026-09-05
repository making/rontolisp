# The `tokenizer` package (the BPE a checkpoint ships with)

`src/main/resources/am/ik/rontolisp/eval/tokenizers.lisp`, the `geom.lisp` / `linalg.lisp` pattern
(`.kb/geom.md`): **GPT-2-style byte-level BPE** (`tokenizer:make-bpe` -- SmolLM2, Qwen 2.5/3/3.5,
Llama 3, LFM2.5) and **SentencePiece-style BPE with per-piece scores**
(`tokenizer:make-sentencepiece` -- Llama 2, TinyLlama) behind one `tokenizer:encode` /
`tokenizer:decode`. Eleven exported functions; the record is the internal `tokenizer::%tk`.

**Invariant: this package does no I/O and reaches for nothing but `cl` and the core**
`rontolisp:octets-to-string` / `string-to-octets` **codec pair** (`.todo/691`; neither is I/O or a
host dependency, so the invariant's actual purpose -- browser playground and both WASM backends --
holds unchanged). The vocabulary is an ARGUMENT, which is what lets it run in the browser
playground and compile to both WASM backends. Pinned by
`TokenizersLibraryTest.theLibraryReachesForNothingButCommonLisp` (checks for `linalg:`/`objc:`/
`java:`/file I/O, not for every non-`cl` name).

## Wiring (the `geom` pair)
`eval/TokenizersLibrary` (`forms()`, `process(program)`, `isTokenizerQualified`);
`LispEvaluator#resolveFunction` lazy load; `cli/CompileFrontend` as the INNERMOST splice pass
(order-free); `web/RontoPlayground`; `LibraryDefunPruner`; `LispNames.TOKENIZER_PKG` +
`PackageRegistry` (`TOKENIZER_FUNCTIONS`, `tokenizerFunctionNames()`, `BUILTIN_PACKAGE_NAMES`).

## Byte-level BPE, in run order
1. **Special tokens first**, whole and leftmost-longest BEFORE pre-tokenization (gated on a
   one-character first-set).
2. **Pre-tokenization** into words.
3. **Byte-level mapping**: UTF-8 bytes -> GPT-2 characters (188 printable Latin-1 bytes to
   themselves, the other 68 to U+0100.. in increasing byte order).
4. **Merge loop**: LOWEST-rank adjacent pair, merged left to right, until no adjacent pair has a
   rank. Llama 3's `ignore_merges` short-circuits a whole-word vocabulary hit.

`tokenizer:decode` = reverse table + UTF-8 decode over the WHOLE id list (a multi-byte character
straddles tokens routinely), with an incomplete TRAILING sequence DROPPED rather than shown as
spurious bytes (`tokenizer::%complete-byte-prefix`, a length CLASSIFIER answering "how many bytes",
never a decode -- `.kb/characters-code-points.md`'s codec-pair section says why a round trip
through `rontolisp:octets-to-string`/`string-to-octets` cannot answer that question instead);
`tokenizer:decode-bytes` is the streaming half.

## The five pre-tokenizer shapes (hand-coded scanners, no regex engine)
Keyed by `tokenizer.ggml.pre` (or `tokenizer.json`'s `pre_tokenizer`); each is an ALTERNATION
matched **leftmost-FIRST** (Rust `regex` semantics), so alternative order is load-bearing.
`:gpt2` (GPT-2) | `:smollm` = `:gpt2` with every `\p{N}` character split off FIRST (SmolLM2) |
`:llama3` (Llama 3, LFM2.5) | `:qwen2` = `:llama3` with one digit at a time (Qwen 2.5/3) |
`:qwen35` = `:qwen2` with `[\p{L}\p{M}]+` as the word class (Qwen 3.5-3.8). GGUF spellings
(`gpt-2`, `smollm`, `llama-bpe`, `qwen2`, ...) are accepted as strings wherever a keyword is,
**including a family's own name for a shape it only shares**: LFM2.5 writes `lfm2`, whose
tokenizer.json holds the Llama 3 pattern character for character (llama.cpp maps it the same
way, beside `llama-v3` / `llama-bpe`). An unmapped alias is refused by name, so the model
does not load at all -- which is how `lfm2` was found (2026-09-05).

Three traps a straight reading of the patterns gets wrong:
- **SmolLM2 is not plain GPT-2**: `Digits(individual_digits)` runs IN FRONT of ByteLevel, and the
  split is on `\p{N}` (Rust `char::is_numeric`) -- `½` and `Ⅻ` split off too.
- **The GPT-2 alternation has no `\s*[\r\n]+` alternative**: `"a\n\n  b"` cuts as
  `("a" "\n\n " " b")` under `:gpt2` but `("a" "\n\n" " " " b")` under `:qwen2`.
  `tokenizer::%whitespace-length` spells the three greedy-backtracking outcomes out.
- **`\s` is `\p{White_Space}`, 25 code points** -- U+001C..U+001F are control characters and fall
  in the SYMBOL class.

## Unicode tables
`\p{L}`, `\p{N}`, `\p{M}` only: three delta-encoded range tables (gap from the previous range's
end, then length), expanded into flat `#(start end ...)` vectors at load and binary-searched.
659 + 137 + 310 = 1106 ranges, 6.4 KB of source. Unicode 15.0.0 (CPython 3.12 `unicodedata`).
A character IS a code point (`.kb/characters-code-points.md`).

## Deliberately NOT here
Unicode normalization (fixtures are stored NFC-composed), chat templates, reading a file.

## Fixtures / oracle
`src/test/resources/tokenizers/*.lisp`: one per shape (`gpt2`, `smollm2`, `qwen25`, `llama32`,
`qwen35`) plus `sentencepiece`, each carrying the corpus (as CODE-POINT LISTS, so no editor or
encoding can rewrite a case), the trimmed vocabulary and merges, and **the ids the Python
`tokenizers` library produced**. **The trim is exact**: every merge the encode SELECTED (in
original relative rank order) and every string it found -- 16-22 KB instead of 1-4 MB.
Regenerating needs the model's `tokenizer.json` and the Python `tokenizers` library, neither in
the repo. The SentencePiece fixture is the WHOLE 512-piece vocabulary of
`examples/llama2/tok512.bin`; expectations are run.c's `encode()`.

## Pins
`TokenizersLibraryTest` (interpreter: six fixtures with ids AND round-trip, the pre-tokenizers
alone, accessors, `:bos`/`:eos`, the splice, the no-I/O invariant); ci-spec
`tokenizer-cross-backend` -- same shapes on all four backends over a 25-token vocabulary written
in the case.

## Consumers
`examples/llama2/llama2.lisp` still carries its own SentencePiece encoder; the `sentencepiece`
fixture exists so folding the two is a deletion rather than a rewrite (ids pinned on both sides).

`examples/llama2/checkpoint-tokenizer.lisp` reads a published checkpoint's tokenizer into
`make-bpe` -- `tokenizer.json` + `tokenizer_config.json`, or a GGUF's `tokenizer.*` fields --
and is where the SPECIALS list is decided. **Every added token is matched whole, whatever its
`"special"` flag says** (the flag only tells a decoder what to skip); in a GGUF that is token type
3 (control) AND 4 (user-defined), both of which `llama.cpp` matches whole. Qwen3 / Qwen3.5 ship
`<think>`, `</think>` and `<tool_call>` unflagged, and a reader that took only the flagged ones
fed a chat prompt's think block as `<th` `ink` `>` -- three ids where the reference gives one --
so the model answered a different prompt from `llama.cpp`'s and the divergence read as a
template bug (`.todo/701`, 2026-09-05). The signature that finds the next instance in one run:
raw completion token-identical to `llama.cpp`, chat mode divergent. Pin:
`examples/llama2/checkpoint-tokenizer-check.lisp` over `tokenizer-fixture.py`'s fixture (both
readers, ids from the Python library), all four backends via `examples.yaml`.

The pre-tokenizer KIND and the BOS rule are the file's, not the family's: the `pre_tokenizer`
block (`Digits` + `ByteLevel` -> `:smollm`, `ByteLevel` with `use_regex` -> `:gpt2`, a `Split`
regex by its number clause -> `:llama3` / `:qwen2` / `:qwen35`; no `ByteLevel` step ->
SentencePiece, not this reader), and `add_bos_token` or a `TemplateProcessing` post-processor
opening with a special token. SmolLM2 names `<|im_start|>` as `bos_token` and adds none.
