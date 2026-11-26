# to-array-2d

`(to-array-2d coll)`

`coll` の各メンバを行とする配列を返します。各行はそのメンバの [to-array](to-array.md) です。`coll` はコレクションのコレクションなら何でもよく、`nil` はオラクルの `NullPointerException`、文字列やマップなどコレクションでないものは `ClassCastException` です。

配列はここでは `make-array` のものと同様にベクタです。答えはベクタのベクタで、[aget](aget.md) で読み、[aset](aset.md) で書き、[alength](alength.md) で測り、[aclone](aclone.md) で複写します（[deviations](../deviations.md)）。すべてのバックエンドで動作します。値としては 1 引数の関数です。

```clojure
(prn (mapv vec (to-array-2d [[1 2] [3]]))) ; [[1 2] [3]]
```
