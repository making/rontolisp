# vector?

`(vector? obj)`

`obj` がベクタなら `#t`、そうでなければ `#f` を返します。文字列、リスト、バイトベクタはベクタではありません。

```scheme
(vector? #(1 2)) ; => #t
(vector? "abc") ; => #f
```
