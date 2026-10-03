# identical?

`(identical? x y)`

`clojure.core/identical?`: `x` と `y` が同じオブジェクトなら `true` を返します。綴りが同じ2つのキーワードは、オラクルのインターンされたキーワードと同様に同じオブジェクトとして扱います。ここでは数値・文字・シンボルを値で比較するため、`(identical? 1000 1000)` と `(identical? 'a 'a)` は `true` です（オラクルは `false`）。値としては2引数の関数です。

```clojure
(println (identical? :a :a) (let [v [1]] (identical? v v)) (identical? [1] [1]))  ; true true false
```
