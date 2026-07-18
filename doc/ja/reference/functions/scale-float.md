# scale-float

`(scale-float float integer)`

`float × 2^integer` を IEEE 754 の正確な意味論 (非正規化数の範囲を含む) で返します。

```lisp
(scale-float 1.5 3) ; => 12.0
```
