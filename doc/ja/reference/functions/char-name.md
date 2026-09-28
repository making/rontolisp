# char-name

`(char-name character)`

非図形文字の名前 (`"Space"`、`"Newline"`、`"Tab"` など)、その他の非印字コードポイントには `"U+XXXX"` 形式、図形文字には nil を返します。文字でない引数は、演算子名を付けた `type-error` を通知します。

```lisp
(char-name #\Space) ; => "Space"
```

```lisp
(char-name #\a) ; => NIL
```
