# syntax-quote

`` `form ``（`~` unquote と `~@` unquote-splicing 付き）

フォームをデータとして組み立てます。すべてのシンボルは `c%` prefix の背後で限定され
（限定先の名前空間は存在しません）、`~` はそのフォームの値を埋め込み、`~@` は
外側のリスト・ベクター・マップ・セットの中に列を継ぎ足します。各 `x#` は展開ごとに
1 つの新しい gensym に束縛されます。同じ展開の中では出現箇所によらず同じシンボルに
なり、展開が異なれば別物になります。マクロ本体の外ではテンプレートは書かれた位置で
評価されます。syntax-quote の外の unquote、列の外の splice はエラーです。

```clojure
(defmacro doc-mwhen [c & body] `(if ~c (do ~@body)))
(println (macroexpand-1 '(doc-mwhen true 1 2))) ; (IF true (DO 1 2))
```
