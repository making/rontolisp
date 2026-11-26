# into-array

`(into-array aseq)` `(into-array type aseq)`

`aseq` の要素を持つ配列を返します。`type` がないとき、要素の型は最初の要素のクラスです（要素がないときや先頭が `nil` のときは `Object`）。それ以外の要素はその型に適合しなければなりません。`type` があるときはクラス名（または `Integer/TYPE` などのプリミティブクラス）で、プリミティブクラスは各要素を変換し（`int` は切り捨てて範囲外を拒否、`long` は整数と文字、`double` はそれに倍精度、`char` は範囲内の数、`boolean` は真偽値）、それ以外のクラスは適合を検査します（`Object` はすべて、`Number` は数、`CharSequence` は文字列）。不適合はオラクルと同じ `IllegalArgumentException`（`array element type mismatch`、プリミティブの unboxing では `argument type mismatch`）です。

配列はここではベクターです（`make-array` の作るものと同じ）。[aget](aget.md) で読み、[aset](aset.md) で書き、[alength](alength.md) で測り、[aclone](aclone.md) で複製し、seq 動詞・`count`・`nth`・`get`・`=` はベクターと同じように受けます。`class` は `:vector` を答え、`str` と `pr` はベクターを印字し、`=` は要素を比べます。オラクルはクラスを答え、`#object` を印字し、同一性で比べます。小さな `N` リテラルはここでは `Long` なので、オラクルの `BigInt` が拒否するところで `Long` の要素型を取ります。計算で求めた `Class` オブジェクトはオラクルと同じ `ClassCastException` です（[相違点](../deviations.md)）。すべてのバックエンドで動きます。値としては 1 引数または 2 引数の関数です。

```clojure
(prn (vec (into-array [1 2])) (vec (into-array ["a"]))) ; [1 2] ["a"]
(prn (vec (into-array String ["a"])) (vec (into-array Integer/TYPE [1.9]))) ; ["a"] [1]
```
