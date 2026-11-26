# make-random-state

`(make-random-state &optional state)`

Returns a fresh random-state. `state` is `nil`, `t` or a random-state, and anything else signals a `type-error`. A random-state here carries no generator: [`random`](random.md) accepts one and draws from the backend's own generator, so the common seeding idiom — store `(make-random-state t)` in a variable and pass it back to `random` — works (uuid's `*uuid-random-state*` is the driving consumer), while a copy does not repeat the draws of its original.

```lisp
(random-state-p (make-random-state t)) ; => T
```
