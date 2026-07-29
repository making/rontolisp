# compute-restarts

`(compute-restarts [condition])`

アクティブなすべてのリスタートレコードを最内から順に並べたリストを返します(1 つの [`restart-case`](../macros/restart-case.md) の各節は記述順です)。各要素は [`restart-name`](restart-name.md) に応答し、[`invoke-restart`](invoke-restart.md) に渡せます。lite: 省略可能な `condition` 引数は受理された上で無視されます。

```lisp
(restart-case
    (restart-case (mapcar (function restart-name) (compute-restarts))
      (aaa () nil)
      (bbb () nil))
  (ccc () nil)) ; => (AAA BBB CCC)
```
