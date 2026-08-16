# 398. The babel shim has no decoding-mapping protocol

Difficulty: Medium

Found by the dexador spike (`.todo/396`). The bundled babel shim
(`src/main/resources/am/ik/rontolisp/eval/babel.lisp` + the `BABEL` /
`BABEL-ENCODINGS` packages seeded in `PackageRegistry`) offers five
functions -- `string-to-octets`, `octets-to-string`, `string-size-in-octets`,
`list-character-encodings`, `babel-encodings:*default-character-encoding*`.
A library that decodes INCREMENTALLY needs babel's mapping objects instead,
and dexador's `src/decoding-stream.lisp` imports exactly those:

```
babel:*string-vector-mappings*  babel:unicode-char
babel-encodings:lookup-mapping  babel-encodings:code-point-counter
babel-encodings:decoder         babel-encodings:enc-max-units-per-char
babel-encodings:get-character-encoding
```

plus, from `src/body.lisp`, `babel:character-decoding-error` (a condition it
`handler-case`s on, to fall back to a binary body) and
`babel-encodings:*suppress-character-coding-errors*`.

The failure is at LOAD time and reads
`The symbol UNICODE-CHAR is not external in the BABEL package`, i.e. the
`defpackage` `:import-from` clause, before any of dexador's code runs.

## The contract to reproduce

Real babel's mapping protocol, in the shape the consumer uses it:

- `(lookup-mapping *string-vector-mappings* encoding)` -> a mapping object for
  that encoding.
- `(code-point-counter mapping)` -> a function
  `(vector start end max-chars)` answering `(values chars new-end)`: how many
  characters fit and the octet index they end at. dexador calls it with
  `max-chars` = 1 to advance exactly one character.
- `(decoder mapping)` -> a function `(src start end dest dest-start)` writing
  the decoded characters into `dest` and answering the new destination index.
- `(enc-max-units-per-char encoding)` -> the buffer-sizing bound.
- `(get-character-encoding name)` -> the encoding object for a charset name.
- `unicode-char` -- a TYPE, used as `(make-string 1 :element-type
  'babel:unicode-char)` and as `stream-element-type`'s answer. `character` is
  the right definition here.

The spike's stand-in (a UTF-8-only counter/decoder pair, ~80 lines) was enough
to load dexador and to decode a Japanese response body correctly on three
backends, so the surface above is complete for a real consumer -- but it is a
STAND-IN: the shim owes the same treatment to the other encodings
`list-character-encodings` already advertises (`:latin-1`, `:us-ascii`).

## The work

- Widen `babel.lisp` with the mapping protocol above. Keep it derived from the
  existing UTF-8/latin-1/ascii slice -- do NOT vendor real babel (28k lines,
  mostly `#.`-generated per-encoding tables, and the encodings rontolisp
  strings can represent are already the ones the shim names).
- `PackageRegistry`'s `BABEL` / `BABEL-ENCODINGS` seeds must gain the new
  external names; the packages are Java-seeded, so a `.lisp`-only change does
  not publish them (and see `.todo/403` -- a run-time `export` is not a
  workaround).
- `character-decoding-error` must be a real condition type
  (`.kb/error-handling.md` standard-condition machinery), catchable under
  `handler-case`, since the consumer's fallback path depends on catching it.
- `*suppress-character-coding-errors*` is a special that the shim's decode path
  must actually read, not a decorative defvar.
- Pin with a test that drives an incremental decode of a multi-byte string
  through the protocol on all four backends, and note the shim's boundary in
  `.kb/asdf.md`'s shim ladder.
