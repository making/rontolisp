# char-array

`(char-array size-or-seq)` `(char-array size init-val-or-seq)`

char の配列を返します。数のときはその個数の null 文字、それ以外のときは seq の要素（各要素は文字）を持つ配列です。`size` と初期値があるとき、各要素は文字 `init` か、seq `init` の次の要素です（余りは null 文字）。

配列はここではベクターです（`make-array` の作るものと同じ）。[aget](aget.md) で読み、[aset](aset.md) で書きます（`aset` はどんな値も格納します。オラクルのリフレクションは他の型を拒否します）。[alength](alength.md) で測り、[aclone](aclone.md) で複製し、seq 動詞・`count`・`nth`・`get`・`=` はベクターと同じように受けます。`class` は `:vector` を答え、`str` と `pr` はベクターを印字し、`=` は要素を比べます。オラクルはクラスを答え、`#object` を印字し、同一性で比べます（[相違点](../deviations.md)）。すべてのバックエンドで動きます。値としては 1 引数または 2 引数の関数です。

```clojure
(prn (vec (char-array [\a \b])) (vec (char-array 2 \z))) ; [\a \b] [\z \z]
```
