# reduce

`(reduce function list &key initial-value)`

Combines the elements of `list` with a binary `function`, left-associatively: `(reduce #'f '(a b c))` computes `(f (f a b) c)`. With `:initial-value` the seed is supplied explicitly and folded in first: `(f (f (f init a) b) c)`; otherwise the first element of the list is the seed. The `:initial-value` keyword must be written literally (the compilers read it at compile time). An empty list returns the initial value, or calls `function` with no arguments when none was given.

```lisp
(reduce #'+ '(1 2 3) :initial-value 0) ; => 6
```
