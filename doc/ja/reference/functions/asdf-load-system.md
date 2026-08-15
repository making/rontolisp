# asdf:load-system

`(asdf:load-system name &rest options)`

名前を指定してシステムをロードします: まず `:depends-on` のシステムを (再帰的に)、次にコンポーネントファイルを `:depends-on`/`:serial` の順序で、それぞれ [`load`](load.md) と同様に評価します。システム名をシンボルとして返します。同じシステムを 2 回ロードしても no-op です ([`require`](require.md) と同様)。システム定義は、先行する [`asdf:defsystem`](asdf-defsystem.md) から、または次の順で探索して見つかる `NAME.asd` から得られます: ロードしているファイルのディレクトリ、`--system-path` で指定したディレクトリ、環境変数 `RONTOLISP_SOURCE_REGISTRY` のディレクトリ (どちらも `PATH` のようにプラットフォームのパス区切り文字で複数ディレクトリを連結できます)。`"lib/tests"` のようなセカンダリシステム名の場合、探索されるファイルはプライマリシステムのもの (`lib.asd`) です。

キーワードオプション (`:verbose nil`、`:force t` など) はすべてのバックエンドで受理され、**無視されます**: 駆動すべき `operate` の機構はなく、同じシステムの 2 回目のロードはもともと no-op だからです。ただし `:keyword value` のペアである必要があります。余分なシステム名を書いた場合はロードが黙って落とされるのではなくエラーになります。

インタプリタでは `load-system` は通常のランタイム関数なので、計算された名前でも動作します (名前には [`asdf:find-system`](asdf-find-system.md) が返すメタオブジェクトも渡せます)。コンパイルパス (JVM/WASM) では、**リテラルなトップレベル**の `(asdf:load-system NAME)` がコンパイル時に展開されます: 依存システムとコンポーネントファイルは、コンパイル時の [`load`](load.md)/[`require`](require.md) インクルードとまったく同様にプログラムへ継ぎ足され、コンパイラはすべてのバックエンドで定義をネイティブに認識します。他のフォームの内側にネストした `load-system` や計算された引数を持つものは実行時の呼び出しにコンパイルされ、システムが既にスプライス済みなら `nil` を返し (ライブラリが [`find-system`](asdf-find-system.md) プローブでガードする「なければロード」の形)、コンパイルされたプログラムが持たないシステムに対してはエラーを通知します (実行時には何もロードできません)。

```console
;; my-lib.asd
(defsystem :my-lib
  :components ((:file "main" :depends-on ("package"))
               (:file "package")))

;; run.lisp
(asdf:load-system :my-lib)
(my-lib:greet)
```

`package.lisp` が `main.lisp` より先にロードされ (`:depends-on` 制約)、その後プログラムがロード済みシステムを呼び出します。プロジェクトのレイアウト全体と探索パスの詳細は[システムガイド](../../guides/asdf-systems.md)を参照してください。
