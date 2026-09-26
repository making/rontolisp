# The interpreter keeps a producer's lowering in a defun defined before the defmethod

Difficulty: Medium

```lisp
(defclass bx () ())
(defun early (x) (floor x))
(defmethod floor ((b bx) &optional d) (declare (ignore d)) 44)
(print (early (make-instance 'bx)))
```

| interpreter | JVM / wasm (2026-09-26) |
|---|---|
| `-: The value #<BX> is not of type NUMBER` | `44` |

`evalDefun` lowers a syntactic multiple-value producer in the body's tail once, at definition
(`LispMacroExpander.spillEscapingMvProducers`), into `floor`'s quotient and `(- x q)`. A
`defmethod` on the producer that comes LATER is invisible to that body: the rename onto
`%floor--dispatch` (`LispEvaluator.dispatchingMethodedProducers`, `.kb/clos.md`, "Interpreter,
the call side") only reaches bodies defined after it. The compile paths see the whole program
and dispatch. Same for the other producers (`gethash`, `find-symbol`, `intern`,
`read-from-string`, `array-displacement`, `values`).

Goal: order independence. Either the tail publish is decided at run time (a producer tail that
calls the current binding of the name -- the publishing function object or the dispatcher --
instead of the syntactic lowering; measure the interpreter cost against the lowering), or the
evaluator re-lowers the affected defuns when a producer gets its first method.
