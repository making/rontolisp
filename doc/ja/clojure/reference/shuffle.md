# shuffle

`(shuffle coll)`

実体化した要素の新しいベクターに対する Fisher-Yates です。プログラム所有の生成器から
引きます。所属と個数は固定されますが順序は固定されません。`nil`・文字列・マップは
シグナルします（oracle でもシャッフルできません）。値としては1引数ラムダです。

```clojure
(println (shuffle [])) ; []
(println (shuffle [1])) ; [1]
```
