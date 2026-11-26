# refer-clojure

`(refer-clojure & filters)`

今の名前空間に `(:refer-clojure ...)` フィルタ -- `:only`、`:exclude`、`:rename` --
を適用し、`nil` を返します。[`ns`](ns.md) 節を単独フォームとして綴ったものと同じです。
改名された core の var は新しい名前でのみ答えます（元の名前はもう refer されません）。
値としては未知です（`(def x refer-clojure)` は拒否されます）。

```clojure
(refer-clojure :rename '{map core-map})
(println (core-map inc [1 2])) ; (2 3)
```
