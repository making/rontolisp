# i36. A `defun` written through a computed name, `eval` or `load` keeps its definition

Difficulty: High

A literal `(setf (fdefinition 'n) ...)`, `(compile 'n def)` or `(fmakunbound 'n)` moves the definition to
the run-time function namespace (`compiler/RedefinedDefuns`). A write the compile cannot read does not:

```lisp
(defun n (x) (list :old x))
(defun install (name f) (setf (fdefinition name) f))
(install 'n (lambda (x) (list :new x)))
(funcall 'n 1) ; JVM, P1, component: (:OLD 1). Interpreter, SBCL: (:NEW 1)
```

Measured 2026-11-05: the same for a direct call, `#'n`, `(apply 'n)`, `(mapcar 'n)`, a literal
`symbol-function` / `fdefinition`, `(compile name def)` with `name` computed, and
`(eval '(defun n ...))`. `(funcall (intern "N") 1)` answers the new definition.
`.kb/symbol-runtime-api.md`, "Divergences".

Holding every defun a program spells as a value (`NestedDefunNamespace`'s rule) is the simple answer and
was not taken: such a program, the ci-spec corpus and any library with a computed write among them, would
move most of its functions into `_fenv` / `GLOBAL_FENV`, an alist that every namespace read and every
dispatcher symbol arm walks, with a rest list per direct call through the forwarder. What it needs first
is a constant-time cell per name on JVM and both WASM backends, then the whole corpus on all four.
