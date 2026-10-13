# loaded-libs

`(loaded-libs)`

プログラムがその呼び出しまでに読んだ名前空間（require したライブラリ、ファイルの走ったプロジェクト名前空間、読んだ起動名前空間）をシンボルの集合で。oracle と同じ（`user` も `clojure.core` も読んだことにはならない）。順序は不定。引数なしの値。

```clojure
(require 'clojure.string)
(println (contains? (set (map str (loaded-libs))) "clojure.string")) ; true
```
