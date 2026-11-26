# restart-name

`(restart-name restart)`

[`find-restart`](find-restart.md) や [`compute-restarts`](compute-restarts.md) で得たリスタートオブジェクトの名前(シンボルまたはキーワード)を返します。

```lisp
(restart-case (restart-name (find-restart :reconnect))
  (:reconnect () nil)) ; => :RECONNECT
```
