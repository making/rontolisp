# d17. Clojure: `vec` of a non-collection and `nth` of a matcher diverge from the oracle

Difficulty: Low

Measured against clj 1.12.6 (2026-10-05):

```clojure
(vec 5)      ; oracle RuntimeException "Unable to convert: class java.lang.Long to Object[]"
             ; here seq's IllegalArgumentException (a catch of IAE takes it, the oracle's passes)
(def m (re-matcher #"(a)(b)" "ab")) (re-find m)
(nth m 2)    ; oracle "b" (Matcher.group); here UnsupportedOperationException
(nth m 5 :d) ; oracle :d
```

`vec` (`ClojureCollectionLowering.vecForm`, `%clojure-realize-all`) reaches the seq view's
refusal; the oracle's `LazilyPersistentVector.create` casts first. `nth` of a matcher is
`%clojure-nth`'s refusal arm (`.kb/clojure-frontend.md`, "Seq verbs over a wrapper"); the
oracle's `RT.nthFrom` reads `Matcher.group(n)` (IllegalStateException before a match,
IndexOutOfBounds past `groupCount` unless a default is given). Pin both in clojure-spec on
all four backends.
