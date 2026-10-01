# clojure.string/escape

`(clojure.string/escape s cmap)`

Answers `s` with every character present as a key of `cmap` replaced by the mapped string; other
characters pass through. The map is char to string. Works as a function value too.

```clojure
(println (clojure.string/escape "<>" {\< "&lt;" \> "&gt;"})) ; &lt;&gt;
(println (clojure.string/escape "hi" {\i "I"})) ; hI
```
