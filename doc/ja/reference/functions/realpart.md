# realpart

`(realpart number)`

`number` の実部を返す。複素数ならその実部、実数ならその数自身である。4バックエンドすべてで動作する。

```lisp
(realpart #c(1 2)) ; => 1
```

```lisp
(realpart 5) ; => 5
```
