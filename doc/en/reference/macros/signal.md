# signal

`(signal datum args...)`

Signals a **non-fatal** condition with the same designator surface as [`error`](error.md): a literal control string (builds a `simple-condition`), a quoted condition-type symbol with initargs, or a condition object. When a [`handler-case`](handler-case.md) handler is established on the current thread of control the condition is raised to it; otherwise `signal` returns nil and execution continues (the Common Lisp fall-through). This works on every backend except `--no-gc`, whose compiler rejects catching (`signal` there always evaluates its arguments and returns nil). Lite: a raised signal that no clause of any established `handler-case` matches aborts like an error instead of falling through to nil.

```lisp
(signal "nothing is listening") ; => nil
```

```lisp
(handler-case (progn (signal "caught mid-flight") :not-raised)
  (condition (c) :raised)) ; => :raised
```
