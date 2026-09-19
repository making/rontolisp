# infinite?

`(infinite? z)`

`z` が正または負の無限大なら `#t` を返します。正確数が無限大になることはありません。

```scheme
(infinite? (/ -1.0 0.0)) ; => #t
(infinite? 3) ; => #f
(infinite? +inf.0) ; => #t
```
