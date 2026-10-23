# g68: an uncaught not-implemented-error traps silently on WASM

Difficulty: Medium

On the WASM backends, an operator that signals `not-implemented-error` (e.g.
`uiop:chdir`, which WASI cannot do) misbehaves when the program does not catch it:

- At compile time the program draws a warning that `%FMT-RENDER` is undefined (the
  condition's report path reaches a formatter helper the tree shaker did not keep).
- At run time the module traps without printing the condition's message, where the
  interpreter and the JVM print the error and exit non-zero.

Found 2026-10-23 while making `truename` answer absolute paths; unrelated to that change.

## Goal

An uncaught `not-implemented-error` on both WASM backends compiles without the warning and
reports like any other uncaught error (message on stderr, the same exit status the other
backends use), pinned by a ci-spec case. Check the other conditions whose report goes
through `format` for the same missing-helper gap.
