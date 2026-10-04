# c89. The eval mirror keeps a special's binding value after the binding's extent

Difficulty: Medium

On the compile paths a special lives in two homes: the variable (JVM `_g$` / `_d$` cell, wasm
module global or task record) and the eval runtime's `_genv` / `GLOBAL_ENV` mirror that `eval`,
`boundp` and a raw `symbol-value` read (`.kb/dynamic-special-variables.md`, limitation 2). A store
from a frame that does not hold the binding's lexical slot -- a callee's `setq`, any `set` --
writes the variable's ACTIVE binding and then the mirror; the binding's restore does not touch the
mirror, so after the extent the mirror holds the binding's value. Measured 2026-10-04 (after
`.todo/c86`), JVM, wasm Preview 1 and component alike, interpreter and SBCL as the last column:

```lisp
(defvar *dw* 1)
(defun f () (setq *dw* 3))
(print (list (let ((*dw* 2)) (f) (list *dw* (symbol-value '*dw*))) *dw* (symbol-value '*dw*)))
;; compiled ((3 3) 1 3), interpreter/SBCL ((3 3) 1 1)

(defvar *dv* 1)
(defun probe (name) (let ((*dv* 2)) (set name 3) (eval '*dv*)))
(print (list (probe '*dv*) *dv* (eval '*dv*)))
;; compiled (3 1 3), interpreter/SBCL (3 1 1)
```

In a program that uses `set` or `progv`, `symbol-value` reads dynamic-first and is right; the
first program has neither, and `eval`/`boundp` read the mirror in every program.

The JVM alone could mirror only when `_dset` fell through to `_g$`; wasm's shallow binding cannot
tell "a binding is active" at the store, so that would split the backends. The essential fix is one
home: the mirror never answers for a special -- `eval`'s variable lookup, `boundp` and the raw
`symbol-value` resolve a special name through the compiled variable (the
`%symbol-value-dynamic` dispatch already does for `symbol-value`), which also retires limitation 2.
Measure the size of making that dispatch reachable from the eval runtime before choosing.
