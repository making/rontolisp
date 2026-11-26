# supers

`(supers c)`

クラス `c` から [bases](bases.md) がたどれるすべてのクラス -- スーパークラスとインタフェースを推移的に、
`Object` を含めて -- をクラス値のセットで返します。ないときと nil には nil を返します。それ以外の引数は
オラクルと同じ `ClassCastException` です。値としては1引数ラムダです。すべてのバックエンドが答え、
ホストがリフレクションしないところでは `bases` が読む表を使い、`bases` の逸脱を引き継ぎます。

セットの順序はここでの順序です。オラクルは `Class` オブジェクトを同一性でハッシュするので、実行ごとに
変わります。

```clojure
(println (sort (map #(.getName %) (supers Long)))) ; (java.io.Serializable java.lang.Comparable java.lang.Number java.lang.Object java.lang.constant.Constable java.lang.constant.ConstantDesc)
(defn f [x] x)
(println (contains? (supers (class f)) clojure.lang.IFn) (count (supers (class [])))) ; true 31
```
