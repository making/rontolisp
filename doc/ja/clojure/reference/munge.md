# munge

`(munge s)`

`s` の `str` の各文字をファイル名やクラス名用に読み替えたもの。oracle と同じ。`-` は `_`、他の特殊文字は `_名前_`（`+` は `_PLUS_`、`?` は `_QMARK_` など、下の例と英語版の表を参照）。値としては 1 引数を取る。

```clojure
(println (munge "foo-bar") (munge "+")) ; foo_bar _PLUS_
```
