# float-array

`(float-array size-or-seq)` `(float-array size init-val-or-seq)`

float の配列を返します。数のときはその個数のゼロ、それ以外のときは seq の要素を広げて持つ配列です。float はここでは倍精度を持つので（`float` と同じ）、`1/3` は長く印字されます（オラクルは `0.33333334`）。`size` と初期値があるとき、各要素は数 `init` の float か、seq `init` の次の要素です（余りはゼロ）。

配列はここではベクターです（`make-array` の作るものと同じ）。[aget](aget.md) で読み、[aset](aset.md) で書きます（`aset` はどんな値も格納します。オラクルのリフレクションは他の型を拒否します）。[alength](alength.md) で測り、[aclone](aclone.md) で複製し、seq 動詞・`count`・`nth`・`get`・`=` はベクターと同じように受けます。`class` は `:vector` を答え、`str` と `pr` はベクターを印字し、`=` は要素を比べます。オラクルはクラスを答え、`#object` を印字し、同一性で比べます（[相違点](../deviations.md)）。すべてのバックエンドで動きます。値としては 1 引数または 2 引数の関数です。

```clojure
(println (vec (float-array [1 2])) (vec (float-array 2 1.5))) ; [1.0 2.0] [1.5 1.5]
```
