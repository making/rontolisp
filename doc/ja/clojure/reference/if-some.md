# if-some

`(if-some [p e] then)` / `(if-some [p e] then else)`

`if-let` と同様ですが、テストに失敗するのは `nil` だけです。`false` は束縛され、then の分岐に進みます。パターンの分割束縛は then の分岐でだけ行われ、else の分岐からは外側の名前がそのまま見えます。else なしでは `nil` です。

```clojure
(println (if-some [x false] [:then x] :else)) ; [:then false]
(println (if-some [x nil] [:then x] :else))   ; :else
```
