# abort

`(abort [condition])`

Invokes the innermost active `abort` restart. A program rontolisp runs establishes no `abort` restart of its own, so this reaches only a restart your program established under that name — and signals an error when none is active, the CL contract. Under [`rontolisp --swank`](../../getting-started/swank.md) it also reaches the ones SWANK establishes: the request's return to SLIME's top level, and each debugger level's return to the level below.

```lisp
(restart-case (progn (abort) :not-reached)
  (abort () :aborted)) ; => :ABORTED
```
