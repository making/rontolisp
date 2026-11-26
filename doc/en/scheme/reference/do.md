# do

`(do ((variable init step)...) (test expression...) command...)`

An iteration: binds each `variable` to its `init`, then repeatedly evaluates `test`; while it is false it runs the `command`s and rebinds each variable to its `step` (a variable without `step` keeps its value). When `test` is true it evaluates the `expression`s and answers the last value, or the unspecified value when there are none. A `do` runs in constant stack.

```scheme
(do ((i 0 (+ i 1)) (sum 0 (+ sum i))) ((= i 5) sum)) ; => 10
(do ((vec (make-vector 3)) (i 0 (+ i 1))) ((= i 3) vec) (vector-set! vec i (* i i))) ; => #(0 1 4)
```
