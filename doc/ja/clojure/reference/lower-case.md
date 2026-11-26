# clojure.string/lower-case

`(clojure.string/lower-case s)`

`String.toLowerCase` と同じに小文字にした `s` を返すため、語末の大文字シグマは `ς` になります。`alias/var` や referred な裸の `lower-case` としても到達し、関数値としても動きます。

```clojure
(println (clojure.string/lower-case "HI")) ; hi
(println (clojure.string/lower-case "ΟΔΥΣΣΕΥΣ")) ; οδυσσευς
```
