# reader-conditional

`(reader-conditional form splicing?)`

`clojure.core/reader-conditional`: the reader conditional over `form` (a list), what
[read-string](read-string.md) and [read](read.md) answer for `#?(...)` (`splicing?` false)
or `#?@(...)` (`splicing?` true) under `{:read-cond :preserve}`. It looks up `:form` and
`:splicing?` like a map (any other key answers the default), is `=` to another of an `=`
form and the same flag, and prints back as it reads; it is no collection and no function.
`splicing?` must be a boolean (`nil` is a `NullPointerException`, anything else a
`ClassCastException`). [reader-conditional?](reader-conditional-p.md) tests one. Runs on
every backend; as a value, two arguments.

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
