# remove-tap

`(remove-tap f)`

`clojure.core/remove-tap`: [tap>](tap-gt.md) が呼ぶ tap から `f` を取り除き、`nil` を答えます。その後に tap した値は `f` に届きません。tap は `identical?` で探します。値としては 1 引数の関数です。

```clojure
(add-tap println)
(println (remove-tap println)) ; nil
```
