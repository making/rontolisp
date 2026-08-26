# load

`(load filename &key verbose print if-does-not-exist external-format)`

ファイルを読み込み、その中のすべてのトップレベルフォームをグローバル環境で評価し、`t` を返します。`:if-does-not-exist` は実際に機能します。偽の値を渡すと、ファイルが存在しない場合にエラーを通知せず `nil` を返します。これにより `(load "optional-config.lisp" :if-does-not-exist nil)` が使えます。残る 3 つは受け付けたうえで無視されます。`load` は進捗出力を行わないため `:verbose` と `:print` には何もすることがなく、どのバックエンドも UTF-8 で読むため選択できる別の `:external-format` は存在しません。オプションの値は、使われるかどうかにかかわらず、書かれた順にすべて評価されます。読み込んだファイル内の `defun` や `setq` などの定義は後続のコードから引き続き利用できます。相対パスの `filename` は、その `load` を書いたファイルのディレクトリ（トップレベルの `load` ならエントリファイル）からの相対で解決されるため、どのワーキングディレクトリから実行しても `(load "sibling.lisp")` を見つけられます。コンパイル出力では、読み込まれた定義はランタイムの `eval` インタプリタのグローバル環境に存在するため、`eval` を通して到達できます (例: `(load "lib.lisp")` の後に `(eval '(square 5))`)。3 つすべてのバックエンドで動作します。WASM の `load` は WASI `path_open` でファイルを読むため、モジュールはディレクトリを許可して実行する必要があります (例: `wasmtime run --dir . prog.wasm`)。

```console
(load "lib.lisp")
(eval '(square 5))
(load "optional.lisp" :if-does-not-exist nil)
```

`square` を定義するファイルを読み込んだ後、その定義は `eval` を通して呼び出されます。WASM バックエンドはパスはプリオープンされたディレクトリに対して解決されます。相対パスは最初の 1 つ、絶対パスは名前がそのパスの最長の接頭辞になるプリオープンディレクトリに対して解決されます。そのため `--dir` が必要です。

`load` は意図的に冪等では**ありません**: 同じファイルを 2 回ロードすると 2 回評価されます (Common Lisp と同じ)。一度だけロードするモジュールセマンティクスには [`require`](require.md) / [`provide`](provide.md) を参照してください。
