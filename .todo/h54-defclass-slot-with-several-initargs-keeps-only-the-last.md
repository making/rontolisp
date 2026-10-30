# h54. A `defclass` slot with several `:initarg` options honors only the last one

Difficulty: Low

Found 2026-10-30 while working h44. CLHS 7.1.1 allows a slot to name more than one
initarg, and any of them initializes the slot:

```lisp
(defclass p () ((x :initarg :x :initarg :ex)))
(slot-value (make-instance 'p :x 1) 'x)   ; CLHS: 1   here: unbound (only :ex is kept)
```

Keep every `:initarg` of a slot (the constructor `&key` list, `:default-initargs`
matching, `change-class`/`reinitialize-instance`, `slot-definition-initargs`), with the
leftmost supplied argument winning per CLHS 7.1.4. All four backends; extend the
ci-spec case `defclass-default-initargs`.
