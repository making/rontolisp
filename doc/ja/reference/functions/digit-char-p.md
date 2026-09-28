# digit-char-p

`(digit-char-p character &optional radix)`

指定された `radix`（デフォルトは 10）における桁としての `character` の整数値を返します。その基数で有効な桁でない場合は `nil` を返します。たとえば `#\7` は `7`、基数 16 では `#\f` は `15` です。10 を超える基数では、英字が大文字・小文字を区別せずに桁として受け付けられます。文字でない引数や整数でない `radix` は、演算子名を付けた `type-error` を通知します。

```lisp
(digit-char-p #\7) ; => 7
```
