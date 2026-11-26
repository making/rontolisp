# ancestors

`(ancestors tag)`
`(ancestors h tag)`

Answers the set of all ancestors of `tag`, transitive, in the hierarchy -- the global one, or
`h` in the two-argument form, nil when there are none. A class spelling is the keyword
`class` answers for it (see `isa?`), and a class adds its Java supers, interfaces and `Object`
included, and their ancestors in the hierarchy. A kind keyword (`:string`, `:function`, ...)
adds the supers of the values it stands for. A host class object (interpreter and JVM) adds
its supers as class objects, like the oracle.

```clojure
(derive :c :p)
(derive :p :q)
(println (ancestors :c)) ; #{:p :q}
(println (count (ancestors Exception))) ; 3
(println (contains? (ancestors :string) :java.lang.CharSequence)) ; true
(println (contains? (ancestors (class (fn []))) :clojure.lang.IFn)) ; true
```
