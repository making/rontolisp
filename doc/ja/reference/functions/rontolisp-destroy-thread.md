# rontolisp:destroy-thread

`(rontolisp:destroy-thread thread)`

ハンドルの背後のスレッドに割り込みをかけ、ハンドルを返します。待機操作でブロック
しているスレッドはそこでエラーとともにブロックが解除されます。Java の
`Thread.interrupt` と同様、ブロックしないボディはそのまま完了することがあります —
これは要求であり、強制終了ではありません。

```lisp
(let ((th (rontolisp:make-thread (lambda () 1))))
  (rontolisp:join-thread th)
  (rontolisp:threadp (rontolisp:destroy-thread th))) ; => T
```

## 制限

- 割り込みの伝達は非同期です。返った直後の `thread-alive-p` はしばらく `t` を
  返すことがあります。
- スレッドハンドルでない値はエラーです。
- [`rontolisp:make-thread`](rontolisp-make-thread.md) 自身と同様、インタプリタと
  JVM バックエンドのみです。
