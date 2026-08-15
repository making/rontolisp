# asdf:find-system

`(asdf:find-system name &optional (error-p t))`

指定した名前のシステムの**システムメタオブジェクト**を返します。これは
[`asdf:system`](asdf-component-name.md) クラス
（`:class :package-inferred-system` のシステムでは
`asdf:package-inferred-system`）の CLOS インスタンスです。インスタンスは名前ごとに
メモ化され、繰り返し呼び出しても**同じオブジェクト**（`eq`）が返ります。本物の ASDF
と同じ振る舞いです。コンポーネントオブジェクトをそのまま渡すと、それ自身が返ります。
`name` は文字列・キーワード・シンボルの指示子です（シンボルは小文字化され、文字列は
そのまま使われます）。

登録されていない名前はエラーを通知しますが、`error-p` が nil のときは `nil` を
返します。これはライブラリが使うプローブの形（`load-system` を
`(asdf:find-system name nil)` でガードする形）です。「登録済み」とは、事前の
[`asdf:defsystem`](asdf-defsystem.md) または読み込まれた `.asd` で定義された、
package-inferred のサブシステムとして導出された、あるいは組み込みのシムシステムで
あることを指します。`find-system` 自身はファイルシステムを探索しません。

インスタンスの背後にあるコンポーネントモデルは本物の ASDF のものです。
`asdf:component`、`asdf:child-component` / `asdf:parent-component`、
`asdf:module`、`asdf:system`、`asdf:package-inferred-system`、
`asdf:source-file`、`asdf:cl-source-file`、`asdf:static-file` はどのバックエンドでも
本物の CLOS クラスなので、それらに対する `typep`、`typecase`、`defmethod` の
特定化子はすべて動作します。

```lisp
(asdf:defsystem :demo :components ((:file "main")))
(asdf:component-name (asdf:find-system :demo)) ; => "demo"
```

```lisp
(asdf:defsystem :demo2 :components ((:file "main")))
(eq (asdf:find-system :demo2) (asdf:find-system "demo2")) ; => T
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。インタプリタは生きたシステムレジストリから
答えます。コンパイルされたプログラムはコンパイル時にスプライスされたレジストリを
持ち運ぶため、`find-system` はプログラムが読み込んだシステム（および `.asd` が宣言した
すべての `defsystem`）を正確に知っています。リテラルの
`(asdf:system-source-directory (asdf:find-system 'lib nil))` はこれまで通り
コンパイル時にリテラルの名前文字列へ畳み込まれるため、同梱データファイルのイディオムに
実行時レジストリは不要です。
