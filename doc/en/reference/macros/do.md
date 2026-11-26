# do

`(do ((var init step?)...) (end-test result...) body...)`

Iterates with one or more loop variables. Each `var` is bound to its `init` on entry and, after every pass through the body, updated *in parallel* to its `step` form (every step is evaluated against the old bindings before any variable is reassigned). Before each iteration `end-test` is checked; when it becomes truthy the loop stops and the `result` forms are evaluated, the last value being returned (nil if there are none). The body and steps are wrapped in the internal block boundary, so `return` exits the loop early.

```lisp
(do ((i 0 (+ i 1)) (sum 0 (+ sum i))) ((= i 5) sum)) ; => 10
```
