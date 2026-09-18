# vector?

`(vector? obj)`

`obj` がベクタなら `#t`、そうでなければ `#f` を返します。文字列やリストはベクタではありません。

```scheme
(vector? #(1 2)) ; => #t
(vector? "abc") ; => #f
```
