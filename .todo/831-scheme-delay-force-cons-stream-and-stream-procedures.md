# Scheme: `delay` / `force` / `make-promise`, `cons-stream` and the stream procedures

Difficulty: Medium

Split off `.todo/826` (its `delay` / `force` and `(scheme lazy)` rows) for `.todo/828`.
Today `(delay e)` is refused by name ("delay is not supported by this experimental front
end yet": 24 corpus files) and `(cons-stream a b)` lowers as a call to an undefined
function ("The function cons-stream is undefined").

Corpus files using each name WITHOUT defining it (2026-09-17): `cons-stream` 72,
`stream-cdr` 48, `stream-car` 47, `stream-map` 39, `the-empty-stream` 29, `stream-null?` 28,
`delay` 24, `force` 24, `stream-append` 17, `stream-ref` 7, `stream-filter` 6.

- **Syntax**: `(delay e)` -> a memoizing promise; `(cons-stream a b)` -> `(cons a (delay b))`;
  `delay-force`, `make-promise`, `promise?` with them. Both are syntactic keywords and must
  be SHADOWABLE like the others: 13 corpus files bind `delay` as a variable
  (`(define (after-delay delay action) ...)`) and pass today only because the refusal fires
  in head position alone. `SchemeLowering`'s `CORE_*` identity scheme already does this for
  `unless` (a file that defines a PROCEDURE named `unless` gets its procedure: verified).
- **Promise representation**: a record with a thunk (`.todo/826`'s note) so `promise?` is
  honest and `force` of a non-promise returns it. Memoization is observable in the corpus
  (`chapter3/section5` counts evaluations with a side effect), so it is not optional.
- **Procedures**, in `scheme.lisp` as Common Lisp so a user `define` of `apply` or `*`
  cannot reach them (measured: a stand-in `stream-map` written in Scheme broke in the two
  files that redefine `apply`): `stream-car` `stream-cdr` `stream-pair?` `stream-null?`
  `empty-stream?` `the-empty-stream` `stream-first` `stream-rest` `stream-head`
  `stream-tail` `stream-ref` `stream-map` (n-ary) `stream-for-each` `stream-filter`
  `stream-append` `stream->list` `list->stream`. `the-empty-stream` is the empty list
  and `stream-null?` is `null?`: the corpus mixes them.
- Depth: `stream-ref` / `stream-filter` must be loops, not recursion -- a sieve walked to
  the 50th prime forces thousands of cells. The `psetq` loop shape is in the kb.
- Measured with a stand-in (`.todo/artefacts/828-sicp-sample-corpus-harness/`): every
  `scheme`-category stream file then passes on the interpreter, the JVM and wasm with
  identical stdout, so no backend work is expected.

Cases in `scheme-spec.yaml` (memoization counted by a side effect; a shadowed `delay`; an
infinite stream), all four backends.
