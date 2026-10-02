# set!

`(set! field value)`

Assigns a deftype field marked `^:unsynchronized-mutable` or `^:volatile-mutable`,
inside the type's own inline methods, answering the value. A closure created in the
method copies the field when created, so a `set!` inside one is refused. Any other
target is refused like the oracle: a local, a parameter or an immutable field with
`Cannot assign to non-mutable: ...`; a non-dynamic global signals
`Can't change/establish root binding of: ... with set` at run time. A dynamic or core
var and a host field are not supported yet.

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
