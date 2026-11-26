# car

`(car pair)`

Returns the car (first element) of `pair`.

Deviation: `(car '())` answers `()` instead of signalling an error. Any other non-pair is an error whose message spells the Common Lisp name: `CAR: The value 5 is not of type LIST`.

```scheme
(car '(1 2 3)) ; => 1
(car '((a b) c)) ; => (a b)
(car '(a . b)) ; => a
```
