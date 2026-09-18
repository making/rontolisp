# eq?

`(eq? obj1 obj2)`

Returns `#t` when `obj1` and `obj2` are the same object. Symbols, `'()`, booleans, characters and exact integers are `eq?` whenever they are equal; flonums and exact ratios are distinct objects, so `(eq? 1.5 1.5)` is `#f` -- use `eqv?` for numbers. Two pairs, strings or vectors are `eq?` only when they are one object.

```scheme
(eq? 'a 'a) ; => #t
(eq? (list 1) (list 1)) ; => #f
(eq? 1.5 1.5) ; => #f
```
