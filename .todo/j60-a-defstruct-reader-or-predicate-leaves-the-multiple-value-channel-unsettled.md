# j60. A defstruct reader or predicate leaves the multiple-value channel unsettled on the compile paths

Difficulty: Medium

Found 2026-11-16 (working j55), present before it. A structure's reader or predicate
called on an argument that publishes extra values hands them on to a consumer:

```lisp
(defstruct pt x y)
(defun two (p) (values p 2))
(defun g (p) (pt-x p))
(let ((p (make-pt :x 1)))
  (print (multiple-value-list (pt-x (two p))))
  (print (multiple-value-list (pt-p (two p))))
  (print (multiple-value-list (g (two p)))))
```

The interpreter prints `(1)`, `(T)`, `(1)`; the JVM, WASM preview 1 and the component
print `(1 2)`, `(T 2)`, `(1 2)`. `(car (two p))` in the same place is `(1)` on all four.

The generated bodies are `(%obj-ref p i failure)` and `(%obj-is p 'tag)`, which
`LispMacroExpander.settleTail` classifies as an internal helper that passes values along
(`.kb/multiple-values.md`, "A tail settles the channel"), so the defun's tail neither
publishes nor clears.

## Do

- A failing case first: the program above as a `ci-spec.yaml` case (four backends).
- `%obj-ref` / `%obj-is` (and the copier's and the typed-vector accessors' tails) as one
  value, cleared: quiet operators where the argument is quiet. A program with a
  multiple-value consumer then carries a clear per accessor defun; measure the sizes.
- wasm compiles a call of a reader or a predicate as its primitive and already emits the
  definition's clear when its settled tail has one (`WasmStructAccess.clearOf`,
  `.kb/defstruct.md`), so the call sites follow the definition; check that with the case.
