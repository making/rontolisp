# xml-seq

`(xml-seq root)`

`root` and the `:content` trees under it, in document order -- the oracle's `tree-seq` over
non-strings. As a value one argument.

```clojure
(println (xml-seq {:tag :a :content [{:tag :b :content ["x"]}]})) ; ({:tag :a, :content [{:tag :b, :content [x]}]} {:tag :b, :content [x]} x)
```
