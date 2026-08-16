# unwind-protect

`(unwind-protect protected cleanup...)`

`protected` フォームを評価してその値を返し、そこからの**あらゆる**脱出時に `cleanup` フォームを実行します: 通常の復帰、`error` による巻き戻し、`return`/`return-from` による非局所脱出のいずれでも実行されます。cleanup の値は捨てられます — 値の個数も含めて捨てられるため、protected フォームが[多値](../functions/values.md)を返す場合は、cleanup が何個の値を返そうとそのすべてが返ります。cleanup フォーム自身がエラーを通知した場合は、進行中の脱出を置き換えます(Common Lisp と同じく、新しいエラーが勝ちます)。

`unwind-protect` は `--no-gc`(コンパイルエラー)を除く**すべてのバックエンド**でサポートされます。wasm-GC バックエンド(Preview 1 と `--component`)では WebAssembly の exception-handling プロポーザルを通じてコンパイルされるため、これを使うプログラムの実行には wasmtime 37+ で `-W exceptions=y` が必要です。捕捉/cleanup フォームを使わないプログラムは従来とバイト単位で同一であり、コマンドラインも変わりません。相違点: cleanup は**シグナルされた**エラーの巻き戻し(`error`/`signal`)では実行されますが、ランタイムトラップ(`(car 5)` のような型エラー、整数のゼロ除算)は依然として cleanup を実行せずインスタンスを終了させます — インタプリタと JVM はこれらでも cleanup を実行します。`with-*` マクロ(`with-open-file`、`with-output-to-string`、`with-input-from-string`、および `usocket:with-*` ファミリ)はすべてのバックエンドで `unwind-protect` に展開されるため、あらゆる脱出時にハンドルを解放します。したがって wasm-GC では `with-*` を使うプログラムも EH モードでコンパイルされ、`-W exceptions=y` が必要です。

```lisp
(let ((n 1))
  (list (unwind-protect (+ n 1) (setq n 10)) n)) ; => (2 10)
```

cleanup 自身が値を返す場合 — 解放ヘルパーの慣用句である「何も返さない」`(values)` を含めて — protected フォームの値が切り詰められることはありません:

```lisp
(multiple-value-list (unwind-protect (values 1 2 3) (values 7 8))) ; => (1 2 3)
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
