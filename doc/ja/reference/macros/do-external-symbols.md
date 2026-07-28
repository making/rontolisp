# do-external-symbols

`(do-external-symbols (var [package [result]]) body...)`

`package` (省略時は現在のパッケージ) の外部シンボル (エクスポートされたシンボル) ごとに `var` をそのシンボルに束縛して本体を 1 回ずつ評価し、その後 `var` を nil に束縛して `result` を評価しその値を返します (result フォームがなければ nil)。シンボルはソート順に渡されます。

これは**インタプリタ専用**のオペレータです。コンパイル済みバックエンドは実行時にパッケージレジストリを持たないため、到達するとコンパイルエラーになります。`#.` の読み込み時フォーム内であればどこでも動作します。マクロ展開時の評価器がコンパイル前に解決するためです。

```lisp
(let ((names nil))
  (do-external-symbols (s :rontolisp names) (push (symbol-name s) names))
  (length names)) ; => 62
```
