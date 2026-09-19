# nan?

`(nan? z)`

`z` が NaN なら `#t` を返します。正確数が NaN になることはありません。

```scheme
(nan? (/ 0.0 0.0)) ; => #t
(nan? 1) ; => #f
(nan? +nan.0) ; => #t
```
