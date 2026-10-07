# d82. An unbound-variable has no cell-error-name, and a compiled unbound read does not signal it

Difficulty: Medium

```lisp
(defvar *dv*)
(handler-case (symbol-value 'unbound-abc)
  (unbound-variable (c) (cell-error-name c)))   ; SBCL: UNBOUND-ABC
(handler-case *dv* (unbound-variable (c) (cell-error-name c)))   ; SBCL: *DV*
```

| backend | `symbol-value` | `*dv*` |
| --- | --- | --- |
| interpreter | NIL | NIL |
| JVM | NIL | no signal: the form answers NIL |
| P1 / component | trap (`unreachable`) past `handler-case` | trap |

The undefined-function twin is fixed (`.kb/error-handling.md`, "Every undefined-function names
its function"); the same mechanism fits here.

## Plan

- Four-backend fixture first (SBCL's answers).
- Interpreter: `Environment`'s unbound throw as a `CellErrorException` (`unboundVariable`),
  `synthesizeCondition` already fills `NAME` for any of them.
- JVM: the pad's unbound-variable arm reads the name between
  `UNBOUND_VARIABLE_MESSAGE_PREFIX` and the suffix, as `emitUndefinedFunctionConstruction` does;
  find why a `defvar` without a value reads as NIL instead of throwing.
- wasm-GC: find the trapping sites and give them a catchable signal carrying the symbol.
