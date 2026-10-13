# print-simple

`(print-simple x w)`

`x` をライター `w` に `print` と同じ書き方で書く。`nil` を返す。oracle と同じ。プログラムの `print-method` があれば使う。ストリームでないライターは `ClassCastException`。値としては 2 引数を取る。

```clojure
(println (with-out-str (print-simple :a *out*))) ; :a
```
