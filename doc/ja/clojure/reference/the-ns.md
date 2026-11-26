# the-ns

`(the-ns x)`

`clojure.core/the-ns` です。`x` が指す名前空間を返します。`x` はプログラムが（呼び出しより上の
`ns` か `in-ns` で）作った名前空間、require した名前空間、`clojure.core` を指すシンボルか、
名前空間そのものです。それ以外のシンボルは oracle と同じく `No namespace: x found` を
シグナルします。名前空間は `#object[clojure.lang.Namespace "name"]` と印字され、`str` は
その名前を答えます。値としては1引数の関数です。

```clojure
(println (str (the-ns 'user)) (= *ns* (the-ns 'user)) (identical? *ns* (the-ns *ns*)))
(println (try (the-ns 'no-such) (catch Exception e (ex-message e))))
```

```
user true true
No namespace: no-such found
```
