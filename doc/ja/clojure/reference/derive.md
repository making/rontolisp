# derive

`(derive tag parent)`
`(derive h tag parent)`

`tag` を `parent` の子として記録します。2 引数形式はグローバルの階層を書き換えて `nil` を返し、3 引数形式は引数に触れずに更新済みの階層値を返します -- `defmulti` の `:hierarchy` はそのような値を通ってディスパッチします。

```clojure
(derive :circle :shape)
(println (isa? :circle :shape)) ; true
(def h (derive (make-hierarchy) :p :q))
(println (isa? h :p :q)) ; true
```
