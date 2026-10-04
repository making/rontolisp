# parents

`(parents tag)`
`(parents h tag)`

Answers the set of the immediate parents of `tag` in the hierarchy -- the global one, or `h` in
the two-argument form, nil when there are none. A class spelling is the keyword `class`
answers for it (see `isa?`), and a class adds its Java bases: the superclass and the
interfaces it implements. A host class object (interpreter and JVM) adds them as class objects,
like the oracle.

```clojure
(derive :c :p)
(println (parents :c)) ; #{:p}
(println (parents :x)) ; nil
(println (parents NumberFormatException)) ; #{:java.lang.IllegalArgumentException}
```
