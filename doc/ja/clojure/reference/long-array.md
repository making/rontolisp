# long-array

`(long-array size-or-seq)` `(long-array size init-val-or-seq)`

long の配列を返します。数のときはその個数のゼロ、それ以外のときは seq の要素を Java の `(long)` と同じように狭めて持つ配列です（整数の下位 64 ビット、範囲内の倍精度・有理数の切り捨て）。`size` と初期値があるとき、各要素は数 `init` の long か、seq `init` の次の要素です（余りはゼロ）。

配列はここではベクターです（`make-array` の作るものと同じ）。[aget](aget.md) で読み、[aset](aset.md) で書きます（`aset` はどんな値も格納します。オラクルのリフレクションは他の型を拒否します）。[alength](alength.md) で測り、[aclone](aclone.md) で複製し、seq 動詞・`count`・`nth`・`get`・`=` はベクターと同じように受けます。`class` は `:vector` を答え、`str` と `pr` はベクターを印字し、`=` は要素を比べます。オラクルはクラスを答え、`#object` を印字し、同一性で比べます（[相違点](../deviations.md)）。すべてのバックエンドで動きます。値としては 1 引数または 2 引数の関数です。

```clojure
(println (vec (long-array [1 2])) (vec (long-array [1.9 1e20]))) ; [1 2] [1 9223372036854775807]
```
