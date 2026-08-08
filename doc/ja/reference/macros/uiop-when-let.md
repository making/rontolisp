# uiop:when-let

`(uiop:when-let ((var form)...) body...)`

暗黙の `progn` ボディを持ち else 分岐のない [`uiop:if-let`](uiop-if-let.md) です:
変数を並列に束縛し、**すべての**変数が非 nil になったときだけボディを評価します。
そうでなければ nil を返します。

入れ子でない単一の束縛 — `(uiop:when-let (x form) ...)` — もここで受け付けます。

```lisp
(list (uiop:when-let ((a 3) (b 4)) (+ a b) (* a b))
      (uiop:when-let ((a 3) (b nil)) (+ a 1)))   ; => (12 NIL)
```

`uiop` は ASDF の移植性レイヤであり Common Lisp の一部ではありません: この名前は
`uiop:` 修飾付きでのみ参照できます。

## バックエンドサポート

4 つすべてのバックエンドで動作します: インタプリタと 2 つのコンパイラが共有する
組み込みマクロ展開です。他の組み込みマクロと同様に関数値は持ちません
(`#'uiop:when-let` はエラーです)。
