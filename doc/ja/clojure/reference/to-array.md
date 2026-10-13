# to-array

`(to-array coll)`

`coll` の中身を持つオブジェクトの配列を返します。配列はここではベクターです（`make-array` の作るものと同じ）。[aget](aget.md) で読み、[aset](aset.md) で書き、[alength](alength.md) で測り、[aclone](aclone.md) で複製し、seq 動詞・`count`・`nth`・`get`・`=` はベクターと同じように受けます。`class` は `:vector` を答え、`str` と `pr` はベクターを印字し、`=` は要素を比べます。オラクルはクラスを答え、`#object` を印字し、同一性で比べます（[相違点](../deviations.md)）。seq 化できない値はオラクルと同じ `RuntimeException`（`Unable to convert: ... to Object[]`）です。すべてのバックエンドで動きます。値としては 1 引数の関数です。

```clojure
(prn (vec (to-array [1 "a" nil])) (vec (to-array nil))) ; [1 "a" nil] []
```
