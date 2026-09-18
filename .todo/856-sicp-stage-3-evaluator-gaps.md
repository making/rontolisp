# SICP stage 3: complete the corpus-shipped evaluators so embedded-* samples run

Difficulty: High

`.todo/828` (third item) lands the harness with `embedded-*` excluded: the
samples are programs FOR the book's chapter 4 evaluators, fed to their
`driver-loop` over stdin -- but measurement (2026-09-18, `.kb/scheme-frontend.md`
"The SICP sample corpus harness") shows no evaluator in the corpus is complete.
Each gap below is book text the sample set leaves as an exercise; closing a gap
means answering it in the harness composition (manifest-recorded glue + core file
list), then wiring that family's samples as driver legs in `SicpCorpusE2eTest`
(core files read from the corpus dir at test time, stdin per sample, reference =
the interpreter, same four legs as a `scheme` file).

## Gaps (nothing in `programs_scm/` defines these)

- amb (`variant=non-det`, 27 files): the `((amb? exp) (analyze-amb exp))`
  dispatch clause (no file wires it into `analyze`), `define-variable!`, and any
  stream support under `ambeval` -- `(cons-stream a b)` there looks up an unbound
  operator, so the stream-using interactions (prime-sum-pair and friends) cannot
  run without evaluator surgery. The stream-free subset wants: section 1
  defines-only files + `chapter4/section3/subsection3/16_driver_loop_amb.scm` +
  an advice redefining `analyze` to delegate `amb?` forms, `require` /
  `an-element-of` prepended to stdin, `(exit)` registered as a primitive for the
  terminating input.
- lazy (`variant=lazy`, 4 files): `define-variable!` only; otherwise the pieces
  (`01/03/05/07/09/12/13/19` of `chapter4/section2/subsection2` + section 1
  defines-only base + `10_driver_loop_lazy.scm` minus its book driver loop plus
  an EOF clause) compose. The `(eval (read) ...)` tails of `03/12/13` must be
  dropped (the harness drives input itself).
- query (`chapter4/section4/subsection1`, 52 files): the whole syntax layer --
  `assertion-to-be-added?`, `query-syntax-process`, `contract-question-mark`,
  `type`/`contents`, the conjunction/disjunction accessors. `02_query_driver_loop.scm`
  is the engine + driver definition (no call); it also needs `prompt-for-input` /
  `announce-output` (section 1 `17_meta_append.scm`, which cannot be included whole
  -- it calls the m-eval driver at top level) and an EOF clause, since the book
  loop has none.

## To do

1. Close one family's gap, keeping every addition manifest-recorded corpus
   composition (no corpus text checked in).
2. Wire that family's samples as driver legs; the other families stay excluded
   with their reason until theirs closes.
3. Record the per-family exit-0 counts in `.kb/scheme-frontend.md`.
