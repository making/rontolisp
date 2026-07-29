# restart-case

`(restart-case form (restart-name (arg...) [:report r] [:interactive i] [:test t] body...)...)`

節ごとに 1 つの**リスタート**をその動的スコープの間確立して `form` を評価します。正常終了時には何も起きません — フォームの値が返り、リスタートは解除されます。フォームの内側で実行中のコード(典型的にはシグナル点で実行される [`handler-bind`](handler-bind.md) ハンドラ)が [`invoke-restart`](../functions/invoke-restart.md) でリスタートを起動すると、制御は `restart-case` まで巻き戻り(途中の `unwind-protect` の cleanup を実行しつつ)、節本体が **restart-case 自身の字句環境で**、起動引数を `arg...` に束縛して実行されます — そのため節本体から外側の関数を `return-from` したり、外側の `tagbody` のタグへ `go` したり(リトライループのイディオム)できます。リスタート名はシンボルまたはキーワードです。[`find-restart`](../functions/find-restart.md) は最内のアクティブなリスタートを第一級オブジェクトとして返し、[`compute-restarts`](../functions/compute-restarts.md) は全リスタートを列挙します。`:report`/`:interactive`/`:test` オプションは受理されリスタートレコードに保存されます(rontolisp にはレポートを描画したり対話的にリスタートを起動するもの — デバッガ — はありません)。

`--no-gc` を除くすべてのバックエンドでサポートされます(`--no-gc` は従来どおり主フォームのみへの簡易展開を保ちます)。リスタートシステムを使うプログラムは wasm-GC バックエンドでは EH モードでコンパイルされます(`wasmtime -W exceptions=y`)。lite の相違点: 節パラメータの `&optional` はデフォルト値の代わりに未指定時 `nil` になり、リスタートとコンディションの関連付けはなく(`find-restart` の省略可能なコンディション引数は無視されます)、リスタートオブジェクトは `#<RESTART ...>` ではなく素のリストとして印字されます。

```lisp
(restart-case (+ 1 2)
  (continue () 99)) ; => 3
```

ハンドラがキーワード名でリスタートを引数付きで起動し、節本体の値がフォーム全体の値になります:

```lisp
(handler-bind ((error (lambda (c) (invoke-restart :reconnect "db-1"))))
  (restart-case (error "connection lost")
    (:reconnect (host) (list :reconnected host)))) ; => (:RECONNECTED "db-1")
```

リトライのイディオム — 節本体から外側の `tagbody` へ `go` で戻ります:

```lisp
(let ((n 0))
  (handler-bind ((error (lambda (c) (invoke-restart 'retry))))
    (tagbody start
      (restart-case
          (progn (setq n (+ n 1)) (when (< n 3) (error "again")))
        (retry () (go start)))))
  n) ; => 3
```
