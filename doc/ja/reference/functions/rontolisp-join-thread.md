# rontolisp:join-thread

`(rontolisp:join-thread thread)`

スレッドの関数が返るまでブロックし、スレッド自身の終了も待ってから、関数の値を
返します。スレッドがエラーを通知して終了した場合、そのエラーは join したスレッドで
再通知されるため、join を囲む `handler-case` は同一スレッドでの通知と全く同じように
コンディション型でディスパッチします。

```lisp
(rontolisp:join-thread (rontolisp:make-thread (lambda () (+ 40 2)))) ; => 42
```

join の後、同じハンドルへの
[`rontolisp:thread-alive-p`](rontolisp-thread-alive-p.md) は `nil` を返します。

## 制限

- [`rontolisp:make-thread`](rontolisp-make-thread.md) 自身と同様、インタプリタと
  JVM バックエンドのみです。
- スレッドハンドルでない値はエラーです。
- タイムアウト付きの join はありません。
