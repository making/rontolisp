# make-random-state

`(make-random-state &optional state)`

Always returns `nil`: rontolisp has no random-state objects. [`random`](random.md) accepts (and ignores) an optional random-state argument and draws from the backend's own generator, so the common seeding idiom — store `(make-random-state t)` in a variable and pass it back to `random` — works unchanged (uuid's `*uuid-random-state*` is the driving consumer). The argument (`nil`, `t`, or a state) is accepted and ignored.

```lisp
(make-random-state t) ; => NIL
```
