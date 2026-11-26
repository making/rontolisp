# macrolet

`(macrolet ((name lambda-list body...)...) body...)`

本体に対してレキシカルスコープの局所マクロを定義します。各局所マクロは `defmacro` で定義したマクロと同様に本体内で展開され（マクロ本体は展開時に、未評価の引数フォームをパラメータに束縛して実行されます）、定義は本体の外へ漏れません。定義のラムダリストは `defmacro` と同じ形（必須パラメータと `&rest`/`&body`、および `destructuring-bind` による拡張ラムダリスト）を受け付けます。局所マクロ本体はグローバルなヘルパー関数を参照できますが、周囲のランタイム束縛は参照できません。

コンパイル経路（`UserMacroExpander`）では JVM/WASM コンパイラが動く前に `macrolet` は完全に展開・除去されます。インタプリタはネイティブに展開します。`macrolet` で標準演算子（関数名前空間の名前）を局所的にシャドウすることはできません。変数名前空間については [`symbol-macrolet`](symbol-macrolet.md) を参照してください。

```lisp
(macrolet ((sq (x) `(* ,x ,x))
           (twice (x) `(+ ,x ,x)))
  (+ (sq 5) (twice 5))) ; => 35
```
