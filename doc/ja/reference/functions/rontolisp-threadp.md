# rontolisp:threadp

`(rontolisp:threadp value)`

`value` が([`rontolisp:make-thread`](rontolisp-make-thread.md) が返す)スレッド
ハンドルなら `t`、そうでなければ `nil` を返します。任意の値を受け付ける唯一の
スレッド操作です。

```lisp
(list (rontolisp:threadp (rontolisp:make-thread (lambda () 1)))
      (rontolisp:threadp 42)) ; => (T NIL)
```

WASM バックエンドではスレッドハンドルは存在し得ないため、`bt2:threadp` シムは常に
`nil` を返します — Clack の `stop` が非スレッド分岐を取るのはこの仕組みによります。
