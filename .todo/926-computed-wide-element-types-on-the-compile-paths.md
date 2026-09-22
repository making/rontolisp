# A COMPUTED wide `:element-type` on the compile paths (and in `with-open-file`)

Difficulty: Medium (one runtime classification plus the registration it feeds;
the cost is the measured trade below, not the code)

Split out of `.todo/919` (2026-09-22).

## What is left

`.todo/919` landed element types wider and narrower than one octet on all four
backends, LITERAL-only on the compile paths: a computed element type still
admits only `character` and the `(unsigned-byte 8)` spellings, and the
`with-open-file` expansion refuses anything else at call time on every backend
-- the interpreter included, since the expansion is shared. The interpreter's
own `open` built-in takes every evaluated type, so `(open p :element-type et)`
works there and `(with-open-file (s p :element-type et))` does not.

ANSI `streams` (interpreter, suite `ca06bd9`) still failing on exactly this:
`FILE-POSITION.7`, `FILE-POSITION.8`, `OPEN.65`, `MAKE-TWO-WAY-STREAM.13`
(`WITH-OPEN-FILE :element-type supports only 'character and '(unsigned-byte 8),
got (UNSIGNED-BYTE 1)` and friends).

## What it needs

- A runtime twin of `macro/StreamElementType.of` (Lisp, prelude) answering
  `(octets signed spec)` or nil, so `lowerRuntimeOpenOptions` can accept any
  integer type, dispatch on binary-or-not onto the SAME six leaves, and register
  the computed classification (`%file-stream-register` already takes it as
  values).
- The gate: a computed element type means any stream can be wide, so the wide
  helpers (`%wide-read-byte` & co.) must be spliced for every program with a
  computed `:element-type` -- every uiop file wrapper and `#'open`. Measure that
  cost first (`.kb/read-load-streams.md`, "Element types wider and narrower than
  one octet", has the literal numbers: +8.6K JVM / +8.0K WASM for the helpers);
  it is the same trade `.todo/918` measured and declined for a computed `:io`.
  If it is not worth it, the finding is the deliverable: record it and close.

## Notes

- `uiop:with-temporary-file` passes its element type down computed, so a wide
  literal there is refused today for the same reason.
