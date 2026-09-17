# Probes for `.todo/825`

Hand-written Common Lisp in the shape a Scheme front end would EMIT, run unmodified on all
four backends. `./run.sh` from the repository root after a `package`; needs `wasmtime`.
Measured 2026-09-17 on develop `726ff2d0f`, wasmtime 47, aarch64 macOS.

| file | question | answer |
|---|---|---|
| `lowered-shape.lisp` | do the lowerings work with no backend change? | yes, identical on all four: `1 21 (YES NO YES) 499999500000 4 IN OUT ESCAPED 10 (1 (2 3))` |
| `mutual-tail.lisp.in` | how deep does a tail call that is NOT a loop go? | table below |
| `global-define.lisp.in` | may `define` lower to `defvar`? | no: `defvar` answers 2 (dynamic), top-level `setq` answers 1 (lexical), on all four |
| `uppercase-identifier.lisp` | is a user identifier always collision-free? | no: `\|CAR\|` REPLACES the built-in `car` |
| `printer-leaves.lisp` | which printer leaves differ? | `\|foo\|`, `T`, `NIL` -- `princ` of a symbol is already bare |

`lowered-shape.lisp` covers: a user procedure named like a CL macro (`|loop|`) with a
parameter named like a CL function (`|list|`); Lisp-1 call through an assigned global;
`'()` = NIL with a distinct `#f`; a 1,000,000-iteration named-`let` shape as `tagbody`/`go`;
an escape-only continuation crossing a `mapcar` lambda; `dynamic-wind`'s exit half over
that escape; variadics and `apply`.

Mutual tail recursion (`even?`/`odd?`), default stacks:

| N | interpreter | JVM (`java Mutual`) | wasm | component |
|---|---|---|---|---|
| 10,000 | ok | StackOverflowError | ok | ok |
| 100,000 | StackOverflowError | StackOverflowError | stack exhausted | stack exhausted |
| 1,000,000 | StackOverflowError | StackOverflowError | stack exhausted | stack exhausted |
