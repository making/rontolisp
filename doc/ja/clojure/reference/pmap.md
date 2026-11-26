# pmap

`(pmap f coll...)`

インタプリタと JVM では要素ごとに一つの future を走らせます。各適用が自分の
スレッドで走り、オラクルの先読み（2 プラス プロセッサ数）で順に deref するの
で、sleep を伴う枝は重なりながら印字される seq は逐次版と同じです。lazy な入力
は `map` と同じく lazy かつ逐次のままで、失敗は deref の順にオラクルの
`ExecutionException` で表面化します。スレッドのない WASM 層では `map` です。印字
される seq は同じです。値としても同じ関数です。

```clojure
(println (pmap inc [1 2 3])) ; (2 3 4)
(println (pmap + [1 2] [10 20 30])) ; (11 22)
```
