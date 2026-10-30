# h70. Ten implemented `cl` functions have no function value on the compile paths

Difficulty: Medium

Found 2026-10-31 working h65. On the JVM, P1 and the component, `#'complement`,
`#'make-condition`, `#'make-sequence`, `#'documentation`, `#'proclaim`, `#'change-class`,
`#'slot-value`, `#'slot-boundp`, `#'slot-exists-p` and `#'slot-makunbound` compile to the
"function X is undefined" run-time error, and a computed `fboundp` of the symbol answers NIL; the
interpreter answers T and calls them. Each is lowered only in call position (no catalog wrapper,
no `NATIVE_VALUE_FUNCTIONS` row, no generated defun).

Plan: give each a function value derived from its call shape (`NativeCallShapes` /
`FunctionValueWrappers`, reference-gated where the body reaches a gated runtime), and add the
names to `FunctionDesignators.isDataCallable` where the value is gated, so a data-held symbol
reaches it as `aref` does. Pin with `BoundpFunctionValueFixture` or a sibling fixture on all
four backends. `.kb/symbol-runtime-api.md` ("Compiled computed probe") holds the measurement.
