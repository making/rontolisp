# ns

`(ns name clauses...)`

namespace へ切り替え（なければ作り）、節を配線します。`:as` は alias を登録し、`:refer`/`:use` は修飾なしの名前を、`:import` は interop 用のクラス名を登録し、`(:refer-clojure :only ...)`/`(:refer-clojure :exclude ...)` は見える core を狭めます。`:require` の libspec の `:as-alias` は namespace をロードせずに alias だけを登録し、`:rename {old new}` は var を別名で refer します（元の名前は refer されません）。`(:refer-clojure :rename {old new})` は core の var に対して同じことをします。`(:load "path" ...)` は [`load`](../semantics.md#namespaces-and-files) と同じようにファイルをロードし、`(:gen-class ...)` は受け付けて無視します（クラスは生成されません）。フォームより下の定義はこの namespace に属します。[組み込みの namespace](namespaces.md#built-in-namespaces) 以外の require された namespace はプロジェクトの namespace で、プログラムの先行する `ns` フォームが宣言したものか、ソースパス上のファイルが節の実行時にロードされるものです（プログラムにつき 1 度、`:reload` では再実行されます。[セマンティクス](../semantics.md#namespaces-and-files)）。どのルートにもないファイルはエラーです。

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
