# d86. The wasm report of a cell-error drops a package-qualified name's package

Difficulty: Low

```lisp
(defpackage :p (:use :cl))
(handler-case (symbol-value 'p::v) (error (c) (princ-to-string c)))
;; interpreter, JVM: "The variable P::V is unbound"   wasm-GC: "The variable V is unbound"
(handler-case (funcall (intern "F" :p)) (error (c) (princ-to-string c)))
;; interpreter: "The function P::F is undefined"     wasm-GC: "The function F is undefined"
```

`cell-error-name` is correct on every backend. Only the message differs. Both wasm
throwers (`WasmRuntimeBuilder.NotFunctionReport.emitUndefinedThrow` and
`UnboundVariableReport.emitThrow`) spell the name with `princ`, which drops the package
prefix. The interpreter and the JVM write the symbol's canonical spelling.

## Plan

- Spell the name in both throwers the way the interpreter does: the canonical spelling,
  not `princ` (not `prin1` either, which adds `|...|` escapes the interpreter does not).
- Pin a package-qualified name in `UnboundVariableNameFixture` and
  `UndefinedFunctionNameFixture`. Without changing the corpus, `(intern "F" :p)` prints
  `P:F` on the compiled backends. That is a separate symbol-printing problem
  (`.todo/156`), so the pin reads the message only.
