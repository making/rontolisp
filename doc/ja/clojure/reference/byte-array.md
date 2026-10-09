# byte-array

`(byte-array size-or-seq)` `(byte-array size init)`

Java の `byte[]` にあたるバイト配列を返します。`size` 個の 0、または seq の要素からなり、要素は
`Number.byteValue` と同じく格納します（整数は下位 8 ビットなので、`300` は `44`）。`size` と
`init` を渡すと、どの要素もバイト `init`、または seq `init` の次の要素になります（seq が尽きた
先は 0）。要素は符号付きのバイト（`-128` から `127`）として読み出します。配列は書き換えられます
（[aset](aset.md)、[aset-byte](aset-byte.md)）。コレクションではありませんが（`coll?` と `vector?`
は false、`=` は同一性）、要素の seq になるので、`count`、`seq`、`vec`、`nth`、`get` と seq の
関数が受け付けます。オラクルの `#object["[B" ...]` から識別ハッシュを除いた形で印字します。
値としては 1 引数か 2 引数の関数です。

```clojure
(def ba (byte-array [104 105 -1 300]))
(println (vec ba) (alength ba) (count ba)) ; [104 105 -1 44] 4 4
(println (vec (byte-array 3)) (vec (byte-array 3 [1 2]))) ; [0 0 0] [1 2 0]
(println (String. (byte-array [104 105]) "UTF-8")) ; hi
```
