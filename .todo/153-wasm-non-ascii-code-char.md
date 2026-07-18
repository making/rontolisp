# WASM: code-char beyond ASCII emits a raw single byte (mojibake)

On the WASM backends a character is an i31 code point, but every char-to-bytes
conversion (`(string char)`, `write-char`, the char-vec accumulation that
`_charvec_to_str` normalizes) writes the code point as ONE byte. A literal
non-ASCII string in source survives (its UTF-8 bytes are carried verbatim),
but a character BUILT at runtime does not:

```lisp
(print (code-char 233))      ; interpreter/JVM: #\é   WASM: byte 0xE9 (invalid UTF-8)
(print (string (code-char 233)))
```

Surfaced by jzon's `\u` escape decoding (`é` -> `code-char` ->
`vector-push-extend` into the string accumulator): the parsed string prints as
mojibake on WASM, so the 4-backend `JzonE2eTest` exercise uses an ASCII `\u`
escape and the non-ASCII case is pinned interpreter-only.

Fix direction: UTF-8-encode code points > 127 at the char-to-bytes seams
(string-of-char, write-char, `_charvec_to_str`) and decode at char-of-string
seams (`char`/`aref` on strings currently index BYTES, so indexing semantics
need a decision: byte positions today vs code-point positions after the fix --
that is the breaking-change question to settle first). Also audit `char-code`
round-trips and the runtime reader.
