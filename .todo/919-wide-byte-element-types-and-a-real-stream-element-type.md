# Element types wider (and narrower) than one byte, and a `stream-element-type` that answers them

Difficulty: High (a bit-packing file stream on four backends, and the one place
`stream-element-type` is a compile-time constant today)

Split out of `.todo/906` (2026-09-20). `open` takes exactly `'character` and
`'(unsigned-byte 8)`; ANSI's `streams` chapter exercises `(unsigned-byte N)` for
N = 1..100, `(signed-byte N)`, `bit` and `(integer lo hi)`.

## Measured (2026-09-20, interpreter, suite `ca06bd9`, ANSI `streams` after `.todo/906`)

| cluster | count | reason |
|---|---:|---|
| `open` `:element-type` | 34 errors | `OPEN supports only the 'character or '(unsigned-byte 8) element type` |
| `with-open-file` `:element-type` | ~30 LOST top-level forms | `WITH-OPEN-FILE :element-type must be the literal 'character or '(unsigned-byte 8)` -- thrown at EXPANSION time, so the whole `deftest` is lost and every test it would define is missing from the counts |
| `stream-element-type` | 3 + 3 | `(SUBTYPEP '(UNSIGNED-BYTE 8) (STREAM-ELEMENT-TYPE S))` fails; `STREAM-ELEMENT-TYPE.2/3/4` |
| `typep s 'broadcast-stream` etc. | 3 + 2 | the composite stream classes are not CL type names, so `make-broadcast-stream.7/8`'s zero-component special cases cannot be detected either |

The ~30 lost forms are the largest measurement distortion left in the chapter: a
first, cheap step is to make that refusal a CALL-time stub
(`LispMacroExpander.callTimeUnsupportedStub`, as the `:if-exists` refusal already
is) so each `deftest` registers and costs one error instead of the whole form.
That does not move the pass rate, it makes the chapter's denominator honest.

## What it needs

- `OpenModes` / `LispMacroExpander.isBinaryElementTypeLiteral` currently answer a
  BOOLEAN (binary or not). A width has to travel instead, into the file mode the
  backends read -- `OUTPUT_BIT`/`BINARY_BIT`/`APPEND_BIT` is a 3-bit space today.
- `read-byte`/`write-byte` on a stream of width N: N <= 8 packs several elements
  per octet (SBCL packs, and `open.29`..`open.40` read back exactly what a width-N
  write produced), N > 8 is ceil(N/8) octets little-endian. A per-handle width, on
  the interpreter's `streams` side table, the JVM's `_streamPaths` sibling and the
  WASM per-fd flag byte the `file-position` work already introduced
  (`STREAM_BINARY_FLAGS_ADDR`, which would become a width rather than a flag).
- `stream-element-type` is a compile-time CONSTANT on both compile backends
  (`expandConstantResult(quotedCharacterTypeName())`) and always `CHARACTER` on
  the interpreter. Making it real means reading that same per-handle width.
- `signed-byte` / `bit` / `(integer lo hi)` are the same machinery plus a sign
  and a bias.

## Notes

- `.kb/read-load-streams.md`, "Binary streams and binary standard I/O" is the
  design home; `.kb/binary-sequence-io.md` owns the packed bulk path, which must
  keep working (it moves one octet per element today).
- `.kb/gray-streams.md` records that a Gray class answers `character` or
  `(unsigned-byte 8)` by `typep` on its base classes -- widen in step.
- Measure as a DIFF of failing test NAMES; report fixed AND regressed, and report
  the LOST-form count separately (it is what admits the tests).
