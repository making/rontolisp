# time

`(time expr)`

式を実行し、オラクル同様 `Elapsed time: N msecs` を報告して、その値を答えにします。決定的なのは値だけです。ミリ秒数は決して決定的にならないため、テストは接頭辞だけを固定します。

```clojure
(println (time (+ 40 2))) ; 42
```
