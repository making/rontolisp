# string-equal

`(string-equal string1 string2)`

2 つの文字列を大文字小文字を無視して 1 文字ずつ比較し、一致するとき `t` を、そうでなければ `nil` を返します。大文字小文字の畳み込みは ASCII 規則に従うため、`"ABC"` と `"abc"` は等しくなります。大文字小文字を区別する比較には `string=` を使用してください。

```lisp
(string-equal "ABC" "abc") ; => T
```
