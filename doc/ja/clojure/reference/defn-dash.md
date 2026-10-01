# defn-

`(defn- name doc? params body...)`

プライベートな `defn` です。慣習以外は同一です（名前を隠す名前空間がありません）。名前の `^:private`、attr マップ、docstring など、メタデータはどこにあっても解析して捨てられます。

```clojure
(defn- double [x] (* x 2))
(println (double 21)) ; 42
```
