# intern

`(intern ns name)` / `(intern ns name val)`

名前空間 `ns`（シンボルか名前空間）の var `name`（シンボル）。あるものはそのまま（`val` があれば `bindRoot` のように結び直す）、ないものは `val` で（なければ未束縛で）自分のセルとともに作る。oracle と同じ。セルは `var-get`、`deref`、`alter-var-root`、`with-redefs-fn` に答える。`def` や `defn` の名前は自分のセルを持つため、直接の呼び出しに intern の根は見えない（oracle の静的リンクと同じ）。シンボルでない名前や名前空間は拒否される。値としては 2 引数か 3 引数を取る。

```clojure
(println (intern 'user 'doc-eg-v 7)) ; #'user/doc-eg-v
(println @(intern 'user 'doc-eg-v)) ; 7
```
