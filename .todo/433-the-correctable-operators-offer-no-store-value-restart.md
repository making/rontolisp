# 433. The correctable operators offer no `store-value` restart

Difficulty: Medium

```lisp
(handler-bind ((error (lambda (c) (declare (ignore c)) (store-value 7))))
  (ccase "x" (7 :seven)))
;; CL: :SEVEN -- the store-value restart re-tests the new value
;; rontolisp: the error is fatal; nothing is established to invoke
```

`ccase` and `ctypecase` are plain aliases (`LispMacroExpander.expandCcase` ->
`expandEcase`, `expandCtypecase` -> `expandEtypecase`), and `check-type` /
`assert` lower to a bare test + `error`. All four are the CORRECTABLE half of
their pair in Common Lisp: the no-match error is signalled inside a
`store-value` restart that stores the supplied value back into the PLACE and
re-tests, looping until it matches. Here the `c`-prefixed operator is
indistinguishable from its `e`-prefixed twin -- which is why `ctypecase`
(`.todo/426`) could ship as a two-line delegation.

`.kb/error-handling.md` lists this under "Out of scope (still)", written when
there was no restart machinery at all. That reason no longer holds: Phase 4
shipped the real restart system (`restart-case`/`restart-bind`/`handler-bind`/
`invoke-restart`, and `use-value`/`store-value` themselves as restart-runtime
defuns), and `cerror` already shows the shape -- `expandCerror(cons,
closRegistry, restartMode)` lowers to a real `restart-case` when
`usesRestartSystem(program)` says the program can invoke one, and keeps the lite
lowering (byte-identical output) when it cannot.

## Definition of done

The same restart-mode split for the four operators, on all four backends: in
restart mode the no-match error is signalled inside a `store-value` restart
whose clause stores into the place and re-tests (a `tagbody`/`go` retry loop --
the `go` crosses the restart clause's lambda, so it lowers to the existing
block-exit throw/catch), and outside restart mode the current expansion stands
unchanged so no program without restarts moves a byte. `ccase`/`ctypecase` need
their keyform to be a PLACE for the store to be observable; `check-type`/
`assert` already name places.

Take the family in one pass -- splitting it leaves `ctypecase` correctable and
`ccase` not, which is worse than today's uniform gap. Pin per operator in
`LispEvaluatorTest` / `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest`
plus one `ci-spec.yaml` case, retire the "Out of scope" entry in
`.kb/error-handling.md`, and update the four doc pages (en+ja) that currently
say the restart is not established.
