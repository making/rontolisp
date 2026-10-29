# intern

`(intern ns name)` / `(intern ns name val)`

名前空間 `ns`（シンボルか名前空間）の var `name`（シンボル）。あるものはそのまま（`val` があれば `bindRoot` のように結び直す）、ないものは `val` で（なければ未束縛で）作る。oracle と同じ。今の名前空間（`*ns*` かそのクォートした名前）のクォートした名前なら、var は呼び出しの場所で `(do (def name val) (var name))`（`val` がなければ `(declare name)`）のように定義されるため、以後の形はその名前を指せ（oracle の一形ずつのコンパイルが var を見つけるのと同じ）、`val` は `def` の読み出しが見る根になる。それ以外は実行時に自分のセルとともに作り、セルは `var-get`、`deref`、`alter-var-root`、`with-redefs-fn` に答える。`def` や `defn` の名前は自分のセルを持つため、直接の呼び出しにその根は見えない（oracle の静的リンクと同じ）。シンボルでない名前や名前空間は拒否される。値としては 2 引数か 3 引数を取る。

```clojure
(println (intern 'user 'doc-eg-v 7)) ; #'user/doc-eg-v
(println @(intern 'user 'doc-eg-v)) ; 7
```
