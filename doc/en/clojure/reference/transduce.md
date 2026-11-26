# transduce

`(transduce xform f coll)` / `(transduce xform f init coll)`

Reduces `coll` with `(xform f)` from `init` -- `(f)` without one, called before `xform`
sees `f` -- then runs the completion `((xform f) result)`. A step answering
[`reduced`](reduced.md) stops the reduction. As a value three or four arguments.

```clojure
(println (transduce (map inc) + [1 2 3])) ; 9
(println (transduce (filter odd?) conj [1 2 3 4 5])) ; [1 3 5]
(println (transduce (map inc) + 10 [1 2 3])) ; 19
```
