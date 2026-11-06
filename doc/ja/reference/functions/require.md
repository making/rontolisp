# require

`(require module-name [pathname])`

モジュールがまだ [`provide`](provide.md) で登録されていなければそのファイルを読み込み、モジュール名をシンボルとして返します。`module-name` は指示子 (designator) で、キーワード (`:util`)、シンボル (`'util`)、文字列 (`"util"`) のいずれかです。明示的な `pathname` がない場合、ファイル `<name>.lisp` は [`load`](load.md) とまったく同じルールで require を書いたファイルからの相対で解決されます。第 2 引数を明示すればそのマッピングを上書きできます。require されるファイルは自身で `(provide <name>)` を呼ぶことが期待されます (慣習的には先頭のフォームとして) — それがモジュールを登録するため、同じ名前の 2 回目の `require` はロードせずに消費されます。これにより `require` はダイヤモンド依存 (`a.lisp` と `b.lisp` の両方が `utils.lisp` を require する場合に 1 回だけロードされる) のための道具になります。`load` ではファイルが 2 回評価されてしまいます。

`(require :asdf)` と `(require :uiop)` は、ASDF を同梱する処理系でスクリプトの先頭に書く形です。どちらも常に利用できるので、何もロードしません。

インタプリタでは `require` は通常のランタイム関数です。コンパイルパス (JVM/WASM) では、**リテラルなトップレベル**の `(require ...)` がコンパイル時に展開されます: モジュールファイルはコンパイル時 `load` インクルードと同様にプログラムへ継ぎ足され、コンパイラはその定義をネイティブに認識します。他のフォーム (関数本体など) の内側にネストした `require` は、モジュール名を計算してもよく、実行時には何もロードしません: プログラムが provide したモジュールと `asdf`/`uiop` はその名前を返し、それ以外はモジュール名を示すエラーを通知します。そのため、オプションの部品を必要になった時点でロードするライブラリもコンパイルでき、部品が欠けているときにだけ呼び出しが失敗します。計算された引数を持つトップレベルの `require`、マクロが展開して生じる `require`、関数値として取り出した `#'require` はコンパイルエラーです。Common Lisp の `*modules*` 変数は利用できません。

```console
;; util.lisp
(provide :util)
(defun u-sq (x) (* x x))

;; main.lisp
(require :util)
(require :util)   ; already provided: no second load
(print (u-sq 7))  ; prints 49
```

最初の `require` が `util.lisp` を読み込み、その中の `provide` がモジュールを登録します。2 回目は no-op で `util` を返します。
