# when-some

`(when-some [p e] body...)`

Like `when-let`, but only `nil` skips the body: `false` binds and runs it.

```clojure
(println (when-some [x false] [:body x])) ; [:body false]
(println (when-some [x nil] [:body x]))   ; nil
```
