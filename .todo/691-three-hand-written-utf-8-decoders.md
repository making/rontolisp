# 691. Three hand-written UTF-8 decoders, and no builtin to fold them onto

Difficulty: Low

Three shipped Lisp files decode UTF-8 by hand, each with its own continuation-byte loop:

- `examples/llama2/llama2.lisp` -- the SentencePiece tokenizer's byte pieces
- `src/main/resources/am/ik/rontolisp/eval/tokenizers.lisp` -- byte-level BPE
- `src/main/resources/am/ik/rontolisp/eval/gguf.lisp` -- the metadata strings

They arrived independently, days apart, because there is nothing to call: a program
holding `(unsigned-byte 8)` octets has no way to ask for the string they spell. The
encoder side has the same hole.

## Do

1. **`rontolisp:octets-to-string` and `rontolisp:string-to-octets`**, over a packed
   `(unsigned-byte 8)` vector, on all four backends -- the full "Adding a Built-in
   Function" checklist in `CLAUDE.md`, docs included. An `:external-format` argument is
   not wanted yet; UTF-8 is the only format any caller has.
2. **Delete the three copies**, in the same commit as the builtin lands or immediately
   after, so a fourth cannot be written against the old shape.
3. **Decide what a malformed sequence does** and pin it: an overlong encoding, a lone
   continuation byte, a truncated tail at the end of the buffer, and a surrogate
   codepoint. The three copies do not agree with each other today, which is the second
   reason to have one -- and whichever answer is chosen has to be the same on the
   interpreter, the JVM and both wasm backends.

## Why now

`.todo/672` (Q8_0) reads GGUF metadata through the third copy, so it inherits whichever
behaviour that one happens to have. Landing this first means 672 does not have to care.

## Verify

- Round-trip over the ASCII range, two- three- and four-byte sequences, and the empty
  vector, identical on all four backends (`ci-spec.yaml`).
- The malformed cases from step 3, pinned by the same case.
- The three call sites are gone: `grep` for the continuation-byte mask in
  `src/main/resources/` and `examples/` finds nothing.
