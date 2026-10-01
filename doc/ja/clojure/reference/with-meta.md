# with-meta

`(with-meta obj metadata)`

オブジェクトをそのまま返します。メタデータ（`^:private`、`^:dynamic`、`^{...}` attr マップ、型ヒント）はディスパッチに影響しないため、どこにあっても解析して捨てられます。読み取るのは `binding` だけで、`^:dynamic` が再束縛可能な var を表します。

```clojure
(println (with-meta [1 2] {:tag :x})) ; [1 2]
```
