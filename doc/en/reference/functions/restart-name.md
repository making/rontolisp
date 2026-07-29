# restart-name

`(restart-name restart)`

The name (a symbol or keyword) of a restart object obtained from [`find-restart`](find-restart.md) or [`compute-restarts`](compute-restarts.md).

```lisp
(restart-case (restart-name (find-restart :reconnect))
  (:reconnect () nil)) ; => :RECONNECT
```
