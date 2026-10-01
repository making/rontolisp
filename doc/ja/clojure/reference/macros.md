# マクロ

コードを書くコードです。`defmacro` はコンパイル時 expander を定義します。呼び出し
位置は lower 中に展開され、バックエンドが動くより前に済みます。同じ expander が
実行時の `macroexpand-1` にも答えます。テンプレートは `c%` 名前空間上の
syntax-quote で、`~`/`~@` と展開ごとの `x#` gensym を伴います。下の `unless` は
`(defmacro unless [c t] (list 'if c nil t))` です。

| 名前 | 例 | 結果 |
|---|---|---|
| `defmacro` | `(do (defmacro unless [c t] (list 'if c nil t)) (unless false 1))` | `1` |
| `syntax-quote` | `(do (defmacro w [c & b] `(if ~c (do ~@b))) (macroexpand-1 '(w true 1)))` | `(IF true (DO 1))` |
| `gensym` | `(= (gensym "g") (gensym "g"))` | `false` |
| `macroexpand-1` | `(macroexpand-1 '(unless true 1))` | `(IF true nil 1)` |
| `macroexpand` | `(macroexpand '(unless false 1))` | `(IF false nil 1)` |
