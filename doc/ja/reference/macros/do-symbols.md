# do-symbols

`(do-symbols (var [package [result]]) body...)`

`package` (省略時はカレントパッケージ) で**アクセス可能な**シンボルごとに `var` を
そのシンボルに束縛して本体を評価し、その後 `var` を nil に束縛して `result` を評価し
その値を返します (result フォームが無ければ nil)。アクセス可能とは、そのパッケージが
所有するシンボル (内部・外部を問わず) に加え、use リスト経由で継承しているエクスポート
済みシンボルのことです。それぞれは所有元パッケージの綴りで表されるため、`cl` を use する
パッケージでは裸の `cl` 名が得られます。シンボルはソート順で渡されますが、その順序は各シンボルの**所有元を含む綴り**に対する
ソートです。そのため継承した名前は、ローカルな名前と裸の名前で混ざるのではなく、
由来するパッケージのもとにまとまって並びます。

これは [`do-external-symbols`](do-external-symbols.md) と同じく**インタプリタ専用**の
オペレータです。コンパイル済みバックエンドは実行時にパッケージレジストリを持たないため、
そこへ到達する呼び出しはコンパイルエラーになります。`#.` の読み取り時フォームの中では
どこでも動作します。コンパイル前にマクロ時評価器が解決するためです。

この例は組み込みパッケージを走査せず自前のパッケージを作ります。訪問する集合全体が
ページ上に見えるようにするためです。`ASHARED` と `ZSHARED` に到達するのは `ds-demo` が
`ds-base` を use しているからで、これはまさに
[`do-external-symbols`](do-external-symbols.md) が除外するものです。これらが**先頭**に
来るのは、ソートの対象が `ashared` ではなく `ds-base:ashared` だからです。

```lisp
(defpackage :ds-base (:export :ashared :zshared))
(defpackage :ds-demo (:use :ds-base) (:export :alpha :mine))
(let ((names nil))
  (do-symbols (s :ds-demo) (push (symbol-name s) names))
  (nreverse names)) ; => ("ASHARED" "ZSHARED" "ALPHA" "MINE")
```
