# make-string

`(make-string k)` `(make-string k char)`

長さ `k` で全要素が `char` の新しい変更可能な文字列を返します。`char` を省略すると空白で埋めます（R7RS では内容は未規定です）。

```scheme
(make-string 3 #\x) ; => "xxx"
(make-string 2) ; => "  "
```
