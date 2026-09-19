# eq?

`(eq? obj1 obj2)`

Returns `#t` when `obj1` and `obj2` are the same object. Symbols, `'()` and booleans are `eq?` whenever they are equal. R7RS leaves `eq?` on numbers and characters unspecified; here it is exactly `eqv?`, so `(eq? 1.5 1.5)` is `#t` and a flonum is always `eq?` to itself -- `eqv?` is the portable spelling. Two pairs, strings or vectors are `eq?` only when they are one object.

```scheme
(eq? 'a 'a) ; => #t
(eq? (list 1) (list 1)) ; => #f
(eq? 1.5 1.5) ; => #t
```
