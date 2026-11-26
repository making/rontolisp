# swap!

`(swap! atom f x...)`

Applies `f` to the atom's value and the extra arguments and stores the answer, answering
the new value. Works as a function value too, so it can travel through `map`/`reduce`.

Like Clojure's, it is a compare-and-set retry loop: `f` runs outside any lock, and when
the atom changed meanwhile (on another thread -- a Ring handler runs one per request on
the interpreter and the JVM -- or inside `f` itself), `f` runs again on the newer value.
No update is lost, and `f` may run more than once.

```clojure
(def a (atom 1))
(println (swap! a + 10 20)) ; 31
(println @a)                ; 31
```

A validator (`set-validator!`) checks each answer before it is stored, and the atom's
watches (`add-watch`) are called after the store. Misuse of a non-atom signals.
