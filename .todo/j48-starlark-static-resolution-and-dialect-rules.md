# j48. Starlark: static name resolution, dialect rules and error positions

Difficulty: Medium

After `.todo/j46`. The prototype (`.todo/artefacts/j45-starlark-front-end-prototype/`) resolves names while lowering and checks nothing else.
The spec makes these static or defined errors:

- An undefined name fails the whole FILE before any statement runs.
- "local variable referenced before assignment": the prototype initializes every local to
  `None`, so 2 conformance chunks pass silently. Use a definite-assignment analysis and emit
  the run-time check only where it cannot decide.
- A global may be bound once; `if` and `for` are not allowed at top level; recursion is a
  dynamic error. The Go implementation makes each optional and the conformance corpus uses
  the relaxed forms. Decide the default and the switch -- program-wide, travelling with the
  seam like `--scheme-standard` (`SourceStandards`, `.kb/source-language.md`).
- Evaluation order: `a[i] = rhs` evaluates `rhs` first (1 chunk fails); keyword and
  default arguments left to right.
- Every error names file, line and column -- static errors from the reader's positions
  (`StarlarkParser`, `.kb/starlark-frontend.md`), dynamic ones as far as the pipeline already carries a position.

Test through the conformance corpus (`.todo/j47`) where it has the case; extend an existing
case otherwise.
