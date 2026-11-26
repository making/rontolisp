# subvec

`(subvec v start)` / `(subvec v start end)`

`clojure.core/subvec`: a fresh vector of the members of `v` from `start` up to `end`
(the end of `v` without one). A bound out of range or `start` past `end` signals, like the
oracle's `IndexOutOfBoundsException`; so does a `nil` bound and a `v` that is no vector
(`nil`, a list, a string). A float bound is truncated. The result is a copy, not a view.
As a value two or three arguments.

```clojure
(println (subvec [1 2 3 4] 1 3)) ; [2 3]
(println (subvec [1 2 3 4] 2))   ; [3 4]
```
