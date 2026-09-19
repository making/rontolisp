# Flush open output file streams when the program ends

Difficulty: Medium

Found by `.todo/874` (measured 2026-09-19): an output file stream that is never closed
loses what it still buffers on the interpreter and the JVM (`BufferedWriter` /
`BufferedOutputStream` in the stream table, nobody flushes at exit), while both wasm
backends keep it (`fd_write` writes through). Same for a Common Lisp `open` and a Scheme
`open-output-file` port. The Scheme docs state it as "close the port"
(`doc/*/scheme/deviations.md`, `open-output-file.md`); C stdio and Gauche flush at exit.

## Plan

- Interpreter: flush every `Flushable` in `Environment`'s stream table where the process
  ends -- the end of `RontoLispCli.interpret`, `%host-exit`, the uncaught-condition exit.
- JVM: a `_flushStreams` over `_streams` at `main`'s return, in the `%host-exit` path and
  the uncaught-condition handler, gated on the program using `open` (byte-identical
  otherwise, measure).
- A test that runs a program leaving a file open on all four backends and reads the file
  after the process ends (the spec corpora compare stdout only, so a JUnit E2E).
- Then drop the stated deviation from the Scheme docs and `.kb/scheme-frontend.md`.
