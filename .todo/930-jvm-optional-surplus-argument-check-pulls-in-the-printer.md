# JVM `&optional` surplus-argument check pulls in the printer

Difficulty: Medium

`10ff1ae31` (`.todo/921`) made a lambda list ending in `&optional` (no `&rest`/`&key`)
signal `program-error` on surplus arguments, emitting the check INLINE
(`LambdaLists.tooManyArgsCheck`). The message `Function expects at most N arguments, got M`
formats M with `prin1-to-string`, which drags the whole printer into a compiled JVM
program.

## Measured by 921 (JVM `.class` / Preview 1 / component, bytes)

- one `&optional` defun: 5,177 / 1,193 / 2,357 -> 12,414 / 1,193 / 2,357 (JVM +7,237, +140%)
- two `&optional` defuns: 5,458 / 1,218 / 2,383 -> 13,029 / 1,249 / 2,414
- a program that already prints costs only ~+350 B.

## Goal

- Keep the check and the message format (consistent with `ClosRegistry.arityMessage` and
  the other arity errors), but render the integer count without the printer -- use the
  rail the other arity errors use. Measure inline vs a shared runtime helper.
- One `&optional` defun, no printing: back to (or within a few hundred bytes of) the
  pre-921 JVM size; WASM sizes do not grow.
- Before/after table on hello_world, pi_approx, `(+ 1 2)`, handler-case, mapcar, one/two
  `&optional` defuns, `merge-pathnames`, zlib, on all three compiled targets.
