# reduce

`(reduce function sequence &key initial-value from-end key)`

Combines the elements of `sequence` with a binary `function`, left-associatively: `(reduce #'f '(a b c))` computes `(f (f a b) c)`. The sequence may be a list or a string, whose characters are folded. With `:initial-value` the seed is supplied explicitly and folded in first: `(f (f (f init a) b) c)`; otherwise the first element of the sequence is the seed. With `:from-end t` the elements are combined right-associatively with the accumulator on the right: `(reduce #'f '(a b c) :from-end t)` computes `(f a (f b c))`, and with `:initial-value i`, `(f a (f b (f c i)))`. `:key` names a one-argument function applied to each sequence element (but not the initial value) before folding. Keywords may appear in any order; `:initial-value`/`:from-end`/`:key` are read at compile time. An empty sequence returns the initial value, or calls `function` with no arguments when none was given.

```lisp
(reduce #'+ '(1 2 3) :initial-value 0) ; => 6
```

```lisp
(reduce (lambda (acc c) (if (char= c #\a) (+ acc 1) acc)) "banana" :initial-value 0) ; => 3
```

```lisp
(reduce #'cons '((1) (2) (3)) :from-end t :key #'car :initial-value nil) ; => (1 2 3)
```
