# random-state-p

`(random-state-p object)`

`object` が random-state、つまり [`make-random-state`](make-random-state.md) の返す値や `*random-state*` の値であれば真を返します。4 つのバックエンドすべてで動作します。

```lisp
(list (random-state-p *random-state*) (random-state-p nil)) ; => (T NIL)
```
