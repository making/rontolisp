# do-external-symbols

`(do-external-symbols (var [package [result]]) body...)`

`package` (省略時は現在のパッケージ) の外部シンボル (エクスポートされたシンボル) ごとに `var` をそのシンボルに束縛して本体を 1 回ずつ評価し、その後 `var` を nil に束縛して `result` を評価しその値を返します (result フォームがなければ nil)。シンボルはソート順に渡されます。

これは**インタプリタ専用**のオペレータです。コンパイル済みバックエンドは実行時にパッケージレジストリを持たないため、到達するとコンパイルエラーになります。`#.` の読み込み時フォーム内であればどこでも動作します。マクロ展開時の評価器がコンパイル前に解決するためです。

この例は組み込みパッケージを走査せず自前のパッケージを作ります。訪問する集合全体がページ上に見えるようにするためです。パッケージの対の作り方は [`do-symbols`](do-symbols.md) が走査するものと同じです (2 つの名前をエクスポートする base パッケージと、それを use して自分でも 2 つエクスポートするパッケージ)。したがって 2 つのページの差はちょうど 1 つの規則です。パッケージが**継承している**ものはそのパッケージが**エクスポートしている**ものではないため、`ASHARED` と `ZSHARED` はここには現れません。

```lisp
(defpackage :des-base (:export :ashared :zshared))
(defpackage :des-demo (:use :des-base) (:export :alpha :mine))
(let ((names nil))
  (do-external-symbols (s :des-demo) (push (symbol-name s) names))
  (nreverse names)) ; => ("ALPHA" "MINE")
```
