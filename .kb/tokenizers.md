# The `tokenizer` package (the BPE a checkpoint ships with)

One hand-written Lisp-source library,
`src/main/resources/am/ik/rontolisp/eval/tokenizers.lisp`, following the `geom.lisp`
/ `linalg.lisp` pattern (`.kb/geom.md`) so a single implementation runs identically
on every backend: **GPT-2-style byte-level BPE** (`tokenizer:make-bpe` -- SmolLM2,
Qwen 2.5 / 3 / 3.5, Llama 3, LFM2.5, and every other current small model) and the
**SentencePiece-style BPE with per-piece scores** (`tokenizer:make-sentencepiece` --
Llama 2 and TinyLlama, the tokenizer `examples/llama2/llama2.lisp` carries) behind
one `tokenizer:encode` / `tokenizer:decode`. Eleven exported functions; the record
they pass around is the internal `tokenizer::%tk` defstruct, so no type name has to
resolve in `PackageRegistry`.

**The one invariant: this package does no I/O and reaches for nothing but `cl`.**
Not `linalg:`, not `objc:`, not `java:`, not `SourceLoader`, not `open`. **The
vocabulary is an ARGUMENT** -- a GGUF reader hands `make-bpe` the
`tokenizer.ggml.tokens` / `merges` fields, a safetensors model's `tokenizer.json`
hands it `vocab` / `merges`, a test hands it a fixture -- which is what lets the same
definitions run in the browser playground and compile to both WASM backends. It is
also what makes the package usable before any reader exists.
`TokenizersLibraryTest.theLibraryReachesForNothingButCommonLisp` pins it.

## Wiring (the `geom` pair, exactly)

- `eval/TokenizersLibrary` -- `forms()` (parsed once, cached), `process(program)`
  (the compile-path splice, fired by any `tokenizer:` symbol), `isTokenizerQualified`.
- `LispEvaluator#resolveFunction` -- the interpreter's lazy load on the first
  `tokenizer:`-qualified resolution.
- `cli/CompileFrontend` -- `TokenizersLibrary.process` is the INNERMOST pass of the
  splice chain and has no place in its order: it references no other library and no
  other library references it.
- `web/RontoPlayground` -- the same splice, so the playground can tokenize.
- `LibraryDefunPruner` -- registered, so a program that only calls
  `tokenizer:pre-tokenize` does not carry the merge loop.
- `LispNames.TOKENIZER_PKG` + `PackageRegistry` (`TOKENIZER_FUNCTIONS`,
  `tokenizerFunctionNames()`, `BUILTIN_PACKAGE_NAMES`).

## The byte-level BPE, in the order it runs

