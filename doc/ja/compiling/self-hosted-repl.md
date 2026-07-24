# セルフホスティング REPL

`read-line`、`read-from-string`、`eval`、`print` はすべてのバックエンドで利用できるため、REPL を RontoLisp 自身で記述し、スタンドアロンの `.class` や `.wasm` にコンパイルできます。

例（`repl.lisp`）:

```console
(princ "> ")
(setq line (read-line))
(while line
  (print (eval (read-from-string line)))
  (princ "> ")
  (setq line (read-line)))
```

```bash
rontolisp repl.lisp               # interpret
rontolisp repl.lisp -o repl.class
java repl                                                                  # REPL on the JVM
rontolisp repl.lisp -o repl.wasm
wasmtime run -W gc repl.wasm                                               # REPL on WASM
```

セルフホスト REPL は入力の各行を埋め込みランタイムリーダー(`read-from-string`)で
解析します。これは Common Lisp 同様にシンボルを大文字化する([リーダーのケースの
ガイド](../guides/reader-case.md)を参照)ため、`(defun square ...)` はネイティブ
REPL と同じく `SQUARE` とエコーされます。

```console
> (defun square (x) (* x x))
SQUARE
> (mapcar #'square '(1 2 3))
(1 4 9)
> ()
NIL
> (- 5)
-5
```

`read-line` は入力の終端でのみ `nil` を返すため、ループは Ctrl-D で終了します。`nil` や `()` を入力した場合は `NIL` と評価され、ループは継続します。これは、読み取った値をループの終了判定に流用するのではなく、終端とデータを区別できる `read-line` で行を読み取っているためです。プロンプトで入力された各行はランタイムのリーダーで解析され、組み込みの `eval` ランタイムで評価されるため、[コンパイルされた `eval` の制限](../guides/eval-limitations.md) と [コンパイルされた `read`/`load` の制限](../guides/read-load-limitations.md) が適用されます。
