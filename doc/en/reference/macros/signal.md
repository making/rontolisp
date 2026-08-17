# signal

`(signal datum args...)`

Signals a **non-fatal** condition with the same designator surface as [`error`](error.md): a control string, literal or computed, with the arguments after it as its format arguments (builds a `simple-condition`), a quoted condition-type symbol with initargs, or a condition object. When an established [`handler-case`](handler-case.md) has a clause matching the condition, the signal transfers control to it; otherwise -- no handler at all, or none whose clauses match -- `signal` returns nil and execution continues (the Common Lisp fall-through, CLHS 9.1.4.1). A `handler-case` whose clauses do not match is declined and stays armed for a later condition that does. This works on every backend except `--no-gc`, whose compiler rejects catching (`signal` there always evaluates its arguments and returns nil).

```lisp
(signal "nothing is listening") ; => NIL
```

```lisp
(handler-case (progn (signal "caught mid-flight") :not-raised)
  (condition (c) :raised)) ; => :RAISED
```

```lisp
(handler-case (progn (signal "nobody handles this") :fell-through)
  (type-error (c) :caught)) ; => :FELL-THROUGH
```
