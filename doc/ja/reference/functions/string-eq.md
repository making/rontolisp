# string=

`(string= string1 string2)`

2 つの文字列を 1 文字ずつ比較し、完全に等しいとき `t` を、そうでなければ `nil` を返します。比較は大文字小文字を区別するため、`"abc"` と `"ABC"` は等しくありません。大文字小文字を区別しないテストには `string-equal` を使用してください。

```lisp
(string= "abc" "abc") ; => t
```
