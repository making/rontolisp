# clojure.string/escape

`(clojure.string/escape s cmap)`

`s` のうち `cmap` のキーとして存在するすべての文字を、対応する文字列へ置き換えて返します。それ以外の文字はそのまま通ります。マップは文字から文字列へです。関数値としても動きます。

```clojure
(println (clojure.string/escape "<>" {\< "&lt;" \> "&gt;"})) ; &lt;&gt;
(println (clojure.string/escape "hi" {\i "I"})) ; hI
```
