# handler-case

`(handler-case expression (type ([var]) body...)... [(:no-error ([var]) body...)])`

`expression` を評価し、その間にエラーが通知されたら、通知されたコンディションに `type` がマッチする最初の節に制御を移します。`var`(省略可)にはコンディションオブジェクトが束縛され、節本体の値がフォーム全体の値になります。どの節もマッチしなければエラーは外側へ伝播します(外側の `handler-case` が捕捉できます)。節の型には任意の `typecase` 指定子が使えます。[`define-condition`](define-condition.md) で定義したコンディションクラスと組み込み階層(`condition` > `serious-condition` > `error`、`warning`)を含みます。コンディションオブジェクトなしで通知されたエラーは、その原因が名指すクラスとして捕捉され、メッセージはコンディションの `format-control` スロットに、それをそのまま出力する制御文字列として(`~` をすべて二重にして)入ります: 素の `(error "...")` は `simple-error` ですが、組み込み内部の失敗はそれ自身の型を持ちます — 不正な `car`、引数型の誤り、範囲外の添字は `type-error`、ゼロ除数は `division-by-zero`、未定義関数の呼び出しは `undefined-function`、未束縛変数の読み出しは `unbound-variable` です(wasm-GC バックエンドで到達できるのは未定義関数のケースと引数型の誤りのケースです。前者はそこでは `simple-error` として、後者はそこでも `type-error` として捕捉されます — 下記を参照)。そしてキーワード引数の並びが不正な呼び出し — その演算子が受け付けないキーワード、値のないキーワード、キーワード位置の非キーワード — はすべてのバックエンドで `program-error` です(`:allow-other-keys t` が余分なキーを許す場合を除きます。コンパイルバックエンドではその呼び出しが失敗すると分かっているので、コンパイル時に警告も出ます)。`:no-error` 節は正常終了時に(主)値を `var` に束縛して、ハンドラの外で実行されます。非局所脱出(`return`/`return-from`)は捕捉されずに通過し、expression 内の `unwind-protect` はハンドラより先に cleanup を実行します。

`handler-case` は `--no-gc`(コンパイルエラー)を除く**すべてのバックエンド**でサポートされます。wasm-GC バックエンド(Preview 1 と `--component`、`wasmtime serve` を含む)では WebAssembly の exception-handling プロポーザルを通じてコンパイルされます。捕捉フォームを使わないプログラムは従来とバイト単位で同一であり、コマンドラインも変わりません。相違点: WASM バックエンドが捕捉できるのは**シグナルされたコンディションのみ**です — ランタイムトラップ(整数のゼロ除算、`(nthcdr 1 5)` のようなリストでない値のたどり)はそこでは捕捉不能のままですが、インタプリタと JVM はエラーとして捕捉します。算術・比較演算子、`car`/`cdr`、配列や `nthcdr` の添字、`numerator`/`denominator`、`random`、`complex` に誤った型の引数が渡った場合(`(+ 1 nil)`、`(< 1 "x")`、`(car 5)`、`(aref v nil)`)は**すべての**バックエンドでシグナルされて捕捉でき、メッセージも同一で、演算子とその演算子が要求する型を示します(`(+ 1 nil)` なら `+: The value NIL is not of type NUMBER`、`(car 5)` なら `CAR: The value 5 is not of type LIST`)。`type-error-datum` と `type-error-expected-type` に答える `type-error` として捕捉されます。次元の範囲外の配列添字も同じです: `(aref (vector 1 2 3) 5)` はどこでも `AREF: The value 5 is not of type (INTEGER 0 (3))` と報告され、期待型はリスト `(INTEGER 0 (3))` です。ハンドラはスレッド単位なので、`rontolisp:http-handler` の並行リクエスト同士は干渉しません。巻き戻し*なしで*シグナル点でハンドラを実行したい場合 — 例えば [`restart-case`](restart-case.md) のリスタートを起動する場合 — は [`handler-bind`](handler-bind.md) を使います。

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

不正な呼び出しはどこでも同じメッセージを持つ `program-error` になります — テストスイートの `(signals-error (remove 'a nil :bogus t) program-error)` という形です:

```lisp
(handler-case (remove 1 '(1 2 3) :bogus 4)
  (program-error (e) (princ-to-string e))
  (error (e) :plain)) ; => "REMOVE expects keyword arguments :TEST/:TEST-NOT/:KEY/:START/:END/:COUNT/:FROM-END, got: :BOGUS"
```
