# uiop:if-let

`(uiop:if-let ((var form)...) then [else])`

[`let`](../special-forms/let.md) と同じように変数を並列に束縛し、**すべての**変数が
非 nil になったときは `then` を、そうでなければ `else` を評価します。束縛はどちらの
分岐でも有効なので、`else` からも変数を参照できます。

入れ子でない単一の束縛 — `(uiop:if-let (x form) ...)` — も受け付けます。UIOP 自身が
1 変数の場合をこう書いており、束縛リストの先頭要素がシンボルならそのリスト自体が
1 つの束縛だと解釈されます。

```lisp
(list (uiop:if-let ((a 1) (b 2)) (list a b) :none)
      (uiop:if-let ((a 1) (b nil)) (list a b) :none)
      (uiop:if-let (x (+ 1 2)) (* x 10) :none))   ; => ((1 2) :NONE 30)
```

`uiop` は ASDF の移植性レイヤであり Common Lisp の一部ではありません: この名前は
`uiop:` 修飾付きでのみ参照できます。これは UIOP が持つ alexandria の同名マクロの
コピーで、両者の挙動は同じです。

## バックエンドサポート

4 つすべてのバックエンドで動作します: インタプリタと 2 つのコンパイラが共有する
組み込みマクロ展開です。他の組み込みマクロと同様に関数値は持ちません
(`#'uiop:if-let` はエラーです)。
