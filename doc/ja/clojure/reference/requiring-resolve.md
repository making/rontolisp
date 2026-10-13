# requiring-resolve

`(requiring-resolve sym)`

修飾シンボル `sym` の名前空間を読んでその var を返す。何も指さないものは `nil`。oracle と同じ。クォートしたシンボルだけが解ける。求まる形でないものは実行時に拒否される。値としては 1 引数を取る（拒否される）。

```clojure
(println (requiring-resolve 'clojure.string/join)) ; #'clojure.string/join
```
