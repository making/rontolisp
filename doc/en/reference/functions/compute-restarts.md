# compute-restarts

`(compute-restarts [condition])`

Returns a list of every active restart record, innermost first (clauses of one [`restart-case`](../macros/restart-case.md) in their written order). Each element answers to [`restart-name`](restart-name.md) and can be passed to [`invoke-restart`](invoke-restart.md). Lite: the optional `condition` argument is accepted and ignored.

```lisp
(restart-case
    (restart-case (mapcar (function restart-name) (compute-restarts))
      (aaa () nil)
      (bbb () nil))
  (ccc () nil)) ; => (AAA BBB CCC)
```
