# d07. Clojure: a keyword in a seq position reads as its representation, and count/empty?/contains? answer where the oracle refuses

Difficulty: Medium

Measured against clj 1.12.6 (2026-10-04); the oracle throws for every one of them:

```clojure
(first :a)        ; :C%KEYWORD   (oracle IllegalArgumentException: Don't know how to create ISeq from: clojure.lang.Keyword)
(seq :a)          ; :a
(rest :a)         ; ("a")
(nth :a 0)        ; :C%KEYWORD
(count :a)        ; 2            (oracle UnsupportedOperationException: count not supported on this type: Keyword)
(empty? 5)        ; false        (oracle IllegalArgumentException)
(empty? :a)       ; false
(contains? 5 1)   ; false        (oracle IllegalArgumentException: contains? not supported on type: java.lang.Long)
(contains? :a 1)  ; false
```

A keyword is the `(:C%KEYWORD spelling)` list, so `%clojure-strict-seq`'s `consp` arm (and
`count`'s, `nth`'s) takes it for a list; the other tagged wrappers whose car is a CL keyword
(`:C%VAR`, `:C%REDUCED`, `:C%NIL`, ...) likely share it -- probe each. `empty?`/`contains?` of a
number fall through to an answer instead of the seq refusal.

The refusals exist (`clojure.lisp` "Refusals", `.kb/clojure-frontend.md`): the work is reaching
them without costing the common path -- `%clojure-strict-seq` and `count` sit under every seq
verb, so a wrapper test there is a cost every program pays; measure it (the wasm size of a
seq-heavy program and a `first`/`count` loop) before choosing between a test on the `consp`
arm and a guard only where a wrapper can reach. Pin the classes in clojure-spec on all four
backends.
