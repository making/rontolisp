# handler-case

`(handler-case expression (type ([var]) body...)... [(:no-error ([var]) body...)])`

`expression` を評価し、その間にエラーが通知されたら、通知されたコンディションに `type` がマッチする最初の節に制御を移します。`var`(省略可)にはコンディションオブジェクトが束縛され、節本体の値がフォーム全体の値になります。どの節もマッチしなければエラーは外側へ伝播します(外側の `handler-case` が捕捉できます)。節の型には任意の `typecase` 指定子が使えます。[`define-condition`](define-condition.md) で定義したコンディションクラスと組み込み階層(`condition` > `serious-condition` > `error`、`warning`)を含みます。コンディションオブジェクトなしで通知されたエラー(素の `(error "...")` や組み込み内部の実行時エラー)は、スロット 1 にメッセージを持つ `simple-error` として捕捉されます。`:no-error` 節は正常終了時に(主)値を `var` に束縛して、ハンドラの外で実行されます。非局所脱出(`return`/`return-from`)は捕捉されずに通過し、expression 内の `unwind-protect` はハンドラより先に cleanup を実行します。

`handler-case` は `--no-gc`(コンパイルエラー)を除く**すべてのバックエンド**でサポートされます。wasm-GC バックエンド(Preview 1 と `--component`、`wasmtime serve` を含む)では WebAssembly の exception-handling プロポーザルを通じてコンパイルされるため、捕捉フォームを使うプログラムの実行には wasmtime 37+ で同プロポーザルの有効化が必要です: 通常の `wasmtime run`/`wasmtime serve` のフラグに `-W exceptions=y` を追加してください。捕捉フォームを使わないプログラムは従来とバイト単位で同一であり、コマンドラインも変わりません。相違点: WASM バックエンドが捕捉できるのは**シグナルされたコンディションのみ**です — ランタイムトラップ(`(car 5)` のような型エラー、整数のゼロ除算)はそこでは捕捉不能のままですが、インタプリタと JVM はエラーとして捕捉します。ハンドラはスレッド単位なので、`rontolisp:http-handler` の並行リクエスト同士は干渉しません。lite: `handler-bind`/`restart-case` のリスタートは未対応です。

```lisp
(handler-case (error "boom")
  (error (e) (list :caught (nth 1 e)))) ; => (:caught "boom")
```

型付きコンディションはクラス階層でディスパッチされ、最初にマッチした節が勝ちます:

```lisp
(define-condition low-fuel (warning) ((level :initarg :level :reader low-fuel-level)))
(handler-case (error 'low-fuel :level 5)
  (error (e) :error)
  (warning (w) (list :warned (low-fuel-level w)))) ; => (:warned 5)
```

```lisp
(handler-case (+ 1 2)
  (error (e) :err)
  (:no-error (v) (list :ok v))) ; => (:ok 3)
```
