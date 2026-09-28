# char-int

`(char-int character)`

`character` を表す非負整数を返します。コードポイント以外に実装固有の属性を持たないため、`char-int` は `char-code` と同じ値を返します。文字でない引数は、演算子名を付けた `type-error` を通知します（`CHAR-INT: The value 1 is not of type CHARACTER`）。

```lisp
(char-int #\A) ; => 65
```
