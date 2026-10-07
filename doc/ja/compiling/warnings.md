# コンパイル時の警告

インタプリタならコードの実行時に通知するだけの誤りで、コンパイルは失敗しません。そのコード
を同じ実行時コンディションにコンパイルし、フォームの位置を付けた `warning:` 行を標準エラー
出力に表示します。

- 呼び出し先が認めない引数の個数やキーワード引数での呼び出し。実行時に `program-error` を
  通知します ([`defun`](../reference/special-forms/defun.md) を参照)
- プログラムがどこでも定義しない関数の呼び出しと参照 (`#'name`、および `(funcall 'name ...)`
  のように関数として渡すクオートされた名前)
- 呼び出し箇所が結局標準の演算子にコンパイルされる `COMMON-LISP` 関数の `defun`
- `--no-wasi` モジュールのロード中に到達するプリミティブ、war が無視する `http-handler` の
  ポート、そして `--warn-java-reflection` 指定時の、実行時リフレクションに残る `java:` 呼び出し
- マクロが展開中に呼ぶ `(warn ...)`。位置はマクロ呼び出しです。`style-warning` は代わりに
  `style-warning:` として表示され、ハンドラが抑止した警告は何も表示されません

```lisp
(defun add (a b) (+ a b))
(handler-case (add 1) (program-error () :caught)) ; => :CAUGHT
```

```console
$ rontolisp counts.lisp -o Counts.class
counts.lisp:3:9: warning: Function expects 2 arguments, got 1; compiled as a call-time program-error
```

## コンパイルを失敗させる (`--warnings-as-errors`)

`--warnings-as-errors` を付けると、そうしたコンパイルは失敗します。警告はすべて表示され、
その後に `error:` 行が 1 行続きます。終了ステータスは 1 で、出力ファイルは書き出されません。

```console
$ rontolisp counts.lisp -o Counts.class --warnings-as-errors
counts.lisp:3:9: warning: Function expects 2 arguments, got 1; compiled as a call-time program-error
counts.lisp:4:9: warning: CAR expects 1 argument, got 2; compiled as a call-time program-error
error: 2 warnings about the program's source, treated as errors (--warnings-as-errors)
```

すべての `-o` 出力 (`.class`、`.jar`、`.war`、`--component` や `--no-gc` の有無を問わない
`.wasm`、`--native`) と `rontolisp test ... -o` に効きます。`-o` がなければ拒否されます。
インタプリタはプログラムの実行前に何も警告しないからです。

数えるのはプログラム自身のソースについての警告だけです。エントリファイル、それが `load`
するファイル、`-e` のプログラム、そしてその隣や `--system-path` で見つかる ASDF システムが
該当します。マクロが組み立てたコードについての警告は、マクロ呼び出しの位置で数えます。次の
ものは表示されますが数えません。

- rontolisp が差し込むライブラリの中の警告
- `ql:quickload` が dist からダウンロードしたシステムの中の警告。利用者には直せません
- ソースについてではない行。`:async t` と `--host-fetch` のホスト側の義務、そして
  `warning: no JDK found`
- マクロが展開中に通知する `style-warning`

Maven プラグインでは `<warningsAsErrors>true</warningsAsErrors>`
(`-Drontolisp.warningsAsErrors=true`)、組み込み利用では
`JvmSourceCompiler.warningsAsErrors(true)` で指定します。後者のコンパイルは
`WarningsAsErrorsException` を投げます。
