# j48. Starlark: definite assignment, dialect rules and error positions

Difficulty: Medium

The lowering resolves every name while it lowers (`.kb/starlark-frontend.md`, "Names"): a
name nothing binds is `undefined: NAME` at its position before anything runs, and
arguments are evaluated as written. What it does not check, and the spec makes an error:

- "local variable referenced before assignment": every local starts as `None`, so 2
  conformance chunks pass silently. Use a definite-assignment analysis and emit the
  run-time check only where it cannot decide.
- "global variable referenced before assignment": a global read too early is Common Lisp's
  `The variable y is unbound` (the same on four backends), and a top-level `def` with a
  direct entry is callable above its statement, because its `defun` is hoisted so that the
  four backends agree.
- A global may be bound once; `if` and `for` are not allowed at top level; recursion is a
  dynamic error (today a tail call that never ends runs forever on the interpreter and the
  JVM). The Go implementation makes each optional and the conformance corpus uses the
  relaxed forms. Decide the default and the switch -- program-wide, travelling with the
  seam like `--scheme-standard` (`SourceStandards`, `.kb/source-language.md`).
  `starlark-spec.yaml`'s first case (the prototype's feature tour) has a top-level `for`.
- Every error names file, line and column. Static ones do (the reader's and the lowering's
  `LispReadException`). Dynamic ones do not: the lowered forms carry no position yet --
  `SourceProvenance.record` wants an offset, the interpreter a `LocatedCons`
  (`.kb/source-positions.md`), and a `StarlarkSyntax` node has line and column.

Test through the conformance corpus (`StarlarkSpecE2eTest#conformance`, whose `KNOWN_FAILURES` names this item's three chunks) where it has the case; extend an existing
`starlark-spec.yaml` case otherwise.
