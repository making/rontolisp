# String streams: direction predicates, `file-position`, and a string that sees output as it is written

Difficulty: Medium (a runtime answer per backend for each of three operators)

Split out of `.todo/921` (2026-09-22), which gave `with-input-from-string` its
`:index`/`:start`/`:end` and `with-output-to-string` its fill-pointer string
(`.kb/read-load-streams.md`, "String streams").

## Measured (2026-09-22, interpreter, suite `ca06bd9`, ANSI `streams`)

| cluster | tests | reason |
|---|---:|---|
| `output-stream-p` of a string INPUT stream answers t | 7 | `WITH-INPUT-FROM-STRING.9 .11 .12 .13 .14 .15 .16` -- "Lite: any stream answers t for both directions" (`Environment` `input-stream-p`/`output-stream-p`, `expandStreamDirectionP` on the compile paths) |

Two mechanisms `.todo/921` worked around rather than fixed:

- `file-position` of a string input stream answers nil on all four backends, so
  `:index` is computed by DRAINING the stream at exit (the unread count). A real
  position would make that a read.
- The fill-pointer string of `with-output-to-string` receives the output when the
  body EXITS, not as it is written: no stream writes through to a caller's vector.
  A body that reads the string mid-way sees it unchanged.

## What it needs

- The direction predicates answering the stream's real direction (a string input
  stream is not an output stream, and the reverse) on all four backends -- check
  what file streams answer at the same time.
- `file-position` on a string input stream (query at least), then `:index` over it.
- Decide whether write-through is worth a stream kind of its own; measure first.
