# gentemp

`(gentemp &optional prefix package)`

Interns and returns a fresh symbol named `prefix` (default `"T"`) followed by a counter, skipping any name a symbol already claims. Unlike [`gensym`](gensym.md) the result is INTERNED, which is the whole point of the function -- CLHS deprecates it, but iterate names its clause dispatch functions with it.

```lisp
(let ((a (gentemp "Q")) (b (gentemp "Q"))) (eq a b)) ; => NIL
```
