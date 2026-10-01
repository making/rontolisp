# clojure.string/re-quote-replacement

`(clojure.string/re-quote-replacement s)`

`s` をそのまま返します。リテラルの世界には quote すべきものがありません。置換文字列はすでにリテラルなので、オラクルが正規表現置換のために行う quote は恒等写像です。

```clojure
(println (clojure.string/re-quote-replacement "a$b")) ; a$b
(println (clojure.string/re-quote-replacement "$1")) ; $1
```
