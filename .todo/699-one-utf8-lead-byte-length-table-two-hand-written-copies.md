# 699. One UTF-8 lead-byte length table, two hand-written copies

Difficulty: Low

`.todo/691` gave the language `rontolisp:octets-to-string` / `string-to-octets` and
deleted every hand-written byte-to-character DECODER against them. It could not delete
the lead-byte length CLASSIFIER two call sites still need -- a different question
("how many bytes does this sequence claim?") that the round-trip-through-the-codec
technique cannot answer without a real regression (below), so both places keep their
own copy of the same six-branch table:

- `examples/llama2/llama2.lisp`'s `utf8-length`
- `src/main/resources/am/ik/rontolisp/eval/tokenizers.lisp`'s `tokenizer::%utf8-lead-length`

They are byte-for-byte the same table today, held in step only by a comment in each
file pointing at the other and `TokenizersLibraryTest`'s
`utf8LeadLengthIsHowManyBytesNotWhatCharacterOverEveryLeadByte` (which pins the LIBRARY
half by value over 0..255, not the example's copy -- nothing catches the two drifting
apart).

## Why a round trip cannot replace it

`rontolisp:octets-to-string` composed with `rontolisp:string-to-octets` looks like it
could find "how many leading bytes are complete" by trying successively shorter
prefixes and checking whether encoding the decoded result reproduces the input bytes.
It cannot: a byte that leads no valid sequence at all (a real SentencePiece
byte-fallback token, e.g. `<0xC0>`) never round-trips at ANY prefix length, so the
technique either stalls for several tokens waiting for bytes that will never validate
it (streaming print) or drops the byte outright (a fixed-length buffer, as in
`tokenizer:decode`, never gets the chance to grow past the point where a fallback
would flush it). Both were tried and reverted while landing 691 -- see that item's
history row / closing commit for the two failing cases
(`#(0xC0 0x41)`, `#(0xC1)`, `#(0xF5 0x41)`).

## Do

Fold the two copies into one, subject to the two constraints that made this
non-trivial the first time:

1. **An example program may only reach a package's PUBLIC (`tokenizer:`) symbols.**
   `examples/llama2/llama2.lisp` cannot call `tokenizer::%utf8-lead-length` directly
   (double-colon, internal) without becoming the fourth thing that reaches past a
   package boundary an example is supposed to respect. Either export a public
   `tokenizer:` name for it, or find a different shared home neither file already
   owns.
2. **The framer must stay separable from the decoder.** Do not fold this back toward
   `rontolisp:octets-to-string` itself, and do not let whatever surface replaces it
   grow a full decode -- it answers "how many bytes", never "what character". If it
   ever needs to answer both, that is two functions sharing a table, not one function
   doing two jobs.

## Verify

- `examples/llama2/llama2.lisp`'s `utf8-length` and the tokenizer package's classifier
  are ONE definition, or one calls the other.
- The exhaustive 0..255 pin (`TokenizersLibraryTest`) still passes and now also covers
  whatever the example calls.
- `#(0xC0 0x41)`, `#(0xC1)`, `#(0xF5 0x41)` still pass through immediately (length 1,
  not held, not dropped) through both call sites.
