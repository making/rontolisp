# rontolisp:thread-alive-p

`(rontolisp:thread-alive-p thread)`

ハンドルの背後のスレッドが実行中なら `t`、終了していれば `nil` を返します。
[`rontolisp:join-thread`](rontolisp-join-thread.md) の後は確実に `nil` です
(join は値だけでなくスレッドの終了処理まで待ちます)。

```lisp
(let ((th (rontolisp:make-thread (lambda () 1))))
  (rontolisp:join-thread th)
  (rontolisp:thread-alive-p th)) ; => NIL
```

## 制限

- スレッドハンドルでない値はエラーです(不確かな場合は先に
  [`rontolisp:threadp`](rontolisp-threadp.md) を使ってください)。
- [`rontolisp:make-thread`](rontolisp-make-thread.md) 自身と同様、インタプリタと
  JVM バックエンドのみです。
