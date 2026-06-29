# char-code

`(char-code character)`

`character` の整数コードポイントを返します。ASCII 文字の場合はおなじみの値になります（たとえば `#\A` は `65`）。`code-char` の逆の操作です。

```lisp
(char-code #\A) ; => 65
```
