# when-some

`(when-some [p e] body...)`

`when-let` と同様ですが、本体を飛ばすのは `nil` のときだけです。`false` は束縛され、本体を実行します。

```clojure
(println (when-some [x false] [:body x])) ; [:body false]
(println (when-some [x nil] [:body x]))   ; nil
```
