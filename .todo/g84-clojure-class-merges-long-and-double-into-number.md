# Clojure: `class` merges `Long`/`Double`/ratio into `:number`, so dispatch on `class` picks the wrong method

Difficulty: Medium

`class` answers a kind keyword (`doc/en/clojure/deviations.md`, "`class` answers a keyword
naming the kind"), and every numeric spelling becomes `:number`. A class name in value
position reads as that keyword, so `Long` and `Double` are the same dispatch value:

```clojure
(defmulti f class)
(defmethod f Long [_] :l) (defmethod f Double [_] :d) (defmethod f String [_] :s)
(defmethod f :default [_] :other)
(println (f 1) (f 1.5) (f "a") (f :k))
;; clj, cljw 1.15.4: :l :d :s :other     rontolisp: :d :d :s :other
(println (= Long (class 1)))            ;; clj, cljw: true   rontolisp: false
```

The second `defmethod` silently replaces the first. Dispatching on `class` (and
`extend-protocol` over `Long`/`Double`, `case` on a class) is common in libraries, so
this is a correctness gap, not a printing one.

Fix: one designator per numeric class the oracle distinguishes (`Long`, `Double`,
`clojure.lang.Ratio`, `clojure.lang.BigInt`, ...) on all four backends, with `isa?`
ancestors keeping `Number` above them, and `(= Long (class 1))` true. cljw answers the
simple class name (`Long`), which is a reasonable target where no host class exists.
Interacts with `.todo/g91` (number kinds); `.kb/clojure-frontend.md` "Boxed-integer
limits, class designators" and "Dispatch" first.
