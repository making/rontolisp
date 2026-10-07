# scale-float

`(scale-float float integer)`

`float × 2^integer` を IEEE 754 の正確な意味論 (非正規化数の範囲を含む) で返します。第 1 引数は浮動小数点数、第 2 引数は整数でなければならず、SBCL と同じです。第 1 引数が整数・有理数・複素数・非数なら型 `FLOAT` の `type-error`、第 2 引数が整数でなければ型 `INTEGER` の `type-error` を通知し、浮動小数点数の検査が先です。fixnum に収まらない指数は結果を飽和させます。

```lisp
(scale-float 1.5 3) ; => 12.0
```
