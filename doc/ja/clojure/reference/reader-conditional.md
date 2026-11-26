# reader-conditional

`(reader-conditional form splicing?)`

`clojure.core/reader-conditional`: `form`（リスト）を持つリーダ条件を返します。`{:read-cond :preserve}` の下で [read-string](read-string.md) と [read](read.md) が `#?(...)`（`splicing?` は false）や `#?@(...)`（`splicing?` は true）に対して返す値と同じものです。マップと同じく `:form` と `:splicing?` を引けます（それ以外のキーは既定値を返します）。`=` なフォームと同じフラグを持つものと `=` になり、読んだとおりに印字されます。コレクションでも関数でもありません。`splicing?` は真偽値でなければなりません（`nil` は `NullPointerException`、それ以外は `ClassCastException`）。[reader-conditional?](reader-conditional-p.md) で判定できます。すべてのバックエンドで動きます。値としては2引数を取ります。

```clojure
(def rc (read-string {:read-cond :preserve} "#?@(:clj [1 2] :cljs [3])"))
(println rc)
(println (:form rc) (:splicing? rc))
(println (= rc (reader-conditional '(:clj [1 2] :cljs [3]) true)))
```

```
#?@(:clj [1 2] :cljs [3])
(:clj [1 2] :cljs [3]) true
true
```
