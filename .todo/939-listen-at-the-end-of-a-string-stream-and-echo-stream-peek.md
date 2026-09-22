# `listen` at the end of a string input stream, and an echo stream that echoes a peek

Difficulty: Medium (one answer per backend for `listen`; the echo half is prelude Lisp)

Split out of `.todo/929` (2026-09-22), which gave string streams a real direction and
`file-position` (`.kb/read-load-streams.md`, "String streams") and left these two
answers where they were.

## Measured (2026-09-22, interpreter, suite `ca06bd9`, ANSI `streams`)

| test | reason |
|---|---|
| `LISTEN.1 .3` | `(listen s)` at the END of a string input stream answers t, where CL wants nil: the interpreter and the JVM answer `BufferedReader.ready()`, which a `StringReader` answers true until close |
| `LISTEN.6` | the same, after the only character was read (the unread half then passes) |
| `PEEK-CHAR.17` | an echo stream ECHOES a character `peek-char` looked at (the output stream's position moves 0 -> 1); sbcl echoes only what is read |

`runtime.RontoStringInputStream.ready()` answers true until close ON PURPOSE: the JVM builds
that class only in a program that calls `file-position`, so answering the real "more to
read" there would make `listen` depend on whether the program names `file-position`.

## What it needs

- `listen` on a string input stream answering whether a character remains, on all four
  backends at once (the WASM record knows: `cursor < end`), with the unread-char cell
  counting as a character, as it already does.
- The echo stream's `stream-peek-char` (gray.lisp's default is read + unread, which echoes)
  answering from the input component without writing, so only a READ echoes.
