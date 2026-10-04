# d05. A top-level psetq / psetf / multiple-value-setq of an undeclared global is refused

Difficulty: Medium

```lisp
(psetq *ta* 1 *tb* 2)
(print (list *ta* *tb*))
(multiple-value-setq (*tv* *tw*) (floor 9 2))
(print (list *tv* *tw*))
```

SBCL and the interpreter print `(1 2)` / `(4 1)`; the JVM, wasm P1 and component refuse with
`Cannot compile symbol reference: *TA*`. `GlobalVarCollector.collect` (and its nested walk,
`collectNestedAssignedNames`) reads only `setq`/`setf`, so no global backs the name, whereas the
function-body collector (`collectFreeAssignedInFunctionBodies`) reads all five heads. The
recognition is already shared (`GlobalVarCollector.assignedPlaces`); what is not shared is the
scope handling. `collect` is scope-blind by design, so adding the three heads to it would give
every `(let (a b) (multiple-value-setq (a b) ...))` at top level a global (the ci-spec program
grew 34 B on P1 when tried), against the "byte-identical for programs not affected" rule.
Likely shape: a scope-aware pass over the top-level non-defun forms, like the function-body one
(the form read as `(lambda () form)`), unioned in last, for the three heads only. Measure the
ci-spec program, examples and size-report first; add the program above to
`ProbedUnboundGlobalFixture` (and a `GlobalVarCollectorTest` case) first.
