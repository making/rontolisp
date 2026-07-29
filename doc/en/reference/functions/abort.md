# abort

`(abort [condition])`

Invokes the innermost active `abort` restart. rontolisp establishes no `abort` restart of its own (there is no debugger REPL), so this reaches only a restart your program established under that name — and signals an error when none is active, the CL contract.

```lisp
(restart-case (progn (abort) :not-reached)
  (abort () :aborted)) ; => :ABORTED
```
