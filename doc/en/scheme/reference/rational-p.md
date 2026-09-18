# rational?

`(rational? obj)`

Returns `#t` when `obj` is a rational number: an exact integer or ratio, or a finite flonum.

```scheme
(rational? 1/3) ; => #t
(rational? 1.5) ; => #t
(rational? 'x) ; => #f
```
