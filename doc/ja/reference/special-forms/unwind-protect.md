# unwind-protect

`(unwind-protect protected cleanup...)`

`protected` フォームを評価してその値を返し、そこからの**あらゆる**脱出時に `cleanup` フォームを実行します: 通常の復帰、`error` による巻き戻し、`return`/`return-from` による非局所脱出のいずれでも実行されます。cleanup の値は捨てられます。cleanup フォーム自身がエラーを通知した場合は、進行中の脱出を置き換えます(Common Lisp と同じく、新しいエラーが勝ちます)。

`unwind-protect` は**インタプリタと JVM バックエンド**でサポートされます。WASM コンパイラは拒否します(WASM のエラーは捕捉不能なトラップであり、cleanup を実行する巻き戻し経路が存在しないため)。`with-*` マクロ(`with-open-file`、`with-output-to-string`、`with-input-from-string`、および `usocket:with-*` ファミリ)はインタプリタと JVM では `unwind-protect` に展開されるため、そこではあらゆる脱出時にハンドルを解放します。WASM では従来どおり正常終了時のみクローズする形を保ちます。

```lisp
(let ((n 1))
  (list (unwind-protect (+ n 1) (setq n 10)) n)) ; => (2 10)
```

protected フォームが `return` で早期脱出する場合も cleanup は実行されます:

```lisp
(let ((log nil))
  (dolist (x '(1 2 3))
    (unwind-protect
        (when (= x 2) (return))
      (setq log (cons x log))))
  log) ; => (2 1)
```

捕捉されない `error` はプログラムを中断するため、エラー経路は静的な例で示します:

```console
> (unwind-protect (error "boom") (print :cleaned))
:cleaned
Error: boom
```
