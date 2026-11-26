# future-call

`(future-call f)`

`clojure.core/future-call`: 関数 `f` を独自のスレッドで走らせます。引数なしで呼び出す `future` と同じで（束縛の引き継ぎも同じ）、同じ future を答えます。値としては 1 引数の関数です。

```clojure
(println @(future-call (fn [] 11))) ; 11
```
