# e80. Clojure: `& {:keys ...}` keyword arguments read as nil

Difficulty: Medium

A rest pattern that is a map reads its keys from the rest seq with `get`, which answers the
default for a list, so every keyword argument is silently nil. Measured 2026-10-08 (clj
1.12.6 against the interpreter):

```clojure
(defn f [x & {:keys [a b] :or {b 9}}] [x a b])
(prn (f 1 :a 2) (f 1 {:a 3}) (f 1 :a 2 :b 3) (f 1 :a 2 {:b 5}))
;; oracle: [1 2 9] [1 3 9] [1 2 3] [1 2 5]
;; here:   [1 nil 9] [1 nil 9] [1 nil 9] [1 nil 9]
(f 1 :a 2 :b) ; oracle: IllegalArgumentException Don't know how to create ISeq from: clojure.lang.Keyword
```

The oracle destructures the rest through `seq-to-map-for-destructuring`: one argument is the
map itself, more go through `PersistentArrayMap/createAsIfByAssoc` (last key wins, an odd
trailing value conj'd like onto a map). `%clojure-iteration-option` (`clojure.lisp`) reads
`iteration`'s options that way already.

## Plan

1. A failing clojure-spec case with the lines above.
2. `ClojureBindingLowering.destructureVector`: a map pattern after `&` binds against the
   rest converted the oracle's way (a library worker building the map, or the option reader
   generalized), on all four backends.
3. `.kb/clojure-frontend.md` (destructuring) and the user doc of `defn`/`let`.