1. **Special tokens first.** The added tokens (`<|im_start|>`, `<|begin_of_text|>`,
   `<|endoftext|>`, Llama 3's 256 reserved ones) are matched whole, leftmost-longest,
   BEFORE pre-tokenization; the text between two of them goes through the model. The
   scan is gated on a one-character first-set, so a text with no `<` costs one
   comparison per character.
2. **Pre-tokenization**: the text is cut into words. Five shapes, below.
3. **Byte-level mapping**: each word's UTF-8 bytes become GPT-2's characters -- the
   188 printable Latin-1 bytes map to themselves, the other 68 to U+0100.. in
   increasing byte order -- so a merge list is plain text and every byte is
   representable.
4. **The merge loop**: repeatedly take the adjacent pair with the LOWEST rank and
   merge every occurrence of it left to right, until no adjacent pair has a rank.
   Then look each piece up. Llama 3's `ignore_merges` short-circuits the loop when
   the whole word is itself in the vocabulary.

`tokenizer:decode` is the reverse table plus a UTF-8 decode, over the WHOLE id list
rather than token by token, because a multi-byte character straddles tokens
routinely. `tokenizer:decode-bytes` is the streaming half a generation loop wants:
it returns the raw bytes and never touches them.

## The five pre-tokenizer shapes, and why they are hand-coded

llama.cpp does not run a Unicode regex engine either: the patterns are four fixed
shapes keyed by `tokenizer.ggml.pre` (`tokenizer.json`'s `pre_tokenizer` on the
safetensors side), and hand-coding them as scanners is what keeps a regex engine out
of the language. Each is an ALTERNATION matched **leftmost-FIRST** -- the Rust
`regex` crate's semantics, which the reference implementations are written in -- so
the order of the alternatives is load-bearing.

| kind | alternation | models |
| --- | --- | --- |
| `:gpt2` | `'s\|'t\|'re\|'ve\|'m\|'ll\|'d` (case-SENSITIVE) \| ` ?\p{L}+` \| ` ?\p{N}+` \| ` ?[^\s\p{L}\p{N}]+` \| `\s+(?!\S)` \| `\s+` | GPT-2 |
| `:smollm` | every `\p{N}` character split off on its own, THEN `:gpt2` | SmolLM2 |
| `:llama3` | `(?i:'s\|...)` \| `[^\r\n\p{L}\p{N}]?\p{L}+` \| `\p{N}{1,3}` \| ` ?[^\s\p{L}\p{N}]+[\r\n]*` \| `\s*[\r\n]+` \| `\s+(?!\S)` \| `\s+` | Llama 3, LFM2.5 |
| `:qwen2` | `:llama3` with `\p{N}` -- one digit at a time | Qwen 2.5, Qwen 3 |
| `:qwen35` | `:qwen2` with `[\p{L}\p{M}]+` as the word class (and `\p{M}` out of the symbol class) | Qwen 3.5 - 3.8 |

The GGUF spellings (`gpt-2`, `smollm`, `llama-bpe`, `qwen2`, ...) are accepted as
strings wherever a keyword is.

Three details that a straight reading of the patterns gets wrong, each of which cost
a test to find (2026-09-03):

- **SmolLM2 is not plain GPT-2.** Its `tokenizer.json` puts a
  `Digits(individual_digits)` pre-tokenizer IN FRONT of the ByteLevel one, and the
  split is on `\p{N}` (Rust's `char::is_numeric`), not on the decimal digits: `½` and
  `Ⅻ` are split off on their own too. `.todo/674` recorded it as plain GPT-2; it is
  not.
- **The GPT-2 alternation has no `\s*[\r\n]+` alternative.** So `"a\n\n  b"` cuts as
  `("a" "\n\n " " b")` under `:gpt2` and as `("a" "\n\n" " " " b")` under `:qwen2`.
  Greedy matching with backtracking has exactly three outcomes on a whitespace run,
  and `tokenizer::%whitespace-length` spells them rather than backtracking: a run
  holding a newline (where the rule exists) ends just after its LAST newline; a run
  that reaches the end of the text is taken whole; any other run gives up its last
  character, which is the space the next word carries.
- **`\s` is `\p{White_Space}`, 25 code points** -- NOT a language's `isspace`, which
  adds U+001C..U+001F. The reference is a Rust regex, where those four are control
  characters and therefore fall in the SYMBOL class.

## The Unicode tables

`\p{L}`, `\p{N}` and `\p{M}` are all the package needs: three range tables,
delta-encoded (the gap from the previous range's end, then the length), expanded into
flat `#(start end ...)` vectors at load and binary-searched. 659 + 137 + 310 = 1106
ranges, **6.4 KB of source**, and because the deltas are small integers they collapse
to a few hundred distinct constants in a compiled program's pool -- far under either
JVM ceiling (`.todo/017`). Generated from Unicode 15.0.0 (CPython 3.12's
`unicodedata`); regenerate against the reference tokenizer's Unicode version if a
newly assigned code point ever matters. `.kb/characters-code-points.md` is why this
works the same on every backend: a character IS a code point, so an emoji is one
`char` and not a surrogate pair.

## What is deliberately NOT here

- **Unicode normalization.** Qwen's `tokenizer.json` declares an NFC normalizer;
  llama.cpp does not implement it either. Measured 2026-09-03: on a random corpus
  built out of raw combining marks, NFC changes 649 of 2000 strings and **every one
  of those changes the ids**; on natural text (Latin accents, kana, hangul,
  Vietnamese, Greek, Cyrillic, Arabic) it changes nothing, because text from a
  keyboard, a file or a chat template is already composed. The checked-in fixtures
  are therefore stored NFC-composed -- a decomposed fixture would be testing the
  normalizer rather than the tokenizer -- and the marks NFC does not compose (the
  Devanagari virama cluster, `o` + combining cedilla) still exercise `\p{M}`.
- **Chat templates.** A base model takes raw text; an instruct model's template is a
  string the program that demos that one model spells out.
- **Reading a file.** See the invariant above.

## The oracle, and the fixtures

`src/test/resources/tokenizers/*.lisp`, one per pre-tokenizer shape --
`gpt2`, `smollm2`, `qwen25`, `llama32`, `qwen35` -- plus `sentencepiece`. Each
carries the corpus, the trimmed vocabulary and merge list, and **the ids the Python
`tokenizers` library produced** for that corpus with that model's own
`tokenizer.json`. The corpus is stored as CODE-POINT LISTS, not string literals, so
that no editor, encoding or line ending can rewrite a test case; it covers ASCII,
contractions in both cases, spaces / tabs / newlines / CRLF, decimal digits and
non-decimal numerals, CJK, hangul, emoji (ZWJ sequences and flags), accents,
combining marks, symbols, source code, Cyrillic / Greek / Arabic, and that model's
special tokens.

**Why the trim is exact.** The merges kept are every merge the encode actually
SELECTED, in their original relative rank order; the vocabulary kept is every string
the encode looked up and found. A merge that is never selected can never change an
outcome, because the loop runs until no adjacent pair has a rank at all -- so every
rankable adjacent pair is eventually merged, and the selected set is the whole set
that matters. The result is 16-22 KB per fixture instead of the 1-4 MB the full
vocabularies would be.

Regenerating a fixture needs the model's `tokenizer.json` and the Python
`tokenizers` library, neither of which the repository carries; the generator and the
corpus live in the todo's working notes, and the recipe is: encode the corpus with
`tokenizers`, record the selected merges and the found vocabulary entries, emit the
ids as the expectation. Nothing in the build needs Python.

The SentencePiece fixture is different: it is the WHOLE 512-piece vocabulary of
`examples/llama2/tok512.bin`, small enough to check in untrimmed, and its
expectations are run.c's `encode()` -- cross-checked at authoring time against
`llama2.lisp`'s own copy of that encoder, which is pinned to run.c token for token.

## Pins

- `TokenizersLibraryTest` -- the interpreter half: the six fixtures (ids AND the
  `decode(encode(s)) == s` round trip), the pre-tokenizers on their own, the
  accessors, `:bos`/`:eos`, the splice, and the no-I/O invariant.
- `ci-spec.yaml`'s `tokenizer-cross-backend` -- the same shapes on all four
  backends over a 25-token vocabulary written out in the case. The vocabulary is
  deliberately NOT a model's: what can differ per backend is the scanner, the range
  tables, the string indexing and the hash keys, and a real vocabulary would only
  make the same loop longer.

## Consumers

`examples/llama2/llama2.lisp` still carries its own SentencePiece encoder as of
2026-09-03: this package reproduces it rather than replacing it, because that file
is the model side's (`.todo/682`'s directory), not this one's, and folding the two
belongs with whoever wires a real checkpoint's tokenizer in. The
`sentencepiece` fixture exists so the fold is a deletion rather than a rewrite --
the ids are already pinned on both sides. The byte-level half is what `.todo/489`'s
SmolLM2 / Qwen / LFM2.5 rungs need, fed by `.todo/673` (GGUF) and `.todo/675`
(safetensors).
