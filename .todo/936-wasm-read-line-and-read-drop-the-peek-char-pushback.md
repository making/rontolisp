# WASM: `read-line` / `read` on a file or stdin stream drop the `peek-char` pushback

Difficulty: Medium

Split out of `.todo/925` (2026-09-22).

## What is wrong

Both WASM backends park a peeked code point in a one-slot pushback keyed on the fd
(`PEEK_FD_ADDR` / `PEEK_CP_ADDR`), and only `_read_char` and the bulk `read-sequence`
character path drain it (`.kb/read-load-streams.md`, "`read-line`, `read-char`,
`peek-char`": "documented not fixed"). Measured 2026-09-22 over a file `ab\r\ncd\n...`:
`(read-char s)` -> `#\c`, `(peek-char nil s)` -> `#\d`, `(read-line s)` answers `"d"` on the
interpreter, the JVM and sbcl, `""` on Preview 1 and the component -- and the NEXT
`read-char` answers the stale `#\d`.

Since `.todo/925` made `file-position` real on a character stream this is also visible as a
position: after that `read-line`, sbcl and the interpreter/JVM answer 7, WASM 6 (the fd is
at 7, less the parked character it still subtracts).

## What it needs

`_read_line` (and whatever `read` reads a stream through) consulting the pushback first, the
way `_read_char` does, on both WASM backends; a ci-spec case mixing `peek-char` with
`read-line` on a file stream, with `file-position` after it.
