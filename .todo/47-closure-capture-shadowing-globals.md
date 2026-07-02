# Compiled closures resolve captured locals to same-named top-level globals

Discovered 2026-07-03 while wiring the JSON library into ci-spec (the
concatenated E2E program defines `(setq a 10)` early, which broke a maphash
closure in `json.lisp` that captured a let variable named `a`).

## Symptom

When a top-level global with the same name exists, a lambda body resolves a
captured local (a surrounding `let` variable or an enclosing `defun`
parameter) to the **global** instead of the captured binding -- both reads and
writes. Straight-line code (no lambda) shadows correctly; the interpreter is
correct in all cases. JVM and WASM are equally affected (the shared
`FreeVarAnalyzer` presumably classifies the name as a global reference before
checking enclosing local scopes).

```lisp
(setq c 777)
(print (let ((c 5)) (funcall (lambda () c))))
; interpreter: 5        JVM/WASM: 777

(setq d 666)
(print (let ((d 5)) (funcall (lambda () (setq d (+ d 1)) d))))
; interpreter: 6        JVM/WASM: 667 (and the let's d stays 5)

(setq e2 555)
(defun g (e2) (funcall (lambda () e2)))
(print (g 42))
; interpreter: 42       JVM/WASM: 555
```

Params and plain lets outside closures are fine:

```lisp
(setq s 999)
(defun f (s) (length s))
(print (f "abc"))            ; 3 everywhere
(setq b 888)
(print (let ((b 1)) (+ b 1))) ; 2 everywhere
```

## Where to look

- `compiler/FreeVarAnalyzer` (`collectFreeVars` / `collectCapturedVars`): the
  classification of a name as "global" must happen only after the enclosing
  lexical scopes (params, lets) have been checked.
- `Jvm/WasmLambdaCompiler` + `Jvm/WasmSetqCompiler`: a captured variable that
  shadows a global must load/store the closure cell, not the global slot.

## Workaround in the tree

`json.lisp`'s only closure (the maphash callback in `%json-out-hash`) uses
`%json-`-prefixed capture variable names so user globals cannot collide. Once
this bug is fixed that rename can stay (harmless) and the pinning test should
be the three snippets above (add a ci-spec case: interpreter vs compiled
outputs must agree).
