# `read-sequence` / `write-sequence`: the STREAM decides the element, and a LIST is a sequence

Difficulty: High (it overturns a documented cross-backend rule and the
compile-time narrowing built on it)

Split out of `.todo/906` (2026-09-20).

## The premise this overturns

`.kb/read-load-streams.md` records, as a design decision: "**The BUFFER, not the
stream, picks the element**: both dispatch on `(stringp seq)`, so a character
vector moves CHARACTERS and anything else moves bytes". CLHS says the opposite --
`read-sequence` reads elements of the STREAM's element type. The rule is what
`compiler/SequenceIoNarrowing` (`.todo/338`) is built on, and it is measured:
a byte-only read loop 6,744 -> 5,735 B, the zlib `--optimize=size` row
125,738 -> 125,081.

## Measured (2026-09-20, interpreter, suite `ca06bd9`, ANSI `streams` after `.todo/906`)

| cluster | count | reason |
|---|---:|---|
| `read-sequence` into a LIST / general vector / fill-pointer vector from a CHARACTER stream | 21 | `READ-BYTE expects a binary input stream` |
| `write-sequence` from a general vector to a CHARACTER stream | 12 | `WRITE-BYTE expects an integer between 0 and 255` |
| the element loop over a LIST | 6 | `AREF expects an array, got (#\a #\b #\c #\d #\e)` |
| non-literal `:start` / `:end` keywords | 4 LOST top-level forms | `READ-SEQUENCE supports only the literal :start and :end keywords` (an EXPANSION-time refusal, so the whole `deftest` is lost) |

## The shape of the answer

- The runtime dispatch `(stringp seq)` becomes `(or (stringp seq)
  <the stream is a character stream>)`. The second test needs a primitive every
  backend can answer -- the per-handle element width `.todo/919` introduces is
  exactly it, so the two items share a mechanism and `.todo/919` should land
  first.
- `SequenceIoNarrowing`'s byte-only expansion then has to narrow on the STREAM as
  well as the buffer, or stand down where the stream is not provably binary. Keep
  the measured sizes: a `read-sequence` from a stream opened `'(unsigned-byte 8)`
  in the same function is still provably byte-only.
- A LIST buffer needs `elt`/`(setf elt)` in the loop instead of `aref`/`%aset`,
  or a cons-walking arm beside the vector one.
- `:start` / `:end` as EXPRESSIONS: the same treatment `open`'s computed options
  got (bind, then dispatch), or simply compile them as expressions -- the loop
  already reads them from temporaries.

## Notes

- `.kb/read-load-streams.md` (the `read-sequence`/`write-sequence` bullets),
  `.kb/binary-sequence-io.md`, `.kb/character-sequence-io.md` and
  `.kb/adjustable-arrays.md` all state the buffer-decides rule; change them
  together with the pinning tests (ci-spec
  `read-sequence-into-a-character-buffer` is the one that names it).
- **If the measurement says the size cost is not worth the 39 tests, that IS the
  result**: land the numbers in `.kb` and say so, rather than forcing the change.
- `.todo/919` (2026-09-22) landed the "what is this stream's element type"
  primitive the runtime dispatch needs: interpreter `streamElementTypes`,
  compile paths the prelude registry `%file-stream-entry` /
  `%file-stream-element-type`, spliced only for a program that asks
  `stream-element-type` (or opens a wide stream) -- reuse it and its gate rather
  than a second mechanism (`.kb/read-load-streams.md`, "Element types wider and
  narrower than one octet").
