# find-ns

`(find-ns sym)`

`clojure.core/find-ns` です。シンボルが指す名前空間を返します。その名前の名前空間を
プログラムが呼び出しより上で（`ns` か `in-ns` で）作っておらず、require もしておらず、
`clj -M` が先にロードするもの（`clojure.core`、`clojure.edn`、`clojure.java.io`、
`clojure.string`）でもなければ `nil` です。値としては1引数の関数です。

```clojure
(in-ns 'demo)
(clojure.core/println (clojure.core/str (clojure.core/find-ns 'demo))
                      (clojure.core/find-ns 'no-such))
```

```
demo nil
```
