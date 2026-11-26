# name-char

`(name-char name)`

文字列指定子が名前とする文字を `string-equal` で比較して返します。対象は [`char-name`](char-name.md) が返す名前 (`"Space"`、`"Newline"`、`"Tab"`、`"Return"`、`"Page"`、`"Backspace"`、`"Null"`、`"Rubout"`)、`"Linefeed"` と `"Nul"`、および `"U+"` に16進数字が続く形式です。それ以外の文字列は、1文字のものも含めてどの文字の名前でもなく、nil を返します。

```lisp
(name-char "space") ; => #\Space
```

```lisp
(name-char "a") ; => NIL
```
