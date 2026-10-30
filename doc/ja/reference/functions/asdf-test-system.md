# asdf:test-system

`(asdf:test-system name &rest keys)`

指定した名前のシステムを読み込み、その
`:in-order-to ((test-op (test-op ...)))` 連鎖をたどり（連鎖先のシステムも同じ方法で
読み込んでテストします）、システムに `asdf:test-op` を perform します
（[`asdf:perform`](asdf-perform.md)）。そのデフォルトメソッドが、システムに記録された
`:perform (test-op (o c) ...)` の本体を実行します。operation 引数は `asdf:test-op` の
インスタンス、component 引数はシステムのメタオブジェクト
（[`asdf:find-system`](asdf-find-system.md) の答え）に束縛されます。`t` を返し、
キーは受け付けて無視します。test-op の配線を持たないシステムでは何もしません。
本物の ASDF のデフォルト `perform` と同じです。ライブラリが `test-op` について定義した
`perform` メソッドは、ほかの operation と同じく実行されます。

これは fukamachi スタイルの `.asd` が備える標準のエントリポイントです。

```console
(defsystem "my-app"
  :components ((:file "main"))
  :in-order-to ((test-op (test-op "my-app/tests"))))

(defsystem "my-app/tests"
  :depends-on ("my-app" "rove")
  :components ((:file "tests/main"))
  :perform (test-op (op c) (symbol-call :rove :run c)))

;; run the tests:
(asdf:test-system "my-app")
```

本体は `.asd` の解析時にデータとして記録されます。裸のシンボルは `asdf-user` が
解決するのと同じように解決されます（`symbol-call` は `uiop:symbol-call`、
`component-name` は `asdf:component-name`）。メソッド修飾子つきの `:perform`
（`test-op :after (o c)`）、ほかの operation についての `:perform`、本体中の `#.`
リーダーマクロは、許容されて無視されます。

## バックエンドサポート

4 つすべてのバックエンドで動作します。コンパイルパスでは、**リテラルなトップレベル**の
`(asdf:test-system NAME)` がコンパイル時にシステム*と* test-op 連鎖をスプライスし
（通常の `load-system` はテストシステムを取り込みません）、実行時に記録された本体を
実行します。ネストした呼び出しや計算された名前の呼び出しは、プログラムが既に
スプライス済みのシステムにしか届きません。
