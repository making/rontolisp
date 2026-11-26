# uiop:with-deprecation

`(uiop:with-deprecation (level) definitions...)`

包んだ定義をそのまま確立し、最後の定義の値を返します。さらに、包んだ各 `defun` を
非推奨としてマークします: 最初の呼び出しで `level` フォームを一度評価し、その
レベルが選ぶ非推奨コンディションをシグナルします。

`level` フォームは通常 `(uiop:version-deprecation ...)` で、バージョン文字列を
`:style-warning` / `:warning` / `:error` / `:delete` に写像します。各レベルは
それぞれ固有のコンディションクラスをシグナルします —
`deprecated-function-style-warning`、`deprecated-function-warning`、
`deprecated-function-error`、`deprecated-function-should-be-deleted` — 関数ごとに
一度だけです (本家はマクロ展開時に level を評価しますが、この移植には展開時評価器が
ないため、最初の呼び出しで評価します)。`defun` 以外のフォームはそのまま通されます。

展開はトップレベルにスプライスされるので、包まれたトップレベルの `defun` は
コンパイルバックエンドでもトップレベル定義のままです (ライブラリが使う形は通常
`eval-when` の内側にあるこの形です)。

```lisp
(uiop:with-deprecation ((uiop:version-deprecation "1.1" :delete "1.1"))
  (defun old-gone () 1))
(handler-case (old-gone)
  (uiop:deprecated-function-should-be-deleted (c)
    (list :caught (uiop:deprecated-function-name c))))   ; => (:CAUGHT OLD-GONE)
```

`uiop` は ASDF の移植性レイヤであり Common Lisp の一部ではありません: この名前は
`uiop:` 修飾付きでのみ参照できます。

## バックエンドサポート

4 つすべてのバックエンドで動作します: インタプリタと 2 つのコンパイラが共有する
組み込みマクロ展開です。他の組み込みマクロと同様に関数値は持ちません
(`#'uiop:with-deprecation` はエラーです)。
