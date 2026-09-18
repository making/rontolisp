# rational?

`(rational? obj)`

`obj` が有理数なら `#t` を返します。正確な整数や分数、有限の浮動小数点数が該当します。

```scheme
(rational? 1/3) ; => #t
(rational? 1.5) ; => #t
(rational? 'x) ; => #f
```
