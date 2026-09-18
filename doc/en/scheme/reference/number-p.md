# number?

`(number? obj)`

Returns `#t` when `obj` is a number. Every number is real: there are no complex numbers.

```scheme
(number? 1/2) ; => #t
(number? 'a) ; => #f
```
