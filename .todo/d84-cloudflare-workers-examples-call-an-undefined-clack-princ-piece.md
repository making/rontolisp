# d84. The cloudflare-workers examples call an undefined CLACK::%PRINC-PIECE on the JVM

Difficulty: Medium

`ExamplesE2eTest` with `-Drontolisp.examples.only=cloudflare-workers` fails 6 of 27: the JVM
`(run)` of hello-/httpbin- clack, tiny-routes and ningle signals
`The function CLACK::%PRINC-PIECE is undefined` (e.g. `hello-clack/worker.lisp:18`).
`%princ-piece` is a `format-render.lisp` helper; with a user package current, a reference
to it resolves in that package instead.

## Plan

- Find the commit that introduced it (bisect / scratch build), reduce to a program without
  the examples, ideally runnable on all four backends.
- Pin it in a test that runs in the normal suite, then fix the root cause.
- Confirm the cloudflare-workers examples E2E (JVM and WASM) all pass.
