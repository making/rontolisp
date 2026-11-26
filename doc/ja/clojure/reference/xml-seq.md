# xml-seq

`(xml-seq root)`

`root` とその下の `:content` の木を文書順に並べたもの。文字列以外を枝とする oracle の `tree-seq` と同じ。値としては 1 引数を取る。

```clojure
(println (xml-seq {:tag :a :content [{:tag :b :content ["x"]}]})) ; ({:tag :a, :content [{:tag :b, :content [x]}]} {:tag :b, :content [x]} x)
```
