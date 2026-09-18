# number?

`(number? obj)`

`obj` が数値なら `#t` を返します。複素数はないため、すべての数値は実数です。

```scheme
(number? 1/2) ; => #t
(number? 'a) ; => #f
```
