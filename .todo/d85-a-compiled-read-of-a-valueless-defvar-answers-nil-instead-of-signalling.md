# d85. A compiled read of a `(defvar x)` with no value answers NIL instead of signalling

Difficulty: High

```lisp
(defvar *dv*)
(handler-case *dv* (unbound-variable (c) (cell-error-name c)))                 ; SBCL: *DV*
(handler-case (symbol-value '*dv*) (unbound-variable (c) (cell-error-name c))) ; SBCL: *DV*
```

The interpreter answers `*DV*` for both. The JVM, P1 and the component answer NIL for both:
the read never signals.

## Why

The compiled backends have no unbound state for such a variable unless a literal `boundp`
probes it. Only the TRACKED specials (`SpecialVarCollector.collectProbedValueless`, and
without the eval mirror `GlobalVarCollector.collectProbedUnbound`) start as the UNBOUND
marker. Even for those, a read turns the marker into nil (`WasmExprCompiler.emitUnboundAsNil`,
`JvmExprCompiler.compileSpecialRead`, `_dget`). Any other valueless `defvar` is an ordinary
field or global that starts as nil. Both points are documented as deliberate
(`.kb/dynamic-special-variables.md`, "Still nil, not an error"; `doc/*/reference/functions/symbol-value.md`).

## Plan

- Every valueless `defvar` (not only probed ones) starts as the marker, and every read
  outside the variable's own binding frame signals `unbound-variable` naming it instead of
  answering nil: the inline marker test on wasm, the `getstatic` test and `_dget` on the
  JVM, the `--reentrant` task cell, the `make-thread` hand-over and the eval mirror
  (`symbol-value` of a literal special reads the variable).
- This changes the read cost of every such special. Libraries declare many of them
  (`iterate` about 30, `jzon`, `cl-who`), and they are read mostly inside a binding. Measure
  size and speed before choosing the shape, for example a shared thrower call or an inline
  test. The value-carrying `defvar`/`defparameter` must stay byte-identical.
- Then update the documents that say nil (symbol-value, `.kb/dynamic-special-variables.md`,
  `.kb/compile-time-boundp.md`) and extend `UnboundVariableNameFixture` and the ci-spec case
  `unbound-variable-carries-its-name` with the direct read.
