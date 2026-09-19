# The browser playground and the doc site's cells read Scheme too

Difficulty: Medium

Split off `.todo/826` ("Not a language feature, same area", first bullet).

The playground's REPL and compile buttons read Common Lisp only
(`RontoPlayground.evalLine` / `frontend`); a `.scm` reaches it only through `(load ...)`
of an uploaded file. The doc site's ```` ```scheme ```` fences are static for the same
reason (`RunnableBlockTransformer` runs `lisp` only), though `DocExamplesTest` verifies
them.

## Plan

- A language pick on the playground: the REPL reads through `eval/SourceSession`
  (`.kb/source-language.md`), one session per language over the one evaluator, and
  echoes only the LAST step's values (`Step.echoes`), not `ReplBuffer`'s every-form loop
  -- the doc site's Run cells rely on "the final value is the annotated one".
- The compile buttons read the picked language (`readStrict` already has a Scheme arm).
- The logic lives in the main source tree (`eval`), JVM-testable; `RontoPlayground`
  stays a thin `@JS` adapter.
- Doc site, same item: a ```` ```scheme ```` fence becomes a Run cell with the semantics
  `DocExamplesTest` asserts -- a whole program on a fresh evaluator (stdout), or, with a
  `; =>`, a REPL session of its own; its ```` ```stdin ```` block and the page's
  `; file:` blocks travel with it. `DocExamplesTest` and the browser share one entry.
- Verify on a real Web Image build in a headless browser if one can be built here;
  otherwise say so.
