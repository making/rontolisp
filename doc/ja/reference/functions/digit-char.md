# digit-char

`(digit-char weight &optional radix)`

基数 `radix` (既定は 10) において `weight` を表す文字を大文字で返します。weight が基数未満でない場合は `nil` を返します。非負整数でない `weight` は `type-error`（型は `UNSIGNED-BYTE`）を通知し、2 から 36 までの整数でない `radix` も同様です（型は `(INTEGER 2 36)`）。検査は weight が先です。[`digit-char-p`](digit-char-p.md) の逆変換です。

```lisp
(list (digit-char 7) (digit-char 11 16) (digit-char 12)) ; => (#\7 #\B NIL)
```
