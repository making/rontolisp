# random-state-p

`(random-state-p object)`

True when `object` is a random-state: what [`make-random-state`](make-random-state.md) answers and `*random-state*` holds. Works on all four backends.

```lisp
(list (random-state-p *random-state*) (random-state-p nil)) ; => (T NIL)
```
