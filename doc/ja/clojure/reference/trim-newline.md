# clojure.string/trim-newline

`(clojure.string/trim-newline s)`

末尾の改行文字（`\n`、`\r`）を削った `s` を返します -- 改行以外の空白は残ります。

```clojure
(println (clojure.string/trim-newline "a\r\n")) ; a
```
