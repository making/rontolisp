# `:test` / `:key` keyword args for sequence & alist functions

**Status:** `:test`/`:test-not`/`:key` are supported on `member`/`assoc`/`rassoc`
and on `find`/`position`/`count`/`remove`/`delete`/`remove-duplicates`/`union`/
`intersection`/`set-difference`/`adjoin`/`substitute`/`nsubstitute`; the
`find`/`position` families additionally take `:start`/`:end`/`:from-end`, and the
predicate forms `find-if(-not)`/`position-if(-not)`/`remove-if(-not)`/
`substitute-if(-not)` take `:key` — on all four backends. One shared expansion
(`LispMacroExpander`'s `testMatchForm`/`keyedForm`/`buildPositionScan`, with the
interpreter routing the same expansions in `LispEvaluator`), so keyword behavior
is backend-identical by construction; unknown keywords are rejected loudly
(`requireTestKeyKeywords`) instead of silently ignored. Tests: the
`LispEvaluatorTest`/`JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest`
sequence cases plus the `sequence-and-alist-test-key-keywords` ci-spec case.

## Remaining (not planned, note-only)

- **`-if`/`-if-not` forms still without `:key`** (CL allows one):
  `count-if(-not)`, `member-if(-not)`, `delete-if(-not)`. The find/position pair,
  `remove-if(-not)` and `substitute-if(-not)` already take it. Add only if real
  code needs it.
- **First-class values outside the find/position family stay fixed-arity** —
  `:test`/`:key` work in call position only. The find/position family
  (`positionScanValues` grew an `elementResult` flag) and the
  `member`/`assoc`/`rassoc` interpreter registrations do parse them, so
  `(apply #'position item seq other-keys)` and friends work.
- **Runtime `eval` on the compiled backends ignores `:test`/`:key`**: the emitted
  eval interpreter resolves these through the fixed-arity compiled registry, so
  the compare stays `eql`; only the interpreter applies them inside `eval`.
  Documented in `doc/*/guides/eval-limitations.md`. Fixing it means teaching
  `Jvm/WasmEvalRuntimeBuilder` keyword parsing per function.
