# handler-case

`(handler-case expression (type ([var]) body...)... [(:no-error ([var]) body...)])`

`expression` を評価し、その間にエラーが通知されたら、通知されたコンディションに `type` がマッチする最初の節に制御を移します。`var`(省略可)にはコンディションオブジェクトが束縛され、節本体の値がフォーム全体の値になります。どの節もマッチしなければエラーは外側へ伝播します(外側の `handler-case` が捕捉できます)。節の型には任意の `typecase` 指定子が使えます。[`define-condition`](define-condition.md) で定義したコンディションクラスと組み込み階層(`condition` > `serious-condition` > `error`、`warning`)を含みます。コンディションオブジェクトなしで通知されたエラーは、その原因が名指すクラスとして捕捉され、メッセージはコンディションの `format-control` スロットに入ります: 素の `(error "...")` は `simple-error` ですが、組み込み内部の失敗はそれ自身の型を持ちます — 不正な `car`、引数型の誤り、範囲外の添字は `type-error`、ゼロ除数は `division-by-zero`、未定義関数の呼び出しは `undefined-function`、未束縛変数の読み出しは `unbound-variable` です(wasm-GC バックエンドで到達できるのは未定義関数のケースだけで、そこでは `simple-error` として捕捉されます — 下記の相違点を参照)。`:no-error` 節は正常終了時に(主)値を `var` に束縛して、ハンドラの外で実行されます。非局所脱出(`return`/`return-from`)は捕捉されずに通過し、expression 内の `unwind-protect` はハンドラより先に cleanup を実行します。

`handler-case` は `--no-gc`(コンパイルエラー)を除く**すべてのバックエンド**でサポートされます。wasm-GC バックエンド(Preview 1 と `--component`、`wasmtime serve` を含む)では WebAssembly の exception-handling プロポーザルを通じてコンパイルされます。捕捉フォームを使わないプログラムは従来とバイト単位で同一であり、コマンドラインも変わりません。相違点: WASM バックエンドが捕捉できるのは**シグナルされたコンディションのみ**です — ランタイムトラップ(`(car 5)` のような型エラー、整数のゼロ除算)はそこでは捕捉不能のままですが、インタプリタと JVM はエラーとして捕捉します。ハンドラはスレッド単位なので、`rontolisp:http-handler` の並行リクエスト同士は干渉しません。巻き戻し*なしで*シグナル点でハンドラを実行したい場合 — 例えば [`restart-case`](restart-case.md) のリスタートを起動する場合 — は [`handler-bind`](handler-bind.md) を使います。

```lisp
(handler-case (error "boom")
  (error (e) (list :caught (simple-condition-format-control e)))) ; => (:CAUGHT "boom")
```

型付きコンディションはクラス階層でディスパッチされ、最初にマッチした節が勝ちます:

```lisp
(define-condition low-fuel (warning) ((level :initarg :level :reader low-fuel-level)))
(handler-case (error 'low-fuel :level 5)
  (error (e) :error)
  (warning (w) (list :warned (low-fuel-level w)))) ; => (:WARNED 5)
```

```lisp
(handler-case (+ 1 2)
  (error (e) :err)
  (:no-error (v) (list :ok v))) ; => (:OK 3)
```

組み込みが起こすエラーはそのクラスでディスパッチされます — テストフレームワークの `(signals form 'type-error)` が主張しているのはこれです。wasm-GC バックエンドではこの失敗はトラップになるため(上記の相違点を参照)、捕捉できるのはインタプリタと JVM です:

```lisp
(handler-case (car 1)
  (type-error (e) :type-error)
  (error (e) :plain)) ; => :TYPE-ERROR
```
