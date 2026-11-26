# test

`(test v)`

`clojure.core/test`: calls the zero-argument fn at `:test` in the var's metadata and
answers `:ok`, or `:no-test` when there is none. Whatever the fn throws passes through,
so a failed `assert` reaches the caller as an `AssertionError`.

```clojure
(defn ^{:test (fn [] (assert (= 4 (sq 2))))} sq [x] (* x x))
(println (test #'sq))   ; :ok
(defn plain [] 1)
(println (test #'plain)) ; :no-test
```
