# set!

`(set! field value)`

Assigns a deftype field marked `^:unsynchronized-mutable` or `^:volatile-mutable`,
inside the type's own inline methods, answering the value. A closure created in the
method copies the field when created, so a `set!` inside one is refused.

A `^:dynamic` var bound by an enclosing `binding` assigns the same way: the write
sets the thread-local value and answers it, even from a function called inside the
binding. Outside any binding it signals
`Can't change/establish root binding of: ... with set` at run time, after the value
evaluates -- the same error a non-dynamic global's `set!` signals. The
`clojure.core` specials assign the same way (`*out*`, `*err*`, `*print-dup*`, ...),
except the flags `clojure.main` binds around a script (`*warn-on-reflection*`,
`*unchecked-math*`, `*print-length*`, `*print-level*`, `*assert*`, ...): those are
always bound, so `set!` assigns them anywhere, like the oracle. `*ns*` answers the
value with no effect here.

Any other target is refused like the oracle: a local, a parameter or an immutable
field with `Cannot assign to non-mutable: ...`; a host field is not supported yet
(the `java:` surface has no field write).

```clojure
(defprotocol Counter (bump! [c]) (total [c]))
(deftype Tally [^:unsynchronized-mutable n]
  Counter
  (bump! [_] (set! n (inc n)))
  (total [_] n))
(def t (Tally. 0))
(bump! t)
(println (bump! t) (total t))
```

```
2 2
```

```clojure
(def ^:dynamic *volume* 1)
(println (binding [*volume* 5] (set! *volume* 2)))
(println *volume*)
(println (set! *warn-on-reflection* true))
```

```
2
1
true
```
