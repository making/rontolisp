# uiop:with-deprecation

`(uiop:with-deprecation (level) definitions...)`

包んだ定義をそのまま確立し、最後の定義の値を返します。本家 UIOP はさらにそれらを
非推奨としてマークし、後から呼ぶ側が `level` の警告を受け取れるようにします。

**rontolisp はこの診断を落とします。** 非推奨警告の仕組みも、それを流すコンパイル時
の警告チャネルも持たないため、正直な変換は `(progn definitions...)` です —
`level` フォームはどこでも評価されず無視されます。API の一部をこのマクロで包んだ
ライブラリは普通にロードでき普通に動きますが、ある名前が廃止に向かっていることは
一切通知されません。

展開はトップレベルにスプライスされるので、包まれたトップレベルの `defun` は
コンパイルバックエンドでもトップレベル定義のままです (ライブラリが使う形は通常
`eval-when` の内側にあるこの形です)。

```lisp
(uiop:with-deprecation (:style-warning)
  (defun old-double (x) (* x 2))
  (defun old-triple (x) (* x 3)))
(list (old-double 4) (old-triple 4))   ; => (8 12)
```

`uiop` は ASDF の移植性レイヤであり Common Lisp の一部ではありません: この名前は
`uiop:` 修飾付きでのみ参照できます。

## バックエンドサポート

4 つすべてのバックエンドで動作します: インタプリタと 2 つのコンパイラが共有する
組み込みマクロ展開です。他の組み込みマクロと同様に関数値は持ちません
(`#'uiop:with-deprecation` はエラーです)。
