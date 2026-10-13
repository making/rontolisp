# print-dup

`(print-dup x w)`

`x` をライター `w` に `pr` と同じ書き方で書く。`nil` を返す。ただし array map（8 件以下）とソート済みコレクションは oracle の読める `#=` 形で書く。プログラムの `print-method` は使わない（oracle の `print-dup` は独自のマルチメソッド）。レコードやキューなどは `pr` と同じ書き方になるところだけが違う（oracle は `toString`）。ストリームでないライターは `ClassCastException`。値としては 2 引数を取る。

```clojure
(println (with-out-str (print-dup {:a 1} *out*))) ; #=(clojure.lang.PersistentArrayMap/create {:a 1})
```
