# ns

`(ns name clauses...)`

namespace へ切り替え（なければ作り）、節を配線します。`:as` は alias を登録し、`:refer`/`:use` は修飾なしの名前を、`:import` は interop 用のクラス名を登録し、`(:refer-clojure :only ...)`/`(:refer-clojure :exclude ...)` は見える core を狭めます。`:rename` はありません。フォームより下の定義はこの namespace に属します。`clojure.string`、`clojure.java.io`（`reader` のみ）、`clojure.test` 以外の require された namespace はプロジェクトの namespace で、プログラムの先行する `ns` フォームが宣言したものか、ソースパス上のファイルから 1 度だけロードされるものです（[セマンティクス](../semantics.md#namespaces-and-files)）。どのルートにもないファイルはエラーです。

```clojure
(ns demo (:require [clojure.string :as s :refer [join]]))
(println (s/upper-case "hi")) ; HI
(println (join "-" ["a" "b"])) ; a-b
```

```clojure
(ns geo.shapes)
(defn area [w h] (* w h))
(ns geo.main (:require [geo.shapes :as s]))
(println (s/area 2 3)) ; 6
```
