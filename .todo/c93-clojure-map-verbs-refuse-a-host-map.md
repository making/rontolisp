# c93. Clojure map verbs refuse a host Map

Difficulty: Medium

`(def hm (doto (java.util.TreeMap.) (.put "a" 1) (.put "b" 2)))`: on the interpreter and the
JVM `(find hm "a")` is `find not supported on this type`, `(select-keys hm ["a"])`
`select-keys needs a map`, `(reduce-kv (fn [a k v] (+ a v)) 0 hm)` `reduce-kv needs a map or a
vector` (`update-vals`/`update-keys` too), `(merge {} hm)` / `(conj {} hm)` `conj needs a map
entry` (measured 2026-10-04). The oracle (clj 1.12.6) answers `["a" 1]`, `{"a" 1}`, `3`,
`{"a" 1, "b" 2}`: `RT.find` takes a `Map` (`containsKey`), `kv-reduce` falls back to a
`reduce` over the seq for any Object, and a map's `cons` takes a `java.util.Map`.

seq, count, empty?, get, contains?, keys and vals already read a host Map through the
host-object family test `%clojure-host-seqable-p` (`.kb/clojure-frontend.md`, "Java interop",
host collections under the seq verbs); these verbs need the same arm ahead of their refusal:
`%clojure-find`'s fall-through, `%clojure-kv-pairs`, the lowered `select-keys`
(`ClojureUpdateLowering`) and the map `conj` sites (`ClojureCollectionLowering` and
`clojure.lisp`). A program naming no `java:` operator must stay byte-identical; the
user-doc deviation bullet in `doc/*/clojure/deviations.md` names these four verbs.
