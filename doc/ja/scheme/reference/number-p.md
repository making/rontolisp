# number?

`(number? obj)`

`obj` が数値なら `#t` を返します。複素数も数値です。

```scheme
(number? 1/2) ; => #t
(number? 'a) ; => #f
```
