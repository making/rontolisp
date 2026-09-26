# rplaca

`(rplaca cons object)`

`cons` の car を `object` で破壊的に置き換え、コンスセルをその場で変更します。変更後のコンスセル自身を返します (新しい car ではありません)。そのため同じセルへの他の参照も変更を見ることができます。これは `car` に対する `setf` が展開される際のプリミティブです。コンス以外 (`nil` を含む) は `type-error` を通知します。

```lisp
(let ((c (cons 1 2))) (rplaca c 99) c) ; => (99 . 2)
```

```lisp
(handler-case (rplaca nil 1) (type-error (e) (princ-to-string e))) ; => "RPLACA: The value NIL is not of type CONS"
```
