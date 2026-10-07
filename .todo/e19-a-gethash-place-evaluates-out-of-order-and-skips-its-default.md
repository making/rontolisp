# e19. A `gethash` place evaluates out of order and never evaluates its default

Difficulty: Low

```lisp
(defun tr (x v) (princ x) v)
(let ((h (make-hash-table))) (setf (gethash (tr "a" 1) (tr "b" h)) (tr "c" 2)))
(terpri)
(let ((h (make-hash-table))) (setf (gethash (tr "a" 1) (tr "b" h) (tr "x" 0)) (tr "c" 2)))
```

SBCL 2.2.9: `abc`, then `abxc` (CLHS 5.1.1.1: the place's subforms left to right, then the
value). Interpreter and JVM: `abc`, then `abc`; P1 and component: `acb`, then `acb`.
`expandSetf`'s `GETHASH` case lowers to `(%puthash key table value)` and drops the default
subform; the wasm `%puthash` emission evaluates the value before the table.

## Plan

- Four-backend fixture first (a traced key, table, default and value; `incf` through a
  `gethash` place with a default).
- Evaluate the default for effect in its place; make the wasm `%puthash` evaluate its operands
  left to right (`.kb/argument-evaluation-order.md`), keeping a site of variables and constants
  byte-identical.
