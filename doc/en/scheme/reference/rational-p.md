# rational?

`(rational? obj)`

Returns `#t` when `obj` is a rational number: an exact integer or ratio, or a finite flonum -- not an infinity or NaN.

```scheme
(rational? 1/3) ; => #t
(rational? 1.5) ; => #t
(rational? (/ 1 0.0)) ; => #f
(rational? 'x) ; => #f
```
