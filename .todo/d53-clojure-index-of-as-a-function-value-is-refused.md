# d53. `clojure.string/index-of` as a function value is refused

Difficulty: Low

`doc/{en,ja}/clojure/reference/index-of.md` says it "works as a function value too", but every
backend refuses the program at lowering (measured on the jar, interpreter):

```clojure
(println (map clojure.string/index-of ["hi"] ["z"]))
; error: m.clj:2:10: index-of takes a string, a value and an optional start
```

The oracle prints `(nil)`. `ClojureStringLowering`'s `index-of` arm checks the call-position
arity only, so a bare reference reaches it with no arguments. Give it the function-value path
the other `clojure.string` verbs take (or drop the doc's claim), keeping the start clamp
(`ClojureStringLowering.searchFrom`). Check `last-index-of` the same way.
