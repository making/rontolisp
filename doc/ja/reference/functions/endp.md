# endp

`(endp list)`

リストの終端判定です。`list` が `nil`（空リスト）のときに `t`、コンスセルのときに `nil` を返します。cdr でリストを辿りながら終端を検出する標準的な方法です。それ以外の値は `type-error`（期待型 `LIST`）を通知するため、非真リストの末尾を検出できます。

```lisp
(endp '(1)) ; => NIL
```

```lisp
(endp nil) ; => T
```

```lisp
(handler-case (endp 5) (type-error (e) (princ-to-string e))) ; => "ENDP: The value 5 is not of type LIST"
```
