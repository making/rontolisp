# name

`(name x)`

文字列はそのまま答えます。キーワード・シンボルの綴りは最初の `/` より後ろの部分で
答えます（`/` がなければ綴り全体です）。それ以外は oracle と同様にシグナルします。
値としては1引数ラムダです。

```clojure
(println (name :foo/bar)) ; bar
(println (name "hello")) ; hello
```
