# simple-string-p

`(simple-string-p object)`

`object` が文字列であれば真を返します。lite 実装: rontolisp のすべての文字列が真になります(独立した simple-string 表現はありません)。そのためポータブルな「`simple-string-p` でなければ coerce する」イディオムは、コピーせず文字列をそのまま使います。

```lisp
(simple-string-p "abc") ; => t
```

```lisp
(simple-string-p 42) ; => nil
```
