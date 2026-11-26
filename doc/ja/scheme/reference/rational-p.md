# rational?

`(rational? obj)`

`obj` が有理数なら `#t` を返します。正確な整数や分数、有限の浮動小数点数が該当し、無限大と NaN は該当しません。

```scheme
(rational? 1/3) ; => #t
(rational? 1.5) ; => #t
(rational? (/ 1 0.0)) ; => #f
(rational? 'x) ; => #f
```
