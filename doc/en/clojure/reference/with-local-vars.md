# with-local-vars

`(with-local-vars [name init ...] body...)`

Binds each name to a fresh var cell holding the init, thread-locally around the
body with dynamic extent: `deref`, `var-get` and the var itself read the cell,
`var-set` stores through it while bound, and leaving restores the unbound root
(an `unwind-protect` restores every cell, so a throw still leaves the unbound
root behind, like the oracle's `try` popping its frame). The cells start
unbound -- the inits bind only the extent -- so an escaped cell derefs to the
unbound root. Inits run in order after every cell exists, so an init reads the
earlier cells unbound. A `recur` across the body is refused, like the oracle's
`try` boundary, and `set!` of a cell is the `Cannot assign to non-mutable`
refusal. [`bound-fn`](bound-fn.md) created in the body captures the cells. A
cell prints `#'ns/name` (the oracle's cells are unnamed); `alter-var-root` of
one is refused. As a value it answers the run-time once-expansion delegate
(see [bound-fn](bound-fn.md)); the oracle refuses a bare macro name in value
position at compile time.

```clojure
(println (with-local-vars [a 1 b 2] [@a @b])) ; [1 2]
(println (with-local-vars [a 1] (var-set a 5) @a)) ; 5
```
