# integer-decode-float

`(integer-decode-float float)`

3 つの値を返します。整数としての仮数部、2 進の指数部、符号(`1.0` または `-1.0`)で、`仮数部 * 2^指数部 * 符号` が元の数になります。0 は `0`、`0`、その符号に分解されます。分解は 2 倍・1/2 倍のみで行われ、2 進浮動小数点数では誤差なく計算できるため、すべてのバックエンドがビット単位で同じ値を返します。仮数部を浮動小数点数で返す同じ分解は [`decode-float`](decode-float.md) です。

```lisp
(multiple-value-list (integer-decode-float 6.5)) ; => (13 -1 1.0)
```
