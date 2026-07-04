# reduce

`(reduce function sequence &key initial-value)`

Combines the elements of `sequence` with a binary `function`, left-associatively: `(reduce #'f '(a b c))` computes `(f (f a b) c)`. The sequence may be a list or a string, whose characters are folded. With `:initial-value` the seed is supplied explicitly and folded in first: `(f (f (f init a) b) c)`; otherwise the first element of the sequence is the seed. The `:initial-value` keyword must be written literally (the compilers read it at compile time). An empty sequence returns the initial value, or calls `function` with no arguments when none was given.

```lisp
(reduce #'+ '(1 2 3) :initial-value 0) ; => 6
```

```lisp
(reduce (lambda (acc c) (if (char= c #\a) (+ acc 1) acc)) "banana" :initial-value 0) ; => 3
```
