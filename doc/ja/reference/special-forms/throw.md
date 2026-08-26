# throw

`(throw tag result)`

タグが `tag` と `eq` であるもっとも内側のアクティブな [`catch`](catch.md) へ制御を移し、`result` をその `catch` フォームの値にします。スタックは実際に巻き戻されるため、途中の [`unwind-protect`](unwind-protect.md) の cleanup は内側から順にすべて実行されます。また、間にある `handler-case` はこれを**捕捉しません** — `throw` はシグナルされたコンディションではなく非局所脱出だからです。

一致する `catch` がアクティブでない場合 `throw` はエラーです: インタプリタは `THROW: no enclosing catch for tag ...` を報告し、JVM バックエンドは対応するランタイムエラーを送出し、wasm-GC バックエンドは(捕捉されない `error` と同じく)トラップします。`result` フォームは巻き戻しが始まる前に評価されます。

```lisp
(let ((log nil))
  (list (catch 'up
          (unwind-protect (throw 'up :out) (setq log (cons :cleaned log))))
        log)) ; => (:OUT (:CLEANED))
```

`handler-case` を通り抜ける `throw` はそれに捕捉されません:

```lisp
(catch 'up (handler-case (throw 'up :through) (error (e) :caught))) ; => :THROUGH
```

一致しない `throw` はプログラムを中断するため、その経路は静的な例で示します:

```console
CL-USER> (throw 'nope 1)
Error: THROW: no enclosing catch for tag NOPE
```
