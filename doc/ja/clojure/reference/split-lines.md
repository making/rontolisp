# clojure.string/split-lines

`(clojure.string/split-lines s)`

改行（`\n`、`\r\n`）の周りで `s` を分割し、部分の seq を返します。改行区切りへのリテラルな split で -- 正規表現の実行時層はありません。関数値としても動きます。

```clojure
(println (clojure.string/split-lines "a\nb")) ; (a b)
(println (clojure.string/split-lines "a\r\nb")) ; (a b)
```
