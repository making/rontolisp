# File-stream residue: `open`'s real keywords, the binary byte calls, and `file-length`/`file-position`

Difficulty: High (the stream format diverges across backends; `.todo/387` closed
with the composite constructors but the `open` keyword and binary I/O rows stayed)

Split out of `.todo/715` (2026-09-19). `.todo/387` closed 2026-09-19 with the
composite stream constructors, but the ANSI `streams` chapter is still the
lowest chapter (32.3% -> 41.0%, 105 fail / 342 error). This is the residue.

## What fails (2026-09-19, interpreter, suite `ca06bd9`, test-level)

| cluster | count | reason |
|---|---:|---|
| `open` `:direction` | 49 | `OPEN supports :input and :output directions` -- only those two literal modes are accepted (`:probe`, `:io`, `:input`+`:output` combos refused) |
| `open` `:if-exists` | 42 | `OPEN: :IF-EXISTS supports only the native default value` (only `:append` non-default landed, `.kb/read-load-streams.md`) |
| `open` `:element-type` | 20+23 | supports only `'character` / `'(unsigned-byte 8)` (a computed or other type refused); `OPEN: cannot open file` 6 |
| byte I/O | 20 + 12 | `READ-BYTE expects a binary input stream` 20, `WRITE-BYTE expects an integer between 0 and 255` 12 |
| `read-sequence` / `write-sequence` | 26 + 26 | keyword / element-type residuals over the sequence representations |
| `file-length` | 16 | `FILE-LENGTH.*` (incl. `.ERROR.*`), call on a non-file stream |
| `file-position` | 10 | `FILE-POSITION.*` (got/want on a non-file stream, `:start`/`:end`) |
| `clear-input` | 11 | `The function CLEAR-INPUT is undefined` (`.todo/041` character-I/O row) |
| composite constructors residue | 41 | byte methods on `%CONCATENATED`/`%ECHO`/`%TWO-WAY` (`No applicable method: STREAM-READ-BYTE`...) |
| misc | ~20 | `file-string-length` 6, `listen`, `open-stream-p`, `probe-file`, `namestring`/`truename` family |

## Notes

- `.kb/read-load-streams.md`, "`open` / `with-open-file` / `%probe-file`" is the
  design home: `:direction` is resolved at compile time (`staticMode`), a failed
  `open` signals a `file-error` on every backend, `%probe-file` is a string-in/
  string-out primitive. `.todo/387`'s composite constructors live here too.
- Binary streams (`read-byte`/`write-byte`) and `file-length`/`file-position`/
  `file-string-length` are separate from the character/stream COLUMN work in
  `.todo/041` -- those two overlap on `clear-input` and the binary I/O.
- Cross-backend: `streams` is the chapter with the most lost forms (56), so the
  chapter is measured optimistically; an in-scope change that closes `open`'s
  keywords may ADMIT tests (that is progress).
- Measure as a DIFF of failing test NAMES; report fixed AND regressed.
