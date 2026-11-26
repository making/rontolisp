# namespace

`(namespace x)`

キーワード・シンボルの綴りのうち最初の `/` より前の部分で答え、なければ `nil` です。
文字列をふくむそれ以外は oracle と同様にシグナルします。値としては1引数ラムダです。

```clojure
(println (namespace :foo/bar)) ; foo
(println (namespace :foo)) ; nil
```
