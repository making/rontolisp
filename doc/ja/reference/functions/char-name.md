# char-name

`(char-name character)`

非図形文字の名前 (`"Space"`、`"Newline"`、`"Tab"` など)、その他の非印字コードポイントには `"U+XXXX"` 形式、図形文字には nil を返します。

```lisp
(char-name #\Space) ; => "Space"
```

```lisp
(char-name #\a) ; => NIL
```
