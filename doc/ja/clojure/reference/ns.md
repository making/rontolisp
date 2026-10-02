# ns

`(ns name clauses...)`

namespace を宣言して節を配線します。何も定義しません。`:as` は alias を登録し、`:refer`/`:use` は修飾なしの名前を、`:import` は interop 用のクラス名を登録し、`(:refer-clojure :only ...)`/`(:refer-clojure :exclude ...)` は見える core を狭めます。`:rename` はありません。解決するのは `clojure.string`、`clojure.java.io`（`reader` のみ）、`clojure.test` で、未知の namespace はエラーです。namespace そのものはフラットなままです -- 名前は帳簿上のものです。

```clojure
(ns demo (:require [clojure.string :as s :refer [join]]))
(println (s/upper-case "hi")) ; HI
(println (join "-" ["a" "b"])) ; a-b
```
