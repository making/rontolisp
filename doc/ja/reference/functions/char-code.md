# char-code

`(char-code character)`

`character` の整数コードポイントを返します。ASCII 文字の場合はおなじみの値になります（たとえば `#\A` は `65`）。`code-char` の逆の操作です。文字でない引数は、演算子名を付けた `type-error` を通知します（`CHAR-CODE: The value 1 is not of type CHARACTER`）。

```lisp
(char-code #\A) ; => 65
```
