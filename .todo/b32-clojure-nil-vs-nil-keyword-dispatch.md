# Clojure: identity dispatch on a literal `:nil` hits the nil method

Difficulty: Trivial (dispatcher or `dispatchKeyForm`).

## Gap (by code trace of `ClojureLowering.defmultiForms`, 2026-10-01)

b19 normalizes a nil dispatch value to the `:nil` keyword at dispatch time, so no
table ever keys on nil. A dispatch value that literally IS the `:nil` keyword
answers the nil method too:

```clojure
(defmulti m identity)
(defmethod m nil [_] "was-nil")
(defmethod m :default [x] "dflt")
(println (m :nil)) ; oracle "dflt", rontolisp "was-nil"
```

The oracle tells nil from `:nil` apart. Fix by keying the nil method distinctly
(e.g. a dedicated `:nil`-vs-nil marker the dispatcher maps both spellings from
only when appropriate) or by normalizing only a true nil. Pinned as the documented
deviation until then (`.kb/clojure-frontend.md`, `doc/*/clojure/deviations.md`).
