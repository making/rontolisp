# endp

`(endp list)`

リストの終端判定です。`list` が `nil`（空リスト）のときに `t`、コンスセルのときに `nil` を返します。cdr でリストを辿りながら終端を検出する標準的な方法です。rontolisp では `null` の同義語として動作し、標準 Common Lisp の厳密な非真リストの型チェックは緩和されています。

```lisp
(endp '(1)) ; => nil
```

```lisp
(endp nil) ; => t
```
