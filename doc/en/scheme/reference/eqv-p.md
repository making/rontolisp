# eqv?

`(eqv? obj1 obj2)`

Like `eq?`, but numbers of the same exactness and value are equivalent, so `(eqv? 1.5 1.5)` is `#t`, while `(eqv? 2 2.0)` is `#f` because the exactness differs. `0.0` and `-0.0` are not `eqv?`. Pairs, strings, vectors and procedures compare by identity; two string literals with the same text in one program may be the same object, which R7RS permits.

```scheme
(eqv? 1.5 1.5) ; => #t
(eqv? 2 2.0) ; => #f
(eqv? (string #\a) (string #\a)) ; => #f
```
