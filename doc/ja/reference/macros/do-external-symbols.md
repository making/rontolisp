# do-external-symbols

`(do-external-symbols (var [package [result]]) body...)`

`package` (省略時は現在のパッケージ) の外部シンボル (エクスポートされたシンボル) ごとに `var` をそのシンボルに束縛して本体を 1 回ずつ評価し、その後 `var` を nil に束縛して `result` を評価しその値を返します (result フォームがなければ nil)。シンボルはソート順に渡されます。
`cl` を走査すると、標準の 978 個の外部名 (`caar` から `cddddr` までの `car`/`cdr` の合成を含む) と `while` を訪問します。

これは全 backend で動作するオペレータです。インタプリタは live
レジストリを読み、コンパイル済みバックエンドは compile 時に焼き込んだパッケージテーブル
(および [`make-package`](../functions/make-package.md)
で実行時に作成したパッケージ)から答えます。本体の `return`
は全体を抜けます -- 反復マクロが持つ暗黙の nil ブロックが確立されるためです --
result フォームは飛ばされます。

この例は組み込みパッケージを走査せず自前のパッケージを作ります。訪問する集合全体がページ上に見えるようにするためです。パッケージの対の作り方は [`do-symbols`](do-symbols.md) が走査するものと同じです (2 つの名前をエクスポートする base パッケージと、それを use して自分でも 2 つエクスポートするパッケージ)。したがって 2 つのページの差はちょうど 1 つの規則です。パッケージが**継承している**ものはそのパッケージが**エクスポートしている**ものではないため、`ASHARED` と `ZSHARED` はここには現れません。

```lisp
(defpackage :des-base (:export :ashared :zshared))
(defpackage :des-demo (:use :des-base) (:export :alpha :mine))
(let ((names nil))
  (do-external-symbols (s :des-demo) (push (symbol-name s) names))
  (nreverse names)) ; => ("ALPHA" "MINE")
```
