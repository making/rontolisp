# セルフホスティング REPL

`read`、`eval`、`print` はすべてのバックエンドで利用できるため、REPL を RontoLisp 自身で記述し、スタンドアロンの `.class` や `.wasm` にコンパイルできます。

例（`repl.lisp`）:

```console
(princ "> ")
(setq form (read))
(while form
  (print (eval form))
  (princ "> ")
  (setq form (read)))
```

```bash
rontolisp repl.lisp               # interpret
rontolisp repl.lisp -o repl.class
java repl                                                                  # REPL on the JVM
rontolisp repl.lisp -o repl.wasm
wasmtime run -W gc repl.wasm                                               # REPL on WASM
```

セルフホスト REPL は入力を埋め込みランタイムリーダーで読みます。これは(フロント
エンドリーダーの Common Lisp 流の大文字化と違い)ケース保存です
([リーダーのケースのガイド](../guides/reader-case.md)を参照)。そのため
`(defun square ...)` はここでは `square` とエコーされます(ネイティブ REPL では
`SQUARE` になります)。

```console
> (defun square (x) (* x x))
square
> (mapcar #'square '(1 2 3))
(1 4 9)
> (- 5)
-5
```

`read` は EOF で `nil` を返すため、ループは Ctrl-D で終了します（`nil` や `()` を入力しても終了します）。プロンプトで入力された各フォームはランタイムのリーダーで解析され、組み込みの `eval` ランタイムで評価されるため、[コンパイルされた `eval` の制限](../guides/eval-limitations.md) と [コンパイルされた `read`/`load` の制限](../guides/read-load-limitations.md) が適用されます。
